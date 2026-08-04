package com.introlabsystems.recognitionvalidator.security;

import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserSessionService {

    private final SessionRegistry sessions;

    public UserSessionService(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    public void expireFor(UUID userId) {
        sessions.getAllPrincipals().stream()
                .filter(OperatorPrincipal.class::isInstance)
                .map(OperatorPrincipal.class::cast)
                .filter(principal -> principal.id().equals(userId))
                .flatMap(principal -> sessions.getAllSessions(principal, false).stream())
                .forEach(session -> session.expireNow());
    }
}
