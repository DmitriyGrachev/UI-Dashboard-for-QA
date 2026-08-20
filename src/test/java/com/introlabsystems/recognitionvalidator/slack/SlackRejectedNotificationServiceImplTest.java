package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlackRejectedNotificationServiceImplTest {

    @Test
    void keepsActiveMessageAfterDownloadingTheLastRejectedScreenshot() {
        RejectedBacklogRepository backlog = mock(RejectedBacklogRepository.class);
        SlackMessageFormatter formatter = mock(SlackMessageFormatter.class);
        SlackWebApiClient slack = mock(SlackWebApiClient.class);
        SlackNotificationStateRepository stateRepository = mock(SlackNotificationStateRepository.class);
        SlackNotificationState state = SlackNotificationState.active("123.456");
        SlackProperties properties = new SlackProperties(
                true,
                "token",
                "channel",
                10,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                "https://slack.test"
        );
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);
        SlackRejectedNotificationServiceImpl service = new SlackRejectedNotificationServiceImpl(
                properties,
                backlog,
                formatter,
                slack,
                stateRepository,
                clock
        );
        RejectedBacklogSnapshot emptyBacklog = new RejectedBacklogSnapshot(0, List.of());
        when(stateRepository.findById(SlackNotificationState.SINGLETON_ID))
                .thenReturn(Optional.of(state));
        when(backlog.snapshot(10)).thenReturn(emptyBacklog);
        when(formatter.archive("admin", 4, clock.instant(), 0))
                .thenReturn("archive downloaded");

        service.archiveDownloaded("admin", 4);

        assertThat(state.getActiveMessageTs()).isEqualTo("123.456");
    }
}
