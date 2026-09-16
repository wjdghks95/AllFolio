package com.allfolio.infra.push;

import com.allfolio.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ALLFOLIO_FCM_CREDENTIALS_JSON} 미설정 상태에서도 앱 전체가 정상 부팅되는지 확인한다
 * (ALLFOLIO_STOCK_SERVICE_KEY와 동일한 "미설정 시 부팅 성공" 정책, .claude/agents/firebase-fcm.md).
 * {@link AbstractIntegrationTest}는 이 프로퍼티에 더미값을 주입하지 않으므로 기본값(빈 문자열)이
 * 그대로 바인딩된다.
 */
class FcmBootTest extends AbstractIntegrationTest {

    @Autowired
    private FcmSender fcmSender;

    @Test
    void contextLoadsWithoutFcmCredentials() {
        assertThat(fcmSender).isNotNull();
    }
}
