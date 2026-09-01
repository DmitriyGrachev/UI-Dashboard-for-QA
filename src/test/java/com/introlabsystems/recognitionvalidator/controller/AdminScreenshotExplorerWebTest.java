package com.introlabsystems.recognitionvalidator.controller;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminScreenshotExplorerWebTest extends AbstractWebIntegrationTest {

    @Test
    void adminCanFilterCheckedScreenshotsWithoutWaitingForSummary() throws Exception {
        UUID operatorId = insertOperator("reviewer", "password");
        String checkedInside = insertReviewImage(
                701, "checked-inside.png", true, "bj_americas_ags", "session-checked",
                "Eight", "Three_Three", null
        );
        String uncheckedInside = insertReviewImage(
                702, "unchecked-inside.png", true, "bj_americas_ags", "session-unchecked",
                "Nine", "Five_Six", null
        );
        String checkedOutside = insertReviewImage(
                703, "checked-outside.png", true, "bj_americas_ags", "session-outside",
                "Ten", "Seven_Four", null
        );
        setCreatedAt(checkedInside, "2026-08-28T10:00:00Z");
        setCreatedAt(uncheckedInside, "2026-08-28T11:00:00Z");
        setCreatedAt(checkedOutside, "2026-08-27T23:59:59Z");
        complete(checkedInside, operatorId, "ACCEPTED", "2026-08-28T12:00:00Z");
        complete(checkedOutside, operatorId, "REJECTED", "2026-08-28T12:01:00Z");

        mockMvc.perform(get("/admin/api/screenshots")
                        .param("reviewState", "CHECKED")
                        .param("createdFrom", "2026-08-28T00:00:00Z")
                        .param("createdTo", "2026-08-29T00:00:00Z")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].imageId").value(checkedInside))
                .andExpect(jsonPath("$.items[0].reviewState").value("CHECKED"))
                .andExpect(jsonPath("$.totalCount").doesNotExist())
                .andExpect(jsonPath("$.oldestCreatedAt").doesNotExist())
                .andExpect(jsonPath("$.newestCreatedAt").doesNotExist());
    }

    @Test
    void summaryReturnsCountAndUtcRangeForTheSameFilters() throws Exception {
        UUID operatorId = insertOperator("summary-reviewer", "password");
        String checkedInside = insertReviewImage(
                713, "summary-checked.png", true, "bj_americas_ags", "summary-checked",
                "Eight", "Three_Three", null
        );
        String uncheckedInside = insertReviewImage(
                714, "summary-unchecked.png", true, "bj_americas_ags", "summary-unchecked",
                "Nine", "Five_Six", null
        );
        setCreatedAt(checkedInside, "2026-08-28T10:00:00Z");
        setCreatedAt(uncheckedInside, "2026-08-28T11:00:00Z");
        complete(checkedInside, operatorId, "ACCEPTED", "2026-08-28T12:00:00Z");

        mockMvc.perform(get("/admin/api/screenshots/summary")
                        .param("reviewState", "CHECKED")
                        .param("createdFrom", "2026-08-28T00:00:00Z")
                        .param("createdTo", "2026-08-29T00:00:00Z")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.oldestCreatedAt").value("2026-08-28T10:00:00Z"))
                .andExpect(jsonPath("$.newestCreatedAt").value("2026-08-28T10:00:00Z"));
    }

    @Test
    void uncheckedIncludesPendingAndAssignedButNotCompleted() throws Exception {
        UUID operatorId = insertOperator("active-reviewer", "password");
        String pending = insertReviewImage(
                704, "pending.png", true, "bj_igt", "session-pending",
                null, "Jack_Two", null
        );
        String assigned = insertReviewImage(
                705, "assigned.png", true, "bj_igt", "session-assigned",
                null, "Queen_Three", null
        );
        String completed = insertReviewImage(
                706, "completed.png", true, "bj_igt", "session-completed",
                null, "King_Four", null
        );
        setCreatedAt(pending, "2026-08-28T09:00:00Z");
        setCreatedAt(assigned, "2026-08-28T10:00:00Z");
        setCreatedAt(completed, "2026-08-28T11:00:00Z");
        assign(assigned, operatorId);
        complete(completed, operatorId, "ACCEPTED", "2026-08-28T12:00:00Z");

        mockMvc.perform(get("/admin/api/screenshots")
                        .param("reviewState", "UNCHECKED")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].imageId").value(assigned))
                .andExpect(jsonPath("$.items[0].reviewState").value("UNCHECKED"))
                .andExpect(jsonPath("$.items[1].imageId").value(pending))
                .andExpect(jsonPath("$.items[1].reviewState").value("UNCHECKED"));
    }

    @Test
    void adminCanCombineMetadataReviewAndStorageFilters() throws Exception {
        UUID reviewerId = insertOperator("Daria", "password");
        String target = insertReviewImage(
                707, "target-rejected.png", true, "bj_americas_ags", "37_target-session",
                "Eight", "Three_Three_Ten", null
        );
        String other = insertReviewImage(
                708, "other-rejected.png", true, "bj_americas_ags", "37_other-session",
                "Eight", "Three_Four", null
        );
        setCreatedAt(target, "2026-08-28T13:00:00Z");
        setCreatedAt(other, "2026-08-28T13:01:00Z");
        complete(target, reviewerId, "REJECTED", "2026-08-28T14:00:00Z");
        complete(other, reviewerId, "REJECTED", "2026-08-28T14:01:00Z");
        jdbc.update("""
                UPDATE image_asset
                SET token_id = 32,
                    is_notification = TRUE,
                    parse_status = 'ERROR',
                    cloud_object_key = 'validator/target-rejected.png',
                    cloud_uploaded_at = now()
                WHERE id = ?
                """, target);
        jdbc.update(
                "UPDATE review_task SET is_notification = TRUE WHERE image_id = ?",
                target
        );

        mockMvc.perform(get("/admin/api/screenshots")
                        .param("reviewState", "CHECKED")
                        .param("gameCode", "bj_americas_ags")
                        .param("tokenId", "32")
                        .param("sessionId", "37_target-session")
                        .param("imageId", target)
                        .param("fileName", "target-rejected.png")
                        .param("decision", "REJECTED")
                        .param("reviewedBy", "Daria")
                        .param("storageState", "BOTH")
                        .param("parseStatus", "ERROR")
                        .param("notification", "true")
                        .param("hasUserHand", "true")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].imageId").value(target))
                .andExpect(jsonPath("$.items[0].storageState").value("BOTH"));
    }

    @Test
    void cursorPaginationDoesNotSkipRowsWithTheSameCreationTime() throws Exception {
        String first = insertReviewImage(
                709, "cursor-first.png", true, "bj_igt", "cursor-session",
                null, "Two_Three", null
        );
        String second = insertReviewImage(
                710, "cursor-second.png", true, "bj_igt", "cursor-session",
                null, "Four_Five", null
        );
        String newest = insertReviewImage(
                711, "cursor-newest.png", true, "bj_igt", "cursor-session",
                null, "Six_Seven", null
        );
        setCreatedAt(first, "2026-08-28T15:00:00Z");
        setCreatedAt(second, "2026-08-28T15:00:00Z");
        setCreatedAt(newest, "2026-08-28T16:00:00Z");
        jdbc.update(
                "UPDATE image_asset SET file_created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-08-27T14:00:00Z")),
                second
        );

        mockMvc.perform(get("/admin/api/screenshots")
                        .param("sessionId", "cursor-session")
                        .param("limit", "2")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].imageId").value(second))
                .andExpect(jsonPath("$.items[1].fileCreatedAt")
                        .value("2026-08-28T15:00:00Z"))
                .andExpect(jsonPath("$.nextCreatedAt").value("2026-08-28T15:00:00Z"))
                .andExpect(jsonPath("$.nextId").value(second));

        mockMvc.perform(get("/admin/api/screenshots/{imageId}", second)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileCreatedAt").value("2026-08-28T15:00:00Z"));

        mockMvc.perform(get("/admin/api/screenshots")
                        .param("sessionId", "cursor-session")
                        .param("limit", "2")
                        .param("cursorCreatedAt", "2026-08-28T15:00:00Z")
                        .param("cursorId", second)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].imageId").value(first))
                .andExpect(jsonPath("$.nextCreatedAt").doesNotExist())
                .andExpect(jsonPath("$.nextId").doesNotExist());
    }

    @Test
    void freshSchemaContainsIndexesForAdminOrderingAndExactFileLookup() {
        var indexNames = jdbc.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN (
                      'ix_admin_review_order',
                      'ix_admin_review_state_order',
                      'ix_admin_review_game_order',
                      'ix_admin_image_file_name'
                  )
                """, String.class);

        assertThat(indexNames).containsExactlyInAnyOrder(
                "ix_admin_review_order",
                "ix_admin_review_state_order",
                "ix_admin_review_game_order",
                "ix_admin_image_file_name"
        );
    }

    @Test
    void invalidDateReviewAndCursorCombinationsAreRejected() throws Exception {
        var admin = user("admin").roles("ADMIN");

        mockMvc.perform(get("/admin/api/screenshots")
                        .param("createdFrom", "2026-08-29T00:00:00Z")
                        .param("createdTo", "2026-08-28T00:00:00Z")
                        .with(admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/admin/api/screenshots")
                        .param("reviewState", "UNCHECKED")
                        .param("decision", "REJECTED")
                        .with(admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/admin/api/screenshots")
                        .param("cursorCreatedAt", "2026-08-28T00:00:00Z")
                        .with(admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanReadCompleteScreenshotDetails() throws Exception {
        UUID reviewerId = insertOperator("Andrey", "password");
        String imageId = insertReviewImage(
                712, "details.png", true, "bj_americas_ags", "32_details-session",
                "Eight", "Three_Three_Ten", "Ace_Four"
        );
        setCreatedAt(imageId, "2026-08-28T17:00:00Z");
        complete(imageId, reviewerId, "REJECTED", "2026-08-28T18:00:00Z");
        jdbc.update("""
                UPDATE image_asset
                SET token_id = 32,
                    payload_raw = 'd_Eight_u_Three_Three_Ten',
                    buttons_raw = 'bSbH',
                    is_notification = TRUE,
                    has_double = TRUE,
                    has_split = TRUE,
                    recognition_duration_ms = 1134,
                    processed_at = ?
                WHERE id = ?
                """, Timestamp.from(Instant.parse("2026-08-28T17:00:02Z")), imageId);

        mockMvc.perform(get("/admin/api/screenshots/{imageId}", imageId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(imageId))
                .andExpect(jsonPath("$.fileName").value("details.png"))
                .andExpect(jsonPath("$.fileCreatedAt").value("2026-08-28T17:00:00Z"))
                .andExpect(jsonPath("$.processedAt").value("2026-08-28T17:00:02Z"))
                .andExpect(jsonPath("$.gameCode").value("bj_americas_ags"))
                .andExpect(jsonPath("$.tokenId").value(32))
                .andExpect(jsonPath("$.sessionId").value("32_details-session"))
                .andExpect(jsonPath("$.dealerCards").value("Eight"))
                .andExpect(jsonPath("$.activeUserCards").value("Three_Three_Ten"))
                .andExpect(jsonPath("$.inactiveUserCards").value("Ace_Four"))
                .andExpect(jsonPath("$.payloadRaw").value("d_Eight_u_Three_Three_Ten"))
                .andExpect(jsonPath("$.buttonsRaw").value("bSbH"))
                .andExpect(jsonPath("$.notification").value(true))
                .andExpect(jsonPath("$.doubleAction").value(true))
                .andExpect(jsonPath("$.split").value(true))
                .andExpect(jsonPath("$.recognitionDurationMs").value(1134))
                .andExpect(jsonPath("$.parseStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.reviewState").value("CHECKED"))
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.reviewedBy").value("Andrey"))
                .andExpect(jsonPath("$.reviewedAt").value("2026-08-28T18:00:00Z"))
                .andExpect(jsonPath("$.storageState").value("LOCAL_ONLY"))
                .andExpect(jsonPath("$.imageUrl")
                        .value("/admin/api/screenshots/" + imageId + "/content"))
                .andExpect(jsonPath("$.downloadUrl")
                        .value("/admin/api/screenshots/" + imageId + "/download"));
    }

    private void setCreatedAt(String imageId, String instant) {
        Timestamp timestamp = Timestamp.from(Instant.parse(instant));
        jdbc.update(
                "UPDATE image_asset SET file_created_at = ?, file_modified_at = ? WHERE id = ?",
                timestamp,
                timestamp,
                imageId
        );
        jdbc.update(
                "UPDATE review_task SET file_created_at = ? WHERE image_id = ?",
                timestamp,
                imageId
        );
    }

    private void complete(String imageId, UUID operatorId, String decision, String reviewedAt) {
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', assigned_to = ?, decision = ?, "
                        + "reviewed_at = ? WHERE image_id = ?",
                operatorId,
                decision,
                Timestamp.from(Instant.parse(reviewedAt)),
                imageId
        );
    }

    private void assign(String imageId, UUID operatorId) {
        jdbc.update(
                "UPDATE review_task SET status = 'ASSIGNED', assigned_to = ?, "
                        + "assigned_at = now(), lease_expires_at = now() + INTERVAL '30 minutes' "
                        + "WHERE image_id = ?",
                operatorId,
                imageId
        );
    }
}
