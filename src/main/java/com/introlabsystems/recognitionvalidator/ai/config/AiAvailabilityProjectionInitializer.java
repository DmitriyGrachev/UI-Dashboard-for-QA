package com.introlabsystems.recognitionvalidator.ai.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

/** Fresh Hibernate-created databases need the same atomic projection as migrated databases. */
@Component
@DependsOn("entityManagerFactory")
public class AiAvailabilityProjectionInitializer implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AiAvailabilityProjectionInitializer(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(10);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (installed()) return;
        String ddl;
        try (var input = new ClassPathResource("db/ai-availability.sql").getInputStream()) {
            ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        transaction.executeWithoutResult(tx -> {
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('validator-ai-availability-schema',0))");
            if (!installed()) jdbc.execute(ddl);
        });
    }

    private boolean installed() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='image_asset'::regclass
                    AND tgname='trg_ai_image_availability' AND tgenabled='O' AND NOT tgisinternal)
                """, Boolean.class));
    }
}
