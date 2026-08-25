package com.allfolio.infra.logging;

/**
 * MDC 키 상수 모음. 인스턴스화하지 않는다.
 *
 * <p>traceId는 MdcFilter가, userId는 JwtFilter가 채운다(서로 다른 패키지) — 리터럴 오타로
 * 두 필터가 서로 다른 키를 쓰게 되는 걸 막기 위해 공용 상수로 뺀다.
 */
public final class MdcKeys {

    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";

    private MdcKeys() {
    }
}
