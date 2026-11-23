// 테스트 도구(테스트 자체는 아님): Redis 없이 요청 횟수를 메모리에서 세는 카운터.
// Docker가 없는 환경에서도 요청 제한 테스트를 돌리기 위해 쓴다.

package com.gakdo.trace.gateway.support;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.gakdo.trace.gateway.ratelimit.RateCounter;

import reactor.core.publisher.Mono;

/** Docker 없는 세션에서도 도는 테스트용 카운터(만료는 테스트마다 reset으로 대신한다). */
public class InMemoryRateCounter implements RateCounter {

    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    @Override
    public Mono<Long> increment(String key, Duration window) {
        return Mono.fromSupplier(() -> counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet());
    }

    public void reset() {
        counts.clear();
    }

    public Map<String, AtomicLong> counts() {
        return counts;
    }
}
