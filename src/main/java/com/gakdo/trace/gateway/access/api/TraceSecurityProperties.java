package com.gakdo.trace.gateway.access.api;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GW1 보안 설정. 값은 환경 변수로 받는다(application.yml).
 *
 * @param jwkSetUri  auth-server 공개키 주소(클러스터 안)
 * @param issuer     JWT iss 값
 * @param webOrigins CORS로 허용할 web 출처
 */
@ConfigurationProperties(prefix = "trace.security")
public record TraceSecurityProperties(String jwkSetUri, String issuer, List<String> webOrigins) {

    public TraceSecurityProperties {
        issuer = issuer == null || issuer.isBlank() ? "trace" : issuer;
        webOrigins = webOrigins == null ? List.of() : webOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
