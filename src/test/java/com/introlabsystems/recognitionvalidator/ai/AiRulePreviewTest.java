package com.introlabsystems.recognitionvalidator.ai;

import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AiRulePreviewTest extends AiTestSupport {
    @Autowired AiTaskRepository tasks;

    private AiRule rule(int priority, boolean enabled, String game, Long token) {
        return new AiRule(null, "Rule " + priority, enabled, priority, game, null, null, token, null, null);
    }

    @Test
    void draftPriorityDefaultDisabledAndOtherGamesMatchClaimWithoutChangingAnyState() {
        String first = image(1, 53), fallback = image(2, 7), nullToken = image(3, 7), other = image(4, 53);
        jdbc.update("UPDATE ai_review_task SET token_id=NULL WHERE image_id=?", nullToken);
        jdbc.update("UPDATE ai_review_task SET game_code='bj_igt' WHERE image_id=?", other);
        var settings = new AiSettings(99, true, List.of(rule(1, false, "bj_single_deck_ags", null),
                rule(2, true, "bj_single_deck_ags", 53L), rule(3, true, "bj_single_deck_ags", 53L),
                rule(4, true, "bj_single_deck_ags", null), rule(5, true, "bj_igt", null)));
        var before = jdbc.queryForList("SELECT * FROM ai_review_task ORDER BY image_id");
        assertThat(tasks.preview(settings, 1).enabled()).isFalse();
        assertThat(tasks.preview(settings, 2).items()).extracting(AiRulePreview.Item::imageId).containsExactly(first);
        assertThat(tasks.preview(settings, 3).items()).isEmpty();
        assertThat(tasks.preview(settings, 4).items()).extracting(AiRulePreview.Item::imageId).containsExactly(fallback, nullToken);
        assertThat(tasks.preview(settings, 5).items()).extracting(AiRulePreview.Item::imageId).containsExactly(other);
        assertThat(tasks.preview(new AiSettings(99, false, settings.rules()), 4).items()).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM ai_review_task ORDER BY image_id")).isEqualTo(before);
        for (String table : List.of("ai_queue_settings", "ai_selection_rule", "ai_rule_activity")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(tasks.claim(settings, 10)).extracting(AiClaim::imageId).containsExactly(first, fallback, nullToken, other);
        assertThat(jdbc.queryForObject("SELECT issued_rule_id FROM ai_review_task WHERE image_id=?", UUID.class, nullToken))
                .isEqualTo(settings.rules().get(3).id());
        // Moving default above the specific rule changes ownership in the unsaved draft.
        jdbc.update("UPDATE ai_review_task SET status='PENDING'");
        var moved = new AiSettings(99, true, List.of(rule(1, true, "bj_single_deck_ags", null), rule(2, true, "bj_single_deck_ags", 53L)));
        assertThat(tasks.preview(moved, 2).items()).isEmpty();
    }

    @Test
    void previewIsBoundedOldestFirstAndUsesAvailabilityRetryLeaseAndEveryRuleCondition() {
        for (int i = 1; i <= 18; i++) image(i, 53);
        jdbc.update("UPDATE ai_review_task SET retry_after=now()+interval '1 hour' WHERE image_id=?", "%064x".formatted(1));
        jdbc.update("UPDATE image_asset SET file_available=false WHERE id=?", "%064x".formatted(2));
        jdbc.update("UPDATE ai_review_task SET file_available=false WHERE image_id=?", "%064x".formatted(3));
        jdbc.update("UPDATE ai_review_task SET status='PROCESSING', claim_id=?, lease_expires_at=now()+interval '1 hour' WHERE image_id=?", UUID.randomUUID(), "%064x".formatted(4));
        jdbc.update("UPDATE ai_review_task SET status='PROCESSING', claim_id=?, lease_expires_at=now()-interval '1 hour', retry_after=now()+interval '1 day' WHERE image_id=?", UUID.randomUUID(), "%064x".formatted(5));
        var settings = new AiSettings(0, true, List.of(rule(1, true, "bj_single_deck_ags", null)));
        var before = jdbc.queryForList("SELECT * FROM ai_review_task ORDER BY image_id");
        var preview = tasks.preview(settings, 1);
        assertThat(preview.items()).hasSize(10).extracting(AiRulePreview.Item::imageId)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(5, 14).mapToObj(i -> "%064x".formatted(i)).toList());
        assertThat(preview.hasMore()).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM ai_review_task ORDER BY image_id")).isEqualTo(before);
        var bounded = new AiSettings(0, true, List.of(new AiRule(null, "Bounded", true, 1, "bj_single_deck_ags",
                Instant.parse("2026-08-30T00:00:06Z"), Instant.parse("2026-08-30T00:00:10Z"), 53L, "session-a", true)));
        jdbc.update("UPDATE ai_review_task SET session_id='other' WHERE image_id=?", "%064x".formatted(7));
        jdbc.update("UPDATE ai_review_task SET has_user_hand=false WHERE image_id=?", "%064x".formatted(8));
        jdbc.update("UPDATE ai_review_task SET token_id=9 WHERE image_id=?", "%064x".formatted(9));
        var one = tasks.preview(bounded, 1);
        assertThat(one.items()).extracting(AiRulePreview.Item::imageId).containsExactly("%064x".formatted(6));
        assertThat(one.hasMore()).isFalse();
        assertThat(tasks.claim(bounded, 10)).extracting(AiClaim::imageId).containsExactly("%064x".formatted(6));
        assertThat(tasks.preview(bounded, 1).items()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.preview(settings, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.preview(settings, 2));
    }
}
