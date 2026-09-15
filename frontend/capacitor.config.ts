import type { CapacitorConfig } from '@capacitor/cli';

// CapacitorHttp는 기본 fetch/EventSource를 가로채는 공개 버그가 있다
// (ionic-team/capacitor#6582) — 이 앱은 표준 fetch/EventSource만 쓰므로 필요 없어 끈다.
// (참고: src/hooks/useCandleStream.ts)
const config: CapacitorConfig = {
  appId: 'com.allfolio.app',
  appName: 'AllFolio',
  webDir: 'dist',
  plugins: {
    CapacitorHttp: {
      enabled: false,
    },
  },
};

export default config;
