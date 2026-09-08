package com.introlabsystems.recognitionvalidator.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewDisagreementRepository;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class DecisionServiceLoggingTest {

    @Test
    void completedDecisionLogsSafeAuditContext(CapturedOutput output) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        DecisionService service = new DecisionService(
                jdbc,
                Clock.fixed(Instant.parse("2026-09-08T08:00:00Z"), ZoneOffset.UTC),
                mock(DailyStatisticsRepository.class),
                mock(ReviewDisagreementRepository.class),
                mock(ApplicationEventPublisher.class)
        );
        String imageId = "c".repeat(64);
        UUID operatorId = UUID.randomUUID();
        Logger logger = (Logger) LoggerFactory.getLogger(DecisionService.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            service.decide(imageId, operatorId, Decision.REJECTED);
        } finally {
            logger.setLevel(previous);
        }

        assertThat(output)
                .contains("Review decision completed")
                .contains("imageId=" + imageId)
                .contains("operatorId=" + operatorId)
                .contains("decision=REJECTED");
    }
}
