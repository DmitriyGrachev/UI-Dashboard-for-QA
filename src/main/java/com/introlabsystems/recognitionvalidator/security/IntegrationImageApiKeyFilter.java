package com.introlabsystems.recognitionvalidator.security;

import com.introlabsystems.recognitionvalidator.ai.security.AiLocalImageUrlSigner;
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
    private final AiLocalImageUrlSigner signer;

    IntegrationImageApiKeyFilter(String key, AiLocalImageUrlSigner signer) {
        this.signer = signer;
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
        boolean validKey = expectedKey.length > 0 && suppliedKey != null
                && MessageDigest.isEqual(expectedKey, suppliedKey.getBytes(StandardCharsets.UTF_8));
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean aiTaskRequest = path.startsWith("/api/integration/ai/tasks/");
        boolean signedImage = false;
        if ("GET".equals(request.getMethod()) && path.matches("/api/integration/images/[0-9a-f]{64}/content")) {
            String id = path.substring("/api/integration/images/".length(), path.length() - "/content".length());
            signedImage = signer.verify(id, request.getParameter("expires"), request.getParameter("signature"));
        }
        if (!validKey && !signedImage) {
            int status = aiTaskRequest && expectedKey.length == 0 ? 503 : 401;
            response.setStatus(status);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.getWriter().write(status == 503
                    ? "{\"code\":\"INTEGRATION_NOT_CONFIGURED\",\"message\":\"Integration access is not configured\"}"
                    : "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"title\":\"Unauthorized\",\"detail\":\"A valid X-API-Key header or image signature is required.\"}");
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
