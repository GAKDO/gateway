// 이 파일이 확인하는 것: IP별 요청 제한(같은 곳에서 로그인 등을 너무 많이 시도하면 잠시 막기).
// 여기서는 "60초에 3번"으로 줄여 시험한다. 실제 Redis 대신 메모리 카운터를 쓴다.

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
    // 지금 시각을 1분 창이 시작되고 15초 지난 때로 고정한다 → 막히면 "45초 뒤 다시"가 나와야 한다.
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

    // [상황] 같은 IP(10.0.0.1)에서 로그인을 4번 시도한다(한도 3번).
    // [기대] 3번만 통과하고 4번째는 429 RATE_LIMITED, "Retry-After: 45"(45초 뒤 다시)를 준다.
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

    // [상황] 뒤쪽 서버 연결 실패로 gateway가 같은 요청을 7번 다시 보낸다(재시도).
    // [기대] 사용자는 한 번 요청했으니 1번으로만 센다(재시도 때문에 사용자가 막히지 않게).
    @Test
    void 같은_요청이_다시_지나가면_한_번만_센다() {
        GatewayFilter filter = filter(counter);
        MockServerWebExchange retried = from("10.0.0.1");
        // Retry 필터가 연결 실패로 같은 교환을 7번 흘려보내는 경우
        for (int i = 0; i < 7; i++) {
            filter.filter(retried, chain).block();
        }
        assertThat(passed.get()).isEqualTo(7);
        assertThat(counter.counts().values()).extracting(java.util.concurrent.atomic.AtomicLong::get).containsExactly(1L);
        assertThat(retried.getResponse().getStatusCode()).isNull();
    }

    // [상황] 10.0.0.1이 한도를 넘긴 뒤 10.0.0.2가 요청한다.
    // [기대] 10.0.0.2는 막히지 않는다. 카운터 이름에 IP와 1분 창 시작 시각이 들어간다.
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

    // [상황] 같은 컴퓨터에서 X-Forwarded-For 헤더만 매번 다른 IP로 꾸며 4번 요청한다.
    // [기대] 꾸민 헤더는 무시하고 실제 연결 주소로 세서 4번째는 막힌다.
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

    // [상황] Cloudflare 같은 프록시를 거쳐 모든 연결 주소가 같고, 실제 사용자 IP는 CF-Connecting-IP에 있다.
    //        사용자 두 명이 각각 3번씩 요청한다.
    // [기대] 사용자별로 따로 세므로 6번 모두 통과한다(남 때문에 내가 막히지 않음).
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

    // [상황] CF-Connecting-IP에 IP가 아닌 이상한 글자를 넣어 보낸다.
    // [기대] 그 값은 버리고 실제 연결 주소로 센다(카운터 이름을 오염시키지 못하게).
    @Test
    void 사용자_IP_헤더가_IP_모양이_아니면_연결_주소로_센다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/x")
                .header("CF-Connecting-IP", "x:*:evil key")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 50000)));
        assertThat(IpRateLimitGatewayFilterFactory.clientIp(exchange, "CF-Connecting-IP")).isEqualTo("10.0.0.9");
        assertThat(IpRateLimitGatewayFilterFactory.clientIp(exchange, null)).isEqualTo("10.0.0.9");
    }

    // [상황] 횟수를 세는 Redis가 고장 났다.
    // [기대] 서비스를 멈추지 않고 제한 없이 통과시킨다(5번 모두 통과).
    @Test
    void Redis_장애면_제한_없이_통과한다() {
        RateCounter broken = (key, window) -> Mono.error(new IllegalStateException("redis down"));
        GatewayFilter filter = filter(broken);
        for (int i = 0; i < 5; i++) {
            filter.filter(from("10.0.0.1"), chain).block();
        }
        assertThat(passed.get()).isEqualTo(5);
    }

    // [상황] 연결 주소를 알 수 없는 요청.
    // [기대] "unknown"으로 묶어 센다.
    @Test
    void 연결_주소가_없으면_unknown으로_센다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/x"));
        assertThat(IpRateLimitGatewayFilterFactory.clientIp(exchange)).isEqualTo("unknown");
    }
}
