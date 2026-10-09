// Confirm and cancel the selected current reservation (docs/api/reservation-cancellation.md); no JSX.
//
// Every account, server, generation, actor, slot or refresh-key change starts a new epoch, including a return to the same values.
// Older reads and writes update only their own intent journal, never the currently displayed state.
// A ref mirrors pending state so a second confirmation in the same event loop is blocked before rendering.
// Use only the verified AuthProvider account; do not read or show its journal while authentication is pending.
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useAuthOptional } from '@/lib/auth-context';
import { selectedTabServer } from '@/lib/serverGameUrl';
import { announceTurnSlotsChanged, type TurnSlotView } from '@/lib/turn-slots';
import { removeUnsettledIntent, subscribeJournal } from '@/lib/command-flow/reservation-cancel-journal';
import {
  restoreIntent, runCheckResult, runFirstCancel, runReadback, runResend, wakeOnJournal, type CancelRun,
} from '@/lib/command-flow/reservation-cancel-flow';
import { IDLE, phaseBusy, type CancelPhase } from '@/lib/command-flow/reservation-cancel-state';
import {
  cancelDialog, cancelEntry, cancelStatus, type CancelAction, type CancelDialog, type CancelEntry, type CancelStatus,
} from '@/lib/command-flow/reservation-cancel-view';
import { acquireSlotMutation, slotMutationKey } from '@/lib/command-flow/slot-mutation-lock';

export interface ReservationCancelInput {
  readonly actor: number;
  readonly turnIdx: number;
  /** The selected slot from the latest verified 12-slot read; null while unread, failed or not yet chosen. */
  readonly row: Pick<TurnSlotView, 'turnIdx' | 'state' | 'revision' | 'name'> | null;
  readonly refreshKey?: number;
  /** Only when the server provides one. Never guessed. */
  readonly generation?: string | null;
}

export interface ReservationCancel {
  readonly entry: CancelEntry;
  readonly status: CancelStatus | null;
  readonly dialog: CancelDialog;
  /** A cancellation read · send for this slot is in flight (finite). */
  readonly busy: boolean;
  /** busy, or the confirmation is open — Esc · close belong to the dialog. */
  readonly blocking: boolean;
  readonly open: () => void;
  readonly confirm: () => void;
  readonly dismiss: () => void;
  readonly act: (action: CancelAction) => void;
}

// Never reused: a scope that comes back (A→B→A) gets a new epoch, so old work cannot land.
let lastEpoch = 0;
const NO_TOKENS = { result: 0, readback: 0 };

export function useReservationCancel({ actor, turnIdx, row, refreshKey = 0, generation = null }: ReservationCancelInput): ReservationCancel {
  // Verified only once AuthProvider has finished: a previous user retained while it re-checks login is not an account.
  // Losing verification is a scope change (new epoch); the old scope's record stays untouched.
  const auth = useAuthOptional();
  const user = auth && !auth.loading ? auth.user : null;
  const accountId = user && Number.isSafeInteger(user.id) && user.id > 0 ? user.id : null;
  const server = selectedTabServer();
  const key = JSON.stringify([accountId, server, generation, actor, turnIdx, refreshKey]);
  const [scope, setScope] = useState(() => ({ key, epoch: ++lastEpoch }));
  if (scope.key !== key) setScope({ key, epoch: ++lastEpoch });
  const epoch = scope.epoch;

  const [shown, setShown] = useState<{ readonly epoch: number; readonly phase: CancelPhase }>({ epoch: 0, phase: IDLE });
  const phase = shown.epoch === epoch ? shown.phase : IDLE;
  const [journal, setJournal] = useState<{ readonly epoch: number; readonly ok: boolean }>({ epoch: 0, ok: true });
  // Transient mirror read by async work and by same-tick handlers; the shown phase is state.
  const live = useRef<{ epoch: number; phase: CancelPhase }>({ epoch: 0, phase: IDLE });
  const tokens = useRef({ ...NO_TOKENS });
  useLayoutEffect(() => {
    live.current = { epoch, phase: IDLE };
    return () => { live.current = { epoch: 0, phase: IDLE }; };
  }, [epoch]);

  const runFor = useCallback((owner: number): CancelRun => ({
    alive: () => live.current.epoch === owner,
    current: () => (live.current.epoch === owner ? live.current.phase : IDLE),
    commit: (next) => {
      if (live.current.epoch !== owner || live.current.phase === next) return;
      live.current = { epoch: owner, phase: next };
      setShown({ epoch: owner, phase: next });
    },
    listChanged: announceTurnSlotsChanged,
    tokens: tokens.current,
  }), []);

  // Same-tab return: only after the account is verified, and only this scope's record. No DELETE here.
  useEffect(() => {
    if (accountId == null || !server) return undefined;
    const run = runFor(epoch);
    const restored = restoreIntent(run, { accountId, server, generation, actor, turnIdx });
    setJournal({ epoch, ok: restored !== 'broken' });
    return subscribeJournal((requestId) => wakeOnJournal(run, requestId));
  }, [epoch, accountId, server, generation, actor, turnIdx, runFor]);

  // A refreshed row with another revision (or no row) ends an open confirmation; a new one needs a new press.
  const rowRevision = row && row.turnIdx === turnIdx && row.state !== 'empty' ? row.revision : null;
  useEffect(() => {
    const run = runFor(epoch);
    const now = run.current();
    if (now.kind === 'confirming' && now.target.revision !== rowRevision) run.commit(IDLE);
  }, [epoch, rowRevision, runFor]);

  const journalOk = journal.epoch !== epoch || journal.ok;
  const entry = cancelEntry({ verified: accountId != null, server, journalOk, row: row?.turnIdx === turnIdx ? row : null, phase });
  const dialog = cancelDialog(phase, rowRevision);

  const open = () => {
    const run = runFor(epoch);
    const now = run.current();
    if (entry.kind !== 'available' || accountId == null || !server || !row?.revision || !row.name || row.turnIdx !== turnIdx) return;
    if (now.kind !== 'idle' && now.kind !== 'done' && now.kind !== 'blocked') return;
    run.commit({ kind: 'confirming', target: { accountId, server, generation, actor, turnIdx, revision: row.revision, sentence: row.name } });
  };

  const dismiss = () => {
    const run = runFor(epoch);
    const now = run.current().kind;
    if (now === 'confirming' || now === 'blocked' || now === 'done') run.commit(IDLE);
  };

  // One explicit confirmation → at most one first send, even for a double click or touch in one event loop.
  const confirm = () => {
    const run = runFor(epoch);
    const now = run.current();
    if (now.kind !== 'confirming' || now.target.revision !== rowRevision) return;
    const release = acquireSlotMutation(slotMutationKey(now.target.server, now.target.actor, now.target.turnIdx));
    if (!release) { run.commit({ kind: 'blocked', turnIdx: now.target.turnIdx, code: 'BUSY' }); return; }
    run.commit({ kind: 'preflight', target: now.target });
    void runFirstCancel(run, now.target).finally(release);
  };

  const act = (action: CancelAction) => {
    const run = runFor(epoch);
    const now = run.current();
    if (action === 'dismiss') { dismiss(); return; }
    if (action === 'retryReadback') { if (now.kind === 'readbackError') void runReadback(run, now.intent); return; }
    if (now.kind !== 'unknown' && now.kind !== 'retryable') return;
    if (now.checking) return;
    if (action === 'checkResult') { void runCheckResult(run, now.intent); return; }
    if (action === 'abandon') {
      // Only a clean WORLD_EXECUTING refusal may be dropped; an ambiguous send or a receipt (from any screen) keeps its UUID.
      if (now.kind === 'retryable' && removeUnsettledIntent(now.intent.accountId, now.intent.requestId)) run.commit(IDLE);
      return;
    }
    const release = acquireSlotMutation(slotMutationKey(now.intent.server, now.intent.actor, now.intent.turnIdx));
    if (!release) return;
    void runResend(run).finally(release);
  };

  const busy = phaseBusy(phase);
  return { entry, status: cancelStatus(phase), dialog, busy, blocking: busy || dialog.open, open, confirm, dismiss, act };
}
