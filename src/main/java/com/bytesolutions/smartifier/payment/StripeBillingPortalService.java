package com.bytesolutions.smartifier.payment;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.billingportal.ConfigurationCreateParams;
import com.stripe.param.billingportal.ConfigurationCreateParams.Features;
import com.stripe.param.billingportal.ConfigurationListParams;
import com.stripe.param.billingportal.SessionCreateParams;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StripeBillingPortalService {
    private static final String PORTAL_VERSION = "smartifier-member-billing-v2";
    private final StripeClient stripe;
    private final SubscriptionAccessService access;
    private final String key;
    private final String returnUrl;
    private final String configurationId;
    private String generatedConfigurationId;

    public StripeBillingPortalService(@Lazy StripeClient stripe, SubscriptionAccessService access,
            @Value("${smartifier.stripe.secret-key:}") String key,
            @Value("${smartifier.public-url}") String publicUrl,
            @Value("${smartifier.stripe.portal-configuration-id:}") String configurationId) {
        this.stripe = stripe;
        this.access = access;
        this.key = key;
        this.configurationId = configurationId;
        this.returnUrl = publicUrl.replaceAll("/+$", "") + "/member/dashboard?tab=billing";
    }

    public record Membership(String id, String status, boolean ending, Long endsAt, Long amount,
            String currency, String interval) {
        static Membership describe(Subscription subscription) {
            var items = subscription.getItems().getData();
            var item = items.size() == 1 ? items.getFirst() : null;
            var price = item == null ? null : item.getPrice();
            var recurring = price == null ? null : price.getRecurring();
            boolean ending = subscription.getCancelAt() != null
                    || Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd());
            Long endsAt = subscription.getCancelAt();
            if (ending && endsAt == null && item != null) endsAt = item.getCurrentPeriodEnd();
            return new Membership(subscription.getId(), subscription.getStatus(), ending, endsAt,
                    price == null ? null : price.getUnitAmount(), price == null ? null : price.getCurrency(),
                    recurring == null || !Long.valueOf(1).equals(recurring.getIntervalCount())
                            ? null : recurring.getInterval());
        }
    }
    public record Portal(String url) {}

    public List<Membership> memberships(String email) {
        if (key.isBlank()) return List.of();
        try {
            return access.billingSubscriptions(email).stream().map(Membership::describe).toList();
        } catch (StripeException e) {
            throw unavailable("Could not load your membership billing. Please try again.");
        }
    }

    public Portal open(String email, String subscriptionId, boolean paymentMethod) {
        if (key.isBlank()) throw unavailable("Billing is currently unavailable.");
        try {
            var subscription = access.billingSubscription(email, subscriptionId);
            var params = SessionCreateParams.builder().setCustomer(subscription.getCustomer())
                    .setConfiguration(portalConfiguration()).setReturnUrl(returnUrl);
            if (paymentMethod) {
                params.setFlowData(SessionCreateParams.FlowData.builder()
                        .setType(SessionCreateParams.FlowData.Type.PAYMENT_METHOD_UPDATE).build());
            }
            String address = stripe.v1().billingPortal().sessions().create(params.build()).getUrl();
            if (address != null) {
                URI url = URI.create(address);
                if ("https".equals(url.getScheme()) && "billing.stripe.com".equals(url.getHost())
                        && url.getRawUserInfo() == null && url.getPort() == -1) {
                    return new Portal(address);
                }
            }
        } catch (StripeException | IllegalArgumentException e) {
            // Provider messages may contain account or configuration details.
        }
        throw unavailable("Could not open Stripe billing. Please try again.");
    }

    private synchronized String portalConfiguration() throws StripeException {
        if (!configurationId.isBlank()) return configurationId;
        if (generatedConfigurationId != null) return generatedConfigurationId;
        var configurations = stripe.v1().billingPortal().configurations();
        var list = ConfigurationListParams.builder().setActive(true).setLimit(100L).build();
        for (var configuration : configurations.list(list).autoPagingIterable()) {
            if (configuration.getMetadata() != null
                    && PORTAL_VERSION.equals(configuration.getMetadata().get("smartifier_portal"))) {
                generatedConfigurationId = configuration.getId();
                return generatedConfigurationId;
            }
        }
        var features = Features.builder()
                .setCustomerUpdate(Features.CustomerUpdate.builder().setEnabled(true)
                        .addAllowedUpdate(Features.CustomerUpdate.AllowedUpdate.NAME)
                        .addAllowedUpdate(Features.CustomerUpdate.AllowedUpdate.ADDRESS).build())
                .setPaymentMethodUpdate(Features.PaymentMethodUpdate.builder().setEnabled(true).build())
                .setInvoiceHistory(Features.InvoiceHistory.builder().setEnabled(true).build())
                .setSubscriptionCancel(Features.SubscriptionCancel.builder().setEnabled(true)
                        .setMode(Features.SubscriptionCancel.Mode.AT_PERIOD_END).build()).build();
        var params = ConfigurationCreateParams.builder().setFeatures(features)
                .putMetadata("smartifier_portal", PORTAL_VERSION).build();
        generatedConfigurationId = configurations.create(params, RequestOptions.builder()
                .setIdempotencyKey(PORTAL_VERSION).build()).getId();
        return generatedConfigurationId;
    }

    private static ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}
