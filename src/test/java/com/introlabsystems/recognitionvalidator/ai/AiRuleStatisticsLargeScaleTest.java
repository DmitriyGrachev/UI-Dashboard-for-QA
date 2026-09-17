package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in, read-only benchmark against src/test/sql/ai-rule-statistics-scale-fixture.sql.
 * Use the documented 2 CPU / 4 GiB PostgreSQL 17 container, never an application database. */
@EnabledIfSystemProperty(named = "ai.rules.scale.url", matches = "jdbc:postgresql://.+")
class AiRuleStatisticsLargeScaleTest {
    @Test
    void exactRuleCountsFitFiveSecondsWhereLegacyQueryTimesOut() throws Exception {
        try (var connection = DriverManager.getConnection(System.getProperty("ai.rules.scale.url"), "validator", "validator")) {
            var dataSource = new SingleConnectionDataSource(connection, true);
            var template = spy(new NamedParameterJdbcTemplate(dataSource));
            var jdbc = template.getJdbcTemplate();
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("rv_ai_stats_analysis");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM image_asset", Long.class)).isEqualTo(5_000_000);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_review_task", Long.class)).isEqualTo(4_400_000);
            connection.setReadOnly(true);
            String legacy = Files.readString(Path.of("src/test/sql/ai-rule-statistics-legacy.sql"));
            jdbc.execute("SET statement_timeout='5s'");
            DataAccessException timeout = assertThrows(DataAccessException.class, () -> jdbc.queryForList(legacy));
            assertThat(((SQLException) timeout.getMostSpecificCause()).getSQLState()).isEqualTo("57014");
            jdbc.execute("SET statement_timeout='90s'"); // Diagnostics only; the application budget remains five seconds.
            var output = Path.of("target/ai-rule-statistics-scale");
            Files.createDirectories(output);
            Files.write(output.resolve("old-plan.txt"), jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + legacy, String.class));
            var legacyCounts = jdbc.query(legacy, (rs, row) -> new AiRuleStatistics(
                    rs.getObject("rule_id", UUID.class), rs.getLong("remaining"), rs.getLong("processing"),
                    rs.getLong("completed"), rs.getLong("failed")));
            var b2 = new B2StorageProperties(true, URI.create("https://s3.us-west-004.backblazeb2.com"),
                    "fixture", "test", "test", "validator/", 10, 1, Duration.ofSeconds(10), Duration.ofMinutes(5),
                    Duration.ofDays(3), Duration.ofMinutes(30), Duration.ofDays(21), Duration.ofSeconds(5),
                    Duration.ofSeconds(30), Duration.ofSeconds(45), Duration.ofMinutes(2), 4);
            var manager = new DataSourceTransactionManager(dataSource);
            var tasks = new AiTaskRepository(template, manager, new AiQueueProperties(Duration.ofMinutes(2)), b2,
                    mock(DailyStatisticsRepository.class), mock(ReviewDisagreementRepository.class),
                    new com.introlabsystems.recognitionvalidator.ai.repository.AiRuleActivityRepository(template));
            var rules = List.of(rule(1, "specific", "bj_igt", 1L), rule(2, "default", "bj_igt", null),
                    rule(3, "other game", "bj_single_deck_ags", null));
            var settings = new AiSettings(0, true, rules);
            var transaction = new TransactionTemplate(manager);
            transaction.setReadOnly(true);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.setTimeout(5);
            try {
                for (int workers : new int[]{2, 0}) {
                    jdbc.execute("SET max_parallel_workers_per_gather=" + workers);
                    for (int run = 0; run < 3; run++) {
                        long started = System.nanoTime();
                        var counts = transaction.execute(tx -> {
                            jdbc.execute("SET LOCAL statement_timeout='5s'");
                            return tasks.ruleStatistics(settings, Instant.parse("2026-09-16T10:00:00Z"));
                        });
                        assertThat(counts).containsExactlyInAnyOrderElementsOf(legacyCounts);
                        assertThat(counts).extracting(AiRuleStatistics::remaining).containsExactly(2934L, 261134L, 132032L);
                        System.out.printf("AI_RULE_SCALE workers=%d run=%d rows=4400000 ms=%d%n", workers, run + 1, (System.nanoTime() - started) / 1_000_000);
                    }
                }
                verify(template, times(6)).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
            } finally {
                var sql = ArgumentCaptor.forClass(String.class);
                var parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
                verify(template, atLeastOnce()).query(sql.capture(), parameters.capture(), any(RowMapper.class));
                var plan = template.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql.getValue(), parameters.getValue(), String.class);
                Files.write(output.resolve("new-plan.txt"), plan);
            }
        }
    }

    private AiRule rule(int number, String name, String game, Long token) {
        return new AiRule(UUID.fromString("00000000-0000-0000-0000-%012d".formatted(number)),
                name, true, number, game, null, null, token, null, null);
    }
}
