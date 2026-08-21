// 구조·동작: senior-frontend / 시각 표현·문구: ui-ux-designer
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import type { AssetType, UpdateHoldingRequest } from '../api/types';
import { assetListFixture, portfolioFixture } from '../api/fixtures';
import { ERROR_MESSAGES, VALIDATION_MESSAGES } from '../lib/messages';
import {
  formatAmount,
  formatQuantity,
  formatSignedAmount,
  formatWeight,
  scaleFor,
} from '../lib/money';
import { Dec, toScaledString } from '../lib/big';
import { simulateAvgPrice, type SimulateResult } from '../lib/simulate';
import { validateAvgPrice, validateQuantity, type ValidationCode } from '../lib/validation';
import Button from '../components/Button';
import Card from '../components/Card';
import ConfirmDialog from '../components/ConfirmDialog';
import TextField from '../components/TextField';
import type { Flash } from './PortfolioPage';

// 화면에는 사용자가 쓰는 말을 내보낸다 — 서버 enum을 그대로 보여주지 않는다.
// (PortfolioPage/AssetNewPage와 동일한 매핑을 페이지 로컬로 복제 — Task 007 컨벤션)
const ASSET_TYPE_LABEL: Record<AssetType, string> = {
  STOCK: '주식',
  COIN: '코인',
  CASH: '현금',
};

// 손익 톤 → 색. PortfolioPage/DevUiPage와 동일한 매핑을 페이지 로컬로 복제.
const TONE_CLASS: Record<'gain' | 'loss' | 'flat' | 'unknown', string> = {
  gain: 'text-gain',
  loss: 'text-loss',
  flat: 'text-ink',
  unknown: 'text-ink-soft',
};
const TONE_MARK: Record<'gain' | 'loss' | 'flat' | 'unknown', string> = {
  gain: '▲',
  loss: '▼',
  flat: '',
  unknown: '',
};
// 차트 두 번째 선(예상 평단가)의 테두리·배지 색 — text-*와 같은 톤 축이지만
// border/bg 유틸이 필요해 별도 테이블로 둔다.
const TONE_LINE_CLASS: Record<'gain' | 'loss' | 'flat', { border: string; bg: string }> = {
  gain: { border: 'border-gain', bg: 'bg-gain' },
  loss: { border: 'border-loss', bg: 'bg-loss' },
  flat: { border: 'border-ink', bg: 'bg-ink' },
};

// 라벨과 값을 점선 리더로 잇는다 (DevUiPage의 ReadRow를 이 페이지로 복제 — 공용화하지 않는다).
function ReadRow({
  label,
  value,
  testId,
  valueClass = 'text-ink',
}: {
  label: string;
  value: ReactNode;
  testId: string;
  valueClass?: string;
}) {
  return (
    <div className="flex items-baseline gap-2 py-1.5" data-testid={testId}>
      <span className="shrink-0 text-sm text-ink-soft">{label}</span>
      <span aria-hidden="true" className="min-w-3 flex-1 border-b border-dotted border-rule" />
      <span className={`font-mono text-sm font-medium ${valueClass}`}>{value}</span>
    </div>
  );
}

export default function AssetDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  // 비중(%) 계산 방식(사용자 확정): GET /v1/assets/{id} 응답에는 분모(전체 평가금액)가 없어
  // 자산 상세 조회 하나만으로는 비중을 못 구한다. assetListFixture(수량·평단가·version)와
  // portfolioFixture.items(취득원가·평가금액·평가손익·비중)를 각각 조회해 합친다.
  // Task 018(실 연동)에서는 이 두 줄이 GET /v1/assets/{id} + GET /v1/portfolio fetch로
  // 치환될 예정이므로 조회 로직을 컴포넌트 최상단에 모아둔다.
  const asset = assetListFixture.items.find((item) => item.id === id);
  const portfolioItem = portfolioFixture.items.find((item) => item.assetId === id);

  // 시뮬레이터 상태(F006)
  const [additionalPrice, setAdditionalPrice] = useState('');
  const [additionalQuantity, setAdditionalQuantity] = useState('');
  const [additionalPriceError, setAdditionalPriceError] = useState<ValidationCode | null>(null);
  const [additionalQuantityError, setAdditionalQuantityError] = useState<ValidationCode | null>(
    null,
  );
  const [simResult, setSimResult] = useState<SimulateResult | null>(null);

  // 수정 폼 상태(F003). version은 화면에 노출하지 않고 제출 조립에만 쓴다.
  const [editQuantity, setEditQuantity] = useState(asset?.quantity ?? '');
  const [editAvgPrice, setEditAvgPrice] = useState<string | null>(
    asset && asset.assetType !== 'CASH' ? asset.avgPrice : null,
  );
  const [editQuantityError, setEditQuantityError] = useState<ValidationCode | null>(null);
  const [editAvgPriceError, setEditAvgPriceError] = useState<ValidationCode | null>(null);
  // 훅이 아니라 값 계산이므로 useState가 필요 없다(모든 사용처가 조기 리턴 이후).
  const version = asset?.version ?? 0;

  // 삭제 확인 다이얼로그(F004)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);

  // 이 앱에서 URL 파라미터가 유효하지 않을 수 있는 첫 화면 — 전용 에러 컴포넌트를 새로
  // 만들지 않고 기존 Card + 안내 문구 한 줄로 처리한다.
  // not-found 판정은 asset 존재 여부만 본다 — portfolioItem은 evaluationKrw/unrealizedPnl/weight/
  // cost 같은 파생 필드의 출처일 뿐이라, 없어도 각 필드가 NULL_DISPLAY("—")로 자연히 빠지면 된다
  // (ROADMAP.md 「API 규격」 — 상세 조회에 진짜 필요한 값은 자산 자체뿐).
  if (!asset) {
    return (
      <div>
        {/* 총 자산 화면의 빈 상태와 같은 골격을 쓴다 — 제목 + 사실 한 줄(이유·다음 행동 한 줄)
            + 가운데 놓인 주 액션. 같은 성격의 화면이 서로 다른 모양으로 비어 있으면 안 된다
            (docs/DESIGN.md §6-2 「빈 상태」). */}
        <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">자산 상세</h1>
        <div className="mt-6">
          <Card testId="asset-detail-not-found">
            <div className="py-6 text-center">
              <p className="text-[15px] font-semibold tracking-tight text-ink">
                자산을 찾을 수 없습니다.
              </p>
              <p className="mx-auto mt-2 max-w-xs text-sm leading-6 text-ink-soft">
                {ERROR_MESSAGES.ASSET_NOT_FOUND}
              </p>
              <div className="mt-6 flex justify-center">
                <Button
                  variant="primary"
                  onClick={() => navigate('/portfolio')}
                  testId="asset-detail-not-found-back"
                >
                  총 자산으로 돌아가기
                </Button>
              </div>
            </div>
          </Card>
        </div>
      </div>
    );
  }

  const handleSimulate = () => {
    const priceCode = validateAvgPrice(additionalPrice, asset.assetType);
    const quantityCode = validateQuantity(additionalQuantity);
    setAdditionalPriceError(priceCode);
    setAdditionalQuantityError(quantityCode);
    // 이전 결과가 남아 있었다면 handleAdditionalPriceChange/handleAdditionalQuantityChange가
    // 입력이 바뀌는 시점에 이미 지웠다 — "계산" 클릭 시점의 입력은 항상 그 이전 simResult가
    // 없는 상태이므로 여기서 다시 지울 것이 없다(docs/DESIGN.md §6-4는 그 무효화 규칙 자체를 말한다).
    if (priceCode || quantityCode) {
      return;
    }

    // simulateAvgPrice는 총수량(보유+추가)이 0이면 throw한다(lib/simulate.ts 주석) —
    // "페이지가 먼저 막아야 하는 입력"이라는 뜻이다. validateQuantity는 추가 수량 0을 허용하므로
    // 보유수량 0 자산에 추가수량 0을 입력하는 조합은 여기서 막는다. 이 분기에 들어오는 시점엔
    // 위 152-154행과 같은 이유로 simResult가 이미 null이라 지울 것이 없다.
    const totalQuantity = Dec(asset.quantity).plus(Dec(additionalQuantity));
    if (totalQuantity.eq('0')) {
      return;
    }

    setSimResult(
      simulateAvgPrice({
        currentQuantity: asset.quantity,
        currentAvgPrice: asset.avgPrice,
        additionalQuantity,
        additionalPrice,
        assetType: asset.assetType,
        currency: asset.currency,
      }),
    );
  };

  // 입력이 바뀌면 이전 계산은 더 이상 그 입력에 대한 답이 아니다 — 결과가 남아 있으면 함께 지운다.
  const handleAdditionalPriceChange = (value: string) => {
    setAdditionalPrice(value);
    if (simResult) setSimResult(null);
  };
  const handleAdditionalQuantityChange = (value: string) => {
    setAdditionalQuantity(value);
    if (simResult) setSimResult(null);
  };

  const handleEditSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();

    const quantityCode = validateQuantity(editQuantity);
    const avgPriceCode = validateAvgPrice(editAvgPrice, asset.assetType);
    setEditQuantityError(quantityCode);
    setEditAvgPriceError(avgPriceCode);
    if (quantityCode || avgPriceCode) return;

    // 수정 API(PUT /v1/assets/{id}/holdings, Task 012)는 아직 없다 — 형태만 조립해
    // 유효성을 확인하고, 실제 전송 없이 성공 흐름으로 넘어간다. 409 HOLDING_CONFLICT
    // 분기는 Task 018에서 실 연동 시 추가한다(지금은 실패할 수 없는 요청이라 흉내내지 않는다).
    const request: UpdateHoldingRequest = {
      quantity: editQuantity,
      avgPrice: editAvgPrice,
      version,
    };
    void request;

    const flash: Flash = { tone: 'success', message: '자산이 수정되었습니다.' };
    navigate('/portfolio', { state: { flash } });
  };

  const handleDeleteConfirm = () => {
    // 삭제 API(DELETE /v1/assets/{id}, Task 012)는 아직 없다 — 확인 후 바로 성공 흐름으로 넘어간다.
    const flash: Flash = { tone: 'success', message: '자산이 삭제되었습니다.' };
    navigate('/portfolio', { state: { flash } });
  };

  const priceOpts = { currency: asset.currency, assetType: asset.assetType };
  // portfolioItem은 옵셔널이다(Minor 4) — 없으면 파생 필드는 전부 null로 두어 기존
  // NULL_DISPLAY("—") 규칙이 자연히 타게 한다.
  const cost = portfolioItem?.cost ?? null;
  const evaluationKrw = portfolioItem?.evaluationKrw ?? null;
  const unrealizedPnl = portfolioItem?.unrealizedPnl ?? null;
  const weight = portfolioItem?.weight ?? null;
  const pnl = formatSignedAmount(unrealizedPnl, { currency: 'KRW' });

  // 시뮬레이션 결과가 있을 때만 차트 두 번째 선(예상 평단가)의 방향(상승/하락/변화없음)을 계산한다.
  // 색은 손익의 좋고나쁨이 아니라 가격의 방향(상승=빨강/하락=파랑, 한국 증권 앱 관례)을 따른다.
  // 현금은 평단가 자체가 없다 — 서버가 넣어주는 avgPrice=1은 저장상의 약속이지 사용자가 읽을
  // 값이 아니다(ROADMAP 결정 #1). 평단가에 딸린 표시(평단가 행·취득원가 행·평단가 차트)는
  // 현금 화면에서 전부 내린다 (docs/DESIGN.md §6-4).
  const isCash = asset.assetType === 'CASH';

  let diffTone: 'gain' | 'loss' | 'flat' | null = null;
  let diffText = '';
  if (simResult) {
    const priceScale = scaleFor(priceOpts);
    const diff = toScaledString(
      Dec(simResult.expectedAvgPrice).minus(Dec(simResult.currentAvgPrice)),
      priceScale,
    );
    const diffFormatted = formatSignedAmount(diff, priceOpts);
    diffTone = diffFormatted.tone === 'unknown' ? 'flat' : diffFormatted.tone;
    diffText = diffFormatted.text;
  }

  // 평단선의 세로 위치. 스케일 계산은 하지 않는다(정적 더미) — 다만 **평단이 오르는데 선을
  // 아래에 긋는 것**은 가격축에서 방향이라는 유일한 시각 정보가 거짓말을 하는 것이라,
  // 방향에 따라 미리 정해둔 두 좌표 중 하나를 고른다. 현재 평단선은 어느 경우에도 움직이지
  // 않는다 — 기준선이 계산할 때마다 자리를 옮기면 기준선이 아니다 (docs/DESIGN.md §6-4).
  const expectedAbove = diffTone === 'gain';
  const expectedTopClass = expectedAbove ? 'top-[20%]' : 'top-[80%]';
  const diffTopClass = expectedAbove ? 'top-[20%]' : 'top-[50%]';

  return (
    <div>
      <Link
        to="/portfolio"
        data-testid="asset-detail-back-link"
        className="inline-flex rounded-control text-sm text-ink-soft transition-colors duration-150 hover:text-ink"
      >
        ‹ 총 자산
      </Link>

      <div className="mt-2">
        <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">{asset.name}</h1>
        <p className="mt-1 text-[13px] leading-5 text-ink-soft">
          <span className="font-mono tracking-[0.1em]">{asset.ticker}</span>
          <span aria-hidden="true" className="mx-1.5">
            ·
          </span>
          {ASSET_TYPE_LABEL[asset.assetType]}
          <span aria-hidden="true" className="mx-1.5">
            ·
          </span>
          <span className="font-mono tracking-[0.1em]">{asset.currency}</span>
        </p>
      </div>

      {/* 읽는 묶음 — 상세 정보 · 평단가 차트 · 시뮬레이터. 셋 다 아무것도 저장하지 않는다.
          아래 "고치는 묶음"(수정·삭제)과는 40px 섹션 간격으로 갈라 놓는다:
          간격의 대비가 곧 구조다 (docs/DESIGN.md §6-2·§6-4). */}
      <div className="mt-8 space-y-4">
        <Card title="상세 정보" testId="asset-detail-info">
          {/* 위 묶음은 내가 적어 넣은 값, 아래 묶음은 시세가 채워 넣는 값이다.
              Phase 2에서 아래 셋이 전부 "—"라, 구분선과 주석 없이 여섯 줄을 한 번에 쌓으면
              뒤쪽 세 줄이 "빈 값"이 아니라 "고장"으로 읽힌다 (docs/DESIGN.md §6-2). */}
          <ReadRow
            label="보유수량"
            value={formatQuantity(asset.quantity)}
            testId="asset-detail-quantity"
          />
          {/* 현금의 평단가는 1 고정(저장상의 약속)이고, 취득원가는 그 1을 곱한 값이라
              보유수량과 늘 같은 숫자다. 같은 수를 라벨만 바꿔 두 번 적지 않는다. */}
          {isCash ? null : (
            <>
              <ReadRow
                label="평단가"
                value={formatAmount(asset.avgPrice, priceOpts)}
                testId="asset-detail-avg-price"
              />
              <ReadRow
                label="취득원가"
                value={formatAmount(cost, priceOpts)}
                testId="asset-detail-cost"
              />
            </>
          )}

          <div className="mt-2 border-t border-rule pt-2">
            <ReadRow
              label="평가금액"
              value={formatAmount(evaluationKrw, { currency: 'KRW' })}
              valueClass={evaluationKrw === null ? 'text-ink-soft' : 'text-ink'}
              testId="asset-detail-evaluation"
            />
            <ReadRow
              label="평가손익"
              value={
                <>
                  {TONE_MARK[pnl.tone] ? (
                    <span aria-hidden="true" className="mr-0.5 text-[10px]">
                      {TONE_MARK[pnl.tone]}
                    </span>
                  ) : null}
                  {pnl.text}
                </>
              }
              valueClass={TONE_CLASS[pnl.tone]}
              testId="asset-detail-pnl"
            />
            <ReadRow
              label="비중"
              value={formatWeight(weight)}
              valueClass={weight === null ? 'text-ink-soft' : 'text-ink'}
              testId="asset-detail-weight"
            />
          </div>

          {/* 총 자산 합계 카드의 주석과 같은 말을 쓴다 — 같은 이유로 비어 있는 값이라면
              화면마다 다른 문장으로 설명하지 않는다 (docs/DESIGN.md §6-2). */}
          <p className="mt-3 text-xs leading-5 text-ink-soft">
            평가금액·평가손익·비중은 시세를 연동하면 채워집니다.
          </p>
        </Card>

        {/* F007: 시세 차트 자체는 Phase 4 범위다. 지금 이 카드가 그리는 것은 평단선(§5 시그니처)
            하나뿐이고, 시뮬레이션 결과가 있으면 예상 평단선을 위나 아래에 겹쳐 그린다.
            현금은 평단가가 없으므로 카드째 렌더하지 않는다. */}
        {isCash ? null : (
          <Card title="평단가 차트" testId="asset-detail-chart">
            <div className="plot-grid relative h-40 rounded-control border border-rule bg-grid px-4">
              {/* 현재 평단선은 언제나 플롯 한가운데 — 예상선이 위로 붙든 아래로 붙든 기준선은
                  자리를 지킨다. `-translate-y-1/2`는 absolute top이 줄의 **윗변**을 잡기 때문에
                  건다. 보정하지 않으면 선이 뱃지 높이의 절반만큼 내려앉아, 두 선 사이에 놓는
                  변화량 라벨과 겹친다(375px 코인 화면에서 실측). */}
              <div className="absolute inset-x-4 top-[50%] flex -translate-y-1/2 items-center">
                <span className="h-0 flex-1 border-t-2 border-dashed border-ink" />
                <span
                  className="ml-2 shrink-0 rounded-full bg-ink px-2.5 py-0.5 font-mono text-[11px] font-medium text-grid"
                  data-testid="asset-detail-chart-current"
                >
                  현재 {formatAmount(asset.avgPrice, priceOpts)}
                </span>
              </div>

              {simResult && diffTone ? (
                <>
                  <div
                    className={`absolute inset-x-4 flex -translate-y-1/2 items-center ${expectedTopClass}`}
                  >
                    <span
                      className={`h-0 flex-1 border-t-2 border-dashed ${TONE_LINE_CLASS[diffTone].border}`}
                    />
                    <span
                      className={`ml-2 shrink-0 rounded-full px-2.5 py-0.5 font-mono text-[11px] font-medium text-surface ${TONE_LINE_CLASS[diffTone].bg}`}
                      data-testid="asset-detail-chart-expected"
                    >
                      예상 {formatAmount(simResult.expectedAvgPrice, priceOpts)}
                    </span>
                  </div>
                  {/* 변화량은 두 선 사이 빈 칸에 놓는다 — 무엇과 무엇의 차이인지를 자리가 말한다.
                      색만으로 방향을 말하지 않으므로 부호(+/−)와 ▲▼를 함께 쓴다(§2-2). */}
                  <div
                    className={`absolute left-4 flex h-[30%] items-center gap-1.5 ${diffTopClass} ${TONE_CLASS[diffTone]}`}
                    data-testid="asset-detail-chart-diff"
                  >
                    {TONE_MARK[diffTone] ? (
                      <span aria-hidden="true" className="text-[10px] leading-none">
                        {TONE_MARK[diffTone]}
                      </span>
                    ) : null}
                    <span className="font-mono text-[11px] leading-none">{diffText}</span>
                  </div>
                </>
              ) : null}
            </div>
            <p className="mt-3 text-xs leading-5 text-ink-soft">
              시세 차트는 시세를 연동하면 이 자리에 채워집니다. 지금은 평단가 기준선만 그립니다.
            </p>
          </Card>
        )}

        {/* F006: 현금은 avgPrice가 항상 1 고정이라 "추가 매수 단가" 개념이 없다 — Card 자체를 렌더하지 않는다. */}
        {isCash ? null : (
          <Card title="물타기 시뮬레이터" testId="asset-detail-simulator">
            {/* 바로 아래 "보유 정보 수정" 카드와 생김새가 같은 폼이라, 무엇이 다른지를
                한 줄로 먼저 말한다 — 이쪽은 아무것도 바꾸지 않는다. */}
            <p className="text-xs leading-5 text-ink-soft">
              추가로 사면 평단가가 어디로 가는지 계산합니다. 결과는 저장되지 않습니다.
            </p>
            <div className="mt-4 flex max-w-md flex-col gap-4">
              <TextField
                label="추가 매수 단가"
                value={additionalPrice}
                onChange={handleAdditionalPriceChange}
                inputMode="decimal"
                error={additionalPriceError ? VALIDATION_MESSAGES[additionalPriceError] : null}
                testId="asset-detail-sim-price"
              />
              <TextField
                label="추가 매수 수량"
                value={additionalQuantity}
                onChange={handleAdditionalQuantityChange}
                inputMode="decimal"
                error={
                  additionalQuantityError ? VALIDATION_MESSAGES[additionalQuantityError] : null
                }
                testId="asset-detail-sim-quantity"
              />
              <div>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={handleSimulate}
                  testId="asset-detail-sim-calculate"
                >
                  계산
                </Button>
              </div>
              {/* 결과는 입력의 연장이 아니라 답이다 — 격자지 색 밴드로 떼어 놓으면
                  같은 흰 표면에 이어 붙은 세 번째·네 번째 줄로 읽히지 않는다. */}
              {simResult ? (
                <div className="rounded-control border border-rule bg-grid px-4 py-2">
                  <ReadRow
                    label="예상 평단가"
                    value={formatAmount(simResult.expectedAvgPrice, priceOpts)}
                    testId="asset-detail-sim-expected-price"
                  />
                  <ReadRow
                    label="예상 보유 수량"
                    value={formatQuantity(simResult.expectedQuantity)}
                    testId="asset-detail-sim-expected-quantity"
                  />
                </div>
              ) : null}
            </div>
          </Card>
        )}
      </div>

      {/* 고치는 묶음 — 여기서부터는 누르면 실제로 저장되거나 사라진다.
          위 묶음과 40px 떨어뜨려, 읽다가 그대로 손이 미끄러져 닿는 자리에 두지 않는다. */}
      <div className="mt-10">
        {/* F003: 수정 폼. 폼 제목·제출 버튼·완료 배너가 모두 "수정"이라는 한 단어를 쓴다
            (한 흐름 = 한 어휘, docs/DESIGN.md §6-1·§6-3). */}
        <Card title="보유 정보 수정" testId="asset-detail-edit">
          <form onSubmit={handleEditSubmit} noValidate className="flex max-w-md flex-col gap-4">
            <TextField
              label="수량"
              value={editQuantity}
              onChange={setEditQuantity}
              inputMode="decimal"
              required
              error={editQuantityError ? VALIDATION_MESSAGES[editQuantityError] : null}
              testId="asset-detail-edit-quantity"
            />
            {/* 사라진 칸의 자리에 사라진 이유를 남긴다 — 자산 등록 화면과 같은 밴드·같은 어법
                (docs/DESIGN.md §6-3). 등록 화면은 "등록합니다", 이 화면은 "수정합니다". */}
            {isCash ? (
              <p className="rounded-control bg-ink/5 px-3 py-3 text-xs leading-5 text-ink-soft">
                현금은 평단가 없이 수량만 수정합니다.
              </p>
            ) : (
              <TextField
                label="평단가"
                value={editAvgPrice ?? ''}
                onChange={setEditAvgPrice}
                inputMode="decimal"
                required
                error={editAvgPriceError ? VALIDATION_MESSAGES[editAvgPriceError] : null}
                testId="asset-detail-edit-avg-price"
              />
            )}
            <div>
              <Button type="submit" variant="primary" testId="asset-detail-edit-submit">
                수정
              </Button>
            </div>
          </form>
        </Card>

        {/* F004: 삭제. 되돌릴 수 없는 유일한 액션이라 카드에 넣지 않고, 실선 아래 따로 세운다 —
            수정 버튼 바로 밑에 붙여 두면 저장하려다 닿는다. 무엇을 위한 버튼인지 한 줄 먼저 말하고,
            무엇을 잃는지는 확인 팝업이 말한다(같은 경고를 두 번 하지 않는다). */}
        <div className="mt-8 border-t border-rule pt-6">
          <p className="text-[13px] leading-5 text-ink-soft">
            더 이상 보유하지 않거나 잘못 등록한 자산을 목록에서 지웁니다.
          </p>
          <div className="mt-3">
            <Button
              variant="danger"
              onClick={() => setDeleteDialogOpen(true)}
              testId="asset-detail-delete-open"
            >
              자산 삭제
            </Button>
          </div>
          {/* 제목에 종목명을 넣어 "어느 자산을 지우는지"를 팝업 안에서 다시 확인하게 한다.
              조사(을/를)를 붙이지 않는 이유는 종목명의 받침에 따라 달라지기 때문이다 —
              "삼성전자를"/"비트코인을"을 코드로 가르느니 명사구로 끊는다.
              description은 ROADMAP 결정 #4의 고정 문구다(변경 금지). */}
          <ConfirmDialog
            open={deleteDialogOpen}
            title={`${asset.name} 삭제`}
            description="보유 정보와 거래 이력이 함께 삭제되며 복구할 수 없습니다."
            confirmLabel="삭제"
            tone="danger"
            onConfirm={handleDeleteConfirm}
            onCancel={() => setDeleteDialogOpen(false)}
            testId="asset-detail-delete-dialog"
          />
        </div>
      </div>
    </div>
  );
}
