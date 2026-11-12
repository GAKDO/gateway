package com.gakdo.trace.gateway.upstream;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 뒤쪽 서버로 나가는 연결의 이름 찾기 설정.
 * Compose에서 서버를 무중단으로 바꾸면(새 컨테이너를 먼저 띄우고 옛 것을 내림) 같은 이름의 IP가 바뀐다.
 * 기본값은 DNS 응답의 TTL(Docker는 600초)만큼 옛 IP를 기억하므로, 짧게 기억하고 여러 IP를 번갈아 쓰게 한다.
 * 옛 컨테이너가 연결을 거절하는 짧은 순간은 application.yml의 Retry(연결 실패만)가 다른 IP로 다시 보낸다.
 */
@Configuration
public class UpstreamClientConfig {

    @Bean
    public HttpClientCustomizer upstreamDnsCustomizer(
            @Value("${trace.upstream.dns-ttl:5s}") Duration dnsTtl) {
        return httpClient -> httpClient.resolver(spec -> spec
                .cacheMaxTimeToLive(dnsTtl)
                .cacheNegativeTimeToLive(Duration.ZERO)
                .roundRobinSelection(true));
    }
}
