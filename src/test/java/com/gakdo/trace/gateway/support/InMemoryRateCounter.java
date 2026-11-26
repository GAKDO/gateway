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
// 진짜 카운터(RateCounter)와 같은 약속을 지켜서 gateway가 Redis 대신 이것을 써도 똑같이 동작하게 한다
public class InMemoryRateCounter implements RateCounter {

    // 키(예: IP)마다 횟수를 담는 표. 여러 요청이 동시에 와도 안전한 ConcurrentHashMap을 쓴다
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    // 진짜 카운터의 "횟수 1 늘리기"를 메모리 버전으로 바꿔 쓴다
    @Override
    public Mono<Long> increment(String key, Duration window) {
        // 키가 처음이면 0으로 만든 뒤 1 늘린 값을 돌려준다(Mono = 나중에 값 하나를 주는 비동기 상자). 창(window)은 테스트마다 reset으로 대신한다
        return Mono.fromSupplier(() -> counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet());
    }

    // 테스트 도구: 센 횟수를 모두 지운다
    public void reset() {
        // 표를 비워 다음 테스트가 0부터 세게 한다(앞 테스트의 횟수가 섞이지 않게)
        counts.clear();
    }

    // 테스트 도구: 지금까지 센 횟수 표를 보여 준다
    public Map<String, AtomicLong> counts() {
        // 테스트가 어떤 키로 몇 번 셌는지 직접 확인할 수 있게 표를 돌려준다
        return counts;
    }
}
