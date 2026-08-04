package com.introlabsystems.recognitionvalidator.image;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
class ImageIndexingTest {

    private static final String VALID_FILE = "bj_igt_39_850746c3-874d-495d-aefa-5ea3636cfb51"
            + "_u_Jack_bSbH_27-07-2026-22-48-01_754.png";
    private static final String SURRENDER_FILE = "bj_double_deck_black_throne_36_"
            + "2e8c1326-fb86-4c51-8e45-8bc65e6d33ee"
            + "_d_Four_u_Nine_Seven_bSbHbDbSR_27-07-2026-12-36-56_481.png";

    @TempDir
    private Path imageRoot;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FilenameParser filenameParser;

    @Autowired
    private ImageAssetBatchWriter batchWriter;

    @Autowired
    private ImageAssetRepository imageRepository;

    @Autowired
    private ImageStorageService imageStorageService;

    @Autowired
    private Clock clock;

    private ImageIndexer indexer;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE operator_daily_statistics, review_task, image_asset, app_user CASCADE");
        indexer = new ImageIndexer(imageRoot, 10, filenameParser, batchWriter, clock);
    }

    @Test
    void imageIdIsStableSha256OfNormalizedRelativePath() {
        String first = ImageId.fromRelativePath(Path.of("folder", "..", "shots", "image.png"));
        String second = ImageId.fromRelativePath(Path.of("shots/image.png"));

        assertThat(first).isEqualTo(second);
        assertThat(first).matches("[0-9a-f]{64}");
    }

    @Test
    void scanIndexesOnlyGamePngWithoutReadingImageBytesAndIsIdempotent() throws Exception {
        Files.write(imageRoot.resolve(VALID_FILE), "not a png".getBytes(StandardCharsets.UTF_8));
        Files.write(imageRoot.resolve("solution_1.png"), new byte[]{1});
        Files.write(imageRoot.resolve("detected_game_area_1.png"), new byte[]{2});
        Files.write(imageRoot.resolve("notes.txt"), new byte[]{3});

        indexer.scanRoot();
        indexer.scanRoot();

        assertThat(imageRepository.count()).isEqualTo(1);
        assertThat(reviewTaskCount()).isEqualTo(1);
        ImageAsset asset = imageRepository.findAll().getFirst();
        assertThat(asset.getFileName()).isEqualTo(VALID_FILE);
        assertThat(asset.isFileAvailable()).isTrue();
        assertThat(asset.getParseStatus()).isEqualTo(ParseStatus.SUCCESS);
    }

    @Test
    void repeatedReconciliationDoesNotRewriteKnownAvailableAsset() throws Exception {
        Files.write(imageRoot.resolve(VALID_FILE), new byte[]{1});
        indexer.scanRoot();
        String imageId = imageRepository.findAll().getFirst().getId();
        Instant sentinel = Instant.parse("2000-01-01T00:00:00Z");
        jdbc.update(
                "UPDATE image_asset SET last_seen_at = ? WHERE id = ?",
                Timestamp.from(sentinel),
                imageId
        );

        indexer.scanRoot();

        Instant storedLastSeen = jdbc.queryForObject(
                "SELECT last_seen_at FROM image_asset WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp("last_seen_at").toInstant(),
                imageId
        );
        assertThat(storedLastSeen).isEqualTo(sentinel);
        assertThat(imageRepository.count()).isEqualTo(1);
        assertThat(reviewTaskCount()).isEqualTo(1);
    }

    @Test
    void missingImageIsMarkedUnavailableEvenWhenOpenThrowsNotFound() throws Exception {
        Files.write(imageRoot.resolve(VALID_FILE), new byte[]{1});
        indexer.scanRoot();
        ImageAsset asset = imageRepository.findAll().getFirst();

        assertThatThrownBy(() -> imageStorageService.open(asset.getId()))
                .isInstanceOf(ImageNotFoundException.class);

        assertThat(imageRepository.findById(asset.getId()).orElseThrow().isFileAvailable())
                .isFalse();
    }

    @Test
    void reconciliationAndReindexPreserveTheFinalDecision() throws Exception {
        Path file = imageRoot.resolve(VALID_FILE);
        Files.write(file, new byte[]{1, 2, 3});
        indexer.scanRoot();
        String imageId = imageRepository.findAll().getFirst().getId();

        Files.delete(file);
        indexer.scanRoot();
        assertThat(imageRepository.findById(imageId).orElseThrow().isFileAvailable()).isFalse();

        Files.write(file, new byte[]{4, 5, 6});
        indexer.scanRoot();
        assertThat(imageRepository.findById(imageId).orElseThrow().isFileAvailable()).isTrue();
        assertThat(imageRepository.count()).isEqualTo(1);

        UUID operatorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user (id, username, password_hash, enabled, created_at)
                VALUES (?, 'operator', 'hash', TRUE, now())
                """, operatorId);
        jdbc.update("""
                UPDATE review_task
                SET status = 'COMPLETED',
                    assigned_to = ?,
                    assigned_at = now(),
                    decision = 'ACCEPTED',
                    reviewed_at = now()
                WHERE image_id = ?
                """, operatorId, imageId);

        indexer.scanRoot();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM review_task WHERE image_id = ?",
                String.class,
                imageId
        )).isEqualTo("COMPLETED");
    }

    @Test
    void fileEventsIndexCreatedMarkDeletedAndRecoverAfterOverflow() throws Exception {
        ImageFileEventHandler eventHandler = new ImageFileEventHandler(
                indexer,
                new ImageEventBuffer(100)
        );
        Path file = imageRoot.resolve(VALID_FILE);

        Files.write(file, new byte[]{1});
        eventHandler.created(file);
        eventHandler.flush();
        ImageAsset created = imageRepository.findAll().getFirst();
        assertThat(created.isFileAvailable()).isTrue();

        Files.delete(file);
        eventHandler.deleted(file);
        eventHandler.flush();
        assertThat(imageRepository.findById(created.getId()).orElseThrow().isFileAvailable())
                .isFalse();

        Files.write(file, new byte[]{2});
        eventHandler.overflow();
        eventHandler.flush();
        assertThat(imageRepository.findById(created.getId()).orElseThrow().isFileAvailable())
                .isTrue();
    }

    @Test
    void createdEventBackfillsSurrenderForExistingMetadata() throws Exception {
        Path file = imageRoot.resolve(SURRENDER_FILE);
        Files.write(file, new byte[]{1});
        indexer.scanRoot();
        String imageId = imageRepository.findAll().getFirst().getId();
        jdbc.update("UPDATE image_asset SET has_surrender = FALSE WHERE id = ?", imageId);

        indexer.index(file);

        assertThat(imageRepository.findById(imageId).orElseThrow().isSurrender()).isTrue();
    }

    @Test
    void failedMetadataBatchPropagatesWithoutRetryingRowsIndividually() {
        Instant now = clock.instant();
        ImageMetadata invalid = new ImageMetadata(
                "invalid-id",
                null,
                "invalid.png",
                now,
                now,
                now,
                now,
                "bj_igt",
                null,
                null,
                null,
                new RecognitionResult(
                        null, null, null, null, null,
                        false, false, false, false, false, false,
                        null, null, ParseStatus.SUCCESS
                )
        );

        assertThatThrownBy(() -> batchWriter.upsert(List.of(invalid)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reconciliationPropagatesDatabaseFailureToTheCoordinator() throws Exception {
        Files.write(imageRoot.resolve(VALID_FILE), new byte[]{1});
        ImageAssetBatchWriter failingWriter = mock(ImageAssetBatchWriter.class);
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(failingWriter)
                .upsert(anyList());
        ImageIndexer failingIndexer = new ImageIndexer(
                imageRoot,
                10,
                filenameParser,
                failingWriter,
                clock
        );

        assertThatThrownBy(failingIndexer::scanRoot)
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private long reviewTaskCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review_task", Long.class);
    }
}
