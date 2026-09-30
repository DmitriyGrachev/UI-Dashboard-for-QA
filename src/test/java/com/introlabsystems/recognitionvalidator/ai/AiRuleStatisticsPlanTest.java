package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.repository.AiRuleActivityRepository;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Bounded plan regression: no production data or million-row fixture. */
class AiRuleStatisticsPlanTest extends AiTestSupport {
    @Autowired AiQueueProperties properties;
    @Autowired B2StorageProperties b2;

    @Test
    void selectiveRulesExposeTheirScopeToThePlannerWithoutChangingCounts() throws Exception {
        // Reuse the shipped index definitions; the test schema otherwise has only ORM indexes.
        for (String script : List.of("ai-pull-integration.sql", "ai-rule-statistics-index.sql")) {
            var indexes = java.util.regex.Pattern.compile("CREATE INDEX(?: CONCURRENTLY)? IF NOT EXISTS [\\s\\S]+?;")
                    .matcher(Files.readString(Path.of("scripts", script)));
            while (indexes.find()) jdbc.execute(indexes.group().replace("CONCURRENTLY ", ""));
        }
        jdbc.update("""
                INSERT INTO image_asset(id,file_name,relative_path,file_created_at,file_modified_at,
                  discovered_at,last_seen_at,file_available,game_code,token_id,session_id,
                  payload_raw,is_notification,has_stand,has_hit,has_double,has_split,parse_status)
                SELECT lpad(to_hex(n),64,'0'),n||'.png',n||'.png',now(),now(),now(),now(),true,
                  CASE WHEN n <= 100 THEN 'bj_igt' ELSE 'bj_single_deck_ags' END,n%2,'plan','',
                  false,false,false,false,false,'SUCCESS' FROM generate_series(1,12000) n
                """);
        jdbc.update("""
                INSERT INTO ai_review_task(image_id,status,file_created_at,game_code,token_id,session_id,
                  is_notification,has_user_hand,file_available)
                SELECT id,'PENDING',file_created_at,game_code,token_id,session_id,false,true,true FROM image_asset
                """);
        jdbc.execute("ANALYZE image_asset");
        jdbc.execute("ANALYZE ai_review_task");
        var first = new AiRule(null,"token",true,1,"bj_igt",null,null,1L,null,null);
        var fallback = new AiRule(null,"default",true,2,"bj_igt",null,null,null,null,null);
        var template = spy(new NamedParameterJdbcTemplate(jdbc));
        var tasks = new AiTaskRepository(template, transactionManager, properties, b2,
                mock(DailyStatisticsRepository.class), mock(ReviewDisagreementRepository.class), new AiRuleActivityRepository(template));
        var counts = tasks.ruleStatistics(new AiSettings(0,true,List.of(first,fallback)), Instant.now());
        assertThat(counts).extracting(AiRuleStatistics::remaining).containsExactly(50L,50L);
        var sql = ArgumentCaptor.forClass(String.class);
        var params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(template).query(sql.capture(), params.capture(), any(RowMapper.class));
        String after = sql.getValue();
        String before = after.replaceAll("(?s)/\\* active rule scope \\*/.*?/\\* end scope \\*/", "");
        assertThat(after).isNotEqualTo(before);
        var plain = new NamedParameterJdbcTemplate(jdbc);
        assertThat(plain.queryForList(before,params.getValue())).containsExactlyInAnyOrderElementsOf(plain.queryForList(after,params.getValue()));
        Path directory = Path.of("target","ai-rule-plans");
        Files.createDirectories(directory);
        for (String version : List.of("before","after")) {
            var times = new ArrayList<Double>();
            for (int run = 0; run < 3; run++) {
                var plan = plain.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + (version.equals("before") ? before : after), params.getValue(), String.class);
                if (version.equals("after")) assertThat(String.join("\n", plan)).contains("ix_ai_pending_game_order");
                Files.write(directory.resolve(version + ".txt"), plan);
                String execution = plan.stream().filter(line -> line.startsWith("Execution Time:")).findFirst().orElseThrow();
                times.add(Double.parseDouble(execution.replace("Execution Time:", "").replace("ms", "").trim()));
            }
            times.sort(Double::compareTo);
            System.out.printf("AI_RULE_PLAN rows=12000 matches=100 version=%s medianMs=%.3f%n",version,times.get(1));
            assertThat(times.get(1)).isLessThan(5000);
        }
    }
}
