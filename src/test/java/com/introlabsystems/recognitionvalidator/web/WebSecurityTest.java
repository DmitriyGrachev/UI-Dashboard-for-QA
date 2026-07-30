package com.introlabsystems.recognitionvalidator.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE review_task, image_asset, app_user CASCADE");
    }

    @Test
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Вход")));
    }

    @Test
    void reviewApiAndImagesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/review"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/login")));
        mockMvc.perform(post("/api/review-tasks/claim").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/login")));
        mockMvc.perform(get("/api/images/missing/content"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/login")));
    }

    @Test
    void validBcryptPasswordLogsOperatorIn() throws Exception {
        insertOperator("operator", "correct-password");

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "operator")
                        .param("password", "correct-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/review"));
    }

    @Test
    void invalidPasswordIsRejected() throws Exception {
        insertOperator("operator", "correct-password");

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "operator")
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    private void insertOperator(String username, String password) {
        String hash = new BCryptPasswordEncoder(12).encode(password);
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, enabled, created_at)
                VALUES (?, ?, ?, TRUE, now())
                """, UUID.randomUUID(), username, hash);
    }
}
