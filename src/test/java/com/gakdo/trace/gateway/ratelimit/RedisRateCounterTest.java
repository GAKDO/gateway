// 이 파일이 확인하는 것: 요청 횟수를 Redis에 세는 방법(가짜 Redis로 확인).

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

    // [상황] IP 1.2.3.4의 횟수를 1 늘린다(창 1분).
    // [기대] "1 늘리기 + 1분 뒤 지우기"를 Redis 스크립트 한 번에 보낸다(둘 사이에 끊겨 영원히 막히는 일 방지).
    @Test
    void 증가와_만료_설정을_스크립트_한_번으로_보낸다() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(eq(RedisRateCounter.INCREMENT_WITH_TTL), any(List.class), any(List.class)))
                .thenReturn(Flux.just(1L));

        Long count = new RedisRateCounter(redis).increment("trace:rl:1.2.3.4", Duration.ofMinutes(1)).block();

        assertThat(count).isEqualTo(1L);
        verify(redis).execute(RedisRateCounter.INCREMENT_WITH_TTL, List.of("trace:rl:1.2.3.4"), List.of("60000"));
    }

    // [상황] Redis 스크립트 내용을 읽는다.
    // [기대] 처음 셀 때(1이 될 때)만 만료 시간을 건다. 그래야 1분 창이 요청마다 늘어나지 않는다.
    @Test
    void 스크립트는_첫_증가일_때만_만료를_건다() {
        String script = RedisRateCounter.INCREMENT_WITH_TTL.getScriptAsString();
        assertThat(script).contains("INCR").contains("if count == 1 then").contains("PEXPIRE");
    }
}
