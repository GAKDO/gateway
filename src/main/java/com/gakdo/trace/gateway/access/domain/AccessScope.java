package com.gakdo.trace.gateway.access.domain;

/** contracts x-trace-access의 scope 값. gateway가 직접 확인하는 것은 경로에 {rentalId}가 있는 OWN_RENTAL뿐이다. */
public enum AccessScope {
    NONE,
    OWN_RENTAL,
    CHECKPOINT,
    BOOTH
}
