package com.comhu.bidmonitor.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiSecurityConfigProfileTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    DevAdminAuthenticationConfig.class,
                    NoLocalUsersAuthenticationConfig.class
            );

    @Test
    void createsDevAdminOnlyWhenDevProfileAndCredentialsAreExplicitlyConfigured() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "biz-assist.dev-admin.username=dev-admin",
                        "biz-assist.dev-admin.password=test-only-password"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("devAdminUsers");
                    assertThat(context).doesNotHaveBean("noLocalUsers");
                    UserDetailsService users = context.getBean(UserDetailsService.class);
                    var admin = users.loadUserByUsername("dev-admin");
                    PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
                    assertThat(encoder.matches("test-only-password", admin.getPassword())).isTrue();
                    assertThat(admin.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
                });
    }

    @Test
    void keepsDevAdminBeansOutOfNonDevProfilesEvenWhenCredentialPropertiesExist() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=postgres",
                        "biz-assist.dev-admin.username=dev-admin",
                        "biz-assist.dev-admin.password=test-only-password"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("devAdminUsers");
                    assertThat(context).doesNotHaveBean(PasswordEncoder.class);
                    assertThat(context).hasBean("noLocalUsers");
                    assertThatThrownBy(() -> context.getBean(UserDetailsService.class)
                            .loadUserByUsername("dev-admin"))
                            .isInstanceOf(UsernameNotFoundException.class);
                });
    }

    @Test
    void refusesToStartDevAuthenticationWhenEitherCredentialIsMissing() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "biz-assist.dev-admin.username=dev-admin"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("BIZ_ASSIST_DEV_ADMIN_PASSWORD");
                });
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "biz-assist.dev-admin.password=test-only-password"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("BIZ_ASSIST_DEV_ADMIN_USERNAME");
                });
    }
}
