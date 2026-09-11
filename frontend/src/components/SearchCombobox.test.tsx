import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import SearchCombobox from './SearchCombobox';
import type { SearchResult } from '../api/types';
import { messageForErrorCode } from '../lib/messages';

const samsungKrw: SearchResult = {
  ticker: '005930',
  name: '삼성전자',
  assetType: 'STOCK',
  currency: 'KRW',
};

const appleUsd: SearchResult = {
  ticker: 'AAPL',
  name: 'Apple Inc.',
  assetType: 'STOCK',
  currency: 'USD',
};

// 검색 입력 → 디바운스(350ms) 대기까지 한 번에 처리한다. 대기 구간만 가짜 타이머를 쓰고
// 끝나면 바로 되돌린다 — 이 테스트 파일은 waitFor를 쓰지 않아 걱정 없지만 습관을 통일해둔다.
async function typeAndWait(input: HTMLElement, value: string, ms = 400) {
  vi.useFakeTimers();
  fireEvent.change(input, { target: { value } });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
  vi.useRealTimers();
}

function renderCombobox(onSelect = vi.fn(), onUnauthorized = vi.fn()) {
  render(
    <SearchCombobox
      label="종목 검색"
      assetType="STOCK"
      onSelect={onSelect}
      onUnauthorized={onUnauthorized}
      testId="symbol-search"
    />,
  );
  return { onSelect, onUnauthorized };
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('SearchCombobox', () => {
  it('빠르게 재입력하면 마지막 입력값만 검색하고 이전 입력은 폐기한다', async () => {
    const fetchMock = vi.fn((path: unknown) => {
      const url = String(path);
      const results = url.includes('currency=USD') ? [appleUsd] : [];
      return Promise.resolve({ ok: true, json: async () => results });
    });
    vi.stubGlobal('fetch', fetchMock);
    renderCombobox();

    const input = screen.getByTestId('symbol-search');
    vi.useFakeTimers();
    fireEvent.change(input, { target: { value: 'AA' } });
    // 디바운스(350ms) 이내에 재입력하면 이전 타이머는 취소된다.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200);
    });
    fireEvent.change(input, { target: { value: 'AAPL' } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400);
    });
    vi.useRealTimers();

    // 'AA' 요청은 발사되지 않고 'AAPL'(KRW+USD 2건)만 나가야 한다.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const urls = fetchMock.mock.calls.map(([p]) => String(p));
    expect(urls.every((u) => u.includes('q=AAPL'))).toBe(true);
  });

  it('두 글자 미만은 검색을 생략하고 목록을 닫는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal('fetch', fetchMock);
    renderCombobox();

    const input = screen.getByTestId('symbol-search');
    await typeAndWait(input, 'A');

    expect(fetchMock).not.toHaveBeenCalled();
    expect((screen.getByTestId('symbol-search-listbox') as HTMLUListElement).hidden).toBe(true);
    // 목록이 hidden으로만 숨겨져 있어도(회귀 지점) aria-activedescendant가 그 안의 옵션 id를
    // 계속 가리키면 안 된다 — 닫힌 상태에서는 활성 옵션 자체가 없어야 한다.
    expect(input.getAttribute('aria-activedescendant')).toBeNull();
  });

  it('KRW/USD 검색 결과를 합쳐서 보여준다', async () => {
    const fetchMock = vi.fn((path: unknown) => {
      const url = String(path);
      if (url.includes('currency=KRW')) {
        return Promise.resolve({ ok: true, json: async () => [samsungKrw] });
      }
      return Promise.resolve({ ok: true, json: async () => [appleUsd] });
    });
    vi.stubGlobal('fetch', fetchMock);
    renderCombobox();

    await typeAndWait(screen.getByTestId('symbol-search'), '검색어');

    expect(screen.getByTestId('symbol-search-listbox')).toBeTruthy();
    expect(screen.getByTestId('symbol-search-option-0').textContent).toContain('삼성전자');
    expect(screen.getByTestId('symbol-search-option-1').textContent).toContain('Apple Inc.');
  });

  it('결과가 없으면 빈 결과 안내를 보여준다', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal('fetch', fetchMock);
    renderCombobox();

    await typeAndWait(screen.getByTestId('symbol-search'), '없는종목');

    expect(screen.getByTestId('symbol-search-listbox').textContent).toContain(
      '검색 결과가 없습니다',
    );
  });

  it('한쪽 통화 호출만 실패하면 성공한 통화의 결과만 조용히 보여준다', async () => {
    const fetchMock = vi.fn((path: unknown) => {
      const url = String(path);
      if (url.includes('currency=KRW')) {
        return Promise.reject(new Error('network down'));
      }
      return Promise.resolve({ ok: true, json: async () => [appleUsd] });
    });
    vi.stubGlobal('fetch', fetchMock);
    renderCombobox();

    await typeAndWait(screen.getByTestId('symbol-search'), '검색어');

    const listbox = screen.getByTestId('symbol-search-listbox');
    expect(listbox.textContent).toContain('Apple Inc.');
    expect(listbox.textContent).not.toContain('검색에 실패했습니다');
  });

  it('양쪽 통화 호출이 모두 실패하면 실패 안내를 보여준다', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('network down'));
    vi.stubGlobal('fetch', fetchMock);
    renderCombobox();

    await typeAndWait(screen.getByTestId('symbol-search'), '검색어');

    // fetch 자체가 실패하면 authApi.ts가 ApiError('NETWORK_ERROR')로 감싸 던진다 — 이는
    // messageForErrorCode가 모르는 코드라 그 폴백 문구가 뜬다(고정 문구가 아니다).
    expect(screen.getByTestId('symbol-search-listbox').textContent).toContain(
      messageForErrorCode('NETWORK_ERROR'),
    );
  });

  it('UNAUTHORIZED 응답을 받으면 다른 쪽이 성공했어도 onUnauthorized를 호출한다', async () => {
    const fetchMock = vi.fn((path: unknown) => {
      const url = String(path);
      if (url.includes('currency=KRW')) {
        return Promise.resolve({
          ok: false,
          json: async () => ({
            code: 'UNAUTHORIZED',
            message: '로그인이 필요합니다.',
            timestamp: '2026-09-10T00:00:00Z',
          }),
        });
      }
      return Promise.resolve({ ok: true, json: async () => [appleUsd] });
    });
    vi.stubGlobal('fetch', fetchMock);
    const { onUnauthorized } = renderCombobox();

    await typeAndWait(screen.getByTestId('symbol-search'), '검색어');

    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('↓/↑로 활성 옵션을 이동하고 Enter로 선택을 확정한다', async () => {
    const fetchMock = vi.fn((path: unknown) => {
      const url = String(path);
      if (url.includes('currency=KRW')) {
        return Promise.resolve({ ok: true, json: async () => [samsungKrw] });
      }
      return Promise.resolve({ ok: true, json: async () => [appleUsd] });
    });
    vi.stubGlobal('fetch', fetchMock);
    const { onSelect } = renderCombobox();

    const input = screen.getByTestId('symbol-search');
    await typeAndWait(input, '검색어');

    // results = [samsungKrw(0), appleUsd(1)] — ↓↓로 1까지 갔다가 ↑로 0으로 돌아온 뒤 Enter.
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'ArrowUp' });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith(samsungKrw);
    expect((screen.getByTestId('symbol-search-listbox') as HTMLUListElement).hidden).toBe(true);
  });

  it('옵션을 클릭하면 onSelect가 호출되고 목록이 닫힌다', async () => {
    const fetchMock = vi.fn((path: unknown) => {
      const url = String(path);
      const results = url.includes('currency=USD') ? [appleUsd] : [];
      return Promise.resolve({ ok: true, json: async () => results });
    });
    vi.stubGlobal('fetch', fetchMock);
    const { onSelect } = renderCombobox();

    await typeAndWait(screen.getByTestId('symbol-search'), '검색어');
    fireEvent.click(screen.getByTestId('symbol-search-option-0'));

    expect(onSelect).toHaveBeenCalledWith(appleUsd);
    expect((screen.getByTestId('symbol-search-listbox') as HTMLUListElement).hidden).toBe(true);
  });

  it('선택 후 검색어를 지우면 onSelect(null)이 호출된다', async () => {
    const fetchMock = vi.fn((path: unknown) => {
      const url = String(path);
      const results = url.includes('currency=USD') ? [appleUsd] : [];
      return Promise.resolve({ ok: true, json: async () => results });
    });
    vi.stubGlobal('fetch', fetchMock);
    const { onSelect } = renderCombobox();

    const input = screen.getByTestId('symbol-search');
    await typeAndWait(input, '검색어');
    fireEvent.click(screen.getByTestId('symbol-search-option-0'));
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith(appleUsd);

    fireEvent.change(input, { target: { value: '' } });

    expect(onSelect).toHaveBeenCalledTimes(2);
    expect(onSelect).toHaveBeenLastCalledWith(null);
  });

  it('응답이 검색어 역순으로 도착해도 최신 검색어 결과만 반영한다', async () => {
    // 각 (검색어, 통화) 조합마다 resolve 시점을 직접 제어할 수 있는 deferred 응답을 둔다 —
    // sequenceRef 기반 stale 응답 폐기는 디바운스 취소(이전 테스트)와 달리 "두 요청 모두 실제로
    // 나갔지만 응답이 역순으로 돌아오는" 상황에서만 드러난다.
    const deferred: Record<string, (value: SearchResult[]) => void> = {};
    const fetchMock = vi.fn((path: unknown) => {
      const url = String(path);
      const currency = url.includes('currency=USD') ? 'USD' : 'KRW';
      const q = decodeURIComponent(url.split('q=')[1] ?? '');
      const key = `${q}:${currency}`;
      return new Promise((resolve) => {
        deferred[key] = (value) => resolve({ ok: true, json: async () => value });
      });
    });
    vi.stubGlobal('fetch', fetchMock);
    renderCombobox();

    const input = screen.getByTestId('symbol-search');
    vi.useFakeTimers();
    fireEvent.change(input, { target: { value: 'AAAA' } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400);
    });
    fireEvent.change(input, { target: { value: 'BBBB' } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400);
    });
    vi.useRealTimers();

    // 두 검색어 모두 디바운스가 끝까지 진행돼 fetch가 나갔다(취소되지 않았다).
    expect(fetchMock).toHaveBeenCalledTimes(4);

    // 나중에 시작한 BBBB 요청을 먼저 resolve하고, 먼저 시작한 AAAA 요청을 그 뒤에 resolve한다.
    await act(async () => {
      deferred['BBBB:KRW']([]);
      deferred['BBBB:USD']([appleUsd]);
      await new Promise((r) => setTimeout(r, 0));
    });
    await act(async () => {
      deferred['AAAA:KRW']([samsungKrw]);
      deferred['AAAA:USD']([]);
      await new Promise((r) => setTimeout(r, 0));
    });

    const listbox = screen.getByTestId('symbol-search-listbox');
    expect(listbox.textContent).toContain('Apple Inc.');
    expect(listbox.textContent).not.toContain('삼성전자');
  });

  it('Esc를 누르면 목록을 닫는다', async () => {
    const fetchMock = vi.fn((path: unknown) => {
      const url = String(path);
      const results = url.includes('currency=USD') ? [appleUsd] : [];
      return Promise.resolve({ ok: true, json: async () => results });
    });
    vi.stubGlobal('fetch', fetchMock);
    renderCombobox();

    const input = screen.getByTestId('symbol-search');
    await typeAndWait(input, '검색어');
    expect((screen.getByTestId('symbol-search-listbox') as HTMLUListElement).hidden).toBe(false);

    // 활성 옵션을 만들어 둔 뒤 Esc로 닫는다 — activeIndex가 리셋되지 않으면 숨겨진 옵션을
    // aria-activedescendant가 계속 가리키게 된다.
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(input.getAttribute('aria-activedescendant')).not.toBeNull();

    fireEvent.keyDown(input, { key: 'Escape' });

    expect((screen.getByTestId('symbol-search-listbox') as HTMLUListElement).hidden).toBe(true);
    expect(input.getAttribute('aria-activedescendant')).toBeNull();
  });

  it('활성 옵션이 없을 때 Enter는 목록만 닫고 폼 제출(기본 동작)을 막지 않는다', async () => {
    const fetchMock = vi.fn((path: unknown) => {
      const url = String(path);
      const results = url.includes('currency=USD') ? [appleUsd] : [];
      return Promise.resolve({ ok: true, json: async () => results });
    });
    vi.stubGlobal('fetch', fetchMock);
    const { onSelect } = renderCombobox();

    const input = screen.getByTestId('symbol-search');
    // 결과가 로드돼 목록은 열려 있지만 화살표 키로 옵션을 고르지 않아 activeIndex는 -1이다.
    await typeAndWait(input, '검색어');
    expect((screen.getByTestId('symbol-search-listbox') as HTMLUListElement).hidden).toBe(false);
    expect(input.getAttribute('aria-activedescendant')).toBeNull();

    // fireEvent가 반환하는 값은 dispatchEvent의 결과다 — preventDefault가 호출됐으면 false다.
    const notPrevented = fireEvent.keyDown(input, { key: 'Enter' });

    expect(notPrevented).toBe(true);
    expect(onSelect).not.toHaveBeenCalled();
    expect((screen.getByTestId('symbol-search-listbox') as HTMLUListElement).hidden).toBe(true);
    expect(input.getAttribute('aria-activedescendant')).toBeNull();
  });
});
