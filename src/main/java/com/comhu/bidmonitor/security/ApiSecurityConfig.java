package com.comhu.bidmonitor.security;

import com.comhu.bidmonitor.bid.api.dto.BidApiErrorResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ApiSecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/bid-source-registrations/*/review",
                                "/api/bid-source-registrations/*/binding",
                                "/api/bid-source-registrations/*/activation")
                        .hasRole("ADMIN")
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, jsonMapper, 401, "Unauthorized", "AUTHENTICATION_REQUIRED",
                                "Authentication is required."
                        ))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, jsonMapper, 403, "Forbidden", "ADMIN_ROLE_REQUIRED",
                                "Administrator authority is required."
                        )))
                .build();
    }

    /** 로컬 계정은 만들지 않으며 실제 인증 공급자는 별도 통합 단계에서 연결한다. */
    @Bean
    UserDetailsService noLocalUsers() {
        return username -> {
            throw new UsernameNotFoundException("Local user authentication is not configured.");
        };
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
