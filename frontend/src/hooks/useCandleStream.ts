// SSE(Server-Sent Events)로 캔들 실시간 갱신을 받는다(F007, Task 028 프론트 8번째 하위 태스크
// 「프론트엔드 SSE 실시간 연동 (COIN)」). 엔드포인트는 GET /v1/assets/{id}/candles/stream이고,
// 이벤트 이름은 "candle"이다(백엔드 CandlePushScheduler 구현 그대로 — 계획 문서의
// "price-update"라는 이름은 실제 구현과 다르다). payload는 REST(GET /v1/assets/{id}/candles)와
// 동일한 CandleBarResponse 1건이다.
//
// 캔들 배열 자체의 상태는 이 훅이 아니라 호출부(AssetDetailPage)가 소유한다 — 이 훅은 SSE 연결의
// 생명주기만 관리하고, 새로 들어온 bar 1건을 onBar 콜백으로 넘길 뿐이다. 병합 방식(같은
// bucketStart면 교체, 다르면 추가)은 호출부의 책임이다.
//
// 인증은 Authorization 헤더가 아니라 쿼리 파라미터 token으로 받는다(브라우저 EventSource가
// 커스텀 헤더를 못 붙이기 때문 — infra/security/JwtFilter.java의 이 경로 전용 폴백과 대칭).
//
// heartbeat는 SSE 코멘트 줄(": heartbeat")로 오는데 EventSource는 코멘트를 이벤트로 노출하지
// 않는다 — 별도 리스너가 필요 없다(연결 유지 신호일 뿐).
//
// [알려진 한계] Access Token은 15분 만료(ROADMAP Task 003 남은 갭·Task 018)인데, EventSource는
// 연결 시점의 토큰을 URL에 한 번 박아 넣는다. 연결이 15분 넘게 유지되다 끊기면 브라우저 기본
// 재연결(EventSource 표준 동작)이 이미 만료된 토큰이 박힌 같은 URL로 재시도해 401을 반복해서
// 받을 수 있다. 아래 onerror 핸들러는 "다른 경로(REST 요청의 401 자동 재시도 등)로 토큰이 이미
// 갱신돼 있는 경우"에 한해 새 토큰으로 재연결을 시도하지만, 이 스트림 연결 자체가 능동적으로
// 토큰을 갱신시키지는 않는다 — 완전한 해결(예: 갱신 트리거·Last-Event-ID 기반 재개)은 이번
// 태스크 범위 밖이다.
//
// [Capacitor 관련] Capacitor의 CapacitorHttp가 기본 EventSource를 가로채는 공개 버그가 있다
// (ionic-team/capacitor#6582) — Task 030(하이브리드 앱 패키징)에서 재검토한다. 이번 태스크는
// 웹 SPA 기준 표준 EventSource만 다룬다.
import { useEffect, useRef } from 'react';
import { getToken } from '../auth/tokenStorage';
import type { CandleBarResponse } from '../api/types';

function streamUrl(assetId: string, interval: string, token: string): string {
  return `/v1/assets/${assetId}/candles/stream?interval=${interval}&token=${encodeURIComponent(token)}`;
}

export function useCandleStream(
  assetId: string,
  interval: string,
  enabled: boolean,
  onBar: (bar: CandleBarResponse) => void,
): void {
  // onBar는 호출부 렌더마다 새 함수일 수 있다 — effect 의존성에 넣으면 렌더마다 재연결되므로
  // ref로 최신 콜백만 따라가고, effect 자체는 연결을 새로 열 필요가 있는 값(assetId/interval/
  // enabled)에만 반응한다.
  const onBarRef = useRef(onBar);
  onBarRef.current = onBar;

  useEffect(() => {
    if (!enabled || !assetId) return;
    const token = getToken();
    if (!token) return; // 로그인 안 된 상태면 애초에 이 화면에 못 옴 — 방어적 가드

    let lastToken = token;
    let es = new EventSource(streamUrl(assetId, interval, token));

    const handleCandle = (e: MessageEvent) => {
      onBarRef.current(JSON.parse(e.data as string) as CandleBarResponse);
    };

    // 위 "알려진 한계" 주석 참고 — 여기서 새 토큰 발급을 능동적으로 트리거하지 않는다.
    const handleError = () => {
      const current = getToken();
      if (current && current !== lastToken) {
        lastToken = current;
        es.removeEventListener('candle', handleCandle);
        es.removeEventListener('error', handleError);
        es.close();
        es = new EventSource(streamUrl(assetId, interval, current));
        es.addEventListener('candle', handleCandle);
        es.addEventListener('error', handleError);
      }
    };

    es.addEventListener('candle', handleCandle);
    es.addEventListener('error', handleError);

    return () => {
      es.removeEventListener('candle', handleCandle);
      es.removeEventListener('error', handleError);
      es.close();
    };
  }, [assetId, interval, enabled]);
}
