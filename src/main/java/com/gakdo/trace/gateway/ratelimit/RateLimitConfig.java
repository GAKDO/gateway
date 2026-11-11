package com.gakdo.trace.gateway.ratelimit;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import com.gakdo.trace.gateway.common.ProblemWriter;

@Configuration
public class RateLimitConfig {

    @Bean
    public RateCounter rateCounter(ReactiveStringRedisTemplate redis) {
        return new RedisRateCounter(redis);
    }

    @Bean
    public IpRateLimitGatewayFilterFactory ipRateLimitGatewayFilterFactory(
            RateCounter rateCounter, ProblemWriter problemWriter,
            @Value("${trace.redis.key-prefix:trace:}") String keyPrefix,
            @Value("${trace.rate-limit.client-ip-header:}") String clientIpHeader,
            ObjectProvider<Clock> clock) {
        // 시계 빈이 있으면 그것(테스트의 고정 시계), 없으면 UTC 시스템 시계
        return new IpRateLimitGatewayFilterFactory(rateCounter, problemWriter, keyPrefix, clientIpHeader,
                clock.getIfAvailable(Clock::systemUTC));
    }
}
