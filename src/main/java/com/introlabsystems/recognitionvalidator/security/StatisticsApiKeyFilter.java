package com.introlabsystems.recognitionvalidator.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class StatisticsApiKeyFilter extends OncePerRequestFilter {
    static final String AUTHORITY = "STATISTICS_READ";
    private final byte[] expected;

    StatisticsApiKeyFilter(String key) {
        expected = key == null || key.isBlank() ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader("X-API-Key");
        if (expected.length == 0 || supplied == null
                || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(expected.length == 0 ? 503 : 401);
            response.setContentType("application/problem+json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(expected.length == 0
                    ? "{\"status\":503,\"code\":\"STATISTICS_NOT_CONFIGURED\",\"detail\":\"Statistics access is not configured\"}"
                    : "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"detail\":\"A valid X-API-Key header is required\"}");
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new PreAuthenticatedAuthenticationToken("statistics-integration", null,
                AuthorityUtils.createAuthorityList(AUTHORITY)));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
