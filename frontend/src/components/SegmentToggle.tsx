// 구조·동작: senior-frontend / className·마크업·문구: ui-ux-designer
// 고정된 소수 선택지 중 하나를 고르는 세그먼트 토글. 자산 유형(3지선다)·통화(2지선다)처럼
// 선택지 개수만 다르고 시각 규약은 같은 필드가 둘 이상 생겨 제네릭 컴포넌트로 일반화했다
// (기존 AssetTypeToggle의 확정 스타일을 그대로 옮김 — docs/DESIGN.md §6 컴포넌트 규약).
export interface SegmentToggleOption<T extends string> {
  value: T;
  label: string;
  // data-testid 접미사(kebab-case) — option.value(예: 'STOCK'/'USD')를 그대로 쓰면 대문자가
  // 섞여 네이밍 규칙(shrimp-rules.md)을 위반하므로 호출부가 명시적으로 kebab-case 값을 준다.
  testIdSuffix: string;
}

export interface SegmentToggleProps<T extends string> {
  value: T;
  options: readonly SegmentToggleOption<T>[];
  onChange: (value: T) => void;
  ariaLabelledBy: string;
  testId?: string;
}

export default function SegmentToggle<T extends string>({
  value,
  options,
  onChange,
  ariaLabelledBy,
  testId,
}: SegmentToggleProps<T>) {
  return (
    // 칸들이 테두리를 공유하는 한 줄짜리 장부 칸(-ml-px로 인접 테두리를 겹친다).
    // 낱개 알약으로 띄우면 입력 필드가 여러 개 늘어선 것처럼 보인다 — 이 그룹은 "한 필드"다.
    <div role="group" aria-labelledby={ariaLabelledBy} data-testid={testId} className="flex">
      {options.map((option) => {
        const selected = option.value === value;
        // 선택 상태는 색(잉크 채움)뿐 아니라 굵기(semibold)로도 구분한다 — 채움이 지워지는
        // 강제 색상(forced-colors) 모드나 색각 이상에서도 형태 신호가 남는다 (docs/DESIGN.md §2-3).
        // 포커스 링은 지우지 않고 z만 올려 인접 칸 위로 온전히 그려지게 한다.
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={selected}
            onClick={() => onChange(option.value)}
            data-testid={testId ? `${testId}-${option.testIdSuffix}` : undefined}
            className={`relative -ml-px h-11 flex-1 rounded-none border px-3 text-sm transition-colors duration-150 first:ml-0 first:rounded-l-control last:rounded-r-control focus-visible:z-10 ${
              selected
                ? 'z-10 border-ink bg-ink font-semibold text-grid'
                : 'border-rule bg-surface font-medium text-ink-soft hover:bg-grid hover:text-ink'
            }`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
