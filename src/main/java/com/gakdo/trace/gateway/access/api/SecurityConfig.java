package com.gakdo.trace.gateway.access.api;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.gateway.access.domain.AccessPolicy;
import com.gakdo.trace.gateway.access.infra.AccessPolicyLoader;
import com.gakdo.trace.gateway.common.ProblemWriter;

/**
 * GW1 보안 체인: CORS(web 출처만) → JWT 검증(auth-server JWKS, iss) → 역할×경로 인가(access-policy.yaml).
 * 세션·폼 로그인·CSRF는 쓰지 않는다(토큰 기반 API 입구).
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(TraceSecurityProperties.class)
public class SecurityConfig {

    /** gateway 자신의 ping과 쿠버네티스 프로브. 계약(5개 서버) 밖이라 여기서 연다. */
    private static final String[] GATEWAY_OWN_PUBLIC = {"/api/v1/gateway/ping", "/actuator/health", "/actuator/health/**"};

    @Bean
    public ProblemWriter problemWriter(ObjectMapper objectMapper) {
        return new ProblemWriter(objectMapper);
    }

    @Bean
    public AccessPolicy accessPolicy(@Value("${trace.security.policy:classpath:access-policy.yaml}") Resource policy) {
        return new AccessPolicy(AccessPolicyLoader.load(policy));
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(TraceSecurityProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(properties.issuer());
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(TraceSecurityProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.webOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "Last-Event-ID"));
        cors.setExposedHeaders(List.of("Retry-After", "Location"));
        cors.setAllowCredentials(true); // 리프레시 쿠키(trace_refresh)
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, AccessPolicy policy,
                                                         ReactiveJwtDecoder jwtDecoder, ProblemWriter problemWriter,
                                                         CorsConfigurationSource corsConfigurationSource) {
        AccessProblemHandlers handlers = new AccessProblemHandlers(problemWriter);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource))
                .authorizeExchange(ex -> ex
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(GATEWAY_OWN_PUBLIC).permitAll()
                        .anyExchange().access(new PolicyAuthorizationManager(policy)))
                .oauth2ResourceServer(o -> o
                        .bearerTokenConverter(new PublicPathBearerTokenConverter(policy))
                        .jwt(j -> j.jwtDecoder(jwtDecoder))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .build();
    }
}
