package com.gakdo.trace.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import reactor.core.publisher.Flux;

class RedisRateCounterTest {

    @Test
    void 증가와_만료_설정을_스크립트_한_번으로_보낸다() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(eq(RedisRateCounter.INCREMENT_WITH_TTL), any(List.class), any(List.class)))
                .thenReturn(Flux.just(1L));

        Long count = new RedisRateCounter(redis).increment("trace:rl:1.2.3.4", Duration.ofMinutes(1)).block();

        assertThat(count).isEqualTo(1L);
        verify(redis).execute(RedisRateCounter.INCREMENT_WITH_TTL, List.of("trace:rl:1.2.3.4"), List.of("60000"));
    }

    @Test
    void 스크립트는_첫_증가일_때만_만료를_건다() {
        String script = RedisRateCounter.INCREMENT_WITH_TTL.getScriptAsString();
        assertThat(script).contains("INCR").contains("if count == 1 then").contains("PEXPIRE");
    }
}
