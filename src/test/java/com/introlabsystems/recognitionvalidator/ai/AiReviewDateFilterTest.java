package com.introlabsystems.recognitionvalidator.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AiReviewDateFilterTest extends AiTestSupport {
    @Autowired MockMvc mvc;

    @Test
    void searchSummaryAndCsvUseAiResultTimeWithExclusiveEndIndependentlyOfOperatorReview() throws Exception {
        String included = image(1, 53), end = image(2, 53);
        image(3, 53);
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED',valid=false,checked_at='2026-09-24T00:00:00Z' WHERE image_id=?", included);
        jdbc.update("UPDATE ai_review_task SET status='COMPLETED',valid=false,checked_at='2026-09-25T00:00:00Z' WHERE image_id=?", end);
        for (String suffix : new String[]{"", "/summary", "/export.csv"}) {
            var response = mvc.perform(get("/admin/api/screenshots" + suffix)
                    .param("aiReviewedFrom", "2026-09-24T00:00:00Z").param("aiReviewedTo", "2026-09-25T00:00:00Z")
                    .param("aiResult", "UNMATCHED").param("reviewState", "UNCHECKED")
                    .with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
            if (suffix.isEmpty()) response.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].imageId").value(included));
            else if (suffix.equals("/summary")) response.andExpect(jsonPath("$.totalCount").value(1));
            else assertThat(response.andReturn().getResponse().getContentAsString()).contains(included).doesNotContain(end);
        }
    }

    @Test
    void invalidAiReviewRangesAreRejectedByAllReadEndpoints() throws Exception {
        for (String suffix : new String[]{"", "/summary", "/export.csv"}) {
            mvc.perform(get("/admin/api/screenshots" + suffix).param("aiReviewedFrom", "2026-09-25T00:00:00Z")
                    .param("aiReviewedTo", "2026-09-24T00:00:00Z").with(user("admin").roles("ADMIN")))
                    .andExpect(status().isBadRequest());
        }
    }
}
