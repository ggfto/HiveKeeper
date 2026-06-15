package io.hivekeeper.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.annotation.Order;
import java.util.ArrayList;
import java.util.List;

/**
 * Under the {@code oidc} profile the gateway is an OAuth2 Resource Server: it validates bearer JWTs minted
 * by the identity provider (Keycloak in dev) on requests that need a human identity ({@code /api/me} today;
 * per-user enforcement on the rest is the next phase). Endpoints still reachable by the {@code X-Tenant-Key}
 * service principal stay {@code permitAll} so a request with no token falls through to the controller's own
 * check; a request that DOES carry a bearer token has it validated.
 *
 * <p>The decoder uses the JWKS endpoint (fetched lazily on first use, so gateway startup does not depend on
 * the IdP being up) plus issuer + timestamp validation. The IdP only authenticates — authorization
 * (org/site/group roles) lives in our own database.
 *
 * <p>Two ordered chains, NOT one: the JWT bearer filter is installed ONLY on the {@code /api/me} chain. If
 * it ran globally, a request to a tenant-key endpoint that also happened to carry an expired/foreign bearer
 * token would be 401'd by the resource server before the controller's {@code X-Tenant-Key} check — so a
 * client that attaches the user's token to every call would intermittently fail tenant-key requests. With a
 * dedicated matcher, tenant-key endpoints fall through to the permit-all chain and any bearer token there is
 * simply ignored.
 */
@Configuration
@EnableWebSecurity
@Profile("oidc")
public class OidcSecurityConfig {

    /** JWT-protected: only {@code /api/me} runs the bearer filter and requires a valid token. */
    @Bean
    @Order(1)
    public SecurityFilterChain meChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/me")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /** Everything else: permit-all so the controllers' own {@code X-Tenant-Key} check runs, with no bearer
     *  filter — a stray/expired token on these paths is ignored, not rejected. */
    @Bean
    @Order(2)
    public SecurityFilterChain tenantKeyChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(@Value("${hivekeeper.oidc.jwk-set-uri}") String jwkSetUri,
                                 @Value("${hivekeeper.oidc.issuer}") String issuer,
                                 @Value("${hivekeeper.oidc.audience:}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(tokenValidator(issuer, audience));
        return decoder;
    }

    /**
     * The full token validator: signature + issuer + timestamp (default), a required non-blank subject
     * (identity keys on it, so a subjectless token must be rejected here, not 500 in provisioning), and — when
     * an {@code audience} is configured — an audience check. Package-visible + static so the unit test pins the
     * exact production wiring rather than re-deriving it.
     */
    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        validators.add(new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()));
        if (audience != null && !audience.isBlank()) {
            validators.add(audienceValidator(audience.trim()));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    /**
     * Reject a token that was not minted for this resource. The same Keycloak realm issues tokens for every
     * client (account console, other apps, ...) — all with our issuer + a valid signature — so without this any
     * of them would be accepted (audience confusion). A token is accepted when its {@code aud} contains our
     * audience (needs a Keycloak audience mapper) OR its {@code azp} (authorized party — always the requesting
     * client) equals it, so it works with a stock Keycloak client and is stricter with the mapper added.
     */
    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        OAuth2Error error = new OAuth2Error("invalid_token",
                "the token was not issued for this resource (aud/azp)", null);
        return jwt -> {
            List<String> aud = jwt.getAudience();
            boolean ok = (aud != null && aud.contains(audience)) || audience.equals(jwt.getClaimAsString("azp"));
            return ok ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(error);
        };
    }
}
