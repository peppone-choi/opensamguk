'use client';

import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type HTMLAttributes,
  type MouseEvent,
  type ReactNode,
  type RefObject,
} from 'react';

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * 열린 Modal 스택 하나(앱 전역). 배경 격리(inert · aria-hidden) · 스크롤 잠금은 각 Modal 이 따로 저장 · 복원하지 않고 이
 * 스택이 맡는다 — 맨 위 Modal 기준으로 다시 걸고, 마지막 Modal 이 닫힐 때만 다 푼다. 각자 저장 · 복원하면 겹쳐 열린 창이
 * 닫히는 순서에 따라 이미 격리된 상태를 「원래 상태」로 되돌려 화면 전체가 막힌 채 남았다(K10 2026-10-05 품질 측정 #1).
 * Esc · Tab 가두기도 맨 위 Modal 만 처리한다.
 */
interface StackEntry {
  readonly id: number;
  readonly overlay: HTMLElement;
}

const stack: StackEntry[] = [];
/** 스택이 격리한 요소의 원래 상태 — 처음 건드릴 때 한 번만 적는다. */
const original = new Map<HTMLElement, { readonly inert: boolean; readonly ariaHidden: string | null }>();
let originalOverflow = '';
let nextId = 0;

/** overlay 에서 body 까지 올라가며 만나는 형제들 — 맨 위 Modal 밖의 전부. */
function outsideOf(overlay: HTMLElement): Set<HTMLElement> {
  const outside = new Set<HTMLElement>();
  let branch: HTMLElement = overlay;
  while (branch.parentElement) {
    const parent: HTMLElement = branch.parentElement;
    for (const sibling of parent.children) {
      if (sibling !== branch && sibling instanceof HTMLElement) outside.add(sibling);
    }
    if (parent === document.body) break;
    branch = parent;
  }
  return outside;
}

function restore(element: HTMLElement, state: { readonly inert: boolean; readonly ariaHidden: string | null }): void {
  element.inert = state.inert;
  if (state.ariaHidden === null) element.removeAttribute('aria-hidden');
  else element.setAttribute('aria-hidden', state.ariaHidden);
}

/** 지금 스택에 맞게 격리를 다시 건다. 스택이 비면 원래 상태로 다 돌리고 스크롤도 푼다. */
function syncIsolation(): void {
  const top = stack[stack.length - 1];
  const want = top ? outsideOf(top.overlay) : new Set<HTMLElement>();
  for (const [element, state] of original) {
    if (want.has(element)) continue;
    restore(element, state);
    original.delete(element);
  }
  for (const element of want) {
    if (!original.has(element)) original.set(element, { inert: element.inert, ariaHidden: element.getAttribute('aria-hidden') });
    element.inert = true;
    element.setAttribute('aria-hidden', 'true');
  }
}

function push(entry: StackEntry): void {
  if (stack.length === 0) {
    originalOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
  }
  stack.push(entry);
  syncIsolation();
}

function remove(id: number): void {
  const index = stack.findIndex((entry) => entry.id === id);
  if (index < 0) return;
  stack.splice(index, 1);
  syncIsolation();
  if (stack.length === 0) document.body.style.overflow = originalOverflow;
}

function isTop(id: number): boolean {
  return stack[stack.length - 1]?.id === id;
}

export type ModalProps = Omit<HTMLAttributes<HTMLDivElement>, 'aria-label' | 'children'> & {
  readonly ariaLabel: string;
  readonly children: ReactNode;
  readonly closeOnBackdrop?: boolean;
  readonly closeOnEscape?: boolean;
  readonly initialFocusRef?: RefObject<HTMLElement | null>;
  readonly onClose: () => void;
  readonly overlayClassName?: string;
};

export function Modal({
  ariaLabel,
  children,
  className = '',
  closeOnBackdrop = true,
  closeOnEscape = true,
  initialFocusRef,
  onClose,
  overlayClassName = '',
  ...props
}: ModalProps) {
  const overlayRef = useRef<HTMLDivElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const [id] = useState(() => ++nextId);

  // 격리 · 스크롤 잠금은 커밋과 같은 시점(layout)에 걸고 푼다 — passive 정리면 닫힌 뒤 한 틈 늦어, 그 사이 새로 뜬
  // status 줄이 숨은 조상 밑에 있어 화면 낭독기 · 시험에서 안 보였다(K5 10-05, community-post).
  useLayoutEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;
    const requestedFocus = initialFocusRef?.current;
    const fallbackFocus = dialog?.querySelector<HTMLElement>(FOCUSABLE_SELECTOR) ?? dialog;
    const focusTarget = requestedFocus && !requestedFocus.matches(':disabled')
      ? requestedFocus
      : fallbackFocus;

    if (overlayRef.current) push({ id, overlay: overlayRef.current });
    focusTarget?.focus();

    return () => {
      remove(id);
      // 앞 초점이 사라졌으면(함께 닫힌 창 안) 남은 맨 위 창으로 돌린다.
      if (previouslyFocused?.isConnected) previouslyFocused.focus();
      else stack[stack.length - 1]?.overlay.querySelector<HTMLElement>('[role="dialog"]')?.focus();
    };
  }, [id, initialFocusRef]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (!isTop(id)) return; // 겹쳐 열렸으면 맨 위 창만 Esc · Tab 을 받는다 — Esc 한 번에 한 겹.
      if (event.key === 'Escape' && closeOnEscape) {
        // 안쪽(사유 시트 · 지도 고르기 등)이 이미 Esc 를 먹었으면(preventDefault) 대화 상자는 닫지 않는다 — Esc 한 번에 한 겹.
        if (event.defaultPrevented) return;
        onClose();
        return;
      }
      if (event.key !== 'Tab') return;

      const focusable = Array.from(
        dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR) ?? [],
      );
      if (focusable.length === 0) {
        event.preventDefault();
        dialogRef.current?.focus();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [id, closeOnEscape, onClose]);

  const handleBackdropClick = (event: MouseEvent<HTMLDivElement>) => {
    if (closeOnBackdrop && event.target === event.currentTarget) onClose();
  };

  return (
    <div
      ref={overlayRef}
      className={`os-modal-overlay ${overlayClassName}`.trim()}
      role="presentation"
      onClick={handleBackdropClick}
    >
      <div
        {...props}
        ref={dialogRef}
        aria-label={ariaLabel}
        aria-modal="true"
        className={`os-modal ${className}`.trim()}
        role="dialog"
        tabIndex={-1}
      >
        {children}
      </div>
    </div>
  );
}
