// 구조·동작: senior-frontend / 시각 표현·문구: ui-ux-designer
import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import type {
  Asset,
  AssetType,
  CandleBarResponse,
  PortfolioItem,
  SimulateAvgPriceResponse,
  UpdateHoldingRequest,
} from '../api/types';
import { deleteAsset, getAsset, getPortfolio, simulateAvgPrice, updateHolding } from '../api/assetApi';
import { fetchCandles } from '../api/candleApi';
import { ApiError } from '../api/authApi';
import { useAuth } from '../auth/useAuth';
import { useCandleStream } from '../hooks/useCandleStream';
import { ERROR_MESSAGES, VALIDATION_MESSAGES, messageForErrorCode } from '../lib/messages';
import {
  formatAmount,
  formatQuantity,
  formatSignedAmount,
  formatWeight,
  scaleFor,
  toEditableAmount,
  toEditableQuantity,
} from '../lib/money';
import { Dec, toScaledString } from '../lib/big';
import { CHART_LINE_COLOR } from '../lib/chartColors';
import {
  validateAdditionalQuantity,
  validateAvgPrice,
  validateQuantity,
  type ValidationCode,
} from '../lib/validation';
import Alert from '../components/Alert';
import Button from '../components/Button';
import Card from '../components/Card';
import CandlestickChart, { type PriceLineSpec } from '../components/CandlestickChart';
import ConfirmDialog from '../components/ConfirmDialog';
import SegmentToggle, { type SegmentToggleOption } from '../components/SegmentToggle';
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
// 캔들 차트 기간 선택지(F007, Task 028 프론트 7번째 하위 태스크). 백엔드 CandleInterval 전체
// 12종을 다 노출하지 않고 실용적인 하위집합만 보여준다(태스크 지시 — 전체 노출은 강제 아님).
// value는 GET /v1/assets/{id}/candles의 interval 쿼리 파라미터 그대로다(대소문자 무관하게
// 백엔드가 파싱하지만, 소문자로 통일해 넘긴다).
//
// 라벨은 캔들 하나가 담는 기간이다 — 화면에 보이는 구간의 길이가 아니다(그래서 필드 이름도
// "기간"이 아니라 "캔들 단위"다). 국내 증권·거래소 앱이 쓰는 어휘 그대로 `1분`·`일`·`주`·`월`·`년`
// 으로 두고, 375px에서 5칸이 한 줄에 들어가도록 두 글자를 넘기지 않는다(`1개월`·`1년`처럼 늘리면
// 칸당 35px 남짓한 글자 자리를 넘긴다 — 실측).
const CANDLE_INTERVAL_OPTIONS: Record<'STOCK' | 'COIN', readonly SegmentToggleOption<string>[]> = {
  STOCK: [
    { value: 'day', label: '일', testIdSuffix: 'day' },
    { value: 'week', label: '주', testIdSuffix: 'week' },
    { value: 'month', label: '월', testIdSuffix: 'month' },
    { value: 'year', label: '년', testIdSuffix: 'year' },
  ],
  COIN: [
    { value: 'minute1', label: '1분', testIdSuffix: 'minute1' },
    { value: 'day', label: '일', testIdSuffix: 'day' },
    { value: 'week', label: '주', testIdSuffix: 'week' },
    { value: 'month', label: '월', testIdSuffix: 'month' },
    { value: 'year', label: '년', testIdSuffix: 'year' },
  ],
};
const DEFAULT_CANDLE_INTERVAL = 'day';

// 자산 상세 조회 상태. GET /v1/assets/{id}(자산 자체)와 GET /v1/portfolio(비중 등 파생 필드의
// 출처)를 병렬 조회한다 — Promise.all이 아니라 Promise.allSettled를 쓰는 이유는 후자가 실패해도
// 전자만 성공했으면 화면은 정상 렌더돼야 하기 때문이다(파생 필드만 NULL_DISPLAY로 빠진다).
// asset이 null인 'ready'는 ASSET_NOT_FOUND 상태를 의미한다 — 기존 not-found 렌더 분기를 그대로 탄다.
// portfolioFetchFailed: GET /v1/portfolio 호출 자체가 실패했는지(응답에 대상 항목이 없는
// 것과는 다르다). true일 때만 상세 정보 카드에 "일부 정보를 불러오지 못했습니다" 안내를
// 추가로 보여준다 — cost 등 파생 필드가 "—"로 빠지는 원인을 화면이 오귀속하지 않게 한다.
type LoadState =
  | { status: 'loading' }
  | { status: 'error'; code: string }
  | {
      status: 'ready';
      asset: Asset | null;
      portfolioItem: PortfolioItem | null;
      portfolioFetchFailed: boolean;
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
  const location = useLocation();
  const auth = useAuth();

  const [state, setState] = useState<LoadState>({ status: 'loading' });

  useEffect(() => {
    if (!id) return;
    // PortfolioPage와 같은 cancelled 플래그 패턴(StrictMode 이중 mount 대비).
    let cancelled = false;
    Promise.allSettled([getAsset(id), getPortfolio()]).then(([assetResult, portfolioResult]) => {
      if (cancelled) return;

      if (assetResult.status === 'rejected') {
        const err = assetResult.reason;
        if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
          auth.logout();
          navigate('/login', { replace: true, state: { from: location } });
          return;
        }
        if (err instanceof ApiError && err.code === 'ASSET_NOT_FOUND') {
          setState({ status: 'ready', asset: null, portfolioItem: null, portfolioFetchFailed: false });
          return;
        }
        setState({ status: 'error', code: err instanceof ApiError ? err.code : 'NETWORK_ERROR' });
        return;
      }

      const fetchedAsset = assetResult.value;
      // getPortfolio 실패는 화면을 막지 않는다 — 파생 필드(취득원가·평가금액·평가손익·비중)만
      // NULL_DISPLAY로 빠지고 화면 자체는 정상 렌더된다. 다만 실패 자체는 portfolioFetchFailed로
      // 남겨 상세 정보 카드에 원인 안내를 보여준다(대상 항목이 없는 것과는 구분한다).
      const fetchedPortfolioItem =
        portfolioResult.status === 'fulfilled'
          ? (portfolioResult.value.items.find((item) => item.assetId === fetchedAsset.id) ?? null)
          : null;
      setState({
        status: 'ready',
        asset: fetchedAsset,
        portfolioItem: fetchedPortfolioItem,
        portfolioFetchFailed: portfolioResult.status === 'rejected',
      });
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const asset = state.status === 'ready' ? state.asset : null;
  const portfolioItem = state.status === 'ready' ? state.portfolioItem : null;
  const portfolioFetchFailed = state.status === 'ready' && state.portfolioFetchFailed;

  // 시뮬레이터 상태(F006)
  const [additionalPrice, setAdditionalPrice] = useState('');
  const [additionalQuantity, setAdditionalQuantity] = useState('');
  const [additionalPriceError, setAdditionalPriceError] = useState<ValidationCode | null>(null);
  const [additionalQuantityError, setAdditionalQuantityError] = useState<ValidationCode | null>(
    null,
  );
  const [simResult, setSimResult] = useState<SimulateAvgPriceResponse | null>(null);
  const [simSubmitting, setSimSubmitting] = useState(false);
  const [simSubmitError, setSimSubmitError] = useState<string | null>(null);

  // 수정 폼 상태(F003). version은 화면에 노출하지 않고 제출 조립에만 쓴다.
  const [editQuantity, setEditQuantity] = useState('');
  const [editAvgPrice, setEditAvgPrice] = useState<string | null>(null);
  const [editQuantityError, setEditQuantityError] = useState<ValidationCode | null>(null);
  const [editAvgPriceError, setEditAvgPriceError] = useState<ValidationCode | null>(null);
  const [editSubmitting, setEditSubmitting] = useState(false);
  const [editSubmitError, setEditSubmitError] = useState<string | null>(null);

  // 자산 조회가 완료된 시점에만 수정 폼의 초깃값을 채운다 — 마운트 시점엔 asset이 아직 없다.
  // 서버 값(asset.quantity/avgPrice)은 NUMERIC(28,8) 컬럼을 그대로 문자열화한 것이라 항상
  // 소수 8자리다(예: "20.00000000") — 주식·현금처럼 실제로는 정수인 값도 그대로 넣으면
  // 편집 입력란에 코인과 같은 소수점이 보인다. toEditable*로 무의미한 후행 0을 지우고 채운다.
  useEffect(() => {
    if (!asset) return;
    setEditQuantity(toEditableQuantity(asset.quantity));
    setEditAvgPrice(asset.assetType !== 'CASH' ? toEditableAmount(asset.avgPrice) : null);
  }, [asset]);

  // 삭제 확인 다이얼로그(F004). deleteSubmitting은 연타 방지용 — 확인 버튼을 disabled 처리해
  // 응답이 오기 전 두 번째 DELETE가 나가지 않게 한다(시뮬레이터의 simSubmitting과 같은 패턴).
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [deleteSubmitError, setDeleteSubmitError] = useState<string | null>(null);
  const [deleteSubmitting, setDeleteSubmitting] = useState(false);

  // 캔들 차트 상태(F007, Task 028 프론트 7번째 하위 태스크). REST 정적 조회만 다룬다 — SSE
  // 실시간 갱신은 다음 하위 태스크(8번) 범위다. 'day'는 COIN/STOCK 모두 유효한 공통 interval이라
  // 자산유형을 몰라도(로딩 중에도) 안전한 기본값으로 쓸 수 있다.
  const [candleInterval, setCandleInterval] = useState(DEFAULT_CANDLE_INTERVAL);
  const [candleState, setCandleState] = useState<
    | { status: 'loading' }
    | { status: 'error'; code: string }
    | { status: 'ready'; bars: CandleBarResponse[]; hasMoreHistory: boolean }
  >({ status: 'loading' });
  const [loadingMoreHistory, setLoadingMoreHistory] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);

  // 자산이 바뀌면(다른 자산 상세로 이동) 이전 자산 기준으로 고른 interval이 새 자산에서도
  // 유효하다는 보장이 없다(예: STOCK 상세에서 COIN 상세로) — 기본값으로 되돌린다.
  useEffect(() => {
    if (!asset) return;
    setCandleInterval(DEFAULT_CANDLE_INTERVAL);
  }, [asset]);

  // CASH는 캔들 조회 대상이 아니다(400 PRICE_NOT_APPLICABLE, ROADMAP Task 028) — 화면도 이미
  // 차트 카드 자체를 렌더하지 않으므로 호출하지 않는다. interval 전환 시에도 이 effect가 재실행돼
  // 새로 조회한다.
  useEffect(() => {
    if (!asset || asset.assetType === 'CASH') return;
    let cancelled = false;
    setCandleState({ status: 'loading' });
    setLoadMoreError(null);
    fetchCandles(asset.id, candleInterval)
      .then((res) => {
        if (cancelled) return;
        // 백엔드는 최신순(내림차순)으로 내려준다 — 차트는 과거→현재 오름차순을 기대하므로 뒤집는다.
        setCandleState({
          status: 'ready',
          bars: [...res.bars].reverse(),
          hasMoreHistory: res.hasMoreHistory,
        });
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
          auth.logout();
          navigate('/login', { replace: true, state: { from: location } });
          return;
        }
        setCandleState({
          status: 'error',
          code: err instanceof ApiError ? err.code : 'NETWORK_ERROR',
        });
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [asset?.id, asset?.assetType, candleInterval]);

  // SSE 실시간 갱신(F007, Task 028 프론트 8번째 하위 태스크). COIN 자산에서만 연결한다 —
  // STOCK/CASH는 이 스트림을 지원하지 않아 구독하면 400을 준다. 새로 들어온 bar 1건을
  // 오름차순 배열의 마지막 원소와 bucketStart로 비교해, 같으면 교체(같은 봉의 갱신)하고
  // 늦으면 추가(새 봉 시작)한다 — REST 초기 로드와 SSE 델타 사이의 병합 계약. 이르면(시간
  // 역행) lightweight-charts의 series.setData()가 오름차순을 요구해 throw하므로 조용히 버린다
  // (실제 재현된 적은 없는 이론적 방어 — code-reviewer Task 028 2차 검증 m9).
  const handleStreamedBar = useCallback((bar: CandleBarResponse) => {
    setCandleState((prev) => {
      if (prev.status !== 'ready') return prev;
      const last = prev.bars.at(-1);
      if (last && new Date(bar.bucketStart).getTime() < new Date(last.bucketStart).getTime()) {
        console.warn('SSE candle bar가 시간 역행해 무시함', bar.bucketStart, last.bucketStart);
        return prev;
      }
      const bars =
        last && last.bucketStart === bar.bucketStart
          ? [...prev.bars.slice(0, -1), bar]
          : [...prev.bars, bar];
      return { ...prev, bars };
    });
  }, []);
  useCandleStream(
    asset?.id ?? '',
    candleInterval,
    asset?.assetType === 'COIN',
    handleStreamedBar,
  );

  // 시뮬레이션 결과가 있을 때만 예상 평단선의 색(방향: 상승/하락/변화없음)을 계산한다.
  // 색은 손익의 좋고나쁨이 아니라 가격의 방향(상승=빨강/하락=파랑, 한국 증권 앱 관례)을 따른다.
  // diffText는 차트 아래 변화량 줄(§6-4 "두 선 사이에 변화량을 둔다")이 쓴다 — 두 평단선은
  // 캔버스 위에서 색으로만 갈리므로, 부호·▲▼가 붙은 이 한 줄이 색 없이 읽는 경로가 된다(§2-2).
  // 아래 candleChartPriceLines useMemo가 이 값을 필요로 해서, Hook은 이른 조건부 return(§ 아래
  // loading/error/not-found 분기) 이전에 호출돼야 한다(react-hooks/rules-of-hooks) — 그래서 이
  // 계산도 함께 여기로 올라와 있다. asset은 아직 null일 수 있어 여기서만 옵셔널 가드를 둔다.
  let diffTone: 'gain' | 'loss' | 'flat' | null = null;
  let diffText: string | null = null;
  if (asset && simResult) {
    const priceOpts = { currency: asset.currency, assetType: asset.assetType };
    const priceScale = scaleFor(priceOpts);
    const diff = toScaledString(
      Dec(simResult.expectedAvgPrice).minus(Dec(simResult.currentAvgPrice)),
      priceScale,
    );
    const signed = formatSignedAmount(diff, priceOpts);
    diffText = signed.text;
    diffTone = signed.tone === 'unknown' ? 'flat' : signed.tone;
  }

  // 캔들 차트에 그릴 평단선. 현재 평단선은 항상, 시뮬레이션 결과가 있을 때만 예상 평단선을
  // 추가로 겹쳐 그린다(기존 CSS 흉내 구현이 하던 것과 동일한 기능, 렌더링 수단만 캔들 차트로
  // 바뀌었다). title은 사람이 읽는 라벨(축에 그대로 표시), id는 테스트·디버깅용 안정 식별자다.
  // 뱃지 라벨은 `현재`/`예상` 두 단어로 고정한다(§6-4). 값은 같은 선이 가격축에 찍는 라벨이
  // 이미 말하므로, 선 위에까지 금액을 적으면 같은 숫자가 나란히 두 번 나오고 코인(소수 8자리)
  // 에서는 그 문자열이 플롯 폭을 가로지른다.
  // useMemo로 안정화한다 — 매 렌더 새 배열을 넘기면 CandlestickChart의 평단선 effect가 매 렌더
  // 지웠다 다시 긋기를 반복해, interval 전환으로 이 컴포넌트가 unmount될 때 disposed 에러가 날
  // 확률을 불필요하게 높인다(위 CandlestickChart.tsx의 disposedRef 수정과는 별개의 원인 축소).
  const candleChartPriceLines = useMemo<PriceLineSpec[]>(() => {
    if (!asset || asset.assetType === 'CASH') return [];
    const lines: PriceLineSpec[] = [
      { id: 'current', price: asset.avgPrice, color: CHART_LINE_COLOR.current, title: '현재' },
    ];
    if (simResult && diffTone) {
      lines.push({
        id: 'expected',
        price: simResult.expectedAvgPrice,
        color: CHART_LINE_COLOR[diffTone],
        title: '예상',
      });
    }
    return lines;
  }, [asset, simResult, diffTone]);

  if (state.status === 'loading') {
    return (
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">자산 상세</h1>
        <div className="mt-6">
          <Card testId="asset-detail-loading">
            <p className="py-6 text-center text-sm text-ink-soft">불러오는 중...</p>
          </Card>
        </div>
      </div>
    );
  }

  if (state.status === 'error') {
    return (
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">자산 상세</h1>
        <div className="mt-6">
          <Alert tone="error" testId="asset-detail-error">
            {messageForErrorCode(state.code)}
          </Alert>
        </div>
      </div>
    );
  }

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

  const version = asset.version;

  const handleSimulate = async () => {
    const priceCode = validateAvgPrice(additionalPrice, asset.assetType);
    const quantityCode = validateAdditionalQuantity(additionalQuantity);
    setAdditionalPriceError(priceCode);
    setAdditionalQuantityError(quantityCode);
    // 이전 결과가 남아 있었다면 handleAdditionalPriceChange/handleAdditionalQuantityChange가
    // 입력이 바뀌는 시점에 이미 지웠다 — "계산" 클릭 시점의 입력은 항상 그 이전 simResult가
    // 없는 상태이므로 여기서 다시 지울 것이 없다(docs/DESIGN.md §6-4는 그 무효화 규칙 자체를 말한다).
    if (priceCode || quantityCode) {
      return;
    }

    setSimSubmitting(true);
    setSimSubmitError(null);
    try {
      const result = await simulateAvgPrice({
        assetId: asset.id,
        additionalPrice,
        additionalQuantity,
      });
      setSimResult(result);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
        auth.logout();
        navigate('/login', { replace: true, state: { from: location } });
        return;
      }
      setSimSubmitError(messageForErrorCode(err instanceof ApiError ? err.code : 'NETWORK_ERROR'));
    } finally {
      setSimSubmitting(false);
    }
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

  const handleEditSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setEditSubmitError(null);

    const quantityCode = validateQuantity(editQuantity);
    const avgPriceCode = validateAvgPrice(editAvgPrice, asset.assetType);
    setEditQuantityError(quantityCode);
    setEditAvgPriceError(avgPriceCode);
    if (quantityCode || avgPriceCode) return;

    const request: UpdateHoldingRequest = {
      quantity: editQuantity,
      avgPrice: editAvgPrice,
      version,
    };

    setEditSubmitting(true);
    try {
      await updateHolding(asset.id, request);
      const flash: Flash = { tone: 'success', message: '자산이 수정되었습니다.' };
      navigate('/portfolio', { state: { flash } });
    } catch (err) {
      if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
        auth.logout();
        navigate('/login', { replace: true, state: { from: location } });
        return;
      }
      setEditSubmitError(messageForErrorCode(err instanceof ApiError ? err.code : 'NETWORK_ERROR'));
    } finally {
      setEditSubmitting(false);
    }
  };

  const handleDeleteConfirm = async () => {
    setDeleteSubmitting(true);
    try {
      await deleteAsset(asset.id);
      const flash: Flash = { tone: 'success', message: '자산이 삭제되었습니다.' };
      navigate('/portfolio', { state: { flash } });
    } catch (err) {
      if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
        auth.logout();
        navigate('/login', { replace: true, state: { from: location } });
        return;
      }
      setDeleteDialogOpen(false);
      setDeleteSubmitError(messageForErrorCode(err instanceof ApiError ? err.code : 'NETWORK_ERROR'));
    } finally {
      setDeleteSubmitting(false);
    }
  };

  // 과거 캔들 더 불러오기(페이징) — 현재 화면에 있는 가장 오래된 bar의 bucketStart를 그대로
  // before 커서로 넘긴다(포맷을 다시 만들 필요 없음, ROADMAP Task 028). 실패해도 기존 화면은
  // 무너지지 않고, 버튼 위 안내만 보여준 뒤 다시 누를 수 있게 둔다.
  const handleLoadMoreHistory = async () => {
    if (candleState.status !== 'ready' || !candleState.hasMoreHistory || candleState.bars.length === 0) {
      return;
    }
    const oldest = candleState.bars[0];
    setLoadingMoreHistory(true);
    setLoadMoreError(null);
    try {
      const res = await fetchCandles(asset.id, candleInterval, oldest.bucketStart);
      const olderAscending = [...res.bars].reverse();
      // 서버가 before 커서를 배타적으로 처리한다는 보장이 없어 프론트에서도 한 번 더 중복을 거른다.
      const existingStarts = new Set(candleState.bars.map((bar) => bar.bucketStart));
      const merged = [
        ...olderAscending.filter((bar) => !existingStarts.has(bar.bucketStart)),
        ...candleState.bars,
      ];
      setCandleState({ status: 'ready', bars: merged, hasMoreHistory: res.hasMoreHistory });
    } catch (err) {
      if (err instanceof ApiError && err.code === 'UNAUTHORIZED') {
        auth.logout();
        navigate('/login', { replace: true, state: { from: location } });
        return;
      }
      setLoadMoreError(messageForErrorCode(err instanceof ApiError ? err.code : 'NETWORK_ERROR'));
    } finally {
      setLoadingMoreHistory(false);
    }
  };

  const priceOpts = { currency: asset.currency, assetType: asset.assetType };
  // portfolioItem은 옵셔널이다(Minor 4) — 없으면 파생 필드는 전부 null로 두어 기존
  // NULL_DISPLAY("—") 규칙이 자연히 타게 한다.
  const cost = portfolioItem?.cost ?? null;
  const evaluationKrw = portfolioItem?.evaluationKrw ?? null;
  const unrealizedPnl = portfolioItem?.unrealizedPnl ?? null;
  const weight = portfolioItem?.weight ?? null;
  const pnl = formatSignedAmount(unrealizedPnl, { currency: 'KRW' });

  // 현금은 평단가 자체가 없다 — 서버가 넣어주는 avgPrice=1은 저장상의 약속이지 사용자가 읽을
  // 값이 아니다(ROADMAP 결정 #1). 평단가에 딸린 표시(평단가 행·취득원가 행·평단가 차트)는
  // 현금 화면에서 전부 내린다 (docs/DESIGN.md §6-4).
  const isCash = asset.assetType === 'CASH';

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
          {/* portfolioFetchFailed: GET /v1/portfolio 호출 자체가 실패했을 때만 보여준다.
              대상 항목이 그냥 없는 경우(§ 정상 케이스)와는 원인이 달라, 아래 취득원가 등
              파생 필드가 "—"인 이유를 여기서 먼저 밝힌다.
              색은 alarm(먹자주)을 쓴다 — loss(청)는 "하락"이라는 다른 축의 색이라 조회 실패가
              가격 하락으로 읽힌다(docs/DESIGN.md §2-3 hue 분리). 같은 카드 아래쪽에 들어오는
              "시세를 불러오지 못해…" 주석과도 같은 색이어야 한 종류의 사건으로 읽힌다. */}
          {portfolioFetchFailed ? (
            <p
              className="mb-3 text-xs leading-5 text-alarm"
              data-testid="asset-detail-portfolio-fetch-failed"
            >
              일부 정보를 불러오지 못했습니다. 새로고침 후 다시 시도하세요.
            </p>
          ) : null}
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

          {/* Phase 2에는 이 셋이 언제나 비어 있어 "시세를 연동하면 채워집니다"를 늘 달아 두었지만,
              시세가 들어온 지금(Task 023) 그 문장은 값이 채워진 화면에서 스스로를 부정한다
              (docs/DESIGN.md §9가 걷어내기로 예고한 문구). 지금 셋이 비어 있는 이유는 하나뿐이다 —
              이 종목의 시세를 못 가져왔다. 그 사실만, 그 경우에만 말한다.
              portfolioFetchFailed일 때는 카드 맨 위가 이미 원인을 말했으므로 겹쳐 말하지 않는다. */}
          {evaluationKrw === null && !portfolioFetchFailed ? (
            <p className="mt-3 text-xs leading-5 text-alarm" data-testid="asset-detail-unpriced-note">
              이 종목의 시세를 불러오지 못해 평가금액·평가손익·비중을 계산하지 못했습니다.
            </p>
          ) : null}
        </Card>

        {/* F007(Task 028 프론트 7번째 하위 태스크): REST로 받은 캔들을 CandlestickChart로 그리고,
            평단선(현재/예상)을 겹친다. SSE 실시간 갱신은 다음 하위 태스크(8번) 범위다.
            현금은 평단가가 없으므로 카드째 렌더하지 않는다. */}
        {isCash ? null : (
          <Card title="가격 흐름 차트" testId="asset-detail-chart">
            {/* 토글은 두 글자짜리 칸 4~5개짜리 한 필드다 — 카드 폭(768px)을 다 쓰면 칸 하나가
                150px로 벌어져 "일" 한 글자가 빈 밭 한가운데 뜬다. 카드 안 입력을 max-w-md로
                묶는 규칙(§6-4)과 같은 이유로, 이 필드는 내용에 맞춰 320px로 더 좁힌다. */}
            <div className="flex max-w-xs flex-col gap-1.5">
              <span
                id="asset-detail-chart-interval-label"
                className="text-[13px] font-medium tracking-tight text-ink-soft"
              >
                캔들 단위
              </span>
              <SegmentToggle
                value={candleInterval}
                options={CANDLE_INTERVAL_OPTIONS[asset.assetType as 'STOCK' | 'COIN']}
                onChange={setCandleInterval}
                ariaLabelledBy="asset-detail-chart-interval-label"
                testId="asset-detail-chart-interval"
              />
            </div>

            <div className="mt-3">
              {candleState.status === 'loading' ? (
                /* 캐시가 비어 있으면 첫 조회가 눈에 띄게 걸린다. 자리표시자는 차트가 놓일 자리를
                   같은 높이(24px 모듈 8/12칸)·같은 격자로 미리 깔아 두어 캔들이 도착할 때
                   카드가 튀지 않게 한다. 진행 표시는 검색 콤보박스와 같은 회전 고리 + 한 문장이다
                   — 펄스 애니메이션만으로 알리면 prefers-reduced-motion에서 신호가 통째로
                   사라진다(index.css가 모든 애니메이션을 0.01ms로 끈다). */
                <div
                  className="plot-grid flex h-48 items-center justify-center rounded-control border border-rule bg-surface sm:h-72"
                  data-testid="asset-detail-chart-loading"
                >
                  <p className="flex items-center gap-2 text-sm text-ink-soft">
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
                    시세를 불러오는 중…
                  </p>
                </div>
              ) : candleState.status === 'error' ? (
                <Alert tone="error" testId="asset-detail-chart-error">
                  {messageForErrorCode(candleState.code)}
                </Alert>
              ) : (
                <>
                  <CandlestickChart
                    bars={candleState.bars}
                    priceLines={candleChartPriceLines}
                    testId="asset-detail-chart-canvas"
                  />
                  {/* 두 평단선 사이의 변화량. 캔버스 위에서 현재선과 예상선을 가르는 것은 색
                      하나뿐이라(잉크 ↔ 빨강/파랑), 부호와 ▲▼가 붙은 이 한 줄이 색을 못 읽는
                      사용자의 읽는 경로가 된다(§2-2). 값은 캔들 밖 흰 표면에 두어 플롯 안에
                      글자를 얹지 않는다 — 플롯 위 라벨은 뱃지(현재/예상) 둘로 끝낸다(§6-4). */}
                  {simResult && diffTone && diffText ? (
                    <p
                      className="mt-2 text-xs leading-5 text-ink-soft"
                      data-testid="asset-detail-chart-diff"
                    >
                      예상 평단가 변화{' '}
                      <span className={`font-mono font-medium ${TONE_CLASS[diffTone]}`}>
                        {TONE_MARK[diffTone] ? (
                          <span aria-hidden="true" className="mr-0.5 text-[10px]">
                            {TONE_MARK[diffTone]}
                          </span>
                        ) : null}
                        {diffText}
                      </span>
                    </p>
                  ) : null}
                  {/* 실패 안내는 버튼 위에 둔다 — 이 버튼이 곧 재시도 수단이라, 읽고 나서
                      바로 아래 손이 닿는 자리에 눌러야 할 것이 있어야 한다(§6-3 배너의 자리). */}
                  {loadMoreError ? (
                    <div className="mt-3">
                      <Alert tone="error" testId="asset-detail-chart-load-more-error">
                        {loadMoreError}
                      </Alert>
                    </div>
                  ) : null}
                  {candleState.hasMoreHistory ? (
                    // 차트에서 과거는 왼쪽이다 — 버튼 라벨이 "무엇이 더 나오는지"를 말한다.
                    // 진행 중에는 라벨을 바꾼다(§6-1: 로그인 → 로그인 중). 회색으로 죽은
                    // 버튼만 남기면 눌렸는지 안 눌렸는지를 화면이 말하지 않는다.
                    <div className="mt-3 flex justify-center">
                      <Button
                        type="button"
                        variant="secondary"
                        onClick={handleLoadMoreHistory}
                        disabled={loadingMoreHistory}
                        testId="asset-detail-chart-load-more"
                      >
                        {loadingMoreHistory ? '불러오는 중' : '과거 구간 더 보기'}
                      </Button>
                    </div>
                  ) : null}
                </>
              )}
            </div>
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
                  disabled={simSubmitting}
                  testId="asset-detail-sim-calculate"
                >
                  계산
                </Button>
              </div>
              {simSubmitError ? (
                <Alert tone="error" testId="asset-detail-sim-error">
                  {simSubmitError}
                </Alert>
              ) : null}
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
          {editSubmitError ? (
            <div className="mb-4">
              <Alert tone="error" testId="asset-detail-edit-error">
                {editSubmitError}
              </Alert>
            </div>
          ) : null}
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
              <Button
                type="submit"
                variant="primary"
                disabled={editSubmitting}
                testId="asset-detail-edit-submit"
              >
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
              onClick={() => {
                setDeleteSubmitError(null);
                setDeleteDialogOpen(true);
              }}
              testId="asset-detail-delete-open"
            >
              자산 삭제
            </Button>
          </div>
          {deleteSubmitError ? (
            <div className="mt-3">
              <Alert tone="error" testId="asset-detail-delete-error">
                {deleteSubmitError}
              </Alert>
            </div>
          ) : null}
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
            confirmDisabled={deleteSubmitting}
            onConfirm={handleDeleteConfirm}
            onCancel={() => setDeleteDialogOpen(false)}
            testId="asset-detail-delete-dialog"
          />
        </div>
      </div>
    </div>
  );
}
