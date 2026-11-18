package com.bytesolutions.smartifier.payment;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionUpdateParams;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MembershipPricingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MembershipPricingService.class);
    private final MongoTemplate mongo;
    private final StripeClient stripe;
    private final String key;
    private final String initialPrice;
    static final Set<String> CURRENCIES = Set.of("cad", "usd", "eur", "gbp", "aud");

    public MembershipPricingService(MongoTemplate mongo, @Lazy StripeClient stripe,
            @Value("${smartifier.stripe.secret-key:}") String key,
            @Value("${smartifier.stripe.price-id:}") String initialPrice) {
        this.mongo = mongo;
        this.stripe = stripe;
        this.key = key;
        this.initialPrice = initialPrice;
    }

    @Document("membership_pricing")
    public record Settings(@Id String id, @Version Long revision, String priceId,
            List<String> priceHistory, String updatedBy, Instant updatedAt) {}
    @Document("membership_price_migrations")
    public record Migration(@Id String id, String admin, String subscriptionId, String itemId,
            String oldPrice, String targetPrice, Long quantity, Instant expiresAt, String state) {}
    @Document("membership_billing_claims")
    public record BillingClaim(@Id String id, String owner, Instant until) {}
    public record Pricing(String priceId, Long revision, Long amount, String currency, String interval,
            boolean live, boolean configured, boolean editable) {}
    public record Candidate(String id, String subscriptionId, Long oldAmount, String currency, String interval) {}
    public record Preview(Pricing target, List<Candidate> candidates, int skipped, boolean more, String nextCursor) {}
    public record Outcome(String status, String message) {}

    Settings settings() { return mongo.findById("premium", Settings.class); }
    public String checkoutPrice() {
        var saved = settings();
        return saved == null ? initialPrice : saved.priceId();
    }
    public boolean accepts(String price) {
        if (price == null || price.isBlank()) return false;
        if (price.equals(initialPrice)) return true;
        var saved = settings();
        return saved != null && saved.priceHistory().contains(price);
    }
    private void configured() {
        if (key.isBlank()) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Stripe is not configured yet.");
    }
    private Pricing describe(Price price, Long revision) {
        String interval = price.getRecurring() == null ? "payment" : price.getRecurring().getInterval();
        boolean editable = CURRENCIES.contains(price.getCurrency()) && price.getUnitAmount() != null
                && "per_unit".equals(price.getBillingScheme()) && price.getTransformQuantity() == null
                && (price.getRecurring() == null || (Long.valueOf(1).equals(price.getRecurring().getIntervalCount())
                    && "licensed".equals(price.getRecurring().getUsageType())));
        return new Pricing(price.getId(), revision, price.getUnitAmount(), price.getCurrency(), interval,
                Boolean.TRUE.equals(price.getLivemode()), true, editable);
    }
    public Pricing get() {
        configured();
        var saved = settings();
        String id = saved == null ? initialPrice : saved.priceId();
        Long revision = saved == null ? -1L : saved.revision();
        if (id.isBlank()) return new Pricing("", revision, null, "cad", "month",
                key.startsWith("sk_live_") || key.startsWith("rk_live_"), false, true);
        try { return describe(stripe.v1().prices().retrieve(id), revision); }
        catch (StripeException e) { throw stripeError("load membership price", e); }
    }

    public Pricing save(long amount, String currency, String interval, long expectedRevision, String admin) {
        configured();
        if (amount < 1 || amount > 99_999_999 || !CURRENCIES.contains(currency)
                || !Set.of("month", "year").contains(interval)) {
            throw problem(HttpStatus.BAD_REQUEST, "Choose a valid price, currency, and monthly or yearly billing.");
        }
        var saved = settings();
        long revision = saved == null ? -1 : saved.revision();
        if (revision != expectedRevision) throw conflict();
        try {
            String oldId = saved == null ? initialPrice : saved.priceId();
            Price old = oldId.isBlank() ? null : stripe.v1().prices().retrieve(oldId);
            if (old != null && !describe(old, revision).editable()) {
                throw problem(HttpStatus.BAD_REQUEST, "This price must use fixed, standard billing.");
            }
            var builder = PriceCreateParams.builder().setCurrency(currency).setUnitAmount(amount)
                    .setRecurring(PriceCreateParams.Recurring.builder()
                        .setInterval(interval.equals("month") ? PriceCreateParams.Recurring.Interval.MONTH
                                : PriceCreateParams.Recurring.Interval.YEAR).build())
                    .putMetadata("smartifier_membership", "premium");
            if (old == null) builder.setProductData(PriceCreateParams.ProductData.builder().setName("Smartifier Premium").build());
            else {
                builder.setProduct(old.getProduct());
                if ("inclusive".equals(old.getTaxBehavior())) builder.setTaxBehavior(PriceCreateParams.TaxBehavior.INCLUSIVE);
                if ("exclusive".equals(old.getTaxBehavior())) builder.setTaxBehavior(PriceCreateParams.TaxBehavior.EXCLUSIVE);
            }
            Price price = stripe.v1().prices().create(builder.build());
            var history = new LinkedHashSet<String>();
            if (saved != null) history.addAll(saved.priceHistory());
            if (!oldId.isBlank()) history.add(oldId);
            history.add(price.getId());
            // Optimistic locking prevents a second admin from silently replacing a newer setting.
            var updated = mongo.save(new Settings("premium", saved == null ? null : revision,
                    price.getId(), List.copyOf(history), admin, Instant.now()));
            return describe(price, updated.revision());
        } catch (OptimisticLockingFailureException e) { throw conflict(); }
        catch (org.springframework.dao.DuplicateKeyException e) { throw conflict(); }
        catch (StripeException e) { throw stripeError("save membership price", e); }
    }

    static boolean eligible(Subscription subscription, String oldPrice) {
        return "active".equals(subscription.getStatus()) && subscription.getSchedule() == null
                && subscription.getPauseCollection() == null
                && "charge_automatically".equals(subscription.getCollectionMethod())
                && subscription.getPendingUpdate() == null && subscription.getCancelAt() == null
                && !Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())
                && subscription.getItems() != null && !Boolean.TRUE.equals(subscription.getItems().getHasMore())
                && subscription.getItems().getData().size() == 1
                && Long.valueOf(1).equals(subscription.getItems().getData().getFirst().getQuantity())
                && oldPrice.equals(subscription.getItems().getData().getFirst().getPrice().getId());
    }

    public Preview preview(String expectedTarget, String cursor, String admin) {
        configured();
        var saved = settings();
        if (saved == null || !saved.priceId().equals(expectedTarget)) throw conflict();
        var candidates = new ArrayList<Candidate>();
        int skipped = 0;
        int inspected = 0;
        int start = 0;
        String after = null;
        if (cursor != null) {
            if (!cursor.matches("[0-9]{1,8}:sub_[A-Za-z0-9_]+")) {
                throw problem(HttpStatus.BAD_REQUEST, "Invalid review page. Start a new review.");
            }
            var parts = cursor.split(":", 2);
            start = Integer.parseInt(parts[0]);
            after = parts[1];
            if (start >= saved.priceHistory().size()) throw conflict();
        }
        String nextCursor = null;
        try {
            Pricing target = describe(stripe.v1().prices().retrieve(saved.priceId()), saved.revision());
            outer: for (int index = start; index < saved.priceHistory().size(); index++) {
                String oldId = saved.priceHistory().get(index);
                if (oldId.equals(saved.priceId())) continue;
                if (oldId.equals(initialPrice) && stripe.v1().prices().retrieve(oldId).getRecurring() == null) {
                    continue;
                }
                var builder = SubscriptionListParams.builder().setPrice(oldId)
                        .setStatus(SubscriptionListParams.Status.ACTIVE).setLimit(100L);
                if (index == start && after != null) builder.setStartingAfter(after);
                var params = builder.build();
                for (Subscription subscription : stripe.v1().subscriptions().list(params).autoPagingIterable()) {
                    // Bounded, individually applied batches avoid long-running bulk billing requests.
                    inspected++;
                    if (eligible(subscription, oldId)) {
                        var item = subscription.getItems().getData().getFirst();
                        var old = item.getPrice();
                        if (Objects.equals(old.getCurrency(), target.currency()) && old.getUnitAmount() != null
                                && old.getRecurring() != null && Long.valueOf(1).equals(old.getRecurring().getIntervalCount())) {
                            String id = UUID.randomUUID().toString();
                            mongo.insert(new Migration(id, admin, subscription.getId(), item.getId(), oldId,
                                    saved.priceId(), item.getQuantity(), Instant.now().plusSeconds(600), "pending"));
                            candidates.add(new Candidate(id, subscription.getId(), old.getUnitAmount(), old.getCurrency(),
                                    old.getRecurring().getInterval()));
                        } else skipped++;
                    } else skipped++;
                    if (candidates.size() >= 25 || inspected >= 250) {
                        nextCursor = index + ":" + subscription.getId();
                        break outer;
                    }
                }
            }
            return new Preview(target, candidates, skipped, nextCursor != null, nextCursor);
        } catch (StripeException e) { throw stripeError("review membership subscriptions", e); }
    }

    public Outcome apply(String id, String admin) {
        configured();
        var query = Query.query(Criteria.where("_id").is(id).and("admin").is(admin)
                .and("state").is("pending").and("expiresAt").gt(Instant.now()));
        var migration = mongo.findAndModify(query, new Update().set("state", "running"),
                FindAndModifyOptions.options().returnNew(true), Migration.class);
        if (migration == null) throw problem(HttpStatus.CONFLICT, "This review expired or was already applied. Review subscriptions again.");
        String state = "failed";
        boolean claimed = false;
        try {
            try {
                // Different admin reviews must not update the same subscription concurrently.
                mongo.findAndModify(Query.query(Criteria.where("_id").is(migration.subscriptionId())
                                .and("until").lt(Instant.now())),
                        new Update().set("owner", id).set("until", Instant.now().plusSeconds(120)),
                        FindAndModifyOptions.options().upsert(true).returnNew(true), BillingClaim.class);
                claimed = true;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                return new Outcome("failed", "Another billing change is in progress. Review again after it finishes.");
            }
            if (!migration.targetPrice().equals(checkoutPrice())) throw conflict();
            var subscription = stripe.v1().subscriptions().retrieve(migration.subscriptionId());
            if (!eligible(subscription, migration.oldPrice())
                    || !migration.itemId().equals(subscription.getItems().getData().getFirst().getId())
                    || !Objects.equals(migration.quantity(), subscription.getItems().getData().getFirst().getQuantity())) {
                state = "skipped";
                return new Outcome(state, "Subscription changed since review; it was left unchanged.");
            }
            var item = SubscriptionUpdateParams.Item.builder().setId(migration.itemId())
                    .setPrice(migration.targetPrice()).setQuantity(migration.quantity()).build();
            var params = SubscriptionUpdateParams.builder().addItem(item)
                    .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE)
                    .setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.ERROR_IF_INCOMPLETE).build();
            stripe.v1().subscriptions().update(migration.subscriptionId(), params,
                    RequestOptions.builder().setIdempotencyKey("smartifier-pricing-" + id).build());
            state = "updated";
            return new Outcome(state, "Subscription updated.");
        } catch (StripeException e) {
            return new Outcome("failed", "Stripe could not confirm this change. Review again to check its current status.");
        } finally {
            if (claimed) mongo.remove(Query.query(Criteria.where("_id").is(migration.subscriptionId())
                    .and("owner").is(id)), BillingClaim.class);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("state", state), Migration.class);
        }
    }
    private static ResponseStatusException conflict() {
        return problem(HttpStatus.CONFLICT, "Membership pricing changed. Reload pricing and review again.");
    }
    private static ResponseStatusException stripeError(String operation, StripeException error) {
        LOGGER.warn("Stripe could not {} (exception={}, status={}, code={}, requestId={})", operation,
                error.getClass().getSimpleName(), error.getStatusCode(), error.getCode(), error.getRequestId());
        return problem(HttpStatus.BAD_GATEWAY, "Stripe could not " + operation + ". Please try again.");
    }
    private static ResponseStatusException problem(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
