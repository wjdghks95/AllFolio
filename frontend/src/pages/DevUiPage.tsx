// 개발 전용 컴포넌트 쇼케이스. Task 018(실 API 연동 착수) 시점에 제거될 예정이다.
// 구조·동작(props/상태/핸들러/data-testid): senior-frontend / className·마크업·문구: ui-ux-designer
import { useState, type ReactNode } from 'react';
import Alert from '../components/Alert';
import Button from '../components/Button';
import Card from '../components/Card';
import ConfirmDialog from '../components/ConfirmDialog';
import TextField from '../components/TextField';
import { formatAmount, formatQuantity, formatSignedAmount, formatWeight } from '../lib/money';
import { simulateAvgPrice } from '../lib/simulate';

const simulateGoldenCase = simulateAvgPrice({
  currentQuantity: '10',
  currentAvgPrice: '60000',
  additionalQuantity: '5',
  additionalPrice: '55000',
  assetType: 'STOCK',
  currency: 'KRW',
});

// 손익 톤 → 색. 색은 부호(+/−)와 방향 표식을 보조할 뿐, 단독으로 의미를 전달하지 않는다.
// 상승 빨강 / 하락 파랑은 한국 증권·거래소 앱 관례를 따른 것이다 (docs/DESIGN.md 참조).
const TONE_CLASS: Record<'gain' | 'loss' | 'flat' | 'unknown', string> = {
  gain: 'text-gain',
  loss: 'text-loss',
  flat: 'text-ink',
  unknown: 'text-ink-soft',
};

const TONE_MARK: Record<'gain' | 'loss' | 'flat' | 'unknown', string> = {
  gain: '▲',
  loss: '▼',
  flat: '—',
  unknown: '',
};

function Section({
  eyebrow,
  title,
  children,
}: {
  eyebrow: string;
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="mt-10">
      <div className="mb-3 flex items-baseline gap-2.5">
        <span className="font-mono text-[11px] tracking-[0.18em] text-ink-soft uppercase">
          {eyebrow}
        </span>
        <span aria-hidden="true" className="h-px flex-1 bg-rule" />
      </div>
      <h2 className="mb-4 text-lg font-semibold tracking-tight text-ink">{title}</h2>
      {children}
    </section>
  );
}

// 라벨과 값을 점선 리더로 잇고, 값은 등폭으로 같은 우측 기준선에 정렬한다.
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

export default function DevUiPage() {
  const [textValue, setTextValue] = useState('');
  const [dialogOpen, setDialogOpen] = useState(false);

  const gain = formatSignedAmount('100', { currency: 'KRW' });
  const loss = formatSignedAmount('-100', { currency: 'KRW' });

  return (
    <div className="min-h-screen">
      <header className="border-b border-rule bg-surface/80 backdrop-blur">
        <div className="mx-auto flex max-w-3xl items-center justify-between px-5 py-4">
          <span className="text-base font-bold tracking-tight text-ink">AllFolio</span>
          <span className="font-mono text-[11px] tracking-[0.18em] text-ink-soft uppercase">
            Dev / UI
          </span>
        </div>
      </header>

      <div className="mx-auto max-w-3xl px-5 pb-20">
        <div className="pt-8">
          <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">
            디자인 시스템 쇼케이스
          </h1>
          <p className="mt-2 max-w-prose text-sm leading-6 text-ink-soft">
            공통 컴포넌트 6종의 모든 상태와 금액 포맷터·평단가 시뮬레이터 출력입니다. 개발
            빌드에서만 열립니다.
          </p>
        </div>

        <Section eyebrow="Signature" title="평단선">
          <Card testId="dev-card-signature">
            <p className="text-sm leading-6 text-ink-soft">
              시세 위에 그어지는 내 평단가 수평선 — 이 서비스가 답하는 단 하나의 질문(
              <span className="text-ink">“추가로 사면 내 평단은 어디로 가는가”</span>)을 그대로
              그린 요소입니다. 점선은 화면 전체에서 이 기준선에만 씁니다.
            </p>

            {/* 값 라벨은 차트 가격축 관례대로 선의 오른쪽 끝에 붙는다. */}
            <div className="plot-grid relative mt-5 h-40 rounded-control border border-rule bg-grid px-4">
              <div className="absolute inset-x-4 top-[32%] flex items-center">
                <span className="h-0 flex-1 border-t-2 border-dashed border-ink" />
                <span className="ml-2 shrink-0 rounded-full bg-ink px-2.5 py-0.5 font-mono text-[11px] font-medium text-grid">
                  현재 60,000
                </span>
              </div>

              <div className="absolute inset-x-4 top-[68%] flex items-center">
                <span className="h-0 flex-1 border-t-2 border-dashed border-loss" />
                <span className="ml-2 shrink-0 rounded-full bg-loss px-2.5 py-0.5 font-mono text-[11px] font-medium text-white">
                  예상 58,333
                </span>
              </div>

              <div className="absolute top-[32%] left-4 flex h-[36%] items-center gap-1.5 text-loss">
                <span aria-hidden="true" className="text-[10px] leading-none">
                  ▼
                </span>
                <span className="font-mono text-[11px] leading-none">−1,667</span>
              </div>
            </div>
            <p className="mt-3 text-xs text-ink-soft">
              위 도형은 좌표가 고정된 예시입니다. 실제 차트는 Task 011(F007)에서 연결합니다.
            </p>
          </Card>
        </Section>

        <Section eyebrow="Button" title="버튼">
          <div className="flex flex-wrap gap-2.5">
            <Button variant="primary" testId="dev-button-primary">
              Primary
            </Button>
            <Button variant="secondary" testId="dev-button-secondary">
              Secondary
            </Button>
            <Button variant="danger" testId="dev-button-danger">
              Danger
            </Button>
          </div>
          <div className="mt-2.5 flex flex-wrap gap-2.5">
            <Button variant="primary" disabled testId="dev-button-primary-disabled">
              Primary (disabled)
            </Button>
            <Button variant="secondary" disabled testId="dev-button-secondary-disabled">
              Secondary (disabled)
            </Button>
            <Button variant="danger" disabled testId="dev-button-danger-disabled">
              Danger (disabled)
            </Button>
          </div>
        </Section>

        <Section eyebrow="TextField" title="입력 필드">
          <Card testId="dev-card-textfield">
            <div className="flex flex-col gap-5">
              <TextField
                label="정상 상태"
                value={textValue}
                onChange={setTextValue}
                hint="힌트 텍스트"
                testId="dev-textfield-normal"
              />
              <TextField
                label="에러 상태"
                value={textValue}
                onChange={setTextValue}
                error="이메일을 name@example.com 형식으로 입력하세요."
                testId="dev-textfield-error"
              />
              <TextField
                label="숫자류 입력 (inputMode=decimal)"
                value={textValue}
                onChange={setTextValue}
                inputMode="decimal"
                testId="dev-textfield-decimal"
              />
            </div>
          </Card>
        </Section>

        <Section eyebrow="Alert" title="알림">
          <div className="flex flex-col gap-2.5">
            <Alert tone="error" testId="dev-alert-error">
              이메일 또는 비밀번호가 일치하지 않습니다.
            </Alert>
            <Alert tone="success" testId="dev-alert-success">
              자산을 등록했습니다.
            </Alert>
            <Alert tone="info" testId="dev-alert-info">
              평가금액과 비중은 시세 연동 이후 표시됩니다.
            </Alert>
          </div>
        </Section>

        <Section eyebrow="Card" title="카드">
          <div className="flex flex-col gap-4">
            <Card title="제목이 있는 카드" testId="dev-card-with-title">
              제목이 있으면 머리글에 구분선이 생기고, 제목과 본문이 aria-labelledby로 연결됩니다.
            </Card>
            <Card testId="dev-card-without-title">
              제목이 없는 카드입니다. 본문 여백만 유지합니다.
            </Card>
          </div>
        </Section>

        <Section eyebrow="ConfirmDialog" title="확인 다이얼로그">
          <Button onClick={() => setDialogOpen(true)} testId="dev-confirm-open-trigger">
            삭제 확인 다이얼로그 열기
          </Button>
          <ConfirmDialog
            open={dialogOpen}
            title="삼성전자를 삭제할까요?"
            description="보유 정보와 거래 이력이 함께 삭제되며 복구할 수 없습니다."
            confirmLabel="삭제"
            tone="danger"
            onConfirm={() => setDialogOpen(false)}
            onCancel={() => setDialogOpen(false)}
            testId="dev-confirm-dialog"
          />
        </Section>

        <Section eyebrow="Formatter" title="금액 표기 (money.ts)">
          <Card testId="dev-card-formatter">
            <ReadRow
              label="KRW (소수 0자리)"
              value={formatAmount('6476733.92', { currency: 'KRW' })}
              testId="dev-format-krw"
            />
            <ReadRow
              label="USD (소수 4자리)"
              value={formatAmount('182.5', { currency: 'USD' })}
              testId="dev-format-usd"
            />
            <ReadRow
              label="COIN (소수 8자리)"
              value={formatAmount('0.05123456', { currency: 'KRW', assetType: 'COIN' })}
              testId="dev-format-coin"
            />
            <ReadRow
              label="값 없음"
              value={formatAmount(null, { currency: 'KRW' })}
              valueClass="text-ink-soft"
              testId="dev-format-null"
            />
            <ReadRow
              label="수량 (뒤 0 제거)"
              value={formatQuantity('10.00000000')}
              testId="dev-format-quantity"
            />
            <ReadRow label="비중" value={formatWeight('12.345')} testId="dev-format-weight" />
            <ReadRow
              label="비중 없음"
              value={formatWeight(null)}
              valueClass="text-ink-soft"
              testId="dev-format-weight-null"
            />
            <ReadRow
              label="손익 (이익)"
              value={
                <>
                  <span aria-hidden="true" className="mr-1 text-[10px]">
                    {TONE_MARK[gain.tone]}
                  </span>
                  {gain.text}
                </>
              }
              valueClass={TONE_CLASS[gain.tone]}
              testId="dev-format-signed-gain"
            />
            <ReadRow
              label="손익 (손실)"
              value={
                <>
                  <span aria-hidden="true" className="mr-1 text-[10px]">
                    {TONE_MARK[loss.tone]}
                  </span>
                  {loss.text}
                </>
              }
              valueClass={TONE_CLASS[loss.tone]}
              testId="dev-format-signed-loss"
            />
            <p className="mt-3 border-t border-rule pt-3 text-xs leading-5 text-ink-soft">
              이익은 빨강, 손실은 파랑입니다(한국 증권 앱 관례). 색을 못 읽어도 값 앞의 부호와
              방향 표식으로 구분됩니다.
            </p>
          </Card>
        </Section>

        <Section eyebrow="Simulator" title="예상 평단가 (simulate.ts)">
          <Card testId="dev-card-simulator">
            <p className="text-sm leading-6 text-ink-soft">
              60,000원 10주 보유 중 55,000원에 5주를 더 사는 경우 (ROADMAP 골든 케이스)
            </p>
            <div className="mt-3">
              <ReadRow
                label="현재 평단가"
                value={simulateGoldenCase.currentAvgPrice}
                testId="dev-simulate-current"
              />
              <ReadRow
                label="예상 평단가"
                value={simulateGoldenCase.expectedAvgPrice}
                valueClass="text-loss"
                testId="dev-simulate-expected"
              />
              <ReadRow
                label="예상 보유 수량"
                value={simulateGoldenCase.expectedQuantity}
                testId="dev-simulate-quantity"
              />
            </div>
            <p className="mt-3 border-t border-rule pt-3 text-xs leading-5 text-ink-soft">
              simulate.ts가 돌려준 원본 문자열입니다. 화면에 붙일 때는 money.ts 포맷터를 한 번 더
              거칩니다.
            </p>
          </Card>
        </Section>
      </div>
    </div>
  );
}
