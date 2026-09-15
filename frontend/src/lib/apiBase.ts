// Capacitor로 패키징된 앱은 capacitor://localhost 같은 커스텀 스킴 origin에서 돌아,
// 상대경로(`/v1/...`) fetch가 백엔드가 아니라 그 스킴 자신을 가리켜버린다(Task 030).
// 브라우저 개발 모드에서는 VITE_API_BASE_URL이 비어 있어 빈 문자열을 더한 원래 상대경로 그대로
// 나가므로, Vite dev 프록시(vite.config.ts)를 그대로 탄다 — 이 함수를 거쳐도 동작이 바뀌지 않는다.
export function apiUrl(path: string): string {
  const base = import.meta.env.VITE_API_BASE_URL ?? ''
  return base + path
}
