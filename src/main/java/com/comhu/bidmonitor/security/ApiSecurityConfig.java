package com.comhu.bidmonitor.security;

import com.comhu.bidmonitor.bid.api.dto.BidApiErrorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ApiSecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JsonMapper jsonMapper,
            Environment environment
    ) throws Exception {
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) -> writeError(
                response, jsonMapper, 401, "Unauthorized", "AUTHENTICATION_REQUIRED",
                "Authentication is required."
        );
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) -> writeError(
                response, jsonMapper, 403, "Forbidden", "ADMIN_ROLE_REQUIRED",
                "Administrator authority is required."
        );
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/bid-source-registrations/*/review",
                                "/api/bid-source-registrations/*/discovery/review",
                                "/api/bid-source-registrations/*/binding",
                                "/api/bid-source-registrations/*/activation")
                        .hasRole("ADMIN")
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            http.httpBasic(basic -> basic.authenticationEntryPoint(authenticationEntryPoint));
        } else {
            http.httpBasic(basic -> basic.disable());
        }
        return http.build();
    }

    private void writeError(
            jakarta.servlet.http.HttpServletResponse response,
            JsonMapper jsonMapper,
            int status,
            String error,
            String code,
            String message
    ) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), new BidApiErrorResponse(
                status, error, code, message
        ));
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("!dev")
class NoLocalUsersAuthenticationConfig {

    @Bean
    UserDetailsService noLocalUsers() {
        return username -> {
            throw new UsernameNotFoundException("Local user authentication is not configured.");
        };
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("dev")
class DevAdminAuthenticationConfig {

    @Bean
    PasswordEncoder devAdminPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService devAdminUsers(
            @Value("${biz-assist.dev-admin.username:}") String username,
            @Value("${biz-assist.dev-admin.password:}") String password,
            PasswordEncoder devAdminPasswordEncoder
    ) {
        String validatedUsername = requiredUsername(username);
        String validatedPassword = requiredPassword(password);
        return new InMemoryUserDetailsManager(User.builder()
                .username(validatedUsername)
                .password(devAdminPasswordEncoder.encode(validatedPassword))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                .build());
    }

    private String requiredUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("BIZ_ASSIST_DEV_ADMIN_USERNAME must be set for the dev profile.");
        }
        String normalized = username.trim();
        if (normalized.contains(":")) {
            throw new IllegalStateException("BIZ_ASSIST_DEV_ADMIN_USERNAME cannot contain ':'.");
        }
        return normalized;
    }

    private String requiredPassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("BIZ_ASSIST_DEV_ADMIN_PASSWORD must be set for the dev profile.");
        }
        return password;
    }
}
