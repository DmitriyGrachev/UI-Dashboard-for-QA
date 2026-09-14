package com.introlabsystems.recognitionvalidator.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticsApiKeyFilterTest {
    @Test
    void unconfiguredKeyNeverPermitsAccess() throws Exception {
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest("GET", "/api/integration/statistics/daily");
        request.addHeader("X-API-Key", "anything");
        new StatisticsApiKeyFilter("").doFilter(request, response, (req, res) -> {
            throw new AssertionError("Unconfigured integration must not reach the controller");
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("STATISTICS_NOT_CONFIGURED");
    }
}
