package com.gakdo.trace.gateway.ratelimit;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

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
    /** IPv4·IPv6 글자만 허용해 헤더 값이 Redis 키를 망가뜨리지 못하게 한다. */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    /** 이 요청을 이미 센 뒤 통과시켰다는 표시(교환 속성 이름 앞부분). */
    private static final String COUNTED_ATTR = IpRateLimitGatewayFilterFactory.class.getName() + ".counted.";

    private final String keyPrefix;
    private final String clientIpHeader;
    private final Clock clock;

    public IpRateLimitGatewayFilterFactory(RateCounter counter, ProblemWriter problemWriter, String keyPrefix,
                                           Clock clock) {
        this(counter, problemWriter, keyPrefix, null, clock);
    }

    /**
     * @param clientIpHeader 앞단 프록시가 넣는 실제 사용자 IP 헤더(예: Cloudflare {@code CF-Connecting-IP}).
     *                       비우면 연결 주소만 쓴다.
     */
    public IpRateLimitGatewayFilterFactory(RateCounter counter, ProblemWriter problemWriter, String keyPrefix,
                                           String clientIpHeader, Clock clock) {
        super(Config.class);
        this.counter = counter;
        this.problemWriter = problemWriter;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.clientIpHeader = clientIpHeader == null || clientIpHeader.isBlank() ? null : clientIpHeader.trim();
        this.clock = clock;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("name", "limit", "windowSeconds");
    }

    @Override
    public GatewayFilter apply(Config config) {
        Duration window = Duration.ofSeconds(config.getWindowSeconds());
        // 기본 필터 Retry가 연결 실패로 이 필터를 다시 지나가게 해도 요청 한 건은 한 번만 센다
        String countedKey = COUNTED_ATTR + config.getName();
        return (exchange, chain) -> {
            if (exchange.getAttribute(countedKey) != null) {
                return chain.filter(exchange);
            }
            long nowSeconds = clock.instant().getEpochSecond();
            long windowStart = nowSeconds - (nowSeconds % config.getWindowSeconds());
            String key = keyPrefix + "gw:rl:" + config.getName() + ":" + clientIp(exchange, clientIpHeader) + ":" + windowStart;
            return counter.increment(key, window)
                    .onErrorResume(e -> {
                        log.warn("요청 제한 카운터를 쓰지 못해 제한 없이 통과시킵니다: {}", e.toString());
                        return Mono.just(0L);
                    })
                    .flatMap(count -> {
                        if (count <= config.getLimit()) {
                            exchange.getAttributes().put(countedKey, Boolean.TRUE);
                            return chain.filter(exchange);
                        }
                        long retryAfter = Math.max(1, windowStart + config.getWindowSeconds() - nowSeconds);
                        exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retryAfter));
                        return problemWriter.write(exchange, 429, "RATE_LIMITED", "요청이 너무 많습니다");
                    });
        };
    }

    /**
     * 사용자 IP. 학교 클러스터는 Cloudflare 프록시 뒤라 연결 주소가 Cloudflare(또는 NodePort SNAT 뒤 노드) IP가 되므로,
     * 설정한 헤더(CF-Connecting-IP)가 IP 모양이면 그 값을 쓴다. X-Forwarded-For는 클라이언트가 붙일 수 있어 믿지 않는다.
     * 한계: NodePort로 직접 붙는 요청은 이 헤더를 꾸밀 수 있다(시연 범위에서 받아들임, docs decisions.md).
     */
    static String clientIp(ServerWebExchange exchange, String clientIpHeader) {
        if (clientIpHeader != null) {
            String value = exchange.getRequest().getHeaders().getFirst(clientIpHeader);
            if (value != null && IP_LITERAL.matcher(value.trim()).matches()) {
                return value.trim();
            }
        }
        return clientIp(exchange);
    }

    /** 연결 주소의 IP. */
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
