package com.introlabsystems.recognitionvalidator.security;

import com.introlabsystems.recognitionvalidator.dao.jpa.AppUserRepository;
import com.introlabsystems.recognitionvalidator.model.entity.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown operator"));
        return new OperatorPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.isEnabled(),
                user.getRole()
        );
    }
}
