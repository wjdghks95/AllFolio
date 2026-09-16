import { afterEach, describe, expect, it, vi } from 'vitest';
import { setToken, getDeviceId, setDeviceId } from '../auth/tokenStorage';

// vi.mock의 factory는 다른 import보다 앞으로 호이스팅되므로, factory 안에서 참조하는 값은
// vi.hoisted로 함께 끌어올려야 한다(CandlestickChart.test.tsx와 동일한 이유).
const mocks = vi.hoisted(() => {
  const isNativePlatformMock = vi.fn();
  const getPlatformMock = vi.fn();
  let registrationListener: ((token: { value: string }) => void) | undefined;
  const addListenerMock = vi.fn((eventName: string, listener: (arg: never) => void) => {
    if (eventName === 'registration') {
      registrationListener = listener as (token: { value: string }) => void;
    }
  });
  const requestPermissionsMock = vi.fn();
  const registerMock = vi.fn();
  return {
    isNativePlatformMock,
    getPlatformMock,
    addListenerMock,
    requestPermissionsMock,
    registerMock,
    fireRegistration: (token: string) => registrationListener?.({ value: token }),
  };
});

vi.mock('@capacitor/core', () => ({
  Capacitor: {
    isNativePlatform: mocks.isNativePlatformMock,
    getPlatform: mocks.getPlatformMock,
  },
}));

vi.mock('@capacitor/push-notifications', () => ({
  PushNotifications: {
    addListener: mocks.addListenerMock,
    requestPermissions: mocks.requestPermissionsMock,
    register: mocks.registerMock,
  },
}));

const { registerPushToken, unregisterPushToken } = await import('./pushRegistration');

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

afterEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

describe('registerPushToken', () => {
  it('웹(비네이티브) 환경에서는 아무 것도 하지 않고 조용히 스킵한다', async () => {
    mocks.isNativePlatformMock.mockReturnValue(false);
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(registerPushToken()).resolves.toBeUndefined();

    expect(mocks.requestPermissionsMock).not.toHaveBeenCalled();
    expect(mocks.registerMock).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('네이티브 환경에서 권한이 허용되면 register()를 호출하고, 발급된 토큰을 대문자 플랫폼과 함께 등록한다', async () => {
    setToken('access-token');
    mocks.isNativePlatformMock.mockReturnValue(true);
    mocks.getPlatformMock.mockReturnValue('android');
    mocks.requestPermissionsMock.mockResolvedValue({ receive: 'granted' });
    mocks.registerMock.mockResolvedValue(undefined);
    const fetchMock = vi.fn(async (_path: string, _options?: RequestInit) =>
      jsonResponse(201, { id: 'device-1', platform: 'ANDROID', createdAt: '2026-09-16T00:00:00Z' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await registerPushToken();
    mocks.fireRegistration('fcm-token-value');
    // 리스너 콜백 내부의 fetch는 여러 await를 거쳐 비동기로 실행되므로 완료될 때까지 기다린다.
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    expect(mocks.registerMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/v1/devices');
    expect(options.method).toBe('POST');
    expect(JSON.parse(options.body as string)).toEqual({
      token: 'fcm-token-value',
      platform: 'ANDROID',
    });
    expect((options.headers as Record<string, string>).Authorization).toBe('Bearer access-token');
  });

  it('등록 응답의 id를 로그아웃 시 해제에 쓸 수 있도록 저장한다', async () => {
    setToken('access-token');
    mocks.isNativePlatformMock.mockReturnValue(true);
    mocks.getPlatformMock.mockReturnValue('android');
    mocks.requestPermissionsMock.mockResolvedValue({ receive: 'granted' });
    mocks.registerMock.mockResolvedValue(undefined);
    const fetchMock = vi.fn(async () =>
      jsonResponse(201, { id: 'device-1', platform: 'ANDROID', createdAt: '2026-09-16T00:00:00Z' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await registerPushToken();
    mocks.fireRegistration('fcm-token-value');
    await vi.waitFor(() => expect(getDeviceId()).toBe('device-1'));
  });

  it('권한이 거부되면 register()를 호출하지 않는다', async () => {
    mocks.isNativePlatformMock.mockReturnValue(true);
    mocks.requestPermissionsMock.mockResolvedValue({ receive: 'denied' });

    await registerPushToken();

    expect(mocks.registerMock).not.toHaveBeenCalled();
  });
});

describe('unregisterPushToken', () => {
  it('저장된 device id가 있으면 DELETE /v1/devices/{id}를 호출하고 저장된 id를 지운다', async () => {
    setToken('access-token');
    setDeviceId('device-1');
    const fetchMock = vi.fn(async (_path: string, _options?: RequestInit) => jsonResponse(204, undefined));
    vi.stubGlobal('fetch', fetchMock);

    unregisterPushToken();

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/v1/devices/device-1');
    expect(options.method).toBe('DELETE');
    expect((options.headers as Record<string, string>).Authorization).toBe('Bearer access-token');
    expect(getDeviceId()).toBeNull();
  });

  it('저장된 device id가 없으면 DELETE를 호출하지 않는다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    unregisterPushToken();

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('DELETE 요청이 실패해도 예외를 던지지 않는다', () => {
    setToken('access-token');
    setDeviceId('device-1');
    const fetchMock = vi.fn(async () =>
      jsonResponse(404, { code: 'NOT_FOUND', message: '기기를 찾을 수 없습니다.', timestamp: '2026-09-16T00:00:00Z' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    expect(() => unregisterPushToken()).not.toThrow();
    expect(getDeviceId()).toBeNull();
  });
});
