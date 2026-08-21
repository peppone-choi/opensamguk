'use client';

import {
  useEffect,
  useRef,
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

  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const previousOverflow = document.body.style.overflow;
    const dialog = dialogRef.current;
    const requestedFocus = initialFocusRef?.current;
    const fallbackFocus = dialog?.querySelector<HTMLElement>(FOCUSABLE_SELECTOR) ?? dialog;
    const focusTarget = requestedFocus && !requestedFocus.matches(':disabled')
      ? requestedFocus
      : fallbackFocus;

    const isolatedElements: Array<{
      element: HTMLElement;
      inert: boolean;
      ariaHidden: string | null;
    }> = [];
    let activeBranch: HTMLElement | null = overlayRef.current;
    while (activeBranch?.parentElement) {
      const parent = activeBranch.parentElement;
      for (const sibling of parent.children) {
        if (sibling === activeBranch || !(sibling instanceof HTMLElement)) continue;
        isolatedElements.push({
          element: sibling,
          inert: sibling.inert,
          ariaHidden: sibling.getAttribute('aria-hidden'),
        });
        sibling.inert = true;
        sibling.setAttribute('aria-hidden', 'true');
      }
      if (parent === document.body) break;
      activeBranch = parent;
    }

    document.body.style.overflow = 'hidden';
    focusTarget?.focus();

    return () => {
      document.body.style.overflow = previousOverflow;
      for (const { element, inert, ariaHidden } of isolatedElements) {
        element.inert = inert;
        if (ariaHidden === null) element.removeAttribute('aria-hidden');
        else element.setAttribute('aria-hidden', ariaHidden);
      }
      previouslyFocused?.focus();
    };
  }, [initialFocusRef]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && closeOnEscape) {
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
  }, [closeOnEscape, onClose]);

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
