package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.dao.jdbc.CloudUploadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class AiAvailabilityProjectionTest extends AiTestSupport {
    @Autowired CloudUploadRepository uploads;

    @Test void everySourceChangeAtomicallyRefreshesAiAvailabilityWithoutResettingItsResult() {
        String id = image(1, 53);
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED',valid=true,verdict='MATCH',checked_at=now() WHERE image_id=?", id);
        jdbc.update("UPDATE image_asset SET file_available=false WHERE id=?", id);
        assertThat(jdbc.queryForObject("SELECT file_available FROM ai_review_task WHERE image_id=?", Boolean.class, id)).isFalse();
        Instant uploaded = Instant.parse("2026-09-03T10:00:00Z");
        uploads.markUploaded(id, "validator/test.png", uploaded);
        assertThat(jdbc.queryForObject("SELECT cloud_available_at FROM ai_review_task WHERE image_id=?", java.sql.Timestamp.class, id).toInstant()).isEqualTo(uploaded);
        jdbc.update("UPDATE image_asset SET cloud_object_key=' ',file_available=true WHERE id=?", id);
        assertThat(jdbc.queryForObject("SELECT cloud_available_at IS NULL AND file_available FROM ai_review_task WHERE image_id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_review_task WHERE image_id=?", String.class, id)).isEqualTo("COMPLETED");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.update("UPDATE image_asset SET file_available=false WHERE id=?", id);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT file_available FROM ai_review_task WHERE image_id=?", Boolean.class, id)).isTrue();
    }
}
