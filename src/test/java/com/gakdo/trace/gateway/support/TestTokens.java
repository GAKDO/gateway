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
    // 테스트 부스 ID(부스 토큰의 scope에 들어감).
    public static final String BOOTH_ID = "booth-out";

    // 진짜 서명용 임시 열쇠를 실행할 때 새로 만든다(가짜 서버가 이 공개키를 내줌)
    private static final RSAKey KEY = generate("test-key");
    // 위조 토큰을 만들 다른 열쇠를 만든다(gateway가 모르는 열쇠)
    private static final RSAKey OTHER_KEY = generate("other-key");

    // 객체를 만들 일이 없는 도구 모음이라 생성자를 막아 둔다
    private TestTokens() {
    }

    // 테스트 도구: 이름(kid)을 붙인 RSA 열쇠 쌍을 만든다
    private static RSAKey generate(String kid) {
        try {
            // 2048비트 RSA 열쇠를 만들어 이름을 붙인다
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            // 열쇠를 못 만들면 테스트 준비 실패로 바로 멈춘다
            throw new IllegalStateException(e);
        }
    }

    /** stub auth-server가 내보낼 공개키 묶음. */
    public static String jwksJson() {
        // 서명 열쇠의 공개 부분만 JWKS JSON 글자로 바꿔 돌려준다(비밀 열쇠는 내보내지 않음)
        return new JWKSet(KEY.toPublicJWK()).toString();
    }

    // 관리자 토큰.
    public static String admin() {
        // ADMIN 역할 토큰을 테스트 열쇠로 서명해 돌려준다
        return sign(base("admin-1", "ADMIN").build(), KEY);
    }

    /** 로그인한 고객: rentals 클레임에 OWN_RENTAL만. scope 없음. */
    public static String customer() {
        // CUSTOMER 역할에 본인 렌탈 번호 목록(rentals)을 넣어 서명한다
        return sign(base("customer-1", "CUSTOMER").claim("rentals", List.of(OWN_RENTAL)).build(), KEY);
    }

    /** 촬영 JWT: CUSTOMER + scope(checkpointId) + rentalId. */
    public static String capture() {
        // CUSTOMER 역할에 시점(scope)과 렌탈 번호를 넣어 촬영 JWT를 만든다
        return sign(base("customer-1", "CUSTOMER").claim("scope", CHECKPOINT).claim("rentalId", OWN_RENTAL).build(), KEY);
    }

    // 부스(Jetson 촬영 부스) 토큰.
    public static String booth() {
        // BOOTH 역할에 부스 ID(scope)를 넣어 서명한다
        return sign(base("booth-out", "BOOTH").claim("scope", BOOTH_ID).build(), KEY);
    }

    // 진단 에이전트 토큰: 페어링 코드로 받은 것, 시점 하나와 렌탈 하나에만 쓸 수 있다.
    public static String agent() {
        // AGENT 역할에 시점(scope)과 렌탈 번호를 넣어 서명한다
        return sign(base("agent-1", "AGENT").claim("scope", CHECKPOINT).claim("rentalId", OWN_RENTAL).build(), KEY);
    }

    // 1시간 전에 만료된 관리자 토큰.
    public static String expiredAdmin() {
        // 지금보다 1시간 전 시각을 만든다
        Instant past = Instant.now().minusSeconds(3600);
        // 발급은 1시간 15분 전, 만료는 1시간 전으로 적은 관리자 토큰을 만든다(이미 만료됨)
        return sign(base("admin-1", "ADMIN").issueTime(Date.from(past.minusSeconds(900)))
                .expirationTime(Date.from(past)).build(), KEY);
    }

    // 발급자가 trace가 아닌 토큰.
    public static String wrongIssuerAdmin() {
        // 발급자를 someone-else로 바꾼 관리자 토큰을 만든다
        return sign(base("admin-1", "ADMIN").issuer("someone-else").build(), KEY);
    }

    // 다른 열쇠로 서명한(위조) 토큰.
    public static String signedByOtherKey() {
        // gateway가 모르는 다른 열쇠로 서명한 관리자 토큰을 만든다
        return sign(base("admin-1", "ADMIN").build(), OTHER_KEY);
    }

    // 원하는 역할 이름을 넣은 토큰(없는 역할 시험용).
    public static String withRole(String role) {
        // 받은 역할 이름을 그대로 넣은 토큰을 만든다
        return sign(base("x-1", role).build(), KEY);
    }

    // 테스트 도구: 모든 토큰에 공통으로 들어가는 내용(클레임)을 채운다
    private static JWTClaimsSet.Builder base(String subject, String role) {
        // 지금 시각을 잡는다
        Instant now = Instant.now();
        // 클레임 만들기를 시작해
        return new JWTClaimsSet.Builder()
                // 발급자를 trace로 적는다(gateway가 이 값만 믿음)
                .issuer("trace")
                // 사용자 ID를 적는다
                .subject(subject)
                // 역할을 적는다
                .claim("role", role)
                // 발급 시각을 지금으로 적는다
                .issueTime(Date.from(now))
                // 만료를 15분 뒤로 적는다
                .expirationTime(Date.from(now.plusSeconds(900)))
                // 토큰마다 다른 고유 번호를 적는다
                .jwtID(UUID.randomUUID().toString());
    }

    // 테스트 도구: 클레임을 RS256으로 서명해 토큰 글자로 만든다
    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            // 열쇠 이름(kid)을 머리말에 넣고 RS256 방식 서명 전 토큰을 만든다
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            // 주어진 열쇠로 서명한다
            jwt.sign(new RSASSASigner(key));
            // 점(.)으로 이어진 토큰 글자로 바꿔 돌려준다
            return jwt.serialize();
        } catch (JOSEException e) {
            // 서명이 실패하면 테스트 준비 실패로 바로 멈춘다
            throw new IllegalStateException(e);
        }
    }
}
