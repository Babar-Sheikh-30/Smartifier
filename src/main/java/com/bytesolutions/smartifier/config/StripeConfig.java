package com.bytesolutions.smartifier.config;

import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class StripeConfig {
    @Bean
    @Lazy
    StripeClient stripeClient(@Value("${smartifier.stripe.secret-key:}") String secretKey) {
        // Bound connection and response waits so a slow payment provider cannot hang checkout.
        var builder = StripeClient.builder().setConnectTimeout(10_000).setReadTimeout(20_000)
                .setMaxNetworkRetries(0);
        return builder.setApiKey(secretKey).build();
    }
}
