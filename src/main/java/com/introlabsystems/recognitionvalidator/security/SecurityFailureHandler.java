package com.introlabsystems.recognitionvalidator.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class SecurityFailureHandler implements
        AuthenticationEntryPoint,
        AccessDeniedHandler,
        SessionInformationExpiredStrategy {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";
    private static final String OPERATOR_ROLE = "ROLE_OPERATOR";

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        boolean expiredSession = hasInvalidSessionId(request);
        if (isApi(request)) {
            writeProblem(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    expiredSession ? "Session expired" : "Authentication required",
                    expiredSession
                            ? "Your session has expired. Sign in again."
                            : "Sign in to continue."
            );
            return;
        }
        redirect(response, request, expiredSession ? "/login?expired" : "/login");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);

        if (isApi(request)) {
            boolean expiredSession = !authenticated && hasInvalidSessionId(request);
            HttpStatus status = authenticated ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
            writeProblem(
                    response,
                    status,
                    authenticated
                            ? "Access denied"
                            : expiredSession ? "Session expired" : "Authentication required",
                    authenticated
                            ? "You do not have permission to perform this operation."
                            : expiredSession
                                    ? "Your session has expired. Sign in again."
                                    : "Sign in to continue."
            );
            return;
        }

        if (!authenticated) {
            redirect(
                    response,
                    request,
                    hasInvalidSessionId(request) ? "/login?expired" : "/login"
            );
            return;
        }

        Set<String> authorities = AuthorityUtils.authorityListToSet(
                authentication.getAuthorities()
        );
        String path = applicationPath(request);
        if (authorities.contains(ADMIN_ROLE) && isOperatorArea(path)) {
            redirect(response, request, "/admin");
            return;
        }
        if (authorities.contains(OPERATOR_ROLE) && path.startsWith("/admin")) {
            redirect(response, request, "/review");
            return;
        }

        response.sendError(HttpStatus.FORBIDDEN.value());
    }

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event)
            throws IOException {
        handleExpiredSession(event.getRequest(), event.getResponse());
    }

    private void handleExpiredSession(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        if (isApi(request)) {
            writeProblem(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "Session expired",
                    "Your session has expired. Sign in again."
            );
            return;
        }
        redirect(response, request, "/login?expired");
    }

    private boolean isApi(HttpServletRequest request) {
        return applicationPath(request).startsWith("/api/");
    }

    private boolean hasInvalidSessionId(HttpServletRequest request) {
        return request.getRequestedSessionId() != null
                && !request.isRequestedSessionIdValid();
    }

    private boolean isOperatorArea(String path) {
        return path.equals("/review") || path.equals("/statistics");
    }

    private String applicationPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        return contextPath.isEmpty() ? requestUri : requestUri.substring(contextPath.length());
    }

    private void redirect(
            HttpServletResponse response,
            HttpServletRequest request,
            String path
    ) throws IOException {
        response.sendRedirect(request.getContextPath() + path);
    }

    private void writeProblem(
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail
    ) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"status":%d,"title":"%s","detail":"%s"}
                """.formatted(status.value(), title, detail).trim());
    }
}
