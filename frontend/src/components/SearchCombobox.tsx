// 구조·동작(props/상태/핸들러/data-testid): senior-frontend / className·마크업·문구: ui-ux-designer
// WAI-ARIA 콤보박스(리스트박스 팝업) 패턴. 통화(KRW/USD)는 사용자가 고르지 않고 검색 결과가
// 정해준다 — GET /v1/assets/search가 currency를 필수 파라미터로 요구하므로 KRW/USD를 병렬
// 호출해 결과를 합친다(ROADMAP Task 026·027-A).
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import Field from './Field';
import { searchSymbols } from '../api/assetApi';
import { ApiError } from '../api/authApi';
import { messageForErrorCode } from '../lib/messages';
import type { AssetType, SearchResult } from '../api/types';

export interface SearchComboboxProps {
  label: string;
  assetType: Exclude<AssetType, 'CASH'>;
  // 선택 확정 시 result, 이전 선택이 무효화(검색어를 지우거나 고쳐 씀)되면 null을 전달한다.
  onSelect: (result: SearchResult | null) => void;
  onUnauthorized: () => void;
  error?: string | null;
  testId?: string;
}

// 두 글자 미만은 결과가 너무 넓어 서버·클라이언트 모두에게 의미가 없다 — 검색 자체를 생략한다.
const MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 350;
const SEARCH_FAILED_MESSAGE = '검색에 실패했습니다. 잠시 후 다시 시도하세요.';

type Status = 'idle' | 'loading' | 'error';

// TextField와 같은 문자열을 쓴다 — 같은 폼 안에서 검색칸만 다른 톤이면 "다른 종류의 칸"으로 읽힌다
// (docs/DESIGN.md §6-3).
const INPUT_BASE =
  'h-11 w-full rounded-control border border-rule bg-surface px-3 font-sans text-[15px] text-ink transition-colors duration-150 placeholder:text-ink-soft/55 hover:border-ink-soft aria-[invalid=true]:border-alarm aria-[invalid=true]:bg-alarm/4';

// 입력 예시는 유형을 따라간다(§6-3) — 코인을 고른 사람이 "삼성전자"를 예시로 보면 안 된다.
// 종목명·티커 어느 쪽으로도 검색된다는 사실을 예시 자체가 말해준다.
const SEARCH_PLACEHOLDER: Record<Exclude<AssetType, 'CASH'>, string> = {
  STOCK: '삼성전자 또는 005930',
  COIN: '비트코인 또는 BTC',
};

export default function SearchCombobox({
  label,
  assetType,
  onSelect,
  onUnauthorized,
  error,
  testId,
}: SearchComboboxProps) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [status, setStatus] = useState<Status>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);

  // 디바운스 타이머와 요청 시퀀스 번호는 렌더와 무관하게 최신 값을 참조해야 해서 ref로 관리한다.
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const sequenceRef = useRef(0);
  // selectResult가 query에 써넣은 "이름 (티커)" 문자열. 사용자가 이 값을 그대로 두지 않고
  // 지우거나 고쳐 쓰면 이전 선택은 더 이상 유효하지 않다 — 부모에 onSelect(null)로 알린다.
  const lastSelectedLabelRef = useRef<string | null>(null);

  const reactId = useId();
  const listboxId = `${reactId}-listbox`;

  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  function runSearch(q: string) {
    const sequence = ++sequenceRef.current;
    setStatus('loading');

    Promise.allSettled([
      searchSymbols(assetType, 'KRW', q),
      searchSymbols(assetType, 'USD', q),
    ]).then((settled) => {
      // 인증 만료는 부분 성공으로 덮을 문제가 아니다 — 다른 쪽이 성공했어도 즉시 리디렉션한다.
      const unauthorized = settled.some(
        (r) => r.status === 'rejected' && r.reason instanceof ApiError && r.reason.code === 'UNAUTHORIZED',
      );
      if (unauthorized) {
        onUnauthorized();
        return;
      }

      // 먼저 시작한 느린 요청이 나중 요청보다 늦게 응답해 최신 검색어 결과를 덮어쓰는 걸 방지한다.
      if (sequence !== sequenceRef.current) return;

      const fulfilled = settled.filter(
        (r): r is PromiseFulfilledResult<SearchResult[]> => r.status === 'fulfilled',
      );
      if (fulfilled.length === 0) {
        // ApiError면(예: SEARCH_RATE_LIMITED) 그 code에 해당하는 문구를 쓴다 — 네트워크 에러 등
        // ApiError가 아닌 사유는 고정 문구로 폴백한다.
        const apiErrorReason = settled.find(
          (r): r is PromiseRejectedResult => r.status === 'rejected' && r.reason instanceof ApiError,
        )?.reason as ApiError | undefined;
        setErrorMessage(apiErrorReason ? messageForErrorCode(apiErrorReason.code) : SEARCH_FAILED_MESSAGE);
        setStatus('error');
        setResults([]);
        return;
      }
      // 부분 실패(한쪽만 성공)는 조용히 무시한다 — 사용자가 계속 타이핑하면 자연히 재시도된다.
      setStatus('idle');
      setResults(fulfilled.flatMap((r) => r.value));
    });
  }

  function handleChange(value: string) {
    setQuery(value);
    // 이전 선택 문자열을 그대로 두지 않고 고쳐 쓰면 그 선택은 더 이상 유효하지 않다.
    if (lastSelectedLabelRef.current !== null && value !== lastSelectedLabelRef.current) {
      lastSelectedLabelRef.current = null;
      onSelect(null);
    }
    setActiveIndex(-1);
    if (debounceRef.current) clearTimeout(debounceRef.current);

    if (value.trim().length < MIN_QUERY_LENGTH) {
      setOpen(false);
      setResults([]);
      setStatus('idle');
      return;
    }

    setOpen(true);
    // 디바운스 대기 구간에도 "검색 중…" 행을 보여준다 — status를 idle로 두면 그 사이 잠깐
    // "검색 결과가 없습니다"가 깜빡인다(runSearch 시작 시 다시 loading을 세팅하므로 중복 호출은 무해).
    setStatus('loading');
    debounceRef.current = setTimeout(() => runSearch(value), DEBOUNCE_MS);
  }

  function selectResult(result: SearchResult) {
    const label = `${result.name} (${result.ticker})`;
    lastSelectedLabelRef.current = label;
    onSelect(result);
    setQuery(label);
    setOpen(false);
    setResults([]);
    setActiveIndex(-1);
  }

  function handleKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Escape') {
      setOpen(false);
      setActiveIndex(-1);
      return;
    }
    if (!open) {
      // 닫힌 상태에서도 ↓로 목록을 다시 열 수 있어야 한다(APG 콤보박스 패턴).
      if (e.key === 'ArrowDown' && query.trim().length >= MIN_QUERY_LENGTH) {
        e.preventDefault();
        handleChange(query);
      }
      return;
    }

    if (e.key === 'ArrowDown' && results.length > 0) {
      e.preventDefault();
      setActiveIndex((i) => Math.min(i + 1, results.length - 1));
    } else if (e.key === 'ArrowUp' && results.length > 0) {
      e.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      // 활성 옵션이 있을 때만 선택을 확정하며 폼 제출을 막는다. 활성 옵션이 없으면(검색 중·결과
      // 없음 등) 목록만 닫고 Enter의 기본 동작(폼 제출)을 그대로 흘려보낸다.
      if (activeIndex >= 0 && results[activeIndex]) {
        e.preventDefault();
        selectResult(results[activeIndex]);
      } else {
        setOpen(false);
        setActiveIndex(-1);
      }
    }
  }

  const listboxTestId = testId ? `${testId}-listbox` : undefined;

  return (
    <div className="relative">
      <Field label={label} error={error}>
        {(control) => (
          <input
            {...control}
            type="text"
            role="combobox"
            aria-expanded={open}
            aria-controls={listboxId}
            aria-autocomplete="list"
            aria-activedescendant={
              activeIndex >= 0 ? `${listboxId}-option-${activeIndex}` : undefined
            }
            autoComplete="off"
            placeholder={SEARCH_PLACEHOLDER[assetType]}
            value={query}
            onChange={(e) => handleChange(e.target.value)}
            onKeyDown={handleKeyDown}
            onBlur={() => {
              setOpen(false);
              setActiveIndex(-1);
            }}
            data-testid={testId}
            className={INPUT_BASE}
          />
        )}
      </Field>
      {/* 결과 목록은 폼 위에 겹쳐 뜨는 층이다. 흐름에 남겨두면 목록이 열릴 때마다 아래 「보유 정보」
          묶음이 최대 240px 밀려 내려가, 검색 한 번에 폼 전체가 출렁인다. 위치는 필드 전체(라벨·에러
          포함) 아래 기준 — 에러가 떠 있을 때도 목록이 에러 문구를 가리지 않는다.
          <ul>은 닫혀 있을 때도 DOM에 남겨 hidden으로만 숨긴다 — 그렇지 않으면 aria-controls가
          존재하지 않는 id를 가리키게 된다. */}
      <ul
        id={listboxId}
        role="listbox"
        hidden={!open}
        data-testid={listboxTestId}
        className="absolute inset-x-0 top-full z-20 mt-1.5 max-h-60 overflow-auto overscroll-contain rounded-control border border-rule bg-surface py-1 shadow-popover"
      >
          {status === 'loading' ? (
            <li role="presentation" className="flex items-center gap-2 px-3.5 py-2.5 text-sm text-ink-soft">
              {/* 진행 표시는 반만 잉크로 남긴 회전 고리. 1/4만 남기면 375px에서 회색 점으로 뭉개진다
                  (실측). prefers-reduced-motion에서는 index.css가 회전을 멈춰 정지한 고리로 남는다. */}
              <svg
                aria-hidden="true"
                viewBox="0 0 16 16"
                className="size-3.5 shrink-0 animate-spin"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
              >
                <circle cx="8" cy="8" r="6" strokeOpacity="0.25" />
                <path d="M8 2a6 6 0 0 1 4.24 10.24" strokeLinecap="round" />
              </svg>
              검색 중…
            </li>
          ) : null}
          {status === 'error' ? (
            // 색만으로 실패를 알리지 않는다 — Field·Alert와 같은 경고 삼각형을 형태 신호로 함께 쓴다.
            <li
              role="presentation"
              className="flex items-start gap-2 px-3.5 py-2.5 text-sm leading-5 font-medium text-alarm"
            >
              <svg
                aria-hidden="true"
                viewBox="0 0 16 16"
                className="mt-0.5 size-3.5 shrink-0 fill-current"
              >
                <path d="M8 1.5 15 14H1L8 1.5Zm0 4.25a.75.75 0 0 0-.75.75v2.75a.75.75 0 0 0 1.5 0V6.5A.75.75 0 0 0 8 5.75Zm0 5.25a.9.9 0 1 0 0 1.8.9.9 0 0 0 0-1.8Z" />
              </svg>
              <span>{errorMessage ?? SEARCH_FAILED_MESSAGE}</span>
            </li>
          ) : null}
          {status === 'idle' && results.length === 0 ? (
            // 빈 결과는 잘못이 아니라 사실이다 — 경고색 대신 보조 잉크로 두고, 다음 행동만 덧붙인다(§7).
            <li role="presentation" className="flex items-start gap-2 px-3.5 py-2.5 text-sm leading-5 text-ink-soft">
              <svg
                aria-hidden="true"
                viewBox="0 0 16 16"
                className="mt-0.5 size-3.5 shrink-0"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.6"
              >
                <circle cx="7" cy="7" r="4.75" />
                <path d="M10.4 10.4 14 14" strokeLinecap="round" />
              </svg>
              <span>검색 결과가 없습니다. 종목명 대신 티커로도 찾을 수 있습니다.</span>
            </li>
          ) : null}
          {results.map((result, index) => (
            // 활성(키보드) 옵션은 채움색뿐 아니라 좌측 2px 잉크 바로도 표시한다 — 채움이 지워지는
            // 강제 색상 모드·색각 이상에서도 형태 신호가 남는다(§2-3). 비활성 옵션도 같은 폭의
            // 투명 테두리를 달고 있어 활성으로 바뀔 때 글자가 밀리지 않는다.
            <li
              key={`${result.currency}:${result.ticker}`}
              id={`${listboxId}-option-${index}`}
              role="option"
              aria-selected={index === activeIndex}
              data-testid={testId ? `${testId}-option-${index}` : undefined}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => selectResult(result)}
              className={`flex cursor-pointer items-center justify-between gap-3 border-l-2 px-3 py-2.5 transition-colors duration-150 ${
                index === activeIndex
                  ? 'border-ink bg-ink/8'
                  : 'border-transparent hover:bg-grid'
              }`}
            >
              {/* 이름·티커는 목록 화면의 행 머리(§6-2)와 같은 규격 — 크기·자면·색 세 축이 갈려
                  한 줄에 있어도 섞이지 않는다. 티커는 통째로 하나의 표식이라 쪼개지지 않게 한다. */}
              <span className="flex min-w-0 flex-wrap items-baseline gap-x-2">
                <span className="min-w-0 truncate text-sm font-medium text-ink">{result.name}</span>
                <span className="font-mono text-[11px] whitespace-nowrap text-ink-soft">
                  {result.ticker}
                </span>
              </span>
              {/* KRW/USD 두 통화의 결과가 한 목록에 섞여 있으므로 통화는 오른쪽 기준선에 세워
                  세로로 훑히게 둔다. 뱃지 자면은 §3의 유틸리티 역할(등폭·자간). */}
              <span className="shrink-0 rounded-control border border-rule px-1.5 py-0.5 font-mono text-[10px] tracking-[0.12em] text-ink-soft">
                {result.currency}
              </span>
            </li>
          ))}
      </ul>
    </div>
  );
}
