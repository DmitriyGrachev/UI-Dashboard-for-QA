package com.introlabsystems.recognitionvalidator.slack;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Hibernate adds columns but does not widen an existing enum CHECK constraint. */
@Component
@DependsOn("entityManagerFactory")
public class SlackOutboxSchemaInitializer implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public SlackOutboxSchemaInitializer(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
    }

    @Override
    public void afterPropertiesSet() {
        if (!legacyConstraint()) return;
        transaction.executeWithoutResult(tx -> {
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('validator-slack-outbox-schema',0))");
            if (legacyConstraint()) {
                jdbc.execute("""
                        ALTER TABLE slack_notification_outbox
                          DROP CONSTRAINT slack_notification_outbox_operation_kind_check,
                          ADD CONSTRAINT slack_notification_outbox_operation_kind_check
                            CHECK (operation_kind IN ('REFRESH', 'ARCHIVE', 'MESSAGE'))
                        """);
            }
        });
    }

    private boolean legacyConstraint() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conrelid='slack_notification_outbox'::regclass
                    AND conname='slack_notification_outbox_operation_kind_check'
                    AND position('MESSAGE' in pg_get_constraintdef(oid))=0)
                """, Boolean.class));
    }
}
