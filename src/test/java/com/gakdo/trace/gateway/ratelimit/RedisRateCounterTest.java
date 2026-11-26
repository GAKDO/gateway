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

/**
 * 요청 횟수를 Redis에 세는 RedisRateCounter 시험. 진짜 Redis 대신 가짜(Mockito mock)를 넣고,
 * Redis에 어떤 명령을 보내는지만 확인한다.
 */
class RedisRateCounterTest {

    // [상황] IP 1.2.3.4의 횟수를 1 늘린다(창 1분).
    // [기대] "1 늘리기 + 1분 뒤 지우기"를 Redis 스크립트 한 번에 보낸다(둘 사이에 끊겨 영원히 막히는 일 방지).
    @Test
    void 증가와_만료_설정을_스크립트_한_번으로_보낸다() {
        // (도구) mock(클래스) = 진짜 일은 안 하고 정해 준 대로만 답하며, 불린 기록을 남기는 가짜(대역) 객체를 만든다
        // 준비: 진짜 Redis 대신 가짜 Redis 도구를 만든다(Redis 없이 보내는 명령만 보려고)
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        // (도구) when(...).thenReturn(값) = 그 메서드가 불리면 이 값을 돌려주라고 가짜에게 정해 둔다. eq는 "정확히 이 값", any는 "아무 값"
        // 그 스크립트가 실행되면 "횟수 1"을 돌려주게 한다
        when(redis.execute(eq(RedisRateCounter.INCREMENT_WITH_TTL), any(List.class), any(List.class)))
                .thenReturn(Flux.just(1L));

        // 실행: 가짜 Redis를 넣은 카운터로 IP 1.2.3.4의 횟수를 1분 창으로 1 늘리고 결과를 기다려 받는다(block)
        Long count = new RedisRateCounter(redis).increment("trace:rl:1.2.3.4", Duration.ofMinutes(1)).block();

        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 확인: 돌려받은 횟수가 1인지 확인한다
        assertThat(count).isEqualTo(1L);
        // 두 번째 목록 = 세는 이름(키), 세 번째 목록 = 만료 시간. "60000"은 1분(창 크기)을 밀리초로 바꾼 값이다.
        // (도구) verify(가짜).메서드(...) = 가짜의 그 메서드가 이 인자로 불렸는지 확인한다
        // 스크립트 하나에 키와 만료 시간(60000밀리초)을 함께 보냈는지 확인한다
        verify(redis).execute(RedisRateCounter.INCREMENT_WITH_TTL, List.of("trace:rl:1.2.3.4"), List.of("60000"));
    }

    // [상황] Redis 스크립트 내용을 읽는다.
    // [기대] 처음 셀 때(1이 될 때)만 만료 시간을 건다. 그래야 1분 창이 요청마다 늘어나지 않는다.
    @Test
    void 스크립트는_첫_증가일_때만_만료를_건다() {
        // 준비: 카운터가 쓰는 Redis 스크립트 글자를 읽는다
        String script = RedisRateCounter.INCREMENT_WITH_TTL.getScriptAsString();
        // 확인: 1 늘리기(INCR), "처음 셀 때만" 조건, 만료 걸기(PEXPIRE)가 모두 들어 있는지 확인한다
        assertThat(script).contains("INCR").contains("if count == 1 then").contains("PEXPIRE");
    }
}
