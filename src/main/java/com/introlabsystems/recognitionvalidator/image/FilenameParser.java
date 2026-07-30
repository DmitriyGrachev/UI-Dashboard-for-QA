package com.introlabsystems.recognitionvalidator.image;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FilenameParser {

    private static final Pattern SESSION_PATTERN = Pattern.compile(
            "^(?<token>\\d+)_(?<uuid>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})_(?<rest>.+)$"
    );
    private static final Pattern TAIL_PATTERN = Pattern.compile(
            "^(?<payload>.*)_(?<time>\\d{2}-\\d{2}-\\d{4}-\\d{2}-\\d{2}-\\d{2})"
                    + "_(?<duration>\\d+)$"
    );
    private static final Pattern BUTTON_PATTERN = Pattern.compile("\\G(?:bSR|b[SHDPN])");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("dd-MM-uuuu-HH-mm-ss")
            .withResolverStyle(ResolverStyle.STRICT);

    private final GameCatalog gameCatalog;

    public FilenameParser(GameCatalog gameCatalog) {
        this.gameCatalog = gameCatalog;
    }

    public ParsedFilename parse(String fileName) {
        try {
            return parseValidName(fileName);
        } catch (RuntimeException exception) {
            return ParsedFilename.error();
        }
    }

    private ParsedFilename parseValidName(String fileName) {
        if (fileName == null || !fileName.endsWith(".png")) {
            return ParsedFilename.error();
        }

        String gameCode = gameCatalog.match(fileName).orElse(null);
        if (gameCode == null) {
            return ParsedFilename.error();
        }

        String withoutExtension = fileName.substring(0, fileName.length() - ".png".length());
        String afterGame = withoutExtension.substring(gameCode.length() + 1);
        Matcher sessionMatcher = SESSION_PATTERN.matcher(afterGame);
        if (!sessionMatcher.matches()) {
            return ParsedFilename.error();
        }

        long tokenId = Long.parseLong(sessionMatcher.group("token"));
        UUID sessionUuid = UUID.fromString(sessionMatcher.group("uuid"));
        Matcher tailMatcher = TAIL_PATTERN.matcher(sessionMatcher.group("rest"));
        if (!tailMatcher.matches()) {
            return ParsedFilename.error();
        }

        String payload = tailMatcher.group("payload");
        Instant processedAt = LocalDateTime
                .parse(tailMatcher.group("time"), TIME_FORMATTER)
                .toInstant(ZoneOffset.UTC);
        long duration = Long.parseLong(tailMatcher.group("duration"));
        PayloadFields fields = parsePayload(payload);

        return new ParsedFilename(
                gameCode,
                tokenId,
                sessionUuid,
                tokenId + "_" + sessionUuid,
                fields.dealerCards(),
                fields.activeUserCards(),
                fields.inactiveUserCards(),
                payload,
                fields.buttonsRaw(),
                fields.notification(),
                fields.stand(),
                fields.hit(),
                fields.doubleAction(),
                fields.split(),
                processedAt,
                duration,
                fields.partial() ? ParseStatus.PARTIAL : ParseStatus.SUCCESS
        );
    }

    private PayloadFields parsePayload(String payload) {
        PayloadAccumulator result = new PayloadAccumulator();
        CardGroup group = CardGroup.NONE;
        int userGroupCount = 0;

        for (String token : payload.split("_")) {
            if ("d".equals(token)) {
                group = CardGroup.DEALER;
            } else if ("u".equals(token)) {
                userGroupCount++;
                group = userGroupCount == 1 ? CardGroup.ACTIVE_USER : CardGroup.INACTIVE_USER;
                if (userGroupCount > 2) {
                    result.partial = true;
                }
            } else if (token.startsWith("b")) {
                result.addButtons(token);
                group = CardGroup.NONE;
            } else if (!token.isBlank() && group != CardGroup.NONE) {
                result.addCards(group, token);
            } else if (!token.isBlank()) {
                result.partial = true;
            }
        }

        return result.toFields();
    }

    private enum CardGroup {
        NONE,
        DEALER,
        ACTIVE_USER,
        INACTIVE_USER
    }

    private static final class PayloadAccumulator {

        private final StringBuilder dealerCards = new StringBuilder();
        private final StringBuilder activeUserCards = new StringBuilder();
        private final StringBuilder inactiveUserCards = new StringBuilder();
        private final StringBuilder buttonsRaw = new StringBuilder();
        private boolean notification;
        private boolean stand;
        private boolean hit;
        private boolean doubleAction;
        private boolean split;
        private boolean partial;

        void addCards(CardGroup group, String value) {
            switch (group) {
                case DEALER -> append(dealerCards, value);
                case ACTIVE_USER -> append(activeUserCards, value);
                case INACTIVE_USER -> append(inactiveUserCards, value);
                case NONE -> partial = true;
            }
        }

        void addButtons(String value) {
            append(buttonsRaw, value);
            Matcher matcher = BUTTON_PATTERN.matcher(value);
            int consumed = 0;
            while (matcher.find()) {
                String code = matcher.group();
                consumed = matcher.end();
                switch (code) {
                    case "bN" -> notification = true;
                    case "bS" -> stand = true;
                    case "bH" -> hit = true;
                    case "bD" -> doubleAction = true;
                    case "bP" -> split = true;
                    case "bSR" -> {
                        // Raw code is intentionally preserved without invented semantics.
                    }
                    default -> partial = true;
                }
            }
            if (consumed != value.length()) {
                partial = true;
            }
        }

        PayloadFields toFields() {
            return new PayloadFields(
                    nullable(dealerCards),
                    nullable(activeUserCards),
                    nullable(inactiveUserCards),
                    nullable(buttonsRaw),
                    notification,
                    stand,
                    hit,
                    doubleAction,
                    split,
                    partial
            );
        }

        private static void append(StringBuilder target, String value) {
            if (!target.isEmpty()) {
                target.append('_');
            }
            target.append(value);
        }

        private static String nullable(StringBuilder value) {
            return value.isEmpty() ? null : value.toString();
        }
    }

    private record PayloadFields(
            String dealerCards,
            String activeUserCards,
            String inactiveUserCards,
            String buttonsRaw,
            boolean notification,
            boolean stand,
            boolean hit,
            boolean doubleAction,
            boolean split,
            boolean partial
    ) {
    }
}
