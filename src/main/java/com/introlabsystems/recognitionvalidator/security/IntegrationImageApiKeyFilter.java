package com.introlabsystems.recognitionvalidator.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// Constructed only in the integration security chain, not registered as a servlet filter.
final class IntegrationImageApiKeyFilter extends OncePerRequestFilter {

    static final String AUTHORITY = "IMAGE_READ";

    private final byte[] expectedKey;

    IntegrationImageApiKeyFilter(String key) {
        expectedKey = key == null || key.isBlank()
                ? new byte[0]
                : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String suppliedKey = request.getHeader("X-API-Key");
        if (expectedKey.length == 0 || suppliedKey == null
                || !MessageDigest.isEqual(expectedKey, suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.getWriter().write("""
                    {"status":401,"title":"Unauthorized","detail":"A valid X-API-Key header is required."}
                    """);
            return;
        }

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new PreAuthenticatedAuthenticationToken(
                "image-integration", null, AuthorityUtils.createAuthorityList(AUTHORITY)));
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
