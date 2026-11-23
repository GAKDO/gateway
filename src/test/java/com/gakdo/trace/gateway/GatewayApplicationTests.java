// 이 파일이 확인하는 것: gateway(모든 요청이 처음 들어오는 문지기 서버)가 설정 오류 없이 켜지는지.

package com.gakdo.trace.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GatewayApplicationTests {

    // [상황] gateway 서버를 통째로 띄워 본다.
    // [기대] 라우트·보안·요청 제한 설정이 깨지지 않아 오류 없이 켜진다.
    @Test
    void contextLoads() {
        // 본문 없음: 스프링 컨텍스트가 뜨는 것 자체가 검증이다(빈 설정이 깨지면 여기서 실패)
    }
}
