package com.aryanyeole.wmp.common.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Phase 9 Task 1: the frontend dev server (Vite, a different origin) needs
 * CORS to call this API at all. One bean, dev-only, origin from a property
 * — no @CrossOrigin on any controller (keeps this a single cross-cutting
 * concern in one place, same spirit as PermissionRegistry/ADR 0001, even
 * though CORS itself is unrelated to authorization).
 *
 * @Profile("dev") means this bean simply does not exist outside the dev
 * profile — SecurityConfig's cors(Customizer.withDefaults()) call looks up
 * a CorsConfigurationSource bean if one is present and is a no-op if none
 * is found, so main/test/prod get no CORS support at all, not a
 * permissive default. See docs/... (Phase 9 Task 1b) for the verification
 * that this is genuinely a no-op outside "dev", not just believed to be.
 *
 * No allowCredentials: this app authenticates via a Bearer header, not
 * cookies, so cross-origin credentials were never needed.
 */
@Configuration
@Profile("dev")
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${wmp.cors.allowed-origin:http://localhost:5173}") String allowedOrigin) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }
}
