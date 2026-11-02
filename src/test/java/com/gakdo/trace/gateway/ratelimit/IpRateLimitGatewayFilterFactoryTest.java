package com.gakdo.trace.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.gateway.common.ProblemWriter;
import com.gakdo.trace.gateway.support.InMemoryRateCounter;

import reactor.core.publisher.Mono;

/** 요청 제한 필터 단위 테스트(IP 구분·Retry-After·Redis 장애). */
class IpRateLimitGatewayFilterFactoryTest {

    // 창 시작에서 15초 지난 시각 → Retry-After 45초
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L - (1_800_000_000L % 60) + 15),
            ZoneOffset.UTC);

    private final InMemoryRateCounter counter = new InMemoryRateCounter();
    private final ProblemWriter writer = new ProblemWriter(new ObjectMapper());
    private final AtomicInteger passed = new AtomicInteger();
    private final GatewayFilterChain chain = exchange -> {
        passed.incrementAndGet();
        return Mono.empty();
    };

    private GatewayFilter filter(RateCounter rateCounter) {
        IpRateLimitGatewayFilterFactory factory = new IpRateLimitGatewayFilterFactory(rateCounter, writer, "trace:", CLOCK);
        IpRateLimitGatewayFilterFactory.Config config = new IpRateLimitGatewayFilterFactory.Config();
        config.setName("login");
        config.setLimit(3);
        config.setWindowSeconds(60);
        assertThat(factory.shortcutFieldOrder()).containsExactly("name", "limit", "windowSeconds");
        return factory.apply(config);
    }

    private static MockServerWebExchange from(String ip) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(ip, 50000)));
    }

    @Test
    void 한도를_넘으면_429와_남은_창_길이를_Retry_After로_준다() {
        GatewayFilter filter = filter(counter);
        for (int i = 0; i < 3; i++) {
            filter.filter(from("10.0.0.1"), chain).block();
        }
        MockServerWebExchange blocked = from("10.0.0.1");
        filter.filter(blocked, chain).block();
        assertThat(passed.get()).isEqualTo(3);
        assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("45");
        assertThat(blocked.getResponse().getBodyAsString().block()).contains("\"code\":\"RATE_LIMITED\"");
    }

    @Test
    void 다른_IP는_영향이_없다() {
        GatewayFilter filter = filter(counter);
        for (int i = 0; i < 4; i++) {
            filter.filter(from("10.0.0.1"), chain).block();
        }
        MockServerWebExchange other = from("10.0.0.2");
        filter.filter(other, chain).block();
        assertThat(other.getResponse().getStatusCode()).isNull();
        assertThat(passed.get()).isEqualTo(4);
        assertThat(counter.counts()).containsKeys(
                "trace:gw:rl:login:10.0.0.1:" + (CLOCK.instant().getEpochSecond() - 15),
                "trace:gw:rl:login:10.0.0.2:" + (CLOCK.instant().getEpochSecond() - 15));
    }

    @Test
    void X_Forwarded_For를_바꿔도_연결_주소로_센다() {
        GatewayFilter filter = filter(counter);
        for (int i = 0; i < 4; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                    .header("X-Forwarded-For", "192.0.2." + i)
                    .remoteAddress(new InetSocketAddress("10.0.0.9", 50000)));
            filter.filter(exchange, chain).block();
        }
        assertThat(passed.get()).isEqualTo(3);
    }

    @Test
    void Cloudflare_뒤에서는_CF_Connecting_IP로_사용자를_구분한다() {
        IpRateLimitGatewayFilterFactory factory = new IpRateLimitGatewayFilterFactory(counter, writer, "trace:",
                "CF-Connecting-IP", CLOCK);
        IpRateLimitGatewayFilterFactory.Config config = new IpRateLimitGatewayFilterFactory.Config();
        config.setName("login");
        config.setLimit(3);
        GatewayFilter filter = factory.apply(config);
        // 연결 주소는 모두 같은 프록시 IP지만 사용자 IP가 다르면 서로 한도를 나눠 쓰지 않는다
        for (int user = 0; user < 2; user++) {
            for (int i = 0; i < 3; i++) {
                MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                        .header("CF-Connecting-IP", "203.0.113." + user)
                        .remoteAddress(new InetSocketAddress("172.68.0.1", 50000)));
                filter.filter(exchange, chain).block();
            }
        }
        assertThat(passed.get()).isEqualTo(6);
    }

    @Test
    void 사용자_IP_헤더가_IP_모양이_아니면_연결_주소로_센다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/x")
                .header("CF-Connecting-IP", "x:*:evil key")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 50000)));
        assertThat(IpRateLimitGatewayFilterFactory.clientIp(exchange, "CF-Connecting-IP")).isEqualTo("10.0.0.9");
        assertThat(IpRateLimitGatewayFilterFactory.clientIp(exchange, null)).isEqualTo("10.0.0.9");
    }

    @Test
    void Redis_장애면_제한_없이_통과한다() {
        RateCounter broken = (key, window) -> Mono.error(new IllegalStateException("redis down"));
        GatewayFilter filter = filter(broken);
        for (int i = 0; i < 5; i++) {
            filter.filter(from("10.0.0.1"), chain).block();
        }
        assertThat(passed.get()).isEqualTo(5);
    }

    @Test
    void 연결_주소가_없으면_unknown으로_센다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/x"));
        assertThat(IpRateLimitGatewayFilterFactory.clientIp(exchange)).isEqualTo("unknown");
    }
}
