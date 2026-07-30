package com.introlabsystems.recognitionvalidator.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ReviewWorkflowTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE review_task, image_asset, app_user CASCADE");
    }

    @Test
    void flywayCreatesValidatorTables() {
        assertThat(tableExists("app_user")).isTrue();
        assertThat(tableExists("image_asset")).isTrue();
        assertThat(tableExists("review_task")).isTrue();
    }

    private Boolean tableExists(String tableName) {
        return jdbc.queryForObject(
                "select to_regclass('public." + tableName + "') is not null",
                Boolean.class
        );
    }
}
