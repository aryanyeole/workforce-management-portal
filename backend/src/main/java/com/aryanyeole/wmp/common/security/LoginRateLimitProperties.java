package com.aryanyeole.wmp.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for RateLimitFilter's one target, POST /api/v1/auth/login.
 * Constructor-bound record, picked up automatically by WmpApplication's
 * {@code @ConfigurationPropertiesScan} — no separate {@code @Bean} needed.
 *
 * Defaults (application.yml): enabled, capacity 5, refill 5/minute — five
 * attempts land immediately (a legitimate user mistyping a password a couple
 * of times never notices this exists), then one recovers every 12 seconds.
 * Deliberately disabled for the whole integration-test suite — see
 * AbstractIntegrationTest — rather than only under the pre-existing "test"
 * Spring profile, which just three of the IT classes that call the real
 * /login endpoint through MockMvc actually activate.
 */
@ConfigurationProperties(prefix = "wmp.rate-limit.login")
public record LoginRateLimitProperties(boolean enabled, int capacity, double refillPerMinute) {

    public LoginRateLimitProperties {
        if (capacity <= 0) {
            throw new IllegalArgumentException("wmp.rate-limit.login.capacity must be positive, was " + capacity);
        }
        if (refillPerMinute <= 0) {
            throw new IllegalArgumentException(
                    "wmp.rate-limit.login.refill-per-minute must be positive, was " + refillPerMinute);
        }
    }
}
