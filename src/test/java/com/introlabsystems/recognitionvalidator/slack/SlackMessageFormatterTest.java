package com.introlabsystems.recognitionvalidator.slack;

import com.introlabsystems.recognitionvalidator.model.enums.ParseStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlackMessageFormatterTest {

    private final SlackMessageFormatter formatter = new SlackMessageFormatter();

    @Test
    void formatsLimitedBacklogDetailsAndReportsTruncation() {
        RejectedBacklogItem first = new RejectedBacklogItem(
                "first.png", "bj_igt", "session-1", "King", "Ace", "Queen",
                true, "Hit,Stand", ParseStatus.SUCCESS, "operator-1",
                Instant.parse("2026-08-20T10:11:12Z")
        );
        RejectedBacklogItem second = new RejectedBacklogItem(
                "second.png", " ", "", null, null, null,
                false, " ", ParseStatus.ERROR, null, null
        );

        String message = formatter.backlog(new RejectedBacklogSnapshot(3, List.of(first, second)), 2);

        assertThat(message).isEqualTo("""
                :red_circle: *Rejected screenshots — 3 waiting*
                _Showing 2 of 3_

                *1.* `first.png`
                Game: `bj_igt` · Session: `session-1`
                Cards: Dealer `King` · Active `Ace` · Other `Queen`
                Notification: *Yes* · Buttons: `Hit,Stand` · Parse: `SUCCESS`
                `operator-1` · 20 Aug 2026, 10:11 UTC

                *2.* `second.png`
                Game: `—` · Session: `—`
                Cards: Dealer `—` · Active `—` · Other `—`
                Notification: *No* · Buttons: `—` · Parse: `ERROR`
                `—` · —

                + 1 more files""");
    }

    @Test
    void formatsArchiveStatusWithAdminExportAndWaitingCount() {
        String message = formatter.archive("alice", 4,
                Instant.parse("2026-08-20T12:00:00Z"), 2);

        assertThat(message).isEqualTo("""
                :white_check_mark: *Rejected archive downloaded*

                *4 files* downloaded by `alice`
                Remaining: *2*
                20 Aug 2026, 12:00 UTC""");
    }
}
