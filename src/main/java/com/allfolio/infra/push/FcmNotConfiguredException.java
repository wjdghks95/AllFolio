package com.allfolio.infra.push;

/**
 * {@code ALLFOLIO_FCM_CREDENTIALS_JSON}이 설정되지 않아 {@link FcmSender}가 발송을 수행할 수 없을 때
 * 던진다. 이 컴포넌트를 호출하는 서비스가 아직 없어(Task 029 범위 외) {@code GlobalExceptionHandler}
 * 매핑은 만들지 않는다 — 실제 호출자가 생기는 후속 태스크에서 처리한다.
 */
public class FcmNotConfiguredException extends RuntimeException {

    public FcmNotConfiguredException(String message) {
        super(message);
    }
}
