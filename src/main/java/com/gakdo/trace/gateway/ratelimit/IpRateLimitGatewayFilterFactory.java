package com.gakdo.trace.gateway.ratelimit;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.web.server.ServerWebExchange;

import com.gakdo.trace.gateway.common.ProblemWriter;

import reactor.core.publisher.Mono;

/**
 * IP당 고정 창 요청 제한(FR-AUTH-03: 로그인 시도·페어링 코드 입력).
 * RequestRateLimiter와 같은 자리에서 동작하지만, 429를 contracts Problem(RATE_LIMITED) + Retry-After로 답하고
 * Redis 키에 trace: 접두사를 붙이려고 직접 만든다. Redis가 죽으면 제한만 끄고 통과시킨다.
 *
 * <p>라우트 설정: {@code - name: IpRateLimit, args: {name: login, limit: 10, windowSeconds: 60}}
 */
public class IpRateLimitGatewayFilterFactory
        extends AbstractGatewayFilterFactory<IpRateLimitGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(IpRateLimitGatewayFilterFactory.class);

    private final RateCounter counter;
    private final ProblemWriter problemWriter;
    private final String keyPrefix;
    private final Clock clock;

    public IpRateLimitGatewayFilterFactory(RateCounter counter, ProblemWriter problemWriter, String keyPrefix,
                                           Clock clock) {
        super(Config.class);
        this.counter = counter;
        this.problemWriter = problemWriter;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.clock = clock;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("name", "limit", "windowSeconds");
    }

    @Override
    public GatewayFilter apply(Config config) {
        Duration window = Duration.ofSeconds(config.getWindowSeconds());
        return (exchange, chain) -> {
            long nowSeconds = clock.instant().getEpochSecond();
            long windowStart = nowSeconds - (nowSeconds % config.getWindowSeconds());
            String key = keyPrefix + "gw:rl:" + config.getName() + ":" + clientIp(exchange) + ":" + windowStart;
            return counter.increment(key, window)
                    .onErrorResume(e -> {
                        log.warn("요청 제한 카운터를 쓰지 못해 제한 없이 통과시킵니다: {}", e.toString());
                        return Mono.just(0L);
                    })
                    .flatMap(count -> {
                        if (count <= config.getLimit()) {
                            return chain.filter(exchange);
                        }
                        long retryAfter = Math.max(1, windowStart + config.getWindowSeconds() - nowSeconds);
                        exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retryAfter));
                        return problemWriter.write(exchange, 429, "RATE_LIMITED", "요청이 너무 많습니다");
                    });
        };
    }

    /**
     * 연결 주소의 IP. gateway 앞에 프록시가 없으므로(NodePort 직접) X-Forwarded-For는 믿지 않는다.
     * 믿으면 헤더만 바꿔 제한을 피할 수 있다.
     */
    static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) {
            return "unknown";
        }
        return remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
    }

    public static class Config {

        private String name = "default";
        private long limit = 10;
        private long windowSeconds = 60;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getLimit() {
            return limit;
        }

        public void setLimit(long limit) {
            this.limit = limit;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            if (windowSeconds <= 0) {
                throw new IllegalArgumentException("windowSeconds는 1 이상이어야 합니다");
            }
            this.windowSeconds = windowSeconds;
        }
    }
}
