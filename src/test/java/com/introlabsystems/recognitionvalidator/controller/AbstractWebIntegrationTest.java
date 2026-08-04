package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.indexing.ImageIndexer;
import com.introlabsystems.recognitionvalidator.security.OperatorPrincipal;
import com.introlabsystems.recognitionvalidator.service.RejectedScreenshotExportService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AbstractWebIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ValidatorProperties properties;

    @Autowired
    protected ImageIndexer imageIndexer;

    @Autowired
    protected ServerProperties serverProperties;

    @Autowired
    protected RejectedScreenshotExportService rejectedExports;

    protected Path imageRoot;

    @BeforeEach
    void setUpWebIntegrationTest() throws Exception {
        jdbc.execute("TRUNCATE TABLE operator_daily_statistics, review_task, image_asset, app_user CASCADE");
        imageRoot = properties.imageRoot().toAbsolutePath().normalize();
        FileSystemUtils.deleteRecursively(imageRoot);
        Files.createDirectories(imageRoot);
    }

    protected UUID insertOperator(String username, String password) {
        return insertUser(username, password, "OPERATOR");
    }

    protected UUID insertUser(String username, String password, String role) {
        UUID id = UUID.randomUUID();
        String hash = new BCryptPasswordEncoder(12).encode(password);
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, enabled, created_at, role)
                VALUES (?, ?, ?, TRUE, now(), ?)
                """, id, username, hash, role);
        return id;
    }

    protected String insertReviewImage(
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

    protected String insertRejectedExportImage(int number, String fileName, byte[] bytes)
            throws IOException {
        Files.write(imageRoot.resolve(fileName), bytes);
        String imageId = insertReviewImage(
                number, fileName, true, "bj_igt", "export-session-" + number,
                null, "Jack", null
        );
        jdbc.update(
                "UPDATE review_task SET status = 'COMPLETED', decision = 'REJECTED', "
                        + "reviewed_at = now() WHERE image_id = ?",
                imageId
        );
        return imageId;
    }

    protected void insertDailyStatistics(
            UUID operatorId,
            LocalDate date,
            long total,
            long matched,
            long notMatched
    ) {
        jdbc.update("""
                INSERT INTO operator_daily_statistics (
                    operator_id, statistics_date, total_checked,
                    matched_count, not_matched_count
                ) VALUES (?, ?, ?, ?, ?)
                """, operatorId, date, total, matched, notMatched);
    }

    protected void insertOperatorWithoutHash(String username) {
        jdbc.update("""
                INSERT INTO app_user (
                    id, username, password_hash, enabled, created_at, role
                ) VALUES (?, ?, 'unused', TRUE, now(), 'OPERATOR')
                """, UUID.randomUUID(), username);
    }

    protected int countOccurrences(String text, String fragment) {
        return text.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }

    protected Map<String, byte[]> readZipEntries(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    protected OperatorPrincipal principal(UUID id, String username) {
        return new OperatorPrincipal(id, username, "unused", true);
    }
}
