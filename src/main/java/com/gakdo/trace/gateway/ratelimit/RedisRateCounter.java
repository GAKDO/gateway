package com.gakdo.trace.gateway.ratelimit;

import java.time.Duration;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import reactor.core.publisher.Mono;

/** Redis INCR + EXPIRE. 여러 gateway 파드가 같은 카운트를 본다. */
public class RedisRateCounter implements RateCounter {

    private final ReactiveStringRedisTemplate redis;

    public RedisRateCounter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Long> increment(String key, Duration window) {
        return redis.opsForValue().increment(key)
                .flatMap(count -> count == 1L
                        ? redis.expire(key, window).thenReturn(count)
                        : Mono.just(count));
    }
}
