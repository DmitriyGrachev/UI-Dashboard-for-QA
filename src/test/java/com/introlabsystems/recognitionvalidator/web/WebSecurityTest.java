package com.introlabsystems.recognitionvalidator.web;

import com.introlabsystems.recognitionvalidator.auth.OperatorPrincipal;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.image.ImageIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @Autowired
    private ValidatorProperties properties;

    @Autowired
    private ImageIndexer imageIndexer;

    @Autowired
    private ServerProperties serverProperties;

    private Path imageRoot;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE TABLE review_task, image_asset, app_user CASCADE");
        imageRoot = properties.imageRoot().toAbsolutePath().normalize();
        FileSystemUtils.deleteRecursively(imageRoot);
        Files.createDirectories(imageRoot);
    }

    @Test
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Operator sign in")));
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
        mockMvc.perform(get("/statistics"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/login")));
        mockMvc.perform(get("/api/statistics/me"))
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

    @Test
    void disabledOperatorCannotLogIn() throws Exception {
        UUID operatorId = insertOperator("disabled", "correct-password");
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", operatorId);

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "disabled")
                        .param("password", "correct-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void logoutInvalidatesTheOperatorSession() throws Exception {
        insertOperator("logout-operator", "password");
        MvcResult login = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "logout-operator")
                        .param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/logout")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void sessionTimeoutIsConfiguredForTwentyFourHours() {
        assertThat(serverProperties.getServlet().getSession().getTimeout())
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    void authenticatedPostWithoutCsrfIsRejected() throws Exception {
        UUID operatorId = insertOperator("csrf-operator", "password");

        mockMvc.perform(post("/api/review-tasks/claim")
                        .with(user(principal(operatorId, "csrf-operator")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void claimReturnsParsedMetadataWithoutAbsolutePath() throws Exception {
        UUID operatorId = insertOperator("api-operator", "password");
        String imageId = insertReviewImage(
                100,
                "screen.png",
                true,
                "bj_igt",
                "39_session",
                "Eight",
                "Seven_Jack",
                "A10J3"
        );

        mockMvc.perform(post("/api/review-tasks/claim")
                        .with(user(principal(operatorId, "api-operator")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(imageId))
                .andExpect(jsonPath("$.fileName").value("screen.png"))
                .andExpect(jsonPath("$.dealerCards").value("Eight"))
                .andExpect(jsonPath("$.activeUserCards").value("Seven_Jack"))
                .andExpect(jsonPath("$.inactiveUserCards").value("A10J3"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString(imageRoot.toString())
                )));
    }

    @Test
    void claimExposesParsedSurrenderFlag() throws Exception {
        UUID operatorId = insertOperator("surrender-operator", "password");
        String fileName = "bj_double_deck_black_throne_36_"
                + "2e8c1326-fb86-4c51-8e45-8bc65e6d33ee"
                + "_d_Four_u_Nine_Seven_bSbHbDbSR_27-07-2026-12-36-56_481.png";
        Files.write(imageRoot.resolve(fileName), new byte[]{1, 2, 3});
        imageIndexer.scanRoot();

        mockMvc.perform(post("/api/review-tasks/claim")
                        .with(user(principal(operatorId, "surrender-operator")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value(fileName))
                .andExpect(jsonPath("$.buttonsRaw").value("bSbHbDbSR"))
                .andExpect(jsonPath("$.surrender").value(true));
    }

    @Test
    void emptyQueueReturnsNoContent() throws Exception {
        UUID operatorId = insertOperator("empty-operator", "password");

        mockMvc.perform(post("/api/review-tasks/claim")
                        .with(user(principal(operatorId, "empty-operator")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void repeatedDecisionReturnsConflict() throws Exception {
        UUID operatorId = insertOperator("decision-operator", "password");
        String imageId = insertReviewImage(
                101, "decision.png", true, "bj_igt", "session",
                null, "Jack", null
        );
        OperatorPrincipal principal = principal(operatorId, "decision-operator");
        mockMvc.perform(post("/api/review-tasks/claim")
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        String decision = "{\"decision\":\"REJECTED\"}";
        mockMvc.perform(post("/api/review-tasks/{imageId}/decision", imageId)
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/review-tasks/{imageId}/decision", imageId)
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPTED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidDateRangeReturnsBadRequest() throws Exception {
        UUID operatorId = insertOperator("date-operator", "password");

        mockMvc.perform(post("/api/review-tasks/claim")
                        .with(user(principal(operatorId, "date-operator")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "createdFrom": "2026-07-31T00:00:00Z",
                                  "createdTo": "2026-07-30T00:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void imageEndpointReturnsExactBytesAndSafeHeaders() throws Exception {
        UUID operatorId = insertOperator("image-operator", "password");
        byte[] bytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};
        Files.write(imageRoot.resolve("exact.png"), bytes);
        String imageId = insertReviewImage(
                102, "exact.png", true, "bj_igt", "session",
                null, "Jack", null
        );

        mockMvc.perform(get("/api/images/{imageId}/content", imageId)
                        .with(user(principal(operatorId, "image-operator"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Disposition", containsString("inline")));
    }

    @Test
    void missingUnavailableAndEscapingImagesReturnNotFound() throws Exception {
        UUID operatorId = insertOperator("missing-operator", "password");
        OperatorPrincipal principal = principal(operatorId, "missing-operator");
        String unavailable = insertReviewImage(
                103, "unavailable.png", false, "bj_igt", "session",
                null, "Jack", null
        );
        String escaping = insertReviewImage(
                104, "../outside.png", true, "bj_igt", "session",
                null, "Jack", null
        );

        mockMvc.perform(get("/api/images/{imageId}/content", "f".repeat(64))
                        .with(user(principal)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/images/{imageId}/content", unavailable)
                        .with(user(principal)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/images/{imageId}/content", escaping)
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void statisticsPageAndApiRenderForAuthenticatedOperator() throws Exception {
        UUID operatorId = insertOperator("statistics-operator", "password");
        OperatorPrincipal principal = principal(operatorId, "statistics-operator");

        mockMvc.perform(get("/review")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Review filters")))
                .andExpect(content().string(containsString("Matches")));
        mockMvc.perform(get("/statistics")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Operator statistics")))
                .andExpect(content().string(containsString("statistics-operator")));
        mockMvc.perform(get("/api/statistics/me")
                        .param("from", "2026-07-29")
                        .param("to", "2026-07-30")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(0))
                .andExpect(jsonPath("$.periodTotal").value(0))
                .andExpect(jsonPath("$.acceptedPercent").value(0.00));
    }

    private UUID insertOperator(String username, String password) {
        UUID id = UUID.randomUUID();
        String hash = new BCryptPasswordEncoder(12).encode(password);
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, enabled, created_at)
                VALUES (?, ?, ?, TRUE, now())
                """, id, username, hash);
        return id;
    }

    private String insertReviewImage(
            int number,
            String relativePath,
            boolean available,
            String gameCode,
            String sessionId,
            String dealerCards,
            String activeCards,
            String inactiveCards
    ) {
        String id = "%064x".formatted(number);
        Instant createdAt = Instant.parse("2026-07-30T10:00:00Z");
        jdbc.update("""
                INSERT INTO image_asset (
                    id, file_name, relative_path, file_created_at, file_modified_at,
                    discovered_at, last_seen_at, file_available, game_code, session_id,
                    dealer_cards, active_user_cards, inactive_user_cards,
                    is_notification, has_stand, has_hit, has_double, has_split,
                    processed_at, recognition_duration_ms, parse_status
                ) VALUES (
                    ?, ?, ?, ?, ?, now(), now(), ?, ?, ?,
                    ?, ?, ?, FALSE, TRUE, TRUE, FALSE, FALSE,
                    ?, 754, 'SUCCESS'
                )
                """,
                id,
                Path.of(relativePath).getFileName().toString(),
                relativePath,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                available,
                gameCode,
                sessionId,
                dealerCards,
                activeCards,
                inactiveCards,
                Timestamp.from(createdAt)
        );
        jdbc.update(
                "INSERT INTO review_task (image_id, status) VALUES (?, 'PENDING')",
                id
        );
        return id;
    }

    private OperatorPrincipal principal(UUID id, String username) {
        return new OperatorPrincipal(id, username, "unused", true);
    }
}
