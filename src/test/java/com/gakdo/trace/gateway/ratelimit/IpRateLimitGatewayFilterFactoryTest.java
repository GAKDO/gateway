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

    // Redis 대신 메모리에서 횟수를 세는 카운터
    private final InMemoryRateCounter counter = new InMemoryRateCounter();
    // 막을 때 표준 오류 응답(Problem JSON)을 쓰는 도구
    private final ProblemWriter writer = new ProblemWriter(new ObjectMapper());
    // 필터를 통과해 다음 단계로 간 요청 수를 센다
    private final AtomicInteger passed = new AtomicInteger();
    // 필터 다음 단계(뒤쪽 서버로 보내기)를 대신하는 가짜. 불리면 "통과"로 센다
    private final GatewayFilterChain chain = exchange -> {
        // 통과 수를 1 늘린다
        passed.incrementAndGet();
        // 아무 응답 없이 끝낸다(Mono.empty = 값 없이 끝나는 비동기 상자)
        return Mono.empty();
    };

    // 테스트 도구: "60초에 3번"짜리 로그인 요청 제한 필터를 만든다
    private GatewayFilter filter(RateCounter rateCounter) {
        // 카운터·오류 작성기·키 접두사(trace:)·고정 시계로 필터 공장을 만든다
        IpRateLimitGatewayFilterFactory factory = new IpRateLimitGatewayFilterFactory(rateCounter, writer, "trace:", CLOCK);
        // 필터 설정을 담을 상자를 만든다
        IpRateLimitGatewayFilterFactory.Config config = new IpRateLimitGatewayFilterFactory.Config();
        // 제한 이름을 login으로 한다(카운터 이름에 들어감)
        config.setName("login");
        // 한도를 3번으로 줄인다(테스트를 짧게)
        config.setLimit(3);
        // 창 크기를 60초로 한다
        config.setWindowSeconds(60);
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 설정 파일의 짧은 표기 순서가 이름·횟수·초인지도 함께 확인한다(application.yml과 맞추려고)
        assertThat(factory.shortcutFieldOrder()).containsExactly("name", "limit", "windowSeconds");
        // 설정대로 필터를 만들어 돌려준다
        return factory.apply(config);
    }

    // 테스트 도구: 주어진 IP에서 온 로그인 요청을 흉내 낸다
    private static MockServerWebExchange from(String ip) {
        // (도구) MockServerWebExchange = 서버를 띄우지 않고 요청·응답 한 쌍을 흉내 내는 테스트용 객체
        // 연결 주소를 그 IP로 한 POST 로그인 요청을 만든다
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(ip, 50000)));
    }

    // [상황] 같은 IP(10.0.0.1)에서 로그인을 4번 시도한다(한도 3번).
    // [기대] 3번만 통과하고 4번째는 429 RATE_LIMITED, "Retry-After: 45"(45초 뒤 다시)를 준다.
    //        ※ 응답 코드: 429 = 요청이 너무 잦아 잠시 막음
    @Test
    void 한도를_넘으면_429와_남은_창_길이를_Retry_After로_준다() {
        // 준비: 한도 3번짜리 필터를 만든다
        GatewayFilter filter = filter(counter);
        // 한도만큼 3번
        for (int i = 0; i < 3; i++) {
            // 10.0.0.1의 요청을 필터에 통과시키고 끝날 때까지 기다린다(block)
            filter.filter(from("10.0.0.1"), chain).block();
        }
        // 4번째 요청을 만든다
        MockServerWebExchange blocked = from("10.0.0.1");
        // 실행: 4번째 요청을 필터에 넣는다
        filter.filter(blocked, chain).block();
        // 확인: 3번만 통과했는지 확인한다
        assertThat(passed.get()).isEqualTo(3);
        // 4번째 응답이 429(너무 잦음)인지 확인한다
        assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // "45초 뒤 다시"가 붙었는지 확인한다(창 시작 뒤 15초라 남은 45초)
        assertThat(blocked.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("45");
        // 본문에 RATE_LIMITED 오류 코드가 있는지 확인한다
        assertThat(blocked.getResponse().getBodyAsString().block()).contains("\"code\":\"RATE_LIMITED\"");
    }

    // [상황] 뒤쪽 서버 연결 실패로 gateway가 같은 요청을 7번 다시 보낸다(재시도).
    // [기대] 사용자는 한 번 요청했으니 1번으로만 센다(재시도 때문에 사용자가 막히지 않게).
    @Test
    void 같은_요청이_다시_지나가면_한_번만_센다() {
        // 준비: 한도 3번짜리 필터를 만든다
        GatewayFilter filter = filter(counter);
        // 다시 보내질 요청 하나를 만든다
        MockServerWebExchange retried = from("10.0.0.1");
        // Retry 필터가 연결 실패로 같은 교환을 7번 흘려보내는 경우
        // 실행: 같은 요청을 7번
        for (int i = 0; i < 7; i++) {
            // 필터에 통과시킨다
            filter.filter(retried, chain).block();
        }
        // 확인: 7번 모두 통과했는지 확인한다
        assertThat(passed.get()).isEqualTo(7);
        // (도구) extracting(...) = 목록의 각 항목에서 값을 뽑아낸다. containsExactly(...)는 뽑은 값이 정확히 이것뿐인지 본다
        // 카운터에는 1번만 셌는지 확인한다
        assertThat(counter.counts().values()).extracting(java.util.concurrent.atomic.AtomicLong::get).containsExactly(1L);
        // 막는 응답 코드를 쓰지 않았는지(null) 확인한다
        assertThat(retried.getResponse().getStatusCode()).isNull();
    }

    // [상황] 10.0.0.1이 한도를 넘긴 뒤 10.0.0.2가 요청한다.
    // [기대] 10.0.0.2는 막히지 않는다. 카운터 이름에 IP와 1분 창 시작 시각이 들어간다.
    @Test
    void 다른_IP는_영향이_없다() {
        // 준비: 한도 3번짜리 필터를 만든다
        GatewayFilter filter = filter(counter);
        // 4번
        for (int i = 0; i < 4; i++) {
            // 10.0.0.1 요청을 넣어 한도를 넘긴다
            filter.filter(from("10.0.0.1"), chain).block();
        }
        // 다른 IP의 요청을 만든다
        MockServerWebExchange other = from("10.0.0.2");
        // 실행: 10.0.0.2 요청을 넣는다
        filter.filter(other, chain).block();
        // 확인: 10.0.0.2는 막히지 않았는지 확인한다
        assertThat(other.getResponse().getStatusCode()).isNull();
        // 통과 수가 10.0.0.1의 3번 + 10.0.0.2의 1번 = 4인지 확인한다
        assertThat(passed.get()).isEqualTo(4);
        // 카운터 이름이 IP와 1분 창 시작 시각(지금 - 15초)으로 따로 만들어졌는지 확인한다
        assertThat(counter.counts()).containsKeys(
                "trace:gw:rl:login:10.0.0.1:" + (CLOCK.instant().getEpochSecond() - 15),
                "trace:gw:rl:login:10.0.0.2:" + (CLOCK.instant().getEpochSecond() - 15));
    }

    // [상황] 같은 컴퓨터에서 X-Forwarded-For 헤더만 매번 다른 IP로 꾸며 4번 요청한다.
    // [기대] 꾸민 헤더는 무시하고 실제 연결 주소로 세서 4번째는 막힌다.
    @Test
    void X_Forwarded_For를_바꿔도_연결_주소로_센다() {
        // 준비: 한도 3번짜리 필터를 만든다
        GatewayFilter filter = filter(counter);
        // 실행: 4번 반복해
        for (int i = 0; i < 4; i++) {
            // X-Forwarded-For만 매번 다른 IP로 꾸미고 연결 주소는 같은 요청을 만든다
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                    .header("X-Forwarded-For", "192.0.2." + i)
                    .remoteAddress(new InetSocketAddress("10.0.0.9", 50000)));
            // 필터에 넣는다
            filter.filter(exchange, chain).block();
        }
        // 확인: 3번만 통과했는지 확인한다(꾸민 헤더는 무시)
        assertThat(passed.get()).isEqualTo(3);
    }

    // [상황] Cloudflare 같은 프록시를 거쳐 모든 연결 주소가 같고, 실제 사용자 IP는 CF-Connecting-IP에 있다.
    //        사용자 두 명이 각각 3번씩 요청한다.
    // [기대] 사용자별로 따로 세므로 6번 모두 통과한다(남 때문에 내가 막히지 않음).
    @Test
    void Cloudflare_뒤에서는_CF_Connecting_IP로_사용자를_구분한다() {
        // 준비: 사용자 IP를 CF-Connecting-IP 헤더에서 읽도록 한 필터 공장을 만든다
        IpRateLimitGatewayFilterFactory factory = new IpRateLimitGatewayFilterFactory(counter, writer, "trace:",
                "CF-Connecting-IP", CLOCK);
        // 설정 상자를 만든다
        IpRateLimitGatewayFilterFactory.Config config = new IpRateLimitGatewayFilterFactory.Config();
        // 제한 이름을 login으로 한다
        config.setName("login");
        // 한도를 3번으로 한다
        config.setLimit(3);
        // 설정대로 필터를 만든다
        GatewayFilter filter = factory.apply(config);
        // 연결 주소는 모두 같은 프록시 IP지만 사용자 IP가 다르면 서로 한도를 나눠 쓰지 않는다
        // 실행: 사용자 두 명이
        for (int user = 0; user < 2; user++) {
            // 각각 3번씩
            for (int i = 0; i < 3; i++) {
                // 같은 프록시 연결 주소, 서로 다른 CF-Connecting-IP로 요청을 만든다
                MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                        .header("CF-Connecting-IP", "203.0.113." + user)
                        .remoteAddress(new InetSocketAddress("172.68.0.1", 50000)));
                // 필터에 넣는다
                filter.filter(exchange, chain).block();
            }
        }
        // 확인: 6번 모두 통과했는지 확인한다(사용자별로 따로 셈)
        assertThat(passed.get()).isEqualTo(6);
    }

    // [상황] CF-Connecting-IP에 IP가 아닌 이상한 글자를 넣어 보낸다.
    // [기대] 그 값은 버리고 실제 연결 주소로 센다(카운터 이름을 오염시키지 못하게).
    @Test
    void 사용자_IP_헤더가_IP_모양이_아니면_연결_주소로_센다() {
        // 준비: CF-Connecting-IP에 이상한 글자를 넣은 요청을 만든다
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/x")
                .header("CF-Connecting-IP", "x:*:evil key")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 50000)));
        // 실행+확인: 사용자 IP를 읽으면 헤더 대신 연결 주소가 나오는지 확인한다
        assertThat(IpRateLimitGatewayFilterFactory.clientIp(exchange, "CF-Connecting-IP")).isEqualTo("10.0.0.9");
        // 헤더 이름을 주지 않아도 연결 주소가 나오는지 확인한다
        assertThat(IpRateLimitGatewayFilterFactory.clientIp(exchange, null)).isEqualTo("10.0.0.9");
    }

    // [상황] 횟수를 세는 Redis가 고장 났다.
    // [기대] 서비스를 멈추지 않고 제한 없이 통과시킨다(5번 모두 통과).
    @Test
    void Redis_장애면_제한_없이_통과한다() {
        // 준비: 셀 때마다 "redis down" 오류를 내는 고장 난 카운터를 만든다
        RateCounter broken = (key, window) -> Mono.error(new IllegalStateException("redis down"));
        // 고장 난 카운터로 필터를 만든다
        GatewayFilter filter = filter(broken);
        // 실행: 5번
        for (int i = 0; i < 5; i++) {
            // 같은 IP 요청을 넣는다
            filter.filter(from("10.0.0.1"), chain).block();
        }
        // 확인: 한도(3)와 상관없이 5번 모두 통과했는지 확인한다
        assertThat(passed.get()).isEqualTo(5);
    }

    // [상황] 연결 주소를 알 수 없는 요청.
    // [기대] "unknown"으로 묶어 센다.
    @Test
    void 연결_주소가_없으면_unknown으로_센다() {
        // 준비: 연결 주소가 없는 요청을 만든다
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/x"));
        // 실행+확인: 사용자 IP가 "unknown"으로 나오는지 확인한다
        assertThat(IpRateLimitGatewayFilterFactory.clientIp(exchange)).isEqualTo("unknown");
    }
}
