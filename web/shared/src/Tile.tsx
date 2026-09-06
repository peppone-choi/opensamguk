import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';

/** 명령 상태 4종(S1): ok=사용 가능 · need=대상 필요 · no=사용 불가(+이유) · sealed=봉인됨/정보 부족 */
export type TileState = 'ok' | 'need' | 'no' | 'sealed';

type TileBase = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'disabled' | 'children'> & {
  readonly name: ReactNode;
  readonly cost?: ReactNode;
};
type TileEnabled = TileBase & { readonly state?: 'ok' | 'need'; readonly reason?: string };
type TileBlocked = TileBase & { readonly state: 'no' | 'sealed'; readonly reason: string };
export type TileProps = TileEnabled | TileBlocked;

/** 명령 타일. 사용 불가·봉인은 반드시 reason 을 갖고 점선으로 남는다(숨기지 않는다). */
export const Tile = forwardRef<HTMLButtonElement, TileProps>(function Tile({ name, cost, state = 'ok', reason, className = '', type = 'button', ...props }, ref) {
  const blocked = state === 'no' || state === 'sealed';
  return (
    <button
      ref={ref}
      type={type}
      className={`os-tile os-tile--${state} ${className}`.trim()}
      disabled={blocked}
      aria-disabled={blocked ? true : undefined}
      title={blocked ? reason : undefined}
      data-state={state}
      {...props}
    >
      <span className="os-tile__name">{name}</span>
      {cost != null && <span className="os-tile__cost">{cost}</span>}
      {reason != null && <span className="os-tile__reason">{reason}</span>}
    </button>
  );
});
