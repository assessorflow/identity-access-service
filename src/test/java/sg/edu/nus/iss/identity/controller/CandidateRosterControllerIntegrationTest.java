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


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for CandidateRosterController — /api/v1/roster endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateRosterControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // ── GET /roster ──

    @Test
    void listRoster_empty_returnsEmptyList() throws Exception {
        String token = registerAndGetAccessToken("roster-list@test.com");

        mockMvc.perform(get("/api/v1/roster")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void listRoster_withEntries_returnsAll() throws Exception {
        String token = registerAndGetAccessToken("roster-data@test.com");

        // Add two entries
        addRosterEntry(token, "Alice", "alice@student.com");
        addRosterEntry(token, "Bob", "bob@student.com");

        mockMvc.perform(get("/api/v1/roster")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void listRoster_noAuth_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/roster"))
                .andExpect(status().isForbidden());
    }

    // ── POST /roster ──

    @Test
    void addToRoster_success_returns201() throws Exception {
        String token = registerAndGetAccessToken("roster-add@test.com");

        mockMvc.perform(post("/api/v1/roster")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "name": "Charlie", "email": "charlie@student.com" }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Charlie"))
                .andExpect(jsonPath("$.email").value("charlie@student.com"))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void addToRoster_duplicate_returns409() throws Exception {
        String token = registerAndGetAccessToken("roster-dup@test.com");

        addRosterEntry(token, "Dave", "dave@student.com");

        // Try same email again
        mockMvc.perform(post("/api/v1/roster")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "name": "Dave Again", "email": "dave@student.com" }
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    void addToRoster_missingName_returns400() throws Exception {
        String token = registerAndGetAccessToken("roster-bad@test.com");

        mockMvc.perform(post("/api/v1/roster")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "email": "noname@student.com" }
                        """))
                .andExpect(status().isBadRequest());
    }

    // ── DELETE /roster/{id} ──

    @Test
    void removeFromRoster_success_returns204() throws Exception {
        String token = registerAndGetAccessToken("roster-del@test.com");

        String rosterId = addRosterEntry(token, "Eve", "eve@student.com");

        mockMvc.perform(delete("/api/v1/roster/" + rosterId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Verify deleted
        mockMvc.perform(get("/api/v1/roster")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void removeFromRoster_notFound_returns404() throws Exception {
        String token = registerAndGetAccessToken("roster-nf@test.com");

        mockMvc.perform(delete("/api/v1/roster/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ──

    private String registerAndGetAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                            {
                                "email": "%s",
                                "password": "password123",
                                "full_name": "Test Assessor",
                                "role": "assessor"
                            }
                        """, email)))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("access_token").asText();
    }

    private String addRosterEntry(String token, String name, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/roster")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                            { "name": "%s", "email": "%s" }
                        """, name, email)))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }
}
