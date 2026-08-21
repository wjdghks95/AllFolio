// 구조·동작: senior-frontend / 시각 표현·문구: ui-ux-designer
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { ASSET_TYPES, type AssetType, type CreateAssetRequest } from '../api/types';
import {
  validateAvgPrice,
  validateName,
  validateQuantity,
  validateTicker,
  type ValidationCode,
} from '../lib/validation';
import { VALIDATION_MESSAGES } from '../lib/messages';
import SegmentToggle from '../components/SegmentToggle';
import TextField from '../components/TextField';
import Button from '../components/Button';
import type { Flash } from './PortfolioPage';

// 서버 enum을 그대로 보여주지 않는다 — 사용자가 쓰는 말로 옮긴다.
const ASSET_TYPE_LABEL: Record<AssetType, string> = {
  STOCK: '주식',
  COIN: '코인',
  CASH: '현금',
};
const ASSET_TYPE_TESTID_SUFFIX: Record<AssetType, string> = {
  STOCK: 'stock',
  COIN: 'coin',
  CASH: 'cash',
};
const ASSET_TYPE_OPTIONS = ASSET_TYPES.map((type) => ({
  value: type,
  label: ASSET_TYPE_LABEL[type],
  testIdSuffix: ASSET_TYPE_TESTID_SUFFIX[type],
}));

// PRD F001 "통화(KRW/USD) 선택" — USD 외 통화 확대는 명시적 비목표(PRD 「비목표」)라
// 자유 텍스트가 아닌 2지선다로 고정한다.
const CURRENCIES = ['KRW', 'USD'] as const;
type Currency = (typeof CURRENCIES)[number];
const CURRENCY_TESTID_SUFFIX: Record<Currency, string> = {
  KRW: 'krw',
  USD: 'usd',
};
const CURRENCY_OPTIONS = CURRENCIES.map((currency) => ({
  value: currency,
  label: currency,
  testIdSuffix: CURRENCY_TESTID_SUFFIX[currency],
}));

// 입력 예시는 유형에 따라 다르다 — 주식 칸에 "BTC"가 떠 있으면 예시로 읽히지 않는다.
// 수치 예시에 쉼표를 넣지 않는 것은 쉼표가 실제로는 입력할 수 없는 문자이기 때문이다
// (VALIDATION_MESSAGES.NUMBER_FORMAT). 예시는 그대로 따라 칠 수 있는 형태여야 한다.
const PLACEHOLDER: Record<
  AssetType,
  { ticker: string; name: string; quantity: string; avgPrice: string }
> = {
  STOCK: { ticker: '005930', name: '삼성전자', quantity: '10', avgPrice: '71500' },
  COIN: { ticker: 'BTC', name: '비트코인', quantity: '0.5', avgPrice: '82000000' },
  // CASH는 평단가 칸 자체가 렌더되지 않으므로 avgPrice 예시는 쓰이지 않는다.
  CASH: { ticker: 'KRW', name: '원화 예수금', quantity: '1000000', avgPrice: '' },
};

export default function AssetNewPage() {
  const [assetType, setAssetType] = useState<AssetType>('STOCK');
  const [ticker, setTicker] = useState('');
  const [name, setName] = useState('');
  const [currency, setCurrency] = useState<Currency>('KRW');
  const [quantity, setQuantity] = useState('');
  const [avgPrice, setAvgPrice] = useState<string | null>(null);

  const [tickerError, setTickerError] = useState<ValidationCode | null>(null);
  const [nameError, setNameError] = useState<ValidationCode | null>(null);
  const [quantityError, setQuantityError] = useState<ValidationCode | null>(null);
  const [avgPriceError, setAvgPriceError] = useState<ValidationCode | null>(null);

  const navigate = useNavigate();

  // CASH로 전환하면 서버가 avgPrice=1을 강제 삽입한다(평단가 개념 없음) — 이전 값이 남아있다가
  // 실수로 제출되는 것을 막기 위해 함께 리셋한다. 에러도 함께 지운다 — 그렇지 않으면 STOCK에서
  // 평단가 에러(예: PRICE_NOT_POSITIVE)를 띄운 채 CASH로 갔다가 STOCK으로 돌아왔을 때
  // 이미 지난 제출 시점의 에러가 그대로 다시 보인다.
  function handleAssetTypeChange(next: AssetType) {
    setAssetType(next);
    if (next === 'CASH') {
      setAvgPrice(null);
      setAvgPriceError(null);
    }
  }

  function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();

    const tickerCode = validateTicker(ticker);
    const nameCode = validateName(name);
    const quantityCode = validateQuantity(quantity);
    const avgPriceCode = validateAvgPrice(avgPrice, assetType);

    setTickerError(tickerCode);
    setNameError(nameCode);
    setQuantityError(quantityCode);
    setAvgPriceError(avgPriceCode);

    if (tickerCode || nameCode || quantityCode || avgPriceCode) return;

    // 등록 API(POST /v1/assets, Task 012)는 아직 없다 — 형태만 조립해 유효성을 확인하고,
    // 실제 전송 없이 성공 흐름으로 넘어간다.
    const request: CreateAssetRequest = {
      ticker,
      name,
      assetType,
      currency,
      quantity,
      avgPrice,
    };
    void request;

    const flash: Flash = { tone: 'success', message: '자산이 등록되었습니다.' };
    navigate('/portfolio', { state: { flash } });
  }

  const placeholder = PLACEHOLDER[assetType];

  return (
    <div>
      <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">자산 등록</h1>

      {/* 폼은 본문 폭(768px)을 다 쓰지 않는다 — 6자짜리 티커를 받으려고 728px 칸을 그리면
          라벨과 값 사이가 멀어지고 유형 토글이 텅 빈다. 입력 폭은 448px로 묶는다.
          6칸을 한 줄로 세우면 벽이 되므로 "무엇을(자산 정보) → 얼마나(보유 정보)" 두 묶음으로 나누고,
          묶음 사이만 32px로 벌린다(필드 사이 16px의 2배) — 간격의 대비가 곧 구조다.
          묶음 제목은 목록 화면의 장부 머리글과 같은 규격(text-sm semibold)이라
          13px ink-soft인 필드 라벨과 섞이지 않는다 (docs/DESIGN.md §6-3). */}
      <form onSubmit={handleSubmit} noValidate className="mt-8 max-w-md">
        <div className="flex flex-col gap-4">
          <h2 className="text-sm font-semibold tracking-tight text-ink">자산 정보</h2>

          <div className="flex flex-col gap-1.5">
            <span
              id="asset-new-type-label"
              className="text-[13px] font-medium tracking-tight text-ink-soft"
            >
              자산 유형
            </span>
            <SegmentToggle
              value={assetType}
              options={ASSET_TYPE_OPTIONS}
              onChange={handleAssetTypeChange}
              ariaLabelledBy="asset-new-type-label"
              testId="asset-new-type"
            />
          </div>
          <TextField
            label="티커"
            value={ticker}
            onChange={setTicker}
            placeholder={placeholder.ticker}
            required
            error={tickerError ? VALIDATION_MESSAGES[tickerError] : null}
            testId="asset-new-ticker"
          />
          <TextField
            label="종목명"
            value={name}
            onChange={setName}
            placeholder={placeholder.name}
            required
            error={nameError ? VALIDATION_MESSAGES[nameError] : null}
            testId="asset-new-name"
          />
          {/* PRD F001 "통화(KRW/USD) 선택" — 자유 입력이 아니라 자산 유형과 같은 세그먼트
              토글이다. 두 선택지뿐이라 CURRENCY_FORMAT 에러는 이 UI로는 재현되지 않는다. */}
          <div className="flex flex-col gap-1.5">
            <span
              id="asset-new-currency-label"
              className="text-[13px] font-medium tracking-tight text-ink-soft"
            >
              통화
            </span>
            <SegmentToggle
              value={currency}
              options={CURRENCY_OPTIONS}
              onChange={setCurrency}
              ariaLabelledBy="asset-new-currency-label"
              testId="asset-new-currency"
            />
          </div>
        </div>

        <div className="mt-8 flex flex-col gap-4">
          <h2 className="text-sm font-semibold tracking-tight text-ink">보유 정보</h2>

          <TextField
            label="수량"
            value={quantity}
            onChange={setQuantity}
            inputMode="decimal"
            placeholder={placeholder.quantity}
            required
            error={quantityError ? VALIDATION_MESSAGES[quantityError] : null}
            testId="asset-new-quantity"
          />
          {/* 평단가 칸은 CASH에서 사라진다. 빈자리를 그대로 두면 제출 버튼이 70px 위로 튀어 오르고,
              무엇보다 칸이 왜 없어졌는지 화면이 말해주지 않는다. 같은 자리에 이유 한 줄을 남긴다. */}
          {assetType !== 'CASH' ? (
            <TextField
              label="평단가"
              value={avgPrice ?? ''}
              onChange={setAvgPrice}
              inputMode="decimal"
              placeholder={placeholder.avgPrice}
              required
              error={avgPriceError ? VALIDATION_MESSAGES[avgPriceError] : null}
              testId="asset-new-avg-price"
            />
          ) : (
            <p className="rounded-control bg-ink/5 px-3 py-3 text-xs leading-5 text-ink-soft">
              현금은 평단가 없이 수량만 등록합니다.
            </p>
          )}
        </div>

        {/* 진입 버튼("자산 등록")·화면 제목·제출 버튼이 모두 같은 단어를 쓴다 — 한 흐름 = 한 어휘. */}
        <div className="mt-8 flex flex-col [&>button]:w-full">
          <Button type="submit" variant="primary" testId="asset-new-submit">
            자산 등록
          </Button>
        </div>
      </form>
    </div>
  );
}
