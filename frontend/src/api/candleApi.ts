// 캔들(candlestick) 차트 API 클라이언트(Task 028 프론트 7번째 하위 태스크 「프론트엔드 캔들스틱
// 차트 렌더링」). assetApi.ts의 authorizedRequest(401 시 refresh 후 1회 재시도)를 그대로
// 재사용한다 — 새 fetch 래퍼를 만들지 않는다.
import { authorizedRequest } from './assetApi';
import type { CandleSeriesResponse } from './types';

// interval 값(예: "day"/"minute1")과 before 커서(직전 응답의 가장 오래된 bar.bucketStart를 그대로
// 넘기면 된다)는 백엔드가 파싱하므로 여기서 형식을 검증·가공하지 않는다.
export function fetchCandles(
  assetId: string,
  interval: string,
  before?: string,
): Promise<CandleSeriesResponse> {
  const params = new URLSearchParams({ interval });
  if (before) params.set('before', before);
  return authorizedRequest<CandleSeriesResponse>(
    `/v1/assets/${assetId}/candles?${params.toString()}`,
  );
}
