package com.introlabsystems.recognitionvalidator.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import jakarta.servlet.FilterChain;
import java.io.IOException;

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void statisticsAuthorityNeverLeaksToTheNextRequestEvenOnFailure(boolean fail) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/integration/statistics/daily");
        request.addHeader("X-API-Key", "test-statistics-key");
        var response = new MockHttpServletResponse();
        var filter = new StatisticsApiKeyFilter("test-statistics-key");
        FilterChain chain = (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("STATISTICS_READ");
            assertThat(auth.getCredentials()).isNull();
            if (fail) throw new IOException("failed response");
        };
        try {
            if (fail) assertThatIOException().isThrownBy(() -> filter.doFilter(request, response, chain));
            else filter.doFilter(request, response, chain);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        } finally { SecurityContextHolder.clearContext(); }
    }
}
