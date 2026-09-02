package com.aryanyeole.wmp.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.aryanyeole.wmp.auth.api.LoginRequest;
import com.aryanyeole.wmp.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/**
 * Proves RateLimitFilter actually fires — AbstractIntegrationTest disables
 * the limiter by default (see its own comment), so this class re-enables it
 * locally with a small, deterministic capacity via its own
 * {@code @TestPropertySource}, which Spring merges with subclass values
 * taking precedence over the inherited ones.
 *
 * Every request here carries its own X-Real-IP so this class's bucket state
 * (one ConcurrentHashMap, shared across every @Test method in this run) never
 * leaks between test methods — each test claims a distinct simulated
 * client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "wmp.rate-limit.login.enabled=true",
        "wmp.rate-limit.login.capacity=3",
        "wmp.rate-limit.login.refill-per-minute=60"
})
class RateLimitFilterIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String loginBody() throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest("nobody@wmp-ratelimit.dev", "wrong-password"));
    }

    @Test
    void requestsWithinCapacityAreNotLimited() throws Exception {
        String ip = "203.0.113.10";
        // capacity=3: all three land as real (failed) auth attempts, not 429s.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Real-IP", ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void requestBeyondCapacityIsRejectedWith429() throws Exception {
        String ip = "203.0.113.20";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Real-IP", ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody()))
                    .andExpect(status().isUnauthorized());
        }

        // 4th request from the same IP, capacity already spent.
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Real-IP", ip)
                        .header("X-Correlation-Id", "rate-limit-it-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-Correlation-Id", "rate-limit-it-trace"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.correlationId").value("rate-limit-it-trace"));
    }

    @Test
    void differentIpIsUnaffectedByAnotherIpsLimit() throws Exception {
        String exhaustedIp = "203.0.113.30";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Real-IP", exhaustedIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody()))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Real-IP", exhaustedIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andExpect(status().isTooManyRequests());

        // A different IP has its own, untouched bucket.
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Real-IP", "203.0.113.31")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andExpect(status().isUnauthorized());
    }
}
