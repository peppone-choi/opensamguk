'use client';

import {
  cloneElement,
  isValidElement,
  useEffect,
  useId,
  useRef,
  useState,
  type HTMLAttributes,
  type MouseEvent,
  type PointerEvent,
  type ReactElement,
  type ReactNode,
} from 'react';

export type ReasonTooltipProps = Omit<HTMLAttributes<HTMLSpanElement>, 'children'> & {
  readonly reason: string;
  readonly children: ReactNode;
  /** 너비를 채우는 조작(블록 버튼 등)을 감쌀 때 */
  readonly block?: boolean;
};

type DescribedChild = ReactElement<{ 'aria-describedby'?: string }>;

/**
 * 비활성 항목의 「왜 못 쓰는지」 — 누르면 열린다(ADR-LITE-049 규칙 (7), 2026-09-26 개정).
 * 데스크톱은 말풍선, 모바일(768px 미만)은 하단 시트로 보인다. 마우스 호버는 미리 보기일 뿐이다.
 * 감싼 조작은 네이티브 `disabled` 가 아니라 `aria-disabled` 여야 한다 — 네이티브 disabled 는 탭을 삼킨다.
 * Escape·바깥 누르기·닫기 단추로 닫힌다.
 */
export function ReasonTooltip({ reason, children, block = false, className = '', onClick, onKeyDown, ...props }: ReasonTooltipProps) {
  const id = useId();
  const root = useRef<HTMLSpanElement>(null);
  const tip = useRef<HTMLSpanElement>(null);
  const closer = useRef<HTMLButtonElement>(null);
  const [pinned, setPinned] = useState(false);
  const [previewed, setPreviewed] = useState(false);
  const open = pinned || previewed;

  useEffect(() => {
    if (!pinned) return undefined;
    const closeOutside = (event: globalThis.PointerEvent | globalThis.MouseEvent) => {
      if (root.current && !root.current.contains(event.target as Node)) setPinned(false);
    };
    document.addEventListener('pointerdown', closeOutside);
    return () => document.removeEventListener('pointerdown', closeOutside);
  }, [pinned]);

  const close = () => { setPinned(false); setPreviewed(false); };
  const preview = (show: boolean) => (event: PointerEvent<HTMLSpanElement>) => {
    if (event.pointerType === 'mouse') setPreviewed(show);
  };

  const child = isValidElement(children)
    ? cloneElement(children as DescribedChild, {
      'aria-describedby': [(children as DescribedChild).props['aria-describedby'], id].filter(Boolean).join(' '),
    })
    : children;

  return (
    <span
      ref={root}
      className={['os-reason', block ? 'os-reason--block' : '', open ? 'os-reason--open' : '', className].filter(Boolean).join(' ')}
      onPointerEnter={preview(true)}
      onPointerLeave={preview(false)}
      onClick={(event: MouseEvent<HTMLSpanElement>) => {
        onClick?.(event);
        const target = event.target as Node;
        if (tip.current?.contains(target) || closer.current?.contains(target)) return;
        setPinned((was) => !was);
      }}
      onKeyDown={(event) => {
        onKeyDown?.(event);
        if (event.key === 'Escape') close();
      }}
      {...props}
    >
      {child}
      <span className="os-reason__backdrop" hidden={!pinned} aria-hidden="true" />
      <span ref={tip} id={id} role="tooltip" className="os-reason__tip" hidden={!open}>{reason}</span>
      <button ref={closer} type="button" className="os-reason__close" hidden={!pinned} onClick={close}>닫기</button>
    </span>
  );
}
