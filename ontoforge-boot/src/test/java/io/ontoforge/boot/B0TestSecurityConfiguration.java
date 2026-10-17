package io.ontoforge.boot;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@TestConfiguration(proxyBeanMethods = false)
public class B0TestSecurityConfiguration {
    public static final String ISSUER = "https://test-idp.ontoforge.local";
    public static final String AUDIENCE = "ontoforge";

    @Bean
    KeyPair b0TestKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Bean
    @Primary
    JwtDecoder b0TestJwtDecoder(KeyPair keyPair) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> issuer =
                JwtValidators.createDefaultWithIssuer(ISSUER);
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> audience =
                new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(AUDIENCE));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        return decoder;
    }

    @Bean
    JwtEncoder b0TestJwtEncoder(KeyPair keyPair) {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyID("b0-test")
                .build();
        JWKSource<SecurityContext> source = (selector, context) -> selector.select(new JWKSet(rsa));
        return new NimbusJwtEncoder(source);
    }

    public static String token(JwtEncoder encoder, String subject) {
        return token(encoder, subject, ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300));
    }

    public static String token(
            JwtEncoder encoder, String subject, String issuer, List<String> audience, Instant expiresAt) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .issuedAt(expiresAt.isBefore(now) ? expiresAt.minusSeconds(300) : now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("b0-test").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
