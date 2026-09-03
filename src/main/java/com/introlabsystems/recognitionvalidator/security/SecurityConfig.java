package com.introlabsystems.recognitionvalidator.security;

import com.introlabsystems.recognitionvalidator.ai.security.AiLocalImageUrlSigner;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
public class SecurityConfig {

    @Bean
    AiLocalImageUrlSigner aiLocalImageUrlSigner(
            @Value("${validator.ai-delivery.signing-key:}") String key,
            ObjectProvider<Clock> clock
    ) {
        return new AiLocalImageUrlSigner(key, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationSuccessHandler roleRedirectHandler() {
        return (request, response, authentication) -> {
            boolean admin = AuthorityUtils.authorityListToSet(authentication.getAuthorities())
                    .contains("ROLE_ADMIN");
            response.sendRedirect(admin ? "/admin" : "/review");
        };
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    ServletListenerRegistrationBean<HttpSessionEventPublisher> sessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    @Bean
    @Order(1)
    SecurityFilterChain integrationImageSecurityFilterChain(
            HttpSecurity http,
            SecurityFailureHandler securityFailureHandler,
            AiLocalImageUrlSigner signer,
            @Value("${validator.integration.image-api-key:}") String imageApiKey
    ) throws Exception {
        return http
                .securityMatcher("/api/integration/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/integration/images/*/content")
                        .hasAuthority(IntegrationImageApiKeyFilter.AUTHORITY)
                        .requestMatchers(HttpMethod.POST, "/api/integration/ai/tasks/claim", "/api/integration/ai/tasks/*/result")
                        .hasAuthority(IntegrationImageApiKeyFilter.AUTHORITY)
                        .anyRequest().denyAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityFailureHandler)
                        .accessDeniedHandler(securityFailureHandler)
                )
                .addFilterBefore(new IntegrationImageApiKeyFilter(imageApiKey, signer), AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationSuccessHandler roleRedirectHandler,
            SessionRegistry sessionRegistry,
            SecurityFailureHandler securityFailureHandler
    ) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/error", "/css/**", "/js/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers(
                                "/review",
                                "/statistics",
                                "/api/review-tasks/**",
                                "/api/images/**",
                                "/api/statistics/**"
                        ).hasRole("OPERATOR")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityFailureHandler)
                        .accessDeniedHandler(securityFailureHandler)
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(roleRedirectHandler)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .deleteCookies("JSESSIONID")
                )
                .sessionManagement(session -> session
                        .maximumSessions(-1)
                        .expiredSessionStrategy(securityFailureHandler)
                        .sessionRegistry(sessionRegistry)
                )
                .build();
    }
}
