package com.aryanyeole.wmp.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.aryanyeole.wmp.common.logging.CorrelationIdFilter;
import com.aryanyeole.wmp.common.security.JwtAuthenticationFilter;
import com.aryanyeole.wmp.common.security.RateLimitFilter;
import com.aryanyeole.wmp.common.security.RouteAuthorizationFilter;

@Configuration
public class SecurityConfig {

    private final CorrelationIdFilter correlationIdFilter;
    private final RateLimitFilter rateLimitFilter;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RouteAuthorizationFilter routeAuthorizationFilter;

    public SecurityConfig(CorrelationIdFilter correlationIdFilter,
                          RateLimitFilter rateLimitFilter,
                          JwtAuthenticationFilter jwtAuthenticationFilter,
                          RouteAuthorizationFilter routeAuthorizationFilter) {
        this.correlationIdFilter = correlationIdFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.routeAuthorizationFilter = routeAuthorizationFilter;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                // Picks up whatever CorsConfigurationSource bean is in context
                // (only CorsConfig's, dev-profile-only) — a no-op everywhere
                // else, since there's nothing to configure with. See CorsConfig.
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Spring's own matcher DSL is intentionally left permissive:
                // RouteAuthorizationFilter is the single enforcement point, and
                // splitting rules across both would defeat the purpose.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // First filter in the whole chain: anchored to
                // JwtAuthenticationFilter's own position, same pattern
                // routeAuthorizationFilter below uses to anchor itself —
                // two addFilterBefore calls both pointing at the same
                // built-in class don't reliably order relative to each
                // other, so a 401 from JwtAuthenticationFilter and a 403
                // from RouteAuthorizationFilter both need MDC already set,
                // which means this has to be anchored before the first of
                // the two, not the built-in class both of them precede.
                .addFilterBefore(correlationIdFilter, JwtAuthenticationFilter.class)
                // Anchored after correlationIdFilter specifically (same
                // reasoning as above, not after the built-in class jwt is
                // itself anchored to) so it's chained strictly between the
                // two: correlated (so a 429 carries an ID) but still ahead
                // of authentication (so a flood is rejected before this
                // spends any work parsing a JWT or resolving a permission).
                .addFilterAfter(rateLimitFilter, CorrelationIdFilter.class)
                .addFilterAfter(routeAuthorizationFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}