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
    void loginPageExplainsExpiredSession() throws Exception {
        mockMvc.perform(get("/login").param("expired", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Your session was closed. Sign in again to continue."
                )));
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
    void adminPageRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/admin")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Operator management")));
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
    void adminLoginRedirectsToAdminAndCannotOpenReviewQueue() throws Exception {
        insertUser("admin", "correct-password", "ADMIN");

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "correct-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));

        mockMvc.perform(get("/review")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesEnabledOperatorWithHashedPassword() throws Exception {
        mockMvc.perform(post("/admin/operators")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("username", "new-operator")
                        .param("password", "new-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin?created"));

        var created = jdbc.queryForMap("""
                SELECT username, password_hash, enabled, role
                FROM app_user
                WHERE username = 'new-operator'
                """);
        assertThat(created.get("username")).isEqualTo("new-operator");
        assertThat(created.get("enabled")).isEqualTo(true);
        assertThat(created.get("role")).isEqualTo("OPERATOR");
        assertThat(new BCryptPasswordEncoder(12).matches(
                "new-password",
                (String) created.get("password_hash")
        )).isTrue();
    }

    @Test
    void duplicateUsernameReturnsFriendlyAdminError() throws Exception {
        insertOperator("existing-operator", "operator-password");

        mockMvc.perform(post("/admin/operators")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("username", "existing-operator")
                        .param("password", "new-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin?error=username"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE username = 'existing-operator'",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void deactivatingOperatorExpiresSessionAndReleasesAssignment() throws Exception {
        UUID operatorId = insertOperator("active-operator", "operator-password");
        String imageId = insertReviewImage(
                120, "assigned.png", true, "bj_igt", "session",
                null, "Jack", null
        );
        jdbc.update("""
                UPDATE review_task
                SET status = 'ASSIGNED',
                    assigned_to = ?,
                    assigned_at = now(),
                    lease_expires_at = now() + interval '30 minutes'
                WHERE image_id = ?
                """, operatorId, imageId);

        MvcResult login = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "active-operator")
                        .param("password", "operator-password"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession operatorSession =
                (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/admin/operators/{id}/deactivate", operatorId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin?deactivated"));

        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM app_user WHERE id = ?",
                Boolean.class,
                operatorId
        )).isFalse();
        var released = jdbc.queryForMap("""
                SELECT status, assigned_to
                FROM review_task
                WHERE image_id = ?
                """, imageId);
        assertThat(released.get("status")).isEqualTo("PENDING");
        assertThat(released.get("assigned_to")).isNull();

        mockMvc.perform(get("/review").session(operatorSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void adminRestoresDeactivatedOperator() throws Exception {
        UUID operatorId = insertOperator("disabled-operator", "operator-password");
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", operatorId);

        mockMvc.perform(post("/admin/operators/{id}/restore", operatorId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin?restored"));

        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM app_user WHERE id = ?",
                Boolean.class,
                operatorId
        )).isTrue();
    }

    @Test
    void changingPasswordExpiresSessionAndReplacesOldPassword() throws Exception {
        UUID operatorId = insertOperator("password-operator", "old-password");
        MvcResult login = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "password-operator")
                        .param("password", "old-password"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession operatorSession =
                (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/admin/operators/{id}/password", operatorId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("password", "new-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin?passwordChanged"));

        String passwordHash = jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?",
                String.class,
                operatorId
        );
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        assertThat(encoder.matches("new-password", passwordHash)).isTrue();
        assertThat(encoder.matches("old-password", passwordHash)).isFalse();

        mockMvc.perform(get("/review").session(operatorSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "password-operator")
                        .param("password", "old-password"))
                .andExpect(redirectedUrl("/login?error"));
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "password-operator")
                        .param("password", "new-password"))
                .andExpect(redirectedUrl("/review"));
    }

    @Test
    void adminDashboardShowsOperatorStatisticsForLastSevenDays() throws Exception {
        UUID operatorId = insertOperator("operator-one", "operator-password");
        UUID inactiveId = insertOperator("operator-two", "operator-password");
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", inactiveId);

        String acceptedToday = insertReviewImage(
                130, "accepted-today.png", true, "bj_igt", "session-1",
                null, "Jack", null
        );
        String rejectedRecent = insertReviewImage(
                131, "rejected-recent.png", true, "bj_igt", "session-2",
                null, "Nine", null
        );
        String acceptedOld = insertReviewImage(
                132, "accepted-old.png", true, "bj_igt", "session-3",
                null, "Seven", null
        );
        completeReview(acceptedToday, operatorId, "ACCEPTED", "1 hour");
        completeReview(rejectedRecent, operatorId, "REJECTED", "2 days");
        completeReview(acceptedOld, operatorId, "ACCEPTED", "8 days");

        mockMvc.perform(get("/admin")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "data-operator=\"operator-one\" data-today=\"1\" "
                                + "data-total=\"2\" data-accepted=\"1\" data-rejected=\"1\""
                )))
                .andExpect(content().string(containsString(
                        "data-operator=\"operator-two\" data-today=\"0\" "
                                + "data-total=\"0\" data-accepted=\"0\" data-rejected=\"0\""
                )));
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
        return insertUser(username, password, "OPERATOR");
    }

    private UUID insertUser(String username, String password, String role) {
        UUID id = UUID.randomUUID();
        String hash = new BCryptPasswordEncoder(12).encode(password);
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, enabled, created_at, role)
                VALUES (?, ?, ?, TRUE, now(), ?)
                """, id, username, hash, role);
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

    private void completeReview(
            String imageId,
            UUID operatorId,
            String decision,
            String age
    ) {
        jdbc.update("""
                UPDATE review_task
                SET status = 'COMPLETED',
                    assigned_to = ?,
                    decision = ?,
                    reviewed_at = now() - CAST(? AS interval)
                WHERE image_id = ?
                """, operatorId, decision, age, imageId);
    }

    private OperatorPrincipal principal(UUID id, String username) {
        return new OperatorPrincipal(id, username, "unused", true);
    }
}
