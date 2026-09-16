package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * 존재하지 않는 기기와 남의 기기 모두 이 예외를 던진다(403이 아닌 404) — 소유권 여부가 새어
 * 나가지 않도록 하기 위함이다(AssetNotFoundException과 동일한 컨벤션, docs/ROADMAP.md Task 012·029).
 */
public class DeviceTokenNotFoundException extends RuntimeException {

    public DeviceTokenNotFoundException() {
        super("기기를 찾을 수 없습니다.");
    }
}
