package com.gakdo.trace.gateway.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

/**
 * Redis INCR + PEXPIRE를 Lua 스크립트 하나로 원자적으로 실행한다. 여러 gateway 파드가 같은 카운트를 본다.
 * 두 명령을 따로 보내면 그 사이에 파드가 죽었을 때 만료 없는 키가 남아 그 IP가 영영 막힐 수 있다.
 */
public class RedisRateCounter implements RateCounter {

    static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisRateCounter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Long> increment(String key, Duration window) {
        return redis.execute(INCREMENT_WITH_TTL, List.of(key), List.of(Long.toString(window.toMillis())))
                .next();
    }
}
