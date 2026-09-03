package com.introlabsystems.recognitionvalidator.ai;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Instant;

@SpringBootTest
@ActiveProfiles("test")
abstract class AiTestSupport {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanAi() {
        jdbc.execute("TRUNCATE operator_daily_statistics, review_task, image_asset, app_user, ai_selection_rule, ai_queue_settings CASCADE");
    }

    protected String image(int number, long token) {
        String id = "%064x".formatted(number);
        Timestamp created = Timestamp.from(Instant.parse("2026-08-30T00:00:00Z").plusSeconds(number));
        jdbc.update("""
                INSERT INTO image_asset (id,file_name,relative_path,file_created_at,file_modified_at,
                  discovered_at,last_seen_at,file_available,game_code,token_id,session_id,
                  payload_raw,is_notification,has_stand,has_hit,has_double,has_split,parse_status)
                VALUES (?,?,?, ?,?,now(),now(),true,'bj_single_deck_ags',?,'session-a',
                  'd_Six_u_Seven_King_bSbH',false,false,false,false,false,'SUCCESS')
                """, id, id + ".png", id + ".png", created, created, token);
        jdbc.update("""
                INSERT INTO review_task(image_id,status,file_created_at,game_code,token_id,session_id,is_notification,has_user_hand)
                VALUES (?,'PENDING',?,'bj_single_deck_ags',?,'session-a',false,true)
                """, id, created, token);
        jdbc.update("""
                INSERT INTO ai_review_task(image_id,status,file_created_at,game_code,token_id,session_id,is_notification,has_user_hand,attempt_count,file_available)
                VALUES (?,'PENDING',?,'bj_single_deck_ags',?,'session-a',false,true,0,true)
                """, id, created, token);
        return id;
    }
}
