package io.hivekeeper.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Without the {@code oidc} profile there is no user login: requests authenticate via {@code X-Tenant-Key}
 * checked inside the controllers (the service-principal model). The oauth2 resource-server starter is on the
 * classpath, which would otherwise make Spring Boot lock every endpoint behind a generated password — so
 * this chain explicitly permits all requests and leaves authentication to the controllers, preserving the
 * existing behavior. {@link OidcSecurityConfig} replaces this chain when {@code oidc} is active.
 */
@Configuration
@EnableWebSecurity
@Profile("!oidc")
public class DefaultSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
