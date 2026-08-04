package com.introlabsystems.recognitionvalidator.image;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FilenameParserTest {

    private static final List<String> ALL_GAMES = List.of(
            "bj_xchange",
            "bj_relax",
            "bj_igt",
            "bj_playtech",
            "bj_netent",
            "bj_poker_and_pairs",
            "bj_first_person",
            "bj_black_throne",
            "bj_double_deck_black_throne",
            "bj_single_deck_ags",
            "bj_side_bets_ags",
            "bj_atlantic_city",
            "bj_multihand_play_n_go"
    );

    private FilenameParser parser;

    @BeforeEach
    void setUp() {
        GameCatalog catalog = new GameCatalog(ALL_GAMES);
        parser = new FilenameParser(catalog);
    }

    @Test
    void parsesDealerHandsButtonsAndTechnicalFields() {
        ParsedFilename parsed = parser.parse(
                "bj_single_deck_ags_37_5730ca78-7535-4e9d-aeb6-22802c3eb0a6"
                        + "_d_Eight_u_Seven_Jack_u_A10J3_bSbHbD_27-07-2026-06-17-06_895.png"
        );
        RecognitionResult recognition = parsed.recognition();

        assertThat(recognition.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(parsed.gameCode()).isEqualTo("bj_single_deck_ags");
        assertThat(parsed.tokenId()).isEqualTo(37L);
        assertThat(parsed.sessionUuid())
                .isEqualTo(UUID.fromString("5730ca78-7535-4e9d-aeb6-22802c3eb0a6"));
        assertThat(parsed.sessionId())
                .isEqualTo("37_5730ca78-7535-4e9d-aeb6-22802c3eb0a6");
        assertThat(recognition.dealerCards()).isEqualTo("Eight");
        assertThat(recognition.activeUserCards()).isEqualTo("Seven_Jack");
        assertThat(recognition.inactiveUserCards()).isEqualTo("A10J3");
        assertThat(recognition.buttonsRaw()).isEqualTo("bSbHbD");
        assertThat(recognition.stand()).isTrue();
        assertThat(recognition.hit()).isTrue();
        assertThat(recognition.doubleAction()).isTrue();
        assertThat(recognition.split()).isFalse();
        assertThat(recognition.notification()).isFalse();
        assertThat(recognition.processedAt()).isEqualTo(Instant.parse("2026-07-27T06:17:06Z"));
        assertThat(recognition.recognitionDurationMs()).isEqualTo(895L);
    }

    @Test
    void parsesSingleActiveHandWithoutDealer() {
        ParsedFilename parsed = parser.parse(
                "bj_igt_39_850746c3-874d-495d-aefa-5ea3636cfb51"
                        + "_u_Jack_bSbH_27-07-2026-22-48-01_754.png"
        );
        RecognitionResult recognition = parsed.recognition();

        assertThat(recognition.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(recognition.dealerCards()).isNull();
        assertThat(recognition.activeUserCards()).isEqualTo("Jack");
        assertThat(recognition.inactiveUserCards()).isNull();
        assertThat(recognition.stand()).isTrue();
        assertThat(recognition.hit()).isTrue();
        assertThat(recognition.processedAt()).isEqualTo(Instant.parse("2026-07-27T22:48:01Z"));
    }

    @Test
    void parsesNotificationThatAlsoContainsUserCards() {
        ParsedFilename parsed = parser.parse(
                "bj_single_deck_ags_32_2f85c92a-b245-4c88-a56b-7bb15fb93c38"
                        + "_bN_u_22769K_30-07-2026-09-28-40_2682.png"
        );
        RecognitionResult recognition = parsed.recognition();

        assertThat(recognition.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(recognition.notification()).isTrue();
        assertThat(recognition.activeUserCards()).isEqualTo("22769K");
        assertThat(recognition.buttonsRaw()).isEqualTo("bN");
        assertThat(recognition.processedAt()).isEqualTo(Instant.parse("2026-07-30T09:28:40Z"));
        assertThat(recognition.recognitionDurationMs()).isEqualTo(2682L);
    }

    @Test
    void parsesSurrenderFromBlackThroneScreenshot() {
        ParsedFilename parsed = parser.parse(
                "bj_double_deck_black_throne_36_"
                        + "2e8c1326-fb86-4c51-8e45-8bc65e6d33ee"
                        + "_d_Four_u_Nine_Seven_bSbHbDbSR"
                        + "_27-07-2026-12-36-56_481.png"
        );
        RecognitionResult recognition = parsed.recognition();

        assertThat(recognition.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(parsed.gameCode()).isEqualTo("bj_double_deck_black_throne");
        assertThat(recognition.dealerCards()).isEqualTo("Four");
        assertThat(recognition.activeUserCards()).isEqualTo("Nine_Seven");
        assertThat(recognition.inactiveUserCards()).isNull();
        assertThat(recognition.buttonsRaw()).isEqualTo("bSbHbDbSR");
        assertThat(recognition.stand()).isTrue();
        assertThat(recognition.hit()).isTrue();
        assertThat(recognition.doubleAction()).isTrue();
        assertThat(recognition.split()).isFalse();
        assertThat(recognition.surrender()).isTrue();
    }

    @Test
    void parsesSplitAndMarksUnknownButtonAsPartial() {
        ParsedFilename split = parser.parse(
                "bj_igt_39_850746c3-874d-495d-aefa-5ea3636cfb51"
                        + "_u_Jack_bPbSR_27-07-2026-22-48-01_754.png"
        );
        ParsedFilename unknown = parser.parse(
                "bj_igt_39_850746c3-874d-495d-aefa-5ea3636cfb51"
                        + "_u_Jack_bX_27-07-2026-22-48-01_754.png"
        );

        assertThat(split.recognition().parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(split.recognition().split()).isTrue();
        assertThat(split.recognition().surrender()).isTrue();
        assertThat(unknown.recognition().parseStatus()).isEqualTo(ParseStatus.PARTIAL);
        assertThat(unknown.recognition().buttonsRaw()).isEqualTo("bX");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bj_xchange",
            "bj_relax",
            "bj_igt",
            "bj_playtech",
            "bj_netent",
            "bj_poker_and_pairs",
            "bj_first_person",
            "bj_black_throne",
            "bj_double_deck_black_throne",
            "bj_single_deck_ags",
            "bj_side_bets_ags",
            "bj_atlantic_city",
            "bj_multihand_play_n_go"
    })
    void parsesEveryConfiguredGamePrefix(String gameCode) {
        ParsedFilename parsed = parser.parse(
                gameCode + "_39_850746c3-874d-495d-aefa-5ea3636cfb51"
                        + "_u_Jack_bS_27-07-2026-22-48-01_754.png"
        );

        assertThat(parsed.recognition().parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(parsed.gameCode()).isEqualTo(gameCode);
    }

    @Test
    void returnsErrorInsteadOfThrowingForMalformedName() {
        ParsedFilename unknownGame = parser.parse("not-a-recognition-screenshot.png");
        ParsedFilename knownGame = parser.parse("bj_igt_not-a-valid-session.png");

        assertThat(unknownGame.recognition().parseStatus()).isEqualTo(ParseStatus.ERROR);
        assertThat(unknownGame.gameCode()).isNull();
        assertThat(unknownGame.sessionId()).isNull();
        assertThat(knownGame.recognition().parseStatus()).isEqualTo(ParseStatus.ERROR);
        assertThat(knownGame.gameCode()).isEqualTo("bj_igt");
        assertThat(knownGame.sessionId()).isNull();
    }
}
