package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.dao.jdbc.ImageAssetBatchWriter;
import com.introlabsystems.recognitionvalidator.indexing.ImageId;
import com.introlabsystems.recognitionvalidator.indexing.ImageIndexer;
import com.introlabsystems.recognitionvalidator.parser.FilenameParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AiQueueSchemaTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageAssetBatchWriter writer;
    @Autowired FilenameParser parser;
    @Autowired Clock clock;
    @TempDir Path root;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE operator_daily_statistics, review_task, image_asset, app_user, ai_daily_statistics CASCADE");
    }

    @Test
    void indexingCreatesIndependentQueueWithoutResettingCompletedResults() throws Exception {
        assertThat(jdbc.queryForObject("SELECT to_regclass('ai_review_task') IS NOT NULL", Boolean.class)).isTrue();
        String name = "bj_single_deck_ags_53_850746c3-874d-495d-aefa-5ea3636cfb51"
                + "_p1_d_Six_u_Seven_King_bSbH_03-09-2026-10-00-00_100.png";
        Files.write(root.resolve(name), new byte[]{1});
        ImageIndexer indexer = new ImageIndexer(root, 10, parser, writer, clock);
        indexer.scanRoot();
        String id = ImageId.fromRelativePath(Path.of(name));
        assertThat(jdbc.queryForMap("SELECT status, game_code, token_id, has_user_hand FROM ai_review_task WHERE image_id=?", id))
                .containsEntry("status", "PENDING").containsEntry("game_code", "bj_single_deck_ags")
                .containsEntry("token_id", 53L).containsEntry("has_user_hand", true);
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED', valid=true, verdict='MATCH', checked_at=now() WHERE image_id=?", id);
        indexer.scanRoot();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_review_task WHERE image_id=?", String.class, id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM review_task WHERE image_id=?", String.class, id)).isEqualTo("PENDING");
        jdbc.update("DELETE FROM image_asset WHERE id=?", id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_review_task", Long.class)).isZero();
    }
}
