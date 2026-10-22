package com.comhu.bidmonitor.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ApiSecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            Environment environment
    ) throws Exception {
        boolean h2ConsoleProfile = environment.acceptsProfiles(Profiles.of("h2"));
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> {
                    if (h2ConsoleProfile) {
                        authorize.requestMatchers("/h2-console/**").permitAll();
                    } else {
                        authorize.requestMatchers("/h2-console/**").denyAll();
                    }
                    authorize.anyRequest().permitAll();
                })
                .httpBasic(basic -> basic.disable());
        if (h2ConsoleProfile) {
            http.headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()));
        }
        return http.build();
    }
}

@Configuration(proxyBeanMethods = false)
class NoLocalUsersAuthenticationConfig {

    @Bean
    UserDetailsService noLocalUsers() {
        return username -> {
            throw new UsernameNotFoundException("Local user authentication is not configured.");
        };
    }
}
