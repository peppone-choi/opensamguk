'use client';

// Current reservation band, cancellation status and confirmation dialog; props only, no API calls or business hooks.
import { ConfirmDialog } from '@opensamguk/ui';
import {
  CANCEL_ACTION_LABEL, type CancelAction, type CancelDialog, type CancelEntry, type CancelStatus,
} from '@/lib/command-flow/reservation-cancel-view';
import styles from './CommandFlow.module.css';

export interface ReservedBandProps {
  /** 「01」 — the selected slot number. */
  readonly no: string;
  /** The stored command sentence of the selected slot; null for an empty slot. */
  readonly name: string | null;
  readonly entry: CancelEntry;
  readonly status: CancelStatus | null;
  readonly onCancel: () => void;
  readonly onAction: (action: CancelAction) => void;
}

const TONE_COLOR: Record<CancelStatus['tone'], string> = { info: 'var(--muted)', ok: 'var(--text)', warn: 'var(--rust-2)' };

export function ReservedBand({ no, name, entry, status, onCancel, onAction }: ReservedBandProps) {
  if (name == null && !status) return null;
  return (
    <div className={styles.band} data-testid="reservation-band">
      {name != null ? (
        <div data-testid="slot-reserved" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <span>{no}순 지금 예약: <strong>{name}</strong></span>
          <span style={{ color: 'var(--muted)', fontSize: 12 }}>다른 명령을 고르고 예약하면 바꿉니다.</span>
        </div>
      ) : null}
      {entry.kind === 'available' ? (
        <button type="button" className="os-button os-button--ghost" onClick={onCancel}>예약 취소</button>
      ) : null}
      {entry.kind === 'blocked' ? (
        <>
          <button type="button" className="os-button os-button--ghost os-button--disabled" aria-disabled="true"
            aria-describedby="reservation-cancel-why">예약 취소</button>
          <span id="reservation-cancel-why" style={{ color: 'var(--muted)', fontSize: 12 }}>{entry.reason}</span>
        </>
      ) : null}
      {status ? <CancelStatusLine status={status} onAction={onAction} /> : null}
    </div>
  );
}

function CancelStatusLine({ status, onAction }: { readonly status: CancelStatus; readonly onAction: (action: CancelAction) => void }) {
  return (
    <div role="status" aria-busy={status.busy || undefined} data-testid="reservation-cancel-status"
      style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8, width: '100%', minWidth: 0 }}>
      <span style={{ color: TONE_COLOR[status.tone], overflowWrap: 'anywhere' }}>{status.text}</span>
      {status.actions.map((action) => (
        <button
          key={action}
          type="button"
          className="os-button os-button--ghost"
          aria-disabled={status.busy || undefined}
          onClick={() => { if (!status.busy) onAction(action); }}
        >
          {CANCEL_ACTION_LABEL[action]}
        </button>
      ))}
    </div>
  );
}

export interface CancelConfirmProps {
  readonly dialog: CancelDialog;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
}

/** Shared confirmation: closing, Esc or 「그대로 두기」 before sending writes nothing. */
export function CancelConfirm({ dialog, onConfirm, onCancel }: CancelConfirmProps) {
  return (
    <ConfirmDialog
      open={dialog.open}
      title={dialog.title}
      message={dialog.message}
      confirmLabel="예약 취소"
      cancelLabel="그대로 두기"
      danger
      busy={dialog.busy}
      onConfirm={onConfirm}
      onCancel={onCancel}
    />
  );
}
