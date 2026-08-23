package com.aryanyeole.wmp.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.aryanyeole.wmp.support.AbstractIntegrationTest;

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
}
