package com.introlabsystems.recognitionvalidator.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "validator.b2.enabled=true",
        "validator.b2.endpoint=https://s3.eu-central-003.backblazeb2.com",
        "validator.b2.bucket=dummy-test-bucket",
        "validator.b2.access-key-id=dummy-access-key",
        "validator.b2.secret-access-key=dummy-secret-key",
        "validator.b2.object-prefix=validator/",
        "validator.b2.upload-batch-size=10",
        "validator.b2.upload-concurrency=2",
        "validator.b2.upload-delay=25ms",
        "validator.b2.upload-retry-delay=5m",
        "validator.b2.local-preferred-age=3d",
        "validator.b2.presigned-url-ttl=30m",
        "validator.b2.metadata-retention=21d"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CloudUploadSchedulerEnabledWiringTest {

    private static final Path TEST_IMAGE = Path.of("target", "test-images", "spring-wired.png")
            .toAbsolutePath()
            .normalize();

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("b2UploadExecutor")
    private ExecutorService uploadExecutor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CloudUploadScheduler scheduler;

    @MockitoBean
    private com.introlabsystems.recognitionvalidator.storage.CloudObjectStorage cloudStorage;

    @MockitoBean(name = "taskScheduler")
    private TaskScheduler taskScheduler;

    @BeforeEach
    void emptyTestQueue() throws Exception {
        Files.deleteIfExists(TEST_IMAGE);
        jdbc.execute("TRUNCATE TABLE operator_daily_statistics, review_task, image_asset, app_user CASCADE");
    }

    @AfterEach
    void removeTestImage() throws Exception {
        Files.deleteIfExists(TEST_IMAGE);
    }

    @Test
    void enabledConfigurationCreatesSchedulerAndFixedSizeExecutorWithoutCallingB2() {
        assertThat(context.getBean(CloudUploadScheduler.class)).isNotNull();
        assertThat(context.getBean("b2UploadExecutor")).isSameAs(uploadExecutor);
        assertThat(uploadExecutor).isInstanceOf(ThreadPoolExecutor.class);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) uploadExecutor;
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
    }

    @Test
    void testContextDoesNotRunScheduledUploadBatchesAutomatically() throws Exception {
        Files.createDirectories(TEST_IMAGE.getParent());
        Files.write(TEST_IMAGE, new byte[]{1, 2, 3, 4});
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO image_asset (
                    id, file_name, relative_path, file_created_at, file_modified_at,
                    discovered_at, last_seen_at, file_available, game_code,
                    is_notification, has_stand, has_hit, has_double, has_split,
                    parse_status, cloud_upload_attempt_count
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, TRUE, 'bj_igt',
                    FALSE, FALSE, FALSE, FALSE, FALSE, 'SUCCESS', 0
                )
                """,
                "spring-wired",
                TEST_IMAGE.getFileName().toString(),
                TEST_IMAGE.getFileName().toString(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now)
        );

        Thread.sleep(150);

        assertThat(jdbc.queryForObject("""
                SELECT cloud_uploaded_at IS NULL
                FROM image_asset
                WHERE id = 'spring-wired'
                """, Boolean.class)).isTrue();
    }

    @Test
    void enabledSpringWiringUploadsARealRepositoryCandidateAndPersistsCloudState()
            throws Exception {
        Files.createDirectories(TEST_IMAGE.getParent());
        Files.write(TEST_IMAGE, new byte[]{1, 2, 3, 4});
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO image_asset (
                    id, file_name, relative_path, file_created_at, file_modified_at,
                    discovered_at, last_seen_at, file_available, game_code,
                    is_notification, has_stand, has_hit, has_double, has_split,
                    parse_status, cloud_upload_attempt_count
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, TRUE, 'bj_igt',
                    FALSE, FALSE, FALSE, FALSE, FALSE, 'SUCCESS', 0
                )
                """,
                "spring-wired",
                TEST_IMAGE.getFileName().toString(),
                TEST_IMAGE.getFileName().toString(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now)
        );

        assertThat(scheduler.runOnce()).isEqualTo(1);

        verify(cloudStorage).upload(eq("validator/spring-wired.png"), eq(TEST_IMAGE));
        assertThat(jdbc.queryForMap("""
                SELECT cloud_object_key, cloud_uploaded_at,
                       cloud_upload_next_attempt_at, cloud_upload_attempt_count
                FROM image_asset
                WHERE id = 'spring-wired'
                """))
                .containsEntry("cloud_object_key", "validator/spring-wired.png")
                .containsEntry("cloud_upload_next_attempt_at", null)
                .containsEntry("cloud_upload_attempt_count", 0);
        assertThat(jdbc.queryForObject("""
                SELECT cloud_uploaded_at IS NOT NULL
                FROM image_asset
                WHERE id = 'spring-wired'
                """, Boolean.class)).isTrue();
    }
}
