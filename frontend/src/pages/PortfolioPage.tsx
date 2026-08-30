// 구조·동작: senior-frontend / 시각 표현·문구: ui-ux-designer
import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import type { PortfolioItem, PortfolioResponse } from '../api/types';
import { getPortfolio } from '../api/assetApi';
import { ApiError } from '../api/authApi';
import { useAuth } from '../auth/useAuth';
import Alert, { type AlertProps } from '../components/Alert';
import Button from '../components/Button';
import Card from '../components/Card';
import { formatAmount, formatQuantity, formatSignedAmount } from '../lib/money';
import { messageForErrorCode } from '../lib/messages';

// 자산 등록 등 다른 화면에서 이동해 올 때 history state로 전달하는 1회성 안내 배너
// (Task 007에서 정한 토스트 대체 패턴: navigate(path, { state: { flash } }) + Alert 렌더).
// export하여 flash를 실어 보내는 화면(AssetNewPage 등)이 이 타입을 그대로 써서
// tone 등의 오타를 컴파일 타임에 잡을 수 있게 한다.
export interface Flash {
  tone: AlertProps['tone'];
  message: string;
}

// 포트폴리오 조회 상태. flash 배너와는 독립적으로 관리한다 — 로딩/에러 중에도 flash는 보여야 한다.
type LoadState =
  | { status: 'loading' }
  | { status: 'error'; code: string }
  | { status: 'ready'; data: PortfolioResponse };

const TONE_CLASS: Record<'gain' | 'loss' | 'flat' | 'unknown', string> = {
  gain: 'text-gain',
  loss: 'text-loss',
  flat: 'text-ink',
  unknown: 'text-ink-soft',
};

// 색은 부호(+/−)를 보조할 뿐이라, 방향이 있는 tone에만 형태 신호를 덧붙인다.
// flat(0)·unknown(—)은 방향이 없으므로 표식도 없다 (docs/DESIGN.md §2-2).
const TONE_MARK: Record<'gain' | 'loss' | 'flat' | 'unknown', string> = {
  gain: '▲',
  loss: '▼',
  flat: '',
  unknown: '',
};

// 로고 이미지는 쓰지 않는다(외부 이미지 호스팅·시세 API 연동 없음). 대신 티커를 등폭 이니셜로 새긴다.
// 국내 종목 코드처럼 숫자로 시작하는 티커는 앞 두 글자가 "00"이 되어 종목을 구분하지 못하므로,
// 그때만 이름의 첫 글자를 쓴다 (005930 → 삼 / BTC → BT / AAPL → AA).
function initials(ticker: string, name: string): string {
  return /^[A-Za-z]/.test(ticker) ? ticker.slice(0, 2).toUpperCase() : name.slice(0, 1);
}

// 목록 행의 수치 한 칸 — 라벨과 값이 **항상 같은 줄에** 붙어 다닌다.
// 컬럼 헤더가 없는 목록이라(docs/DESIGN.md §6-2) 값은 자기 라벨을 달고 다녀야 하고,
// 라벨을 값 위에 쌓으면 한 행이 4줄이 된다. 값은 통째로 `whitespace-nowrap` —
// COIN(소수 8자리) 금액이 어떤 폭에서도 숫자 중간에서 잘리면 안 된다.
function Metric({
  label,
  value,
  mark = '',
  valueClass = 'text-ink',
}: {
  label: string;
  value: string;
  mark?: string;
  valueClass?: string;
}) {
  return (
    <span className="inline-flex shrink-0 items-baseline gap-1.5 whitespace-nowrap">
      <span className="text-[11px] leading-5 text-ink-soft">{label}</span>
      <span className={`font-mono text-[13px] leading-5 font-medium ${valueClass}`}>
        {mark ? (
          <span aria-hidden="true" className="mr-0.5 text-[10px]">
            {mark}
          </span>
        ) : null}
        {value}
      </span>
    </span>
  );
}

// 한 자산 행. 투자 자산 목록·현금 자산 목록이 이 렌더링을 그대로 공유한다.
//
// 2×2 배치: 이름+티커 / 평가금액 (윗줄) · 수량 / 손익 (아랫줄).
// 왼쪽 열은 "무엇을 얼마나 갖고 있나"(신원·수량), 오른쪽 열은 "그게 지금 얼마인가"(금액)다.
// 두 금액은 오른쪽 기준선에 붙어 행을 가로질러 자리수가 세로로 맞는다 (docs/DESIGN.md §3).
function PortfolioItemRow({ item }: { item: PortfolioItem }) {
  // unrealizedPnl은 evaluationKrw와 마찬가지로 항상 KRW 환산액이다(PRD F005 "전체 자산을
  // KRW 기준으로 환산해 평가금액·비중·손익 표시"). item.currency/assetType을 넘기면 원자산
  // 통화·스케일로 잘못 찍힌다 — evaluationKrw에서 이미 한 번 겪은 버그와 같은 유형이다.
  const pnl = formatSignedAmount(item.unrealizedPnl, { currency: 'KRW' });

  return (
    <li>
      <Link
        to={`/assets/${item.assetId}`}
        data-testid={`portfolio-item-${item.assetId}`}
        className="flex items-start gap-3 px-5 py-4 transition-colors duration-150 hover:bg-grid focus-visible:-outline-offset-2"
      >
        {/* 이니셜 배지는 장식이라 무채로 둔다 — 유채색은 정보일 때만 쓴다(§2-3).
            티커·이름이 바로 옆에 있으므로 보조기술에는 중복이다. */}
        <span
          aria-hidden="true"
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-ink/8 font-mono text-[12px] font-medium text-ink"
        >
          {initials(item.ticker, item.name)}
        </span>

        <div className="min-w-0 flex-1">
          {/* 윗줄: 이름·티커 ↔ 평가금액.
              티커는 이름과 같은 줄에 둔다 — 15px 반굵기 잉크 옆의 11px 등폭 ink-soft라
              크기·자면·색 세 축이 갈려 이름과 섞이지 않는다. 자산 유형(주식/코인/현금)·통화는
              행마다 반복하지 않는다 — 유형은 구획 이름이, 통화는 자산 상세(Task 011)가 말한다. */}
          <div className="flex items-baseline justify-between gap-3">
            {/* 이름과 티커는 flex 두 칸으로 두고 `flex-wrap`으로 접는다. 한 문단 안에 인라인으로
                이어 붙이면 폭이 모자랄 때 티커가 `0059 / 30`처럼 토큰 한가운데서 끊긴다(375px 실측)
                — 티커는 통째로 하나의 표식이라 쪼개지면 뜻을 잃는다.
                접히더라도 티커 줄은 왼쪽 기준선에 그대로 붙는다(들여쓰기 없음). */}
            <p className="flex min-w-0 flex-wrap items-baseline gap-x-2">
              <span className="min-w-0 text-[15px] leading-5 font-semibold tracking-tight text-ink">
                {item.name}
              </span>
              <span className="font-mono text-[11px] leading-5 font-medium tracking-[0.1em] whitespace-nowrap text-ink-soft">
                {item.ticker}
              </span>
            </p>
            {/* evaluationKrw는 필드명 그대로 항상 KRW 환산액이라, 원자산 통화/유형과
                무관하게 KRW 스케일(정수)로 표시한다 — item.currency/assetType을 넘기면
                COIN 등에서 소수 8자리로 잘못 찍힌다 (docs/DESIGN.md §9 후속 과제였음). */}
            <Metric
              label="평가금액"
              value={formatAmount(item.evaluationKrw, { currency: 'KRW' })}
              valueClass="text-ink-soft"
            />
          </div>

          {/* 아랫줄: 수량 ↔ 손익. 목록 행은 이 4개만 보여준다 —
              나머지(평단가·취득원가·비중)는 행을 눌러 들어가는 자산 상세(Task 011)가 맡는다. */}
          <div className="mt-1.5 flex items-baseline justify-between gap-3">
            <Metric label="수량" value={formatQuantity(item.quantity)} />
            <Metric
              label="손익"
              value={pnl.text}
              mark={TONE_MARK[pnl.tone]}
              valueClass={TONE_CLASS[pnl.tone]}
            />
          </div>
        </div>

        {/* 이동 표식은 이름 줄 오른쪽 끝에 둔다 — 세로 가운데에 두면
            우측 정렬된 숫자열 한가운데로 끼어든다. */}
        <span aria-hidden="true" className="shrink-0 self-start text-sm text-ink-soft">
          ›
        </span>
      </Link>
    </li>
  );
}

// 장부 한 장 안의 구획. `label`이 있으면 구획 이름(주식/코인)을 머리에 단다.
// 현금처럼 구획이 하나뿐인 장부는 `label: null`로 두어 이름을 붙이지 않는다 —
// 장부 제목("현금")이 이미 같은 말을 한다.
interface ItemSection {
  label: string | null;
  items: PortfolioItem[];
}

// 투자/현금 그룹 하나(제목 + 구획별 목록). 항목이 0건이면 아무것도 렌더하지 않는다 —
// 그룹 제목만 남겨두고 빈 목록을 보여주지 않는다.
//
// 그룹 제목은 장부 표면 **밖**, 카드 바로 위에 둔다. 예전에 같은 자리에 18px h2를 세웠다가
// 두 그룹이 똑같이 반복돼 "같은 목록이 두 번"으로 보인 적이 있어(§8), 이번에는 세 가지를 바꿨다.
//  (1) 크기를 카드 제목(`합계`, 14px)보다 한 단 낮춰(13px) "하위 분류 라벨"로 읽히게 한다.
//  (2) 제목→카드 8px, 카드→다음 제목 24px. 붙는 쪽이 자기 카드라 제목이 카드에 딸린 표찰로 읽힌다.
//  (3) 이름을 한 단어(`투자`/`현금`)로 줄여, 반복되는 것은 형태가 아니라 분류라는 것이 먼저 보인다.
function PortfolioItemGroup({
  title,
  sections,
  testId,
}: {
  title: string;
  sections: ItemSection[];
  testId: string;
}) {
  const filled = sections.filter((section) => section.items.length > 0);
  const count = filled.reduce((sum, section) => sum + section.items.length, 0);
  if (count === 0) return null;

  return (
    <section aria-label={title}>
      {/* 건수는 제목 옆에 붙인다. 오른쪽 끝으로 보내면 바로 아래 미세 라벨·`›`와
          세로로 겹쳐 쌓이고, 700px 떨어진 자리에서 홀로 뜬다. 맨숫자 "4"는 값으로 오독되므로
          단위를 붙이고, 수치는 등폭으로 적는다(§3). */}
      <div className="flex items-baseline gap-2 pb-2">
        <h2 className="text-[13px] font-semibold tracking-tight text-ink">{title}</h2>
        <p className="text-[12px] leading-4 text-ink-soft">
          <span className="font-mono">{count}</span>건
        </p>
      </div>

      {/* 행마다 카드를 두지 않고 한 장의 장부 안에 담는다 — 반복되는 테두리가 사라져야
          열의 자리수가 세로로 이어져 보인다.
          선은 어디에도 긋지 않는다(구획 이름 아래도 포함). 자산 5건이면 실선이 4줄 그어지는데,
          정작 읽어야 할 두 기준선(왼쪽 수량·오른쪽 금액)을 가로줄이 계속 끊는다. 행의 경계는
          32px 여백과 각 행 왼쪽의 이니셜 배지가 말하고, 누를 수 있는 범위는 `hover:bg-grid`가
          말한다. 구획(주식/코인) 사이의 경계는 구획 이름 위 여백(`pt-4`)만으로 충분하다 —
          밴드(`bg-grid`)·점선 모두 쓰지 않는다(점선은 시그니처 전용, §5). */}
      <div
        data-testid={testId}
        className="overflow-hidden rounded-card border border-rule bg-surface"
      >
        {filled.map((section) => (
          <div key={section.label ?? title}>
            {section.label ? (
              <h3 className="px-5 pt-4 pb-2 text-[12px] leading-4 font-medium text-ink-soft">
                {section.label}
              </h3>
            ) : null}
            <ul>
              {section.items.map((item) => (
                <PortfolioItemRow key={item.assetId} item={item} />
              ))}
            </ul>
          </div>
        ))}
      </div>
    </section>
  );
}

export default function PortfolioPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const auth = useAuth();

  // location.state는 아래 effect가 navigate로 곧장 비워버리므로, 배너에 쓸 값은 첫 렌더
  // 시점에 한 번만 떠서 로컬 state로 들고 있는다. location.state를 매 렌더 다시 읽으면
  // state가 비워지자마자(같은 틱 안에서) 배너도 함께 사라져 사용자가 읽을 새도 없이
  // 사라진다 — Task 007 결정("자동 소멸 타이머 없는 배너")과 어긋난다.
  const [flash] = useState<Flash | null>(
    () => (location.state as { flash?: Flash } | null)?.flash ?? null,
  );

  // history state는 1회성으로 비운다 — 뒤로가기로 돌아오거나 화면이 재마운트돼도
  // 같은 안내가 다시 뜨지 않게 한다(화면에 남는 배너 자체는 위 flash 로컬 state가 계속 들고 있는다).
  // navigate(..., { replace: true })로 state를 비운 뒤에는 location.pathname/location.search
  // 문자열 값 자체가 바뀌지 않으므로, exhaustive-deps를 그대로 채워도 effect가 다시 돌지 않는다.
  useEffect(() => {
    if (!flash) return;
    navigate(location.pathname + location.search, { replace: true, state: {} });
  }, [flash, navigate, location.pathname, location.search]);

  // 포트폴리오 조회는 마운트 시 1회. flash 정리 effect와 관심사가 달라 별도로 둔다.
  const [state, setState] = useState<LoadState>({ status: 'loading' });

  useEffect(() => {
    // StrictMode(main.tsx)가 dev 모드에서 effect를 mount→cleanup→mount로 두 번 실행하므로,
    // cleanup 시점에 이미 언마운트된(또는 재마운트 전) 첫 번째 호출의 응답이 뒤늦게 와서
    // state를 덮어쓰지 않도록 cancelled 플래그로 막는다. 이 파일이 이 앱 최초의 실 API GET
    // 호출 effect라 이후 자산 상세/등록 화면(Task 018 하위 태스크)도 이 패턴을 따르면 된다.
    let cancelled = false;
    getPortfolio()
      .then((data) => {
        if (cancelled) return;
        setState({ status: 'ready', data });
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
          auth.logout();
          navigate('/login', { replace: true, state: { from: location } });
          return;
        }
        setState({ status: 'error', code: err instanceof ApiError ? err.code : 'NETWORK_ERROR' });
      });
    return () => {
      cancelled = true;
    };
    // deps를 의도적으로 비워둔다: location을 채우면 위쪽 flash 정리 effect가 매번
    // navigate(..., {replace:true})로 새 location 객체를 만들어내 이 effect가 재실행되고,
    // flash가 있을 때마다 GET /v1/portfolio가 불필요하게 두 번 나간다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const flashAlert = flash ? (
    <Alert tone={flash.tone} testId="portfolio-flash">
      {flash.message}
    </Alert>
  ) : null;

  // 로딩·에러 중에도 flash 배너와 제목은 그대로 보여야 하므로, 데이터 렌더보다 먼저 분기한다.
  if (state.status === 'loading') {
    return (
      <div>
        {flashAlert ? <div className="mb-6">{flashAlert}</div> : null}
        <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">총 자산</h1>
        <div className="mt-6">
          <Card testId="portfolio-loading">
            <p className="py-6 text-center text-sm text-ink-soft">불러오는 중...</p>
          </Card>
        </div>
      </div>
    );
  }

  if (state.status === 'error') {
    return (
      <div>
        {flashAlert ? <div className="mb-6">{flashAlert}</div> : null}
        <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">총 자산</h1>
        <div className="mt-6">
          <Alert tone="error" testId="portfolio-error">
            {messageForErrorCode(state.code)}
          </Alert>
        </div>
      </div>
    );
  }

  const { items, totalEvaluationKrw, totalUnrealizedPnl } = state.data;

  const totalPnl = formatSignedAmount(totalUnrealizedPnl, { currency: 'KRW' });

  // 표시 상태 플래그(시각 전용). 값이 없을 때 히어로를 축소하고 "시세 연동 전" 표식을 붙인다 —
  // 42px짜리 "—" 하나만 떠 있으면 값이 없는 것이 아니라 화면이 깨진 것으로 읽힌다 (docs/DESIGN.md §6-2).
  const hasTotalEvaluation = totalEvaluationKrw !== null;

  // 투자 자산 장부는 주식·코인 두 구획으로 나눈다. 두 장의 장부로 쪼개지 않는 이유는
  // 둘 다 "시세로 평가되는 자산"이라 현금과의 경계(§6-2)만큼 성격이 갈리지 않기 때문이다.
  const investmentSections: ItemSection[] = [
    { label: '주식', items: items.filter((item) => item.assetType === 'STOCK') },
    { label: '코인', items: items.filter((item) => item.assetType === 'COIN') },
  ];
  const cashSections: ItemSection[] = [
    { label: null, items: items.filter((item) => item.assetType === 'CASH') },
  ];

  if (items.length === 0) {
    return (
      <div>
        {flashAlert ? <div className="mb-6">{flashAlert}</div> : null}
        <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">총 자산</h1>

        <div className="mt-6">
          <Card>
            <div className="py-6 text-center">
              <p className="text-[15px] font-semibold tracking-tight text-ink">
                등록된 자산이 없습니다.
              </p>
              <p className="mx-auto mt-2 max-w-xs text-sm leading-6 text-ink-soft">
                자산을 등록하면 전체 자산과 평가손익을 여기서 확인할 수 있습니다.
              </p>
              <div className="mt-6 flex justify-center">
                <Button
                  variant="primary"
                  onClick={() => navigate('/assets/new')}
                  testId="portfolio-add-asset-button"
                >
                  자산 등록
                </Button>
              </div>
            </div>
          </Card>
        </div>
      </div>
    );
  }

  return (
    <div>
      {/* 안내 배너는 본문 맨 위, 제목 줄 위에 둔다. 제목 아래에 끼우면 위 16px·아래 24px로
          여백이 비슷해져 합계 카드와 한 덩어리로 뭉치고, 배너가 있는 방문과 없는 방문에서
          제목 아래 리듬(제목 → mt-6 합계 → mt-10 장부)이 달라진다. 배너는 화면 전체에 대한
          알림이므로 본문의 첫 줄이 제자리다 (docs/DESIGN.md §6-3). */}
      {flashAlert ? <div className="mb-6">{flashAlert}</div> : null}

      {/* "자산 등록"은 투자 자산/현금 자산 어느 한쪽의 액션이 아니라 화면 전체의 액션이라
          페이지 제목과 같은 줄에 세운다. 합계 카드와 첫 장부 사이에 혼자 놓으면 위아래 여백이
          똑같이 벌어져 "버튼만 있는 세 번째 섹션"으로 읽힌다 (docs/DESIGN.md §6-2). */}
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">총 자산</h1>
        <Button
          variant="primary"
          onClick={() => navigate('/assets/new')}
          testId="portfolio-add-asset-button"
        >
          자산 등록
        </Button>
      </div>

      <div className="mt-6">
        <Card title="합계" testId="portfolio-summary">
          <p className="text-[13px] font-medium text-ink-soft">평가금액</p>
          <div className="mt-1.5 flex flex-wrap items-baseline gap-x-3 gap-y-2">
            <p
              className={`font-mono leading-none font-semibold tracking-tight ${
                hasTotalEvaluation
                  ? 'text-[32px] text-ink sm:text-[42px]'
                  : 'text-[22px] text-ink-soft sm:text-[26px]'
              }`}
            >
              {formatAmount(totalEvaluationKrw, { currency: 'KRW' })}
            </p>
            {/* 값이 비어 있는 이유를 숫자 바로 옆에서 한 번 말한다. 카드 하단 주석까지 내려가서야
                알 수 있으면, 그 사이 동안 화면은 고장 난 것으로 보인다.
                에이브로우 자간(§3의 tracking-[0.18em])은 라틴·숫자 기준이라 한글에 그대로 걸면
                글자가 낱자로 흩어진다 — 한글 표식은 본문 자면(sans)에 기본 자간으로 둔다. */}
            {hasTotalEvaluation ? null : (
              <span className="rounded-control bg-ink/8 px-2 py-1 text-[11px] leading-4 font-medium text-ink-soft">
                시세 연동 전
              </span>
            )}
          </div>

          {/* 리더 점선을 쓰지 않는다 — 카드 안의 단 한 줄짜리 라벨-값이라, 점선은 잇는 게 아니라
              700px짜리 빈 줄을 하나 더 그린다. 리더는 여러 행이 쌓일 때만 쓴다 (docs/DESIGN.md §5). */}
          <div className="mt-5 flex items-baseline gap-3 border-t border-rule pt-3">
            <span className="text-[13px] font-medium text-ink-soft">평가손익</span>
            <span className={`font-mono text-sm font-medium ${TONE_CLASS[totalPnl.tone]}`}>
              {TONE_MARK[totalPnl.tone] ? (
                <span aria-hidden="true" className="mr-0.5 text-[10px]">
                  {TONE_MARK[totalPnl.tone]}
                </span>
              ) : null}
              {totalPnl.text}
            </span>
          </div>

          <p className="mt-4 text-xs leading-5 text-ink-soft">
            통화가 다른 자산은 KRW로 환산해 이 총액에 더합니다. 평가금액·평가손익은 시세를 연동하면
            채워집니다.
          </p>
        </Card>
      </div>

      {/* 투자 자산·현금 자산은 "총 자산"의 두 하위 분류다. 두 장을 카드 간격(gap-4)으로 묶고,
          묶음 전체를 합계 카드에서 섹션 간격(mt-10)만큼 떼어 놓는다 — 간격의 대비가
          "따로 반복되는 목록 둘"이 아니라 "한 벌"로 읽히게 한다 (docs/DESIGN.md §6-2). */}
      <div className="mt-10 space-y-4">
        <PortfolioItemGroup
          title="투자"
          sections={investmentSections}
          testId="portfolio-investment-list"
        />
        <PortfolioItemGroup title="현금" sections={cashSections} testId="portfolio-cash-list" />
      </div>
    </div>
  );
}
