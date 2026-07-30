package com.introlabsystems.recognitionvalidator.maintenance;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

@Service
public class RetentionCleanupService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ValidatorProperties properties;
    private final Clock clock;

    public RetentionCleanupService(
            NamedParameterJdbcTemplate jdbc,
            ValidatorProperties properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${validator.cleanup-cron:0 15 * * * *}", zone = "UTC")
    @Transactional
    public int runOnce() {
        Instant cutoff = clock.instant().minus(properties.retention());
        return jdbc.update(
                "DELETE FROM image_asset WHERE file_created_at < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff))
        );
    }
}
