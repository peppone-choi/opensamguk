import { forwardRef, type ButtonHTMLAttributes } from 'react';

export type ButtonVariant = 'primary' | 'ghost' | 'danger';

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  readonly block?: boolean;
  readonly variant?: ButtonVariant;
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    block = false,
    className = '',
    type = 'button',
    variant = 'ghost',
    ...props
  },
  ref,
) {
  const classes = [
    'os-button',
    `os-button--${variant}`,
    block ? 'os-button--block' : '',
    className,
  ].filter(Boolean).join(' ');

  return <button ref={ref} className={classes} type={type} {...props} />;
});
