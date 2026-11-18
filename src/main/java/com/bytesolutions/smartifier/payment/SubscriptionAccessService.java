package com.bytesolutions.smartifier.payment;

import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.account.services.AccountService;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.param.SubscriptionRetrieveParams;
import com.stripe.param.checkout.SessionListParams;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Reconciles access from current Stripe state rather than an event's possibly stale snapshot. */
@Service
public class SubscriptionAccessService {
    private final MongoTemplate mongo;
    private final StripeClient stripe;
    private final MembershipPricingService pricing;

    public SubscriptionAccessService(MongoTemplate mongo, @Lazy StripeClient stripe, MembershipPricingService pricing) {
        this.mongo = mongo;
        this.stripe = stripe;
        this.pricing = pricing;
    }

    @Document("premium_access_sources")
    public record Grant(@Id String id, String email, String kind, boolean active, String stripeStatus, Instant checkedAt) {}
    @Document("premium_billing_members")
    public record BillingMember(@Id String email, Instant importedAt) {}
    @Document("premium_access_claims")
    public record Claim(@Id String email, String owner, Instant until) {}

    private Query member(String email) {
        return Query.query(Criteria.where("email").is(email).and("role").is(Account.Role.MEMBER));
    }
    private Subscription retrieve(String id) throws StripeException {
        return stripe.v1().subscriptions().retrieve(id,
                SubscriptionRetrieveParams.builder().addExpand("latest_invoice").build());
    }
    private boolean membershipPrice(Subscription subscription) {
        return subscription.getItems() != null && subscription.getItems().getData().stream()
                .anyMatch(item -> item.getPrice() != null && pricing.accepts(item.getPrice().getId()));
    }
    private boolean paid(Subscription subscription) {
        var invoice = subscription.getLatestInvoiceObject();
        return "active".equals(subscription.getStatus()) && membershipPrice(subscription)
                && invoice != null && "paid".equals(invoice.getStatus());
    }
    private boolean appCheckout(Session session, String email) {
        return session.getClientReferenceId() != null
                && AccountService.normalizeEmail(session.getClientReferenceId()).equals(email)
                && session.getMetadata() != null && pricing.accepts(session.getMetadata().get("smartifier_price_id"));
    }

    List<Subscription> billingSubscriptions(String email) throws StripeException {
        var subscriptions = new ArrayList<Subscription>();
        for (String id : billingSubscriptionIds(email)) {
            var subscription = stripe.v1().subscriptions().retrieve(id);
            if (manageable(subscription)) subscriptions.add(subscription);
        }
        return subscriptions;
    }

    Subscription billingSubscription(String email, String id) throws StripeException {
        var subscription = id.matches("sub_[A-Za-z0-9_]+")
                && billingSubscriptionIds(email).contains(id)
                ? stripe.v1().subscriptions().retrieve(id) : null;
        if (subscription == null || !manageable(subscription)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found.");
        }
        return subscription;
    }

    private List<String> billingSubscriptionIds(String email) throws StripeException {
        String normalized = AccountService.normalizeEmail(email);
        var sources = new LinkedHashMap<String, Grant>();
        for (Grant grant : mongo.find(Query.query(Criteria.where("email").is(normalized)
                .and("kind").is("subscription")), Grant.class)) {
            sources.put(grant.id(), grant);
        }
        if (mongo.findById(normalized, BillingMember.class) == null) importCheckouts(normalized, sources);
        return List.copyOf(sources.keySet());
    }

    private boolean manageable(Subscription subscription) {
        return subscription.getCustomer() != null && membershipPrice(subscription)
                && Set.of("active", "trialing", "past_due", "unpaid", "paused", "incomplete")
                        .contains(subscription.getStatus());
    }

    private String resolveEmail(String id) throws StripeException {
        var known = mongo.findById(id, Grant.class);
        if (known != null) return known.email();
        var subscription = retrieve(id);
        var metadata = subscription.getMetadata();
        if (metadata != null && "premium".equals(metadata.get("smartifier_membership"))
                && metadata.get("smartifier_member_email") != null && membershipPrice(subscription)) {
            return AccountService.normalizeEmail(metadata.get("smartifier_member_email"));
        }
        // Older app checkouts did not put member metadata on the subscription itself.
        var params = SessionListParams.builder().setSubscription(id).setLimit(100L).build();
        for (Session session : stripe.v1().checkout().sessions().list(params).autoPagingIterable()) {
            String email = session.getClientReferenceId();
            if (email != null && !email.isBlank() && "subscription".equals(session.getMode())
                    && id.equals(session.getSubscription()) && appCheckout(session, AccountService.normalizeEmail(email))) {
                return AccountService.normalizeEmail(email);
            }
        }
        return null;
    }

    public void synchronize(String subscriptionId) {
        if (subscriptionId == null || !subscriptionId.matches("sub_[A-Za-z0-9_]+")) return;
        try {
            String email = resolveEmail(subscriptionId);
            if (email == null || email.isBlank()) return; // Never identify a member by customer_email alone.
            withMemberLock(email, owner -> {
                try {
                    requireMember(email);
                    var sources = new LinkedHashMap<String, Grant>();
                    for (Grant grant : mongo.find(Query.query(Criteria.where("email").is(email).and("kind").is("subscription")), Grant.class)) {
                        sources.put(grant.id(), grant);
                    }
                    boolean importing = mongo.findById(email, BillingMember.class) == null;
                    if (importing) importCheckouts(email, sources);
                    sources.putIfAbsent(subscriptionId, new Grant(subscriptionId, email, "subscription", false, "unknown", null));
                    var checked = new ArrayList<Grant>();
                    for (Grant source : sources.values()) {
                        // Read inside the member lock so concurrent/out-of-order events cannot overwrite newer state.
                        var subscription = retrieve(source.id());
                        checked.add(new Grant(source.id(), email, "subscription", paid(subscription),
                                subscription.getStatus(), Instant.now()));
                    }
                    requireClaim(email, owner);
                    // Gather all Stripe responses before writing: a provider failure leaves access unchanged.
                    for (Grant source : checked) mongo.save(source);
                    updatePremium(email, checked.stream().anyMatch(Grant::active));
                    if (importing) mongo.save(new BillingMember(email, Instant.now()));
                } catch (StripeException e) { throw unavailable(); }
            });
        } catch (StripeException e) { throw unavailable(); }
    }

    private void importCheckouts(String email, Map<String, Grant> sources) throws StripeException {
        // Discover overlapping subscriptions before revoking access.
        var params = SessionListParams.builder().setStatus(SessionListParams.Status.COMPLETE).setLimit(100L)
                .setCustomerDetails(SessionListParams.CustomerDetails.builder().setEmail(email).build()).build();
        for (Session session : stripe.v1().checkout().sessions().list(params).autoPagingIterable()) {
            if (!appCheckout(session, email)) continue;
            if ("subscription".equals(session.getMode()) && session.getSubscription() != null) {
                sources.putIfAbsent(session.getSubscription(), new Grant(session.getSubscription(), email,
                        "subscription", false, "unknown", null));
            }
        }
    }

    private void requireClaim(String email, String owner) {
        if (!mongo.exists(Query.query(Criteria.where("_id").is(email).and("owner").is(owner)
                .and("until").gt(Instant.now())), Claim.class)) throw unavailable();
    }
    private void withMemberLock(String email, java.util.function.Consumer<String> work) {
        String owner = UUID.randomUUID().toString();
        try {
            mongo.findAndModify(Query.query(Criteria.where("_id").is(email).and("until").lt(Instant.now())),
                    new Update().set("owner", owner).set("until", Instant.now().plusSeconds(300)),
                    FindAndModifyOptions.options().upsert(true).returnNew(true), Claim.class);
        } catch (DuplicateKeyException e) { throw unavailable(); }
        try { work.accept(owner); }
        finally {
            mongo.remove(Query.query(Criteria.where("_id").is(email).and("owner").is(owner)), Claim.class);
        }
    }
    private void requireMember(String email) {
        if (!mongo.exists(member(email), Account.class)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout member was not found.");
        }
    }
    private void updatePremium(String email, boolean active) {
        var result = mongo.updateFirst(member(email), new Update().set("premium", active), Account.class);
        if (result.getMatchedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout member was not found.");
        }
    }
    private static ResponseStatusException unavailable() {
        // A non-2xx response makes Stripe retry; do not acknowledge a failed reconciliation.
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not synchronize membership access. Please retry this webhook.");
    }
}
