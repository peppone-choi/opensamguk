'use client';

import type { ReactNode } from 'react';
import { ReasonTooltip } from '../ReasonTooltip';
import type { HelpTopicRef, InputAvailability } from './types';

export interface InputActionProps {
  /** 원장 inputId. 조작에 `data-input-id` 로 붙는다(K7 앵커). */
  readonly inputId: string;
  /** 서버가 준 가능 여부 한 행. `null` = 원장에 이 입력이 없음 → 아무것도 그리지 않는다. */
  readonly availability: InputAvailability | null;
  readonly label: ReactNode;
  /** AVAILABLE 에서만 불린다. */
  readonly onAct: () => void;
  readonly variant?: 'primary' | 'ghost' | 'danger';
  readonly block?: boolean;
  /** 보내는 중 — 누르기를 무시하고 aria-busy. 모양은 그대로다. */
  readonly busy?: boolean;
  /** 사유 시트 머리 · 「이렇게 하면 됩니다」 · 도움말 고리(K7). 서버 reason 은 availability 에서 온다. */
  readonly reasonTitle?: string;
  readonly recovery?: string;
  readonly recoveryDraft?: boolean;
  readonly helpTopic?: HelpTopicRef;
  readonly onHelp?: (topicId: string) => void;
  /** BLOCKED · NOT_DELIVERED 로 처음 그려질 때 사유 시트를 연 채로(제출이 서버에서 거절된 직후 — K6). */
  readonly reasonDefaultOpen?: boolean;
  readonly className?: string;
}

/** 서버가 사유 없이 BLOCKED 를 줬을 때의 글자. 사유를 지어내지 않는다. */
export const MISSING_REASON = '사유를 받지 못했습니다';
/** 원장 PLANNED(NOT_DELIVERED) — 보드 input_btn 그대로. */
export const NOT_DELIVERED_LABEL = '준비 중';

/**
 * 입력 단추(보드 InputAction) — 가능 여부를 코드에 박지 않고 서버 응답으로만 그린다.
 * AVAILABLE → 보통 단추 · BLOCKED → 점선 + 보이는 사유 꼬리표, 누르면 사유 시트 · NOT_DELIVERED → 점선 + 「준비 중」 ·
 * 행 없음(null) → 그리지 않음. 누를 영역은 늘 44, 비활성은 aria-disabled.
 */
export function InputAction({
  inputId,
  availability,
  label,
  onAct,
  variant = 'primary',
  block = false,
  busy = false,
  reasonTitle,
  recovery,
  recoveryDraft,
  helpTopic,
  onHelp,
  reasonDefaultOpen = false,
  className = '',
}: InputActionProps) {
  if (!availability) return null;
  const base = ['os-button', 'os-ia__button', block ? 'os-button--block' : ''];

  if (availability.status === 'AVAILABLE') {
    return (
      <button
        type="button"
        className={[...base, `os-button--${variant}`, className].filter(Boolean).join(' ')}
        data-input-id={inputId}
        data-input-status="AVAILABLE"
        aria-busy={busy || undefined}
        onClick={() => { if (!busy) onAct(); }}
      >
        {label}
      </button>
    );
  }

  const blocked = availability.status === 'BLOCKED';
  const reason = blocked ? (availability.reason?.trim() || MISSING_REASON) : NOT_DELIVERED_LABEL;
  return (
    <ReasonTooltip
      reason={reason}
      title={reasonTitle}
      code={availability.code}
      inputId={inputId}
      recovery={blocked ? recovery : undefined}
      recoveryDraft={recoveryDraft}
      helpTopic={helpTopic}
      onHelp={onHelp}
      defaultOpen={reasonDefaultOpen}
      block={block}
      className={['os-ia', className].filter(Boolean).join(' ')}
    >
      {(describedBy) => (
        <>
          <button
            type="button"
            className={[...base, 'os-button--ghost', 'os-button--disabled'].filter(Boolean).join(' ')}
            aria-disabled="true"
            aria-haspopup="dialog"
            aria-describedby={describedBy}
            data-input-id={inputId}
            data-input-status={availability.status}
          >
            {label}
          </button>
          <span className="os-ia__why" aria-hidden="true">{reason}</span>
        </>
      )}
    </ReasonTooltip>
  );
}
