package com.gakdo.trace.gateway.common;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import io.netty.channel.ConnectTimeoutException;
import reactor.core.publisher.Mono;

/**
 * 라우팅 중에 난 오류(뒤쪽 서버에 끝내 연결하지 못함, 응답 시간 초과 등)도 contracts Problem(RFC 9457 + code)으로 쓴다.
 * 스프링 기본 처리기(@Order -1)는 {@code {timestamp, path, status, error}} 형식이라 계약의 "모든 오류는 problem+json + code"와 어긋난다.
 *
 * <ul>
 *   <li>연결 실패(재시도 6번 뒤): 503 INTERNAL_ERROR (chat-server의 503과 같은 code)</li>
 *   <li>응답 시간 초과: 504 INTERNAL_ERROR</li>
 *   <li>그 밖의 ResponseStatusException: 그 상태 그대로, 4xx는 상태에 맞는 code</li>
 *   <li>나머지: 500 INTERNAL_ERROR</li>
 * </ul>
 * 401·403·404(정책)는 보안 체인의 AccessProblemHandlers가, 429는 IpRateLimit가 따로 쓴다.
 */
@Order(-2)
public class UpstreamProblemHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UpstreamProblemHandler.class);

    private final ProblemWriter writer;

    public UpstreamProblemHandler(ProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        int status = statusOf(ex);
        String path = exchange.getRequest().getPath().value();
        if (status >= 500) {
            log.warn("라우팅 오류 {} {} → {}: {}", exchange.getRequest().getMethod(), path, status, ex.toString());
        }
        return writer.write(exchange, status, codeOf(status), titleOf(status));
    }

    static int statusOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof NoRouteToHostException
                    || t instanceof UnknownHostException || t instanceof ConnectTimeoutException) {
                return 503;
            }
            if (t instanceof TimeoutException) {
                return 504;
            }
            if (t instanceof ResponseStatusException rse) {
                return rse.getStatusCode().value();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return 500;
    }

    static String codeOf(int status) {
        return switch (status) {
            case 400, 405, 413, 415 -> "VALIDATION_FAILED";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 429 -> "RATE_LIMITED";
            default -> "INTERNAL_ERROR";
        };
    }

    static String titleOf(int status) {
        return switch (status) {
            case 503 -> "서버에 잠시 연결할 수 없습니다";
            case 504 -> "서버 응답이 늦습니다";
            case 404 -> "찾을 수 없습니다";
            default -> status >= 500 ? "서버 오류가 발생했습니다" : "요청을 처리할 수 없습니다";
        };
    }
}
