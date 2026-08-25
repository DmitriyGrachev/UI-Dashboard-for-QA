package com.introlabsystems.recognitionvalidator.slack;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class SlackMessageFormatter {

    private static final int MAX_MESSAGE_LENGTH = 3900;
    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    public String backlog(RejectedBacklogSnapshot snapshot, int detailLimit) {
        int details = Math.min(Math.max(detailLimit, 0), snapshot.items().size());
        StringBuilder message = new StringBuilder(":red_circle: *Rejected screenshots — ")
                .append(snapshot.count())
                .append(" waiting*\n_Showing ")
                .append(details)
                .append(" of ")
                .append(snapshot.count())
                .append("_");
        for (int index = 0; index < details; index++) {
            RejectedBacklogItem item = snapshot.items().get(index);
            message.append("\n\n*").append(index + 1).append(".* `")
                    .append(value(item.fileName())).append("`")
                    .append("\nGame: `").append(value(item.game()))
                    .append("` · Session: `").append(value(item.session())).append("`")
                    .append("\nCards: Dealer `").append(value(item.dealer()))
                    .append("` · Active `").append(value(item.activeHand()))
                    .append("` · Other `").append(value(item.otherHands())).append("`")
                    .append("\nNotification: *").append(item.notification() ? "Yes" : "No")
                    .append("* · Buttons: `").append(value(item.buttons()))
                    .append("` · Parse: `").append(value(item.parseStatus())).append("`")
                    .append("\n`").append(value(item.operator())).append("` · ")
                    .append(format(item.reviewedAt()));
        }
        long more = Math.max(0, snapshot.count() - details);
        if (more > 0) {
            message.append("\n\n+ ").append(more).append(" more files");
        }
        return truncate(message.toString());
    }

    public String archive(String adminUsername, int exportedCount, Instant exportedAt, long waitingCount) {
        String fileLabel = exportedCount == 1 ? "file" : "files";
        return truncate(":white_check_mark: *Rejected archive downloaded*\n\n*"
                + exportedCount + " " + fileLabel + "* downloaded by `" + value(adminUsername)
                + "`\nRemaining: *" + waitingCount + "*\n" + format(exportedAt));
    }

    private String truncate(String message) {
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }

    private String format(Instant value) {
        return value == null ? "—" : UTC_FORMAT.format(value);
    }

    private String value(Object value) {
        return value == null || value.toString().isBlank() ? "—" : value.toString();
    }
}
