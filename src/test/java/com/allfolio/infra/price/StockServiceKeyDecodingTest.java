package com.allfolio.infra.price;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StockPriceClient.decodeIfAlreadyEncoded()의 서비스키 형식 판별 로직 전용 단위 테스트
 * (code-reviewer M4 지적 — 이 경로를 검증하는 테스트가 이전엔 0건이었다). Spring 컨텍스트가
 * 필요 없는 순수 로직이라 AbstractIntegrationTest를 상속하지 않는다.
 *
 * <p>공공데이터포털은 서비스키를 "인코딩 키"(이미 URL-인코딩됨)와 "디코딩 키"(원문, Base64라
 * 리터럴 "+"/"="를 포함할 수 있음) 두 형식으로 발급한다 — 어느 쪽이 왔는지 "%XX" 패턴 유무로
 * 판별해야, 인코딩 키의 이중 인코딩과 디코딩 키의 "+"→공백 오염을 동시에 피할 수 있다.
 */
class StockServiceKeyDecodingTest {

    @Test
    void decodesKeyThatContainsPercentEncoding() {
        // "인코딩 키" 형식 — "=="가 "%3D%3D"로 이미 인코딩돼 있다.
        assertThat(StockPriceClient.decodeIfAlreadyEncoded("abcd1234%3D%3D")).isEqualTo("abcd1234==");
    }

    @Test
    void leavesRawKeyWithLiteralPlusUntouched() {
        // "디코딩 키" 형식 — Base64 원문이라 리터럴 "+"를 포함할 수 있다. 무조건 URLDecoder를
        // 돌리면 "+"가 공백으로 바뀌어 키가 깨진다(회귀 대상 결함).
        assertThat(StockPriceClient.decodeIfAlreadyEncoded("abcd12+34==")).isEqualTo("abcd12+34==");
    }

    @Test
    void leavesPlainKeyWithoutSpecialCharactersUntouched() {
        assertThat(StockPriceClient.decodeIfAlreadyEncoded("test-service-key")).isEqualTo("test-service-key");
    }
}
