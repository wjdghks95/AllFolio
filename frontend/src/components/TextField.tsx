// 구조·동작(props/상태/핸들러/data-testid): senior-frontend / className·마크업·문구: ui-ux-designer
import Field from './Field';

export interface TextFieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  // 'number' 타입은 사용하지 않는다 — valueAsNumber가 double을 경유해 정밀도 규칙을
  // 깨뜨리고 스크롤로 값이 바뀔 수 있다. 숫자류 입력은 type='text' + inputMode로 처리한다.
  type?: 'text' | 'email' | 'password';
  inputMode?: 'text' | 'decimal' | 'numeric';
  placeholder?: string;
  autoComplete?: string;
  required?: boolean;
  error?: string | null;
  hint?: string;
  testId?: string;
}

const INPUT_BASE =
  'h-11 w-full rounded-control border border-rule bg-surface px-3 text-[15px] text-ink transition-colors duration-150 placeholder:text-ink-soft/55 hover:border-ink-soft aria-[invalid=true]:border-alarm aria-[invalid=true]:bg-alarm/4';

export default function TextField({
  label,
  value,
  onChange,
  type = 'text',
  inputMode,
  placeholder,
  autoComplete,
  required,
  error,
  hint,
  testId,
}: TextFieldProps) {
  // 숫자류 입력은 등폭(tabular)으로 — 자리수가 세로로 맞아야 8자리 소수를 눈으로 검산할 수 있다.
  const numeric = inputMode === 'decimal' || inputMode === 'numeric';

  return (
    <Field label={label} error={error} hint={hint}>
      {(control) => (
        <input
          {...control}
          type={type}
          inputMode={inputMode}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          autoComplete={autoComplete}
          required={required}
          data-testid={testId}
          className={`${INPUT_BASE} ${numeric ? 'text-right font-mono tracking-tight' : 'font-sans'}`}
        />
      )}
    </Field>
  );
}
