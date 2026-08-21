// 구조·동작: senior-frontend / 시각 표현·문구: ui-ux-designer
import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import type { PortfolioItem } from '../api/types';
import { portfolioFixture } from '../api/fixtures';
import Alert, { type AlertProps } from '../components/Alert';
import Button from '../components/Button';
import Card from '../components/Card';
import { formatAmount, formatQuantity, formatSignedAmount } from '../lib/money';

// 자산 등록 등 다른 화면에서 이동해 올 때 history state로 전달하는 1회성 안내 배너
// (Task 007에서 정한 토스트 대체 패턴: navigate(path, { state: { flash } }) + Alert 렌더).
// export하여 flash를 실어 보내는 화면(AssetNewPage 등)이 이 타입을 그대로 써서
// tone 등의 오타를 컴파일 타임에 잡을 수 있게 한다.
export interface Flash {
  tone: AlertProps['tone'];
  message: string;
}

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

// 화면에는 사용자가 쓰는 말을 내보낸다 — 서버 enum(STOCK/COIN/CASH)을 그대로 보여주지 않는다.
const ASSET_TYPE_LABEL: Record<string, string> = {
  STOCK: '주식',
  COIN: '코인',
  CASH: '현금',
};

// 로고 이미지는 쓰지 않는다(외부 이미지 호스팅·시세 API 연동 없음). 대신 티커를 등폭 이니셜로 새긴다.
// 국내 종목 코드처럼 숫자로 시작하는 티커는 앞 두 글자가 "00"이 되어 종목을 구분하지 못하므로,
// 그때만 이름의 첫 글자를 쓴다 (005930 → 삼 / BTC → BT / AAPL → AA).
function initials(ticker: string, name: string): string {
  return /^[A-Za-z]/.test(ticker) ? ticker.slice(0, 2).toUpperCase() : name.slice(0, 1);
}

// 목록 행의 수치 한 칸.
// 좁은 화면: 라벨 왼쪽 / 값 오른쪽 한 줄. 넓은 화면: 라벨 위 / 값 아래 3열.
// 어느 쪽이든 값은 오른쪽 기준선에 붙어, 자리수가 행을 가로질러 세로로 맞는다 (docs/DESIGN.md §3).
// 좁은 화면에서 2열로 쪼개지 않는 이유는 COIN(소수 8자리) 값이 칸 폭을 넘겨 숫자 중간에서
// 줄바꿈되기 때문이다 — 금액은 어떤 폭에서도 한 줄로 읽혀야 한다.
function Cell({
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
    <div className="flex items-baseline justify-between gap-4 sm:block sm:text-right">
      <dt className="shrink-0 text-[11px] leading-5 text-ink-soft sm:leading-4">{label}</dt>
      <dd
        className={`font-mono text-[13px] leading-5 font-medium whitespace-nowrap sm:mt-0.5 ${valueClass}`}
      >
        {mark ? (
          <span aria-hidden="true" className="mr-0.5 text-[10px]">
            {mark}
          </span>
        ) : null}
        {value}
      </dd>
    </div>
  );
}

// 한 자산 행. 투자 자산 목록·현금 자산 목록이 이 렌더링을 그대로 공유한다 —
// 두 목록으로 나뉘어도 이니셜 배지·3필드 Cell·이동 표식 등 행 자체의 모양은 달라지지 않는다.
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

        <div className="min-w-0 flex-1 sm:flex sm:items-start sm:gap-6">
          <div className="min-w-0 sm:flex-1">
            <p className="text-[15px] font-semibold tracking-tight text-ink">{item.name}</p>
            <p className="mt-0.5 text-[11px] leading-4 text-ink-soft">
              <span className="font-mono tracking-[0.1em]">{item.ticker}</span>
              <span aria-hidden="true" className="mx-1.5">
                ·
              </span>
              {ASSET_TYPE_LABEL[item.assetType] ?? item.assetType}
              <span aria-hidden="true" className="mx-1.5">
                ·
              </span>
              <span className="font-mono tracking-[0.1em]">{item.currency}</span>
            </p>
          </div>

          {/* 목록 행은 요약 3필드만 보여준다 — 나머지(평단가·취득원가·비중 등)는
              행 클릭으로 이동하는 자산 상세 화면(Task 011)에서 전부 보여준다.
              넓은 화면에서 이 블록은 폭을 고정해 오른쪽에 붙인다(`flex-1` 아님) —
              3등분하면 칸마다 빈자리가 흩어져 행이 성글게 보인다 (docs/DESIGN.md §6-2). */}
          <dl className="mt-3 grid gap-y-1 sm:mt-0 sm:w-96 sm:shrink-0 sm:grid-cols-3 sm:gap-x-4 sm:gap-y-2.5">
            {/* evaluationKrw는 필드명 그대로 항상 KRW 환산액이라, 원자산 통화/유형과
                무관하게 KRW 스케일(정수)로 표시한다 — item.currency/assetType을 넘기면
                COIN 등에서 소수 8자리로 잘못 찍힌다 (docs/DESIGN.md §9 후속 과제였음). */}
            <Cell
              label="평가금액"
              value={formatAmount(item.evaluationKrw, { currency: 'KRW' })}
              valueClass="text-ink-soft"
            />
            <Cell
              label="손익"
              value={pnl.text}
              mark={TONE_MARK[pnl.tone]}
              valueClass={TONE_CLASS[pnl.tone]}
            />
            <Cell label="수량" value={formatQuantity(item.quantity)} />
          </dl>
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

// 투자 자산/현금 자산 그룹 하나(제목 머리글 + 목록). 항목이 0건이면 아무것도 렌더하지 않는다 —
// 그룹 헤더만 남겨두고 빈 목록을 보여주지 않는다.
//
// 그룹 제목은 격자지 배경 위에 떠 있는 h2가 아니라 **장부 한 장의 머리글**로 둔다
// (합계 카드와 같은 규격: 실선 머리글 + 14px 반굵기). 그래야 "총 자산" 아래에
// 합계 / 투자 자산 / 현금 자산 세 장이 나란히 놓인 한 벌로 읽힌다 (docs/DESIGN.md §6-2).
function PortfolioItemGroup({
  title,
  items,
  testId,
}: {
  title: string;
  items: PortfolioItem[];
  testId: string;
}) {
  if (items.length === 0) return null;

  return (
    <section
      aria-label={title}
      className="overflow-hidden rounded-card border border-rule bg-surface"
    >
      <div className="flex items-baseline gap-2.5 border-b border-rule px-5 py-3.5">
        <h2 className="text-sm font-semibold tracking-tight text-ink">{title}</h2>
        {/* 건수는 제목 옆에 붙인다. 오른쪽 끝으로 보내면 바로 아래 `수량`·`›`와 미세 라벨이
            세로로 겹쳐 쌓이고, 700px 떨어진 자리에서 홀로 뜬다. 맨숫자 "4"는 값으로 오독되므로
            단위를 붙이고, 수치는 등폭으로 적는다(§3). */}
        <p className="text-[12px] leading-4 text-ink-soft">
          <span className="font-mono">{items.length}</span>건
        </p>
      </div>

      {/* 행마다 카드를 두지 않고 한 장의 장부 안에서 실선으로만 나눈다 —
          반복되는 테두리가 사라져야 열의 자리수가 세로로 이어져 보인다. */}
      <ul data-testid={testId} className="divide-y divide-rule">
        {items.map((item) => (
          <PortfolioItemRow key={item.assetId} item={item} />
        ))}
      </ul>
    </section>
  );
}

export default function PortfolioPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { items, totalEvaluationKrw, totalUnrealizedPnl } = portfolioFixture;

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

  const flashAlert = flash ? (
    <Alert tone={flash.tone} testId="portfolio-flash">
      {flash.message}
    </Alert>
  ) : null;

  const totalPnl = formatSignedAmount(totalUnrealizedPnl, { currency: 'KRW' });

  // 표시 상태 플래그(시각 전용). 값이 없을 때 히어로를 축소하고 "시세 연동 전" 표식을 붙인다 —
  // 42px짜리 "—" 하나만 떠 있으면 값이 없는 것이 아니라 화면이 깨진 것으로 읽힌다 (docs/DESIGN.md §6-2).
  const hasTotalEvaluation = totalEvaluationKrw !== null;

  const investmentItems = items.filter(
    (item) => item.assetType === 'STOCK' || item.assetType === 'COIN',
  );
  const cashItems = items.filter((item) => item.assetType === 'CASH');

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
          title="투자 자산"
          items={investmentItems}
          testId="portfolio-investment-list"
        />
        <PortfolioItemGroup title="현금 자산" items={cashItems} testId="portfolio-cash-list" />
      </div>
    </div>
  );
}
