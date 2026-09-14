package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.dao.jdbc.ReviewClaimRepository;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSharedSummaryTest extends AbstractReviewIntegrationTest {
    @Autowired ReviewClaimRepository claims;

    @Test
    void remainingIncludesOtherOperatorsAssignmentsButNotCompletedReviews() {
        var first = insertOperator("first");
        var second = insertOperator("second");
        var now = Instant.parse("2026-09-14T10:00:00Z");
        for (int i = 1; i <= 3; i++) insertImage(i, now.plusSeconds(i), "bj_igt", "session", false, true);
        var item = claims.claim(first, ReviewFilters.none(), now, Duration.ofMinutes(30), false, false).item().orElseThrow();
        claims.claim(second, ReviewFilters.none(), now, Duration.ofMinutes(30), false, false);
        assertThat(claims.summarize(first, ReviewFilters.none()).remaining()).isEqualTo(3);
        assertThat(claims.summarize(second, ReviewFilters.none()).remaining()).isEqualTo(3);
        completeReview(item.imageId(), first, Decision.ACCEPTED, now);
        assertThat(claims.summarize(second, ReviewFilters.none()).remaining()).isEqualTo(2);
    }
}
