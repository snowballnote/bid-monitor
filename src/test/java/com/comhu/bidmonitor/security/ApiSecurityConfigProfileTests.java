package com.comhu.bidmonitor.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiSecurityConfigProfileTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(NoLocalUsersAuthenticationConfig.class);

    @Test
    void doesNotCreateLocalAuthenticationUsersInDevProfile() {
        assertNoLocalAuthenticationUsers("dev");
    }

    @Test
    void doesNotCreateLocalAuthenticationUsersInNonDevProfile() {
        assertNoLocalAuthenticationUsers("postgres");
    }

    private void assertNoLocalAuthenticationUsers(String profile) {
        contextRunner
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("noLocalUsers");
                    assertThatThrownBy(() -> context.getBean(UserDetailsService.class)
                            .loadUserByUsername("any-user"))
                            .isInstanceOf(UsernameNotFoundException.class);
                });
    }
}
