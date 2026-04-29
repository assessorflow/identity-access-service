package sg.edu.nus.iss.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AuthController — tests actual HTTP flow
 * through Spring Security, validation, and H2 database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AuthControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // ── Register ──

    @Test
    void register_success_returns201WithTokens() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "email": "new@test.com",
                                "password": "password123",
                                "full_name": "New User",
                                "role": "assessor"
                            }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.expires_in").value(900))
                .andExpect(jsonPath("$.user.email").value("new@test.com"))
                .andExpect(jsonPath("$.user.full_name").value("New User"))
                .andExpect(jsonPath("$.user.role").value("assessor"));
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        // Register first
        registerUser("dup@test.com");

        // Try again
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "email": "dup@test.com",
                                "password": "password123",
                                "full_name": "Dup User",
                                "role": "assessor"
                            }
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    void register_adminRole_viaPubicEndpoint_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "email": "admin@test.com",
                                "password": "password123",
                                "full_name": "Admin",
                                "role": "admin"
                            }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_missingEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "password": "password123",
                                "full_name": "No Email",
                                "role": "assessor"
                            }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "email": "not-an-email",
                                "password": "password123",
                                "full_name": "Bad Email",
                                "role": "assessor"
                            }
                        """))
                .andExpect(status().isBadRequest());
    }

    // ── Login ──

    @Test
    void login_success_returnsTokens() throws Exception {
        registerUser("login@test.com");

        // Small delay to ensure JWT iat timestamp differs from register
        Thread.sleep(1100);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "email": "login@test.com",
                                "password": "password123"
                            }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("login@test.com"));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        registerUser("wrongpw@test.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "email": "wrongpw@test.com",
                                "password": "wrong"
                            }
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_nonExistentUser_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "email": "nobody@test.com",
                                "password": "password123"
                            }
                        """))
                .andExpect(status().isUnauthorized());
    }

    // ── Refresh ──

    @Test
    void refresh_success_returnsNewAccessToken() throws Exception {
        String refreshToken = registerAndGetRefreshToken("refresh@test.com");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                            { "refresh_token": "%s" }
                        """, refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.expires_in").value(900));
    }

    @Test
    void refresh_invalidToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "refresh_token": "invalid-token" }
                        """))
                .andExpect(status().isUnauthorized());
    }

    // ── Logout ──

    @Test
    void logout_withValidToken_returns204() throws Exception {
        String accessToken = registerAndGetAccessToken("logout@test.com");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void logout_withoutToken_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
    }

    // ── Helpers ──

    private void registerUser(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                    {
                        "email": "%s",
                        "password": "password123",
                        "full_name": "Test User",
                        "role": "assessor"
                    }
                """, email)));
    }

    private String registerAndGetAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                            {
                                "email": "%s",
                                "password": "password123",
                                "full_name": "Test User",
                                "role": "assessor"
                            }
                        """, email)))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("access_token").asText();
    }

    private String registerAndGetRefreshToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                            {
                                "email": "%s",
                                "password": "password123",
                                "full_name": "Test User",
                                "role": "assessor"
                            }
                        """, email)))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("refresh_token").asText();
    }
}