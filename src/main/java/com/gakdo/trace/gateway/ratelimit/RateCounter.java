package com.gakdo.trace.gateway.ratelimit;

import java.time.Duration;

import reactor.core.publisher.Mono;

/** 고정 창 카운터. 키를 1 늘리고 늘린 값을 준다. 창이 시작될 때 만료를 건다. */
public interface RateCounter {

    Mono<Long> increment(String key, Duration window);
}
