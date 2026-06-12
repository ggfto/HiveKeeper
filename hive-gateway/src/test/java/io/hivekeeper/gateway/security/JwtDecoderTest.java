package io.hivekeeper.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the production decoder wiring ({@code NimbusJwtDecoder.withJwkSetUri(...)} +
 * {@code JwtValidators.createDefaultWithIssuer}) against a real JWKS served by an in-process HTTP server, so
 * the signature / issuer / expiry guarantees are pinned without a Keycloak container. Mirrors
 * {@link OidcSecurityConfig#jwtDecoder}.
 */
class JwtDecoderTest {

    private static final String ISSUER = "http://localhost/realms/hivekeeper";

    private static RSAKey signingKey;
    private static RSAKey otherKey;
    private static HttpServer jwks;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();   // same kid, different key

        String jwkJson = new JWKSet(signingKey.toPublicJWK()).toString();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/certs", exchange -> {
            byte[] body = jwkJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwks.start();

        String jwkSetUri = "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs";
        decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
    }

    @AfterAll
    static void tearDown() {
        jwks.stop(0);
    }

    private String token(RSAKey key, String issuer, Instant expiry) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("11111111-1111-1111-1111-111111111111")
                .issuer(issuer)
                .expirationTime(Date.from(expiry))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void decodesAProperlySignedTokenWithTheRightIssuer() throws Exception {
        Jwt jwt = decoder.decode(token(signingKey, ISSUER, Instant.now().plusSeconds(300)));
        assertEquals("11111111-1111-1111-1111-111111111111", jwt.getSubject());
        assertEquals(ISSUER, jwt.getIssuer().toString());
    }

    @Test
    void rejectsATokenFromTheWrongIssuer() throws Exception {
        String t = token(signingKey, "http://evil/realms/x", Instant.now().plusSeconds(300));
        assertThrows(JwtException.class, () -> decoder.decode(t));
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        String t = token(signingKey, ISSUER, Instant.now().minusSeconds(60));
        assertThrows(JwtException.class, () -> decoder.decode(t));
    }

    @Test
    void rejectsATokenSignedByAnUnknownKey() throws Exception {
        String t = token(otherKey, ISSUER, Instant.now().plusSeconds(300));
        assertThrows(JwtException.class, () -> decoder.decode(t));
    }

    @Test
    void acceptsATokenWithNoAudience() throws Exception {
        // Pins intentional behavior: createDefaultWithIssuer does NOT check audience, so a token with no
        // aud is accepted. Adding an audience requirement later (needs a Keycloak aud mapper) is a conscious
        // change that should flip this test.
        assertDoesNotThrow(() -> decoder.decode(token(signingKey, ISSUER, Instant.now().plusSeconds(300))));
    }
}
