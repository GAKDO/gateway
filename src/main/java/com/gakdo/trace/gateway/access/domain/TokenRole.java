package com.gakdo.trace.gateway.access.domain;

import java.util.Optional;

/** JWT role 클레임 값(contracts codes.yaml auth.token_role). */
public enum TokenRole {
    ADMIN,
    CUSTOMER,
    BOOTH,
    AGENT;

    /** 모르는 값이면 비어 있다. 모르는 역할은 어떤 경로에도 들어가지 못한다. */
    public static Optional<TokenRole> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (TokenRole role : values()) {
            if (role.name().equals(value)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
