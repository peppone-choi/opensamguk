import { forwardRef, type ButtonHTMLAttributes } from 'react';

export type ButtonVariant = 'primary' | 'ghost' | 'danger';
export type ButtonSize = 'md' | 'sm';

// 비활성 버튼은 사유를 반드시 동반한다(OPENSAM-113 표시 원칙 · S1 「이유 없는 회색 버튼 금지」).
// disabled 가 true 인 순간 reason 이 타입에서 필수가 된다. 흐리기 대신 점선 테두리 + title 툴팁으로 렌더한다.
type EnabledProps = { readonly disabled?: false; readonly reason?: string };
type DisabledProps = { readonly disabled: true; readonly reason: string };

export type ButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'disabled'> & {
  readonly block?: boolean;
  readonly variant?: ButtonVariant;
  readonly size?: ButtonSize;
} & (EnabledProps | DisabledProps);

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    block = false,
    className = '',
    type = 'button',
    variant = 'ghost',
    size = 'md',
    disabled,
    reason,
    title,
    ...props
  },
  ref,
) {
  const classes = [
    'os-button',
    `os-button--${variant}`,
    size === 'sm' ? 'os-button--sm' : '',
    block ? 'os-button--block' : '',
    disabled ? 'os-button--disabled' : '',
    className,
  ].filter(Boolean).join(' ');

  return (
    <button
      ref={ref}
      className={classes}
      type={type}
      disabled={disabled === true}
      aria-disabled={disabled === true ? true : undefined}
      title={disabled ? reason : title}
      data-reason={disabled ? reason : undefined}
      {...props}
    />
  );
});
