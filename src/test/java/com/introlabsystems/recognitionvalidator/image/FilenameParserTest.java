package com.introlabsystems.recognitionvalidator.image;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FilenameParserTest {

    private FilenameParser parser;

    @BeforeEach
    void setUp() {
        GameCatalog catalog = new GameCatalog(List.of(
                "bj_igt",
                "bj_single_deck_ags"
        ));
        parser = new FilenameParser(catalog);
    }

    @Test
    void parsesDealerHandsButtonsAndTechnicalFields() {
        ParsedFilename parsed = parser.parse(
                "bj_single_deck_ags_37_5730ca78-7535-4e9d-aeb6-22802c3eb0a6"
                        + "_d_Eight_u_Seven_Jack_u_A10J3_bSbHbD_27-07-2026-06-17-06_895.png"
        );

        assertThat(parsed.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(parsed.gameCode()).isEqualTo("bj_single_deck_ags");
        assertThat(parsed.tokenId()).isEqualTo(37L);
        assertThat(parsed.sessionUuid())
                .isEqualTo(UUID.fromString("5730ca78-7535-4e9d-aeb6-22802c3eb0a6"));
        assertThat(parsed.sessionId())
                .isEqualTo("37_5730ca78-7535-4e9d-aeb6-22802c3eb0a6");
        assertThat(parsed.dealerCards()).isEqualTo("Eight");
        assertThat(parsed.activeUserCards()).isEqualTo("Seven_Jack");
        assertThat(parsed.inactiveUserCards()).isEqualTo("A10J3");
        assertThat(parsed.buttonsRaw()).isEqualTo("bSbHbD");
        assertThat(parsed.stand()).isTrue();
        assertThat(parsed.hit()).isTrue();
        assertThat(parsed.doubleAction()).isTrue();
        assertThat(parsed.split()).isFalse();
        assertThat(parsed.notification()).isFalse();
        assertThat(parsed.processedAt()).isEqualTo(Instant.parse("2026-07-27T06:17:06Z"));
        assertThat(parsed.recognitionDurationMs()).isEqualTo(895L);
    }

    @Test
    void parsesSingleActiveHandWithoutDealer() {
        ParsedFilename parsed = parser.parse(
                "bj_igt_39_850746c3-874d-495d-aefa-5ea3636cfb51"
                        + "_u_Jack_bSbH_27-07-2026-22-48-01_754.png"
        );

        assertThat(parsed.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(parsed.dealerCards()).isNull();
        assertThat(parsed.activeUserCards()).isEqualTo("Jack");
        assertThat(parsed.inactiveUserCards()).isNull();
        assertThat(parsed.stand()).isTrue();
        assertThat(parsed.hit()).isTrue();
        assertThat(parsed.processedAt()).isEqualTo(Instant.parse("2026-07-27T22:48:01Z"));
    }

    @Test
    void parsesNotificationThatAlsoContainsUserCards() {
        ParsedFilename parsed = parser.parse(
                "bj_single_deck_ags_32_2f85c92a-b245-4c88-a56b-7bb15fb93c38"
                        + "_bN_u_22769K_30-07-2026-09-28-40_2682.png"
        );

        assertThat(parsed.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(parsed.notification()).isTrue();
        assertThat(parsed.activeUserCards()).isEqualTo("22769K");
        assertThat(parsed.buttonsRaw()).isEqualTo("bN");
        assertThat(parsed.processedAt()).isEqualTo(Instant.parse("2026-07-30T09:28:40Z"));
        assertThat(parsed.recognitionDurationMs()).isEqualTo(2682L);
    }

    @Test
    void returnsErrorInsteadOfThrowingForMalformedName() {
        ParsedFilename parsed = parser.parse("not-a-recognition-screenshot.png");

        assertThat(parsed.parseStatus()).isEqualTo(ParseStatus.ERROR);
        assertThat(parsed.gameCode()).isNull();
        assertThat(parsed.sessionId()).isNull();
    }
}
