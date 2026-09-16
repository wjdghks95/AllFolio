// Capacitor 네이티브 앱에서 푸시 토큰을 발급받아 백엔드(POST /v1/devices, Task 029)에 등록한다.
// 알림 수신 리스너·알림 탭 라우팅은 이번 범위가 아니다(실제 알림이 없어 검증 불가능한 코드라 제외).
// apiUrl()이 Capacitor.isNativePlatform()으로 분기하는 패턴(frontend/src/lib/apiBase.ts)과 같은 이유로,
// 웹 개발 모드에서는 이 플러그인이 쓰일 일이 없어 조용히 스킵한다.
import { Capacitor } from '@capacitor/core';
import { PushNotifications, type Token } from '@capacitor/push-notifications';
import { authorizedRequest } from '../api/assetApi';
import type { DeviceResponse } from '../api/types';
import { getDeviceId, setDeviceId, removeDeviceId } from '../auth/tokenStorage';

async function sendDeviceToken(token: string): Promise<void> {
  try {
    const device = await authorizedRequest<DeviceResponse>('/v1/devices', {
      method: 'POST',
      // Capacitor.getPlatform()은 소문자('ios'/'android'/'web')를 반환하지만, 백엔드
      // DevicePlatform enum은 대문자 이름(ANDROID/IOS/WEB)만 역직렬화한다 — 반드시 변환할 것.
      body: JSON.stringify({ token, platform: Capacitor.getPlatform().toUpperCase() }),
    });
    // 로그아웃 시 DELETE /v1/devices/{id}로 해제할 수 있도록 id를 보관한다.
    setDeviceId(device.id);
  } catch (error) {
    console.warn('[push] 기기 토큰 등록 실패', error);
  }
}

// 로그아웃 시 AuthProvider가 호출한다. 등록된 기기 토큰이 없으면(웹이거나 등록 실패) 아무 것도 하지 않는다.
// DELETE 요청은 fire-and-forget — 실패해도 로그아웃 자체를 막지 않는다.
export function unregisterPushToken(): void {
  const deviceId = getDeviceId();
  if (!deviceId) return;

  void authorizedRequest(`/v1/devices/${deviceId}`, { method: 'DELETE' }).catch((error) => {
    console.warn('[push] 기기 토큰 해제 실패', error);
  });
  removeDeviceId();
}

export async function registerPushToken(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;

  PushNotifications.addListener('registration', (token: Token) => {
    void sendDeviceToken(token.value);
  });
  PushNotifications.addListener('registrationError', (error) => {
    console.warn('[push] 등록 실패', error);
  });

  const permission = await PushNotifications.requestPermissions();
  if (permission.receive !== 'granted') return;

  await PushNotifications.register();
}
