package com.zhituagent.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test verifying multi-tenant isolation.
 *
 * Covers the full request lifecycle:
 * 1. Register/login to obtain JWT tokens (tenant-scoped)
 * 2. Create sessions under a specific tenant
 * 3. Verify cross-tenant access is denied (TenantAwareSessionRepository filters by tenant)
 */
@SpringBootTest
@AutoConfigureMockMvc
class MultiTenantIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void tenantsShouldBeIsolated() throws Exception {
        // Register tenant-1 user
        String token1 = registerAndLogin("e2e-tenant-1", "e2e-user1@example.com", "pass123");

        // Register tenant-2 user
        String token2 = registerAndLogin("e2e-tenant-2", "e2e-user2@example.com", "pass123");

        // Create session as tenant-1
        String sessionId = createSession(token1, "Tenant 1 Session");

        // tenant-2 should NOT see tenant-1's session
        // TenantAwareSessionRepository.findById filters by tenantId from TenantContext;
        // when mismatched, returns Optional.empty() -> SessionService throws ApiException(SESSION_NOT_FOUND)
        // GlobalExceptionHandler maps ApiException to 400 BAD_REQUEST
        mockMvc.perform(get("/api/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));

        // tenant-1 SHOULD see their own session
        mockMvc.perform(get("/api/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.sessionId").value(sessionId))
                .andExpect(jsonPath("$.session.title").value("Tenant 1 Session"));
    }

    private String registerAndLogin(String tenantId, String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                    "tenantId": "%s",
                                    "email": "%s",
                                    "password": "%s"
                                }
                                """, tenantId, email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(response);
        return json.get("token").asText();
    }

    private String createSession(String token, String title) throws Exception {
        String response = mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                    "userId": "placeholder",
                                    "title": "%s"
                                }
                                """, title)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(response);
        return json.get("sessionId").asText();
    }
}
