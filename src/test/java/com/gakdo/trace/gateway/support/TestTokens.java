package com.gakdo.trace.gateway.support;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** 테스트용 RS256 키와 6종 토큰. auth-server(AU1)가 낼 클레임 모양(contracts bearerJwt)을 따른다. */
public final class TestTokens {

    public static final String OWN_RENTAL = "rNtOwn0000000000000a1";
    public static final String OTHER_RENTAL = "rNtOther00000000000z9";
    public static final String CHECKPOINT = "cKpRcv000000000000302";
    public static final String BOOTH_ID = "booth-out";

    private static final RSAKey KEY = generate("test-key");
    private static final RSAKey OTHER_KEY = generate("other-key");

    private TestTokens() {
    }

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** stub auth-server가 내보낼 공개키 묶음. */
    public static String jwksJson() {
        return new JWKSet(KEY.toPublicJWK()).toString();
    }

    public static String admin() {
        return sign(base("admin-1", "ADMIN").build(), KEY);
    }

    /** 로그인한 고객: rentals 클레임에 OWN_RENTAL만. scope 없음. */
    public static String customer() {
        return sign(base("customer-1", "CUSTOMER").claim("rentals", List.of(OWN_RENTAL)).build(), KEY);
    }

    /** 촬영 JWT: CUSTOMER + scope(checkpointId) + rentalId. */
    public static String capture() {
        return sign(base("customer-1", "CUSTOMER").claim("scope", CHECKPOINT).claim("rentalId", OWN_RENTAL).build(), KEY);
    }

    public static String booth() {
        return sign(base("booth-out", "BOOTH").claim("scope", BOOTH_ID).build(), KEY);
    }

    public static String agent() {
        return sign(base("agent-1", "AGENT").claim("scope", CHECKPOINT).claim("rentalId", OWN_RENTAL).build(), KEY);
    }

    public static String expiredAdmin() {
        Instant past = Instant.now().minusSeconds(3600);
        return sign(base("admin-1", "ADMIN").issueTime(Date.from(past.minusSeconds(900)))
                .expirationTime(Date.from(past)).build(), KEY);
    }

    public static String wrongIssuerAdmin() {
        return sign(base("admin-1", "ADMIN").issuer("someone-else").build(), KEY);
    }

    public static String signedByOtherKey() {
        return sign(base("admin-1", "ADMIN").build(), OTHER_KEY);
    }

    public static String withRole(String role) {
        return sign(base("x-1", role).build(), KEY);
    }

    private static JWTClaimsSet.Builder base(String subject, String role) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("trace")
                .subject(subject)
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .jwtID(UUID.randomUUID().toString());
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
