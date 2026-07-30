package com.introlabsystems.recognitionvalidator.image;

import com.introlabsystems.recognitionvalidator.review.ReviewStatus;
import com.introlabsystems.recognitionvalidator.review.ReviewTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ImageIndexingTest {

    private static final String VALID_FILE = "bj_igt_39_850746c3-874d-495d-aefa-5ea3636cfb51"
            + "_u_Jack_bSbH_27-07-2026-22-48-01_754.png";

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
    private ReviewTaskRepository taskRepository;

    @Autowired
    private Clock clock;

    private ImageIndexer indexer;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE review_task, image_asset, app_user CASCADE");
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
        assertThat(taskRepository.count()).isEqualTo(1);
        ImageAsset asset = imageRepository.findAll().getFirst();
        assertThat(asset.getFileName()).isEqualTo(VALID_FILE);
        assertThat(asset.isFileAvailable()).isTrue();
        assertThat(asset.getParseStatus()).isEqualTo(ParseStatus.SUCCESS);
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

        assertThat(taskRepository.findById(imageId).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.COMPLETED);
    }
}
