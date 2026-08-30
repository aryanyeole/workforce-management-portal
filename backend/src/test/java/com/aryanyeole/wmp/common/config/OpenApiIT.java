package com.aryanyeole.wmp.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.aryanyeole.wmp.support.AbstractIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves springdoc-openapi is wired in and sees every expense endpoint —
 * the actual count-verification tool for Phase 3's "10 expense endpoints"
 * claim, since /swagger-ui.html is meant to make that checkable by a human
 * too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class OpenApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void apiDocsListsAllTenExpenseEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/expenses']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses/{id}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses/categories']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses/approvals']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses/{id}/submit']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses/{id}/approve']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/expenses/{id}/reject']").exists());
    }

    @Test
    void swaggerUiHtmlRedirectsToTheRenderedUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void swaggerUiIndexRenders() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    /**
     * The standing proof of the resume claim ("30 endpoints"): counts
     * method+path combinations under the three domain prefixes only,
     * excluding /auth/** (outside the 30 per ROADMAP) and /actuator/**.
     * Fails loudly the moment the count drifts in either direction —
     * an endpoint added without updating this count, or one removed
     * without noticing, both break the build.
     *
     * PayrollAccrualJob's on-demand trigger (Phase 8) briefly lived at
     * POST /api/v1/payroll/accrual/run and bumped this to 31 — it was
     * relocated to a custom actuator endpoint (PayrollAccrualEndpoint,
     * /actuator/payroll-accrual) specifically because it's operational
     * tooling, not domain API, restoring this count to 30 with no new
     * exclusion needed: actuator endpoints were already outside these
     * domain prefixes.
     */
    @Test
    void apiDocsExposesExactlyThirtyDomainEndpoints() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode paths = root.get("paths");

        Set<String> httpMethodKeys = Set.of("get", "post", "put", "patch", "delete");
        List<String> domainPrefixes = List.of("/api/v1/expenses", "/api/v1/onboarding", "/api/v1/payroll");

        int endpointCount = 0;
        for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
            String path = pathEntry.getKey();
            if (domainPrefixes.stream().noneMatch(path::startsWith)) {
                continue;
            }
            for (String operationName : pathEntry.getValue().propertyNames()) {
                if (httpMethodKeys.contains(operationName)) {
                    endpointCount++;
                }
            }
        }

        assertThat(endpointCount).isEqualTo(30);
    }
}
