package com.gakdo.trace.gateway.access.domain;

/** 인가 결과. 거부면 응답 상태와 contracts error_code가 정해진다. */
public enum AccessDecision {
    ALLOW(200, null, null),
    UNAUTHENTICATED(401, "UNAUTHENTICATED", "로그인이 필요합니다"),
    FORBIDDEN(403, "FORBIDDEN", "이 기능을 쓸 권한이 없습니다"),
    OUT_OF_SCOPE(403, "OUT_OF_SCOPE", "접근할 수 없는 렌탈입니다"),
    NOT_FOUND(404, "NOT_FOUND", "찾을 수 없습니다");

    private final int status;
    private final String code;
    private final String title;

    AccessDecision(int status, String code, String title) {
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public boolean allowed() {
        return this == ALLOW;
    }
}
