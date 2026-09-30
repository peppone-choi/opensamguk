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
import type { ReasonContent } from './parts/types';

export type ReasonTooltipProps = Omit<HTMLAttributes<HTMLSpanElement>, 'children' | 'title'> & ReasonContent & {
  /**
   * 감쌀 조작. 요소 하나면 `aria-describedby` 를 그 요소에 붙이고, 함수면 사유 id 를 받아 직접 붙인다
   * (단추 + 꼬리표처럼 둘 이상을 감쌀 때).
   */
  readonly children: ReactNode | ((describedById: string) => ReactNode);
  /** 너비를 채우는 조작(블록 버튼 · 목록 행)을 감쌀 때 */
  readonly block?: boolean;
  /** 「도움말 — …」을 누를 때. 없으면 `?help=<id>` 링크로 간다. */
  readonly onHelp?: (topicId: string) => void;
};

type DescribedChild = ReactElement<{ 'aria-describedby'?: string }>;

/**
 * 비활성 항목의 「왜 못 쓰는지」(보드 ReasonTooltip · K0 「사유 시트」) — 누르면 열린다(ADR-LITE-049 규칙 (7)).
 * 데스크톱은 말풍선(폭 320), 모바일(768px 미만)은 하단 시트다. 마우스 호버는 미리 보기일 뿐이다.
 * 담는 것: 머리 · 사유 · 「이렇게 하면 됩니다」 · 도움말 고리(K7). 감싼 조작은 네이티브 `disabled` 가 아니라
 * `aria-disabled` 여야 한다 — 네이티브 disabled 는 탭을 삼킨다. Escape · 바깥 누르기 · 닫기 단추로 닫힌다.
 */
export function ReasonTooltip({
  reason,
  title,
  code,
  inputId,
  recovery,
  recoveryDraft = false,
  helpTopic,
  onHelp,
  children,
  block = false,
  className = '',
  onClick,
  onKeyDown,
  ...props
}: ReasonTooltipProps) {
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

  let child: ReactNode;
  if (typeof children === 'function') child = children(id);
  else if (isValidElement(children)) {
    child = cloneElement(children as DescribedChild, {
      'aria-describedby': [(children as DescribedChild).props['aria-describedby'], id].filter(Boolean).join(' '),
    });
  } else child = children;

  const rich = Boolean(title || recovery || helpTopic);

  return (
    <span
      ref={root}
      className={['os-reason', block ? 'os-reason--block' : '', open ? 'os-reason--open' : '', className].filter(Boolean).join(' ')}
      data-reason-code={code}
      data-reason-input-id={inputId}
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
      <span
        ref={tip}
        id={id}
        role={pinned && rich ? 'dialog' : 'tooltip'}
        aria-label={pinned && rich ? (title ?? reason) : undefined}
        className={['os-reason__tip', rich ? 'os-reason__tip--rich' : ''].filter(Boolean).join(' ')}
        hidden={!open}
      >
        {title ? <span className="os-reason__title">{title}</span> : null}
        <span className="os-reason__body">{reason}</span>
        {recovery ? (
          <span className="os-reason__recovery">
            <span className="os-reason__recovery-head">
              이렇게 하면 됩니다{recoveryDraft ? <span className="os-chip os-reason__draft">초안</span> : null}
            </span>
            <span>{recovery}</span>
          </span>
        ) : null}
        {helpTopic ? (
          <a
            className="os-reason__help"
            href={`?help=${encodeURIComponent(helpTopic.id)}`}
            onClick={(event) => {
              if (!onHelp) return;
              event.preventDefault();
              onHelp(helpTopic.id);
              close();
            }}
          >
            도움말 — {helpTopic.title} →
          </a>
        ) : null}
      </span>
      <button ref={closer} type="button" className="os-reason__close" hidden={!pinned} onClick={close}>닫기</button>
    </span>
  );
}

/** K0 · 레인이 부르는 이름. 보드 부품 id 는 ReasonTooltip 이고 둘은 같은 것이다. */
export const ReasonSheet = ReasonTooltip;
export type ReasonSheetProps = ReasonTooltipProps;
