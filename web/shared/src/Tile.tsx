import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';

/** 명령 상태 4종(S1): ok=사용 가능 · need=대상 필요 · no=사용 불가(+이유) · sealed=봉인됨/정보 부족 */
export type TileState = 'ok' | 'need' | 'no' | 'sealed';

type TileBase = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'disabled' | 'children' | 'name'> & {
  readonly name: ReactNode;
  readonly cost?: ReactNode;
};
type TileEnabled = TileBase & { readonly state?: 'ok' | 'need'; readonly reason?: string; readonly disabled?: boolean };
type TileBlocked = TileBase & { readonly state: 'no' | 'sealed'; readonly reason: string };
export type TileProps = TileEnabled | TileBlocked;

/**
 * 명령 타일. 사용 불가·봉인은 반드시 reason 을 갖고 점선으로 남는다(숨기지 않는다).
 * 사유는 타일 안에 글자로 늘 보이고, 막힌 타일도 누를 수 있게 aria-disabled 로 둔다(ADR-LITE-049 (7)).
 * 처리 중(disabled)은 잠깐 막는 것이라 네이티브 disabled 를 쓴다.
 */
export const Tile = forwardRef<HTMLButtonElement, TileProps>(function Tile({ name, cost, state = 'ok', reason, className = '', type = 'button', ...props }, ref) {
  const { disabled: busy, onClick, ...rest } = props as typeof props & { disabled?: boolean };
  const blocked = state === 'no' || state === 'sealed';
  return (
    <button
      ref={ref}
      type={type}
      className={`os-tile os-tile--${state} ${className}`.trim()}
      disabled={busy === true}
      aria-disabled={blocked || busy === true ? true : undefined}
      data-state={state}
      {...rest}
      onClick={blocked ? (event) => event.preventDefault() : onClick}
    >
      <span className="os-tile__name">{name}</span>
      {cost != null && <span className="os-tile__cost">{cost}</span>}
      {reason != null && <span className="os-tile__reason">{reason}</span>}
    </button>
  );
});
