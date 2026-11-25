// 테스트 도구(테스트 자체는 아님): 테스트용 로그인 토큰(JWT) 공장.
// 테스트 실행 때마다 새로 만든 임시 열쇠로 서명하므로 진짜 운영 열쇠·토큰과는 무관하다.
// 관리자·고객·촬영 JWT·부스·진단 에이전트 토큰과, 만료·발급자 틀림·다른 열쇠 서명 같은 "나쁜 토큰"을 만든다.

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

    // 테스트 고객 본인의 렌탈 번호.
    public static final String OWN_RENTAL = "rNtOwn0000000000000a1";
    // 다른 고객의 렌탈 번호(남의 렌탈 접근을 시험할 때).
    public static final String OTHER_RENTAL = "rNtOther00000000000z9";
    // 테스트에 쓰는 수령(RCV) 시점 번호.
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

    // 관리자 토큰.
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

    // 부스(Jetson 촬영 부스) 토큰.
    public static String booth() {
        return sign(base("booth-out", "BOOTH").claim("scope", BOOTH_ID).build(), KEY);
    }

    // 진단 에이전트 토큰: 페어링 코드로 받은 것, 시점 하나와 렌탈 하나에만 쓸 수 있다.
    public static String agent() {
        return sign(base("agent-1", "AGENT").claim("scope", CHECKPOINT).claim("rentalId", OWN_RENTAL).build(), KEY);
    }

    // 1시간 전에 만료된 관리자 토큰.
    public static String expiredAdmin() {
        Instant past = Instant.now().minusSeconds(3600);
        return sign(base("admin-1", "ADMIN").issueTime(Date.from(past.minusSeconds(900)))
                .expirationTime(Date.from(past)).build(), KEY);
    }

    // 발급자가 trace가 아닌 토큰.
    public static String wrongIssuerAdmin() {
        return sign(base("admin-1", "ADMIN").issuer("someone-else").build(), KEY);
    }

    // 다른 열쇠로 서명한(위조) 토큰.
    public static String signedByOtherKey() {
        return sign(base("admin-1", "ADMIN").build(), OTHER_KEY);
    }

    // 원하는 역할 이름을 넣은 토큰(없는 역할 시험용).
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
