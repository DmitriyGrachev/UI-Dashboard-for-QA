package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.AdminScreenshotRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import com.introlabsystems.recognitionvalidator.dto.request.AdminScreenshotSearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.Writer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiRuleStatisticsScaleTest extends AiTestSupport {
    @Autowired AiQueueProperties properties;
    @Autowired B2StorageProperties b2;

    @Test
    void hundredThousandTasksAndTwentyRulesUseOneAggregateAndOneStreamingExportQuery() {
        jdbc.update("""
                INSERT INTO image_asset(id,file_name,relative_path,file_created_at,file_modified_at,
                  discovered_at,last_seen_at,file_available,game_code,token_id,session_id,
                  payload_raw,is_notification,has_stand,has_hit,has_double,has_split,parse_status)
                SELECT lpad(to_hex(n),64,'0'),n||'.png',n||'.png',now(),now(),now(),now(),true,
                  'bj_igt',n%20,'scale','',false,false,false,false,false,'SUCCESS'
                FROM generate_series(1,100000) n
                """);
        jdbc.update("""
                INSERT INTO review_task(image_id,status,file_created_at,game_code,token_id,session_id,is_notification,has_user_hand)
                SELECT id,'PENDING',file_created_at,game_code,token_id,session_id,false,true FROM image_asset
                """);
        jdbc.update("""
                INSERT INTO ai_review_task(image_id,status,file_created_at,game_code,token_id,session_id,is_notification,has_user_hand,file_available)
                SELECT id,'PENDING',file_created_at,game_code,token_id,session_id,false,true,true FROM image_asset
                """);
        jdbc.execute("ANALYZE image_asset");
        jdbc.execute("ANALYZE review_task");
        jdbc.execute("ANALYZE ai_review_task");
        var rules = new ArrayList<AiRule>();
        for (int i = 1; i <= 20; i++) rules.add(new AiRule(null, i == 20 ? "default" : "token-" + i,
                true, i, "bj_igt", null, null, i == 20 ? null : (long) i, null, null));
        var template = spy(new NamedParameterJdbcTemplate(jdbc));
        var tasks = new AiTaskRepository(template, transactionManager, properties, b2,
                mock(DailyStatisticsRepository.class), mock(ReviewDisagreementRepository.class),
                new com.introlabsystems.recognitionvalidator.ai.repository.AiRuleActivityRepository(template));
        long started = System.nanoTime();
        // Budget matches the production rule-statistics transaction timeout, including all 20 rules.
        var counts = assertTimeout(Duration.ofSeconds(5), () -> tasks.ruleStatistics(new AiSettings(0, true, rules), Instant.now()));
        long ruleMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(counts).hasSize(20).extracting(AiRuleStatistics::remaining).containsOnly(5000L);
        verify(template, times(1)).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        doAnswer(invocation -> {
            PreparedStatementCallback<Long> callback = invocation.getArgument(2);
            return new NamedParameterJdbcTemplate(jdbc).execute(invocation.getArgument(0, String.class),
                    invocation.getArgument(1, SqlParameterSource.class), statement -> {
                        assertThat(statement.getConnection().getAutoCommit()).isFalse();
                        long result = callback.doInPreparedStatement(statement);
                        assertThat(statement.getFetchSize()).isEqualTo(500);
                        return result;
                    });
        }).when(template).execute(anyString(), any(SqlParameterSource.class), any(PreparedStatementCallback.class));
        var export = new AdminScreenshotRepository(template);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setTimeout(120);
        started = System.nanoTime();
        long exported = assertTimeout(Duration.ofSeconds(120), () -> transaction.execute(tx ->
                export.writeCsv(new AdminScreenshotSearchRequest().toFilters(), Instant.now().minusSeconds(86400), Writer.nullWriter())));
        assertThat(exported).isEqualTo(100000);
        verify(template, times(1)).execute(anyString(), any(SqlParameterSource.class), any(PreparedStatementCallback.class));
        System.out.printf("SCALE_FIXTURE rows=100000 rules=20 ruleQueries=1 ruleMs=%d exportQueries=1 exportMs=%d%n",
                ruleMillis, (System.nanoTime() - started) / 1_000_000);
    }
}
