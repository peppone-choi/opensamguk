'use client';

// E04 출사 — one owner of the screen's client data: candidates, the owned 12-slot ring, the pick and the receipt.
//
// Ownership is the exact (selected server, generalId) pair. Every owner change opens a new epoch during render,
// so the first commit for B never shows A's data and A→B→A never re-accepts a response started under the first A.
// Receipts live outside React, keyed by owner, so a parent refresh or remount keeps duplicate protection.
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import { fromReservedCommands, type TurnSlotsLoad } from '@/lib/turn-slots';
import {
  EnlistHttpError, EnlistScopeChanged, readEnlistOptions, readEnlistSlots, selectedEnlistServer, sendEnlist,
  type EnlistOption, type EnlistSlotsRead,
} from '@/lib/api/enlist-slots';
import {
  calendarKey, candidateKey, effectiveSlot, enlistAvailability, reservationMatches, withCalendar, type EnlistReceipt,
} from '@/lib/enlist/enlist-view';
import type { EnlistmentOptionsResponse } from '@/lib/types';

export type EnlistMode = EnlistOption['mode'];
type Read<T> = { readonly epoch: number; readonly gen: number } & ({ readonly state: 'ready'; readonly data: T } | { readonly state: 'error'; readonly error: Error });
type Choice = { readonly epoch: number; readonly mode: EnlistMode; readonly candidate: string | null; readonly slot: number | null };

const receipts = new Map<string, EnlistReceipt>();
const listeners = new Set<() => void>();
let receiptSeq = 0;
function putReceipt(owner: string, receipt: EnlistReceipt | null) {
  if (receipt) receipts.set(owner, receipt); else receipts.delete(owner);
  for (const listener of [...listeners]) listener();
}
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };
const asError = (error: unknown, fallback: string) => error instanceof Error ? error : new Error(fallback);
export const isEnlistDenied = (error: unknown): error is EnlistHttpError =>
  error instanceof EnlistHttpError && (error.status === 401 || error.status === 403);

/** Test seam: receipts are tab-lifetime state. */
export function resetEnlistReceipts() { receipts.clear(); for (const listener of [...listeners]) listener(); }

export function useEnlist(generalId: number, onRefresh: () => void) {
  const server = selectedEnlistServer();
  const owner = JSON.stringify([server, generalId]);
  const scope = useRef({ owner, epoch: 0 });
  if (scope.current.owner !== owner) scope.current = { owner, epoch: scope.current.epoch + 1 };
  const epoch = scope.current.epoch;
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  const [gen, setGen] = useState(0);
  const reload = useCallback(() => setGen(g => g + 1), []);
  useTurnRefresh(reload);
  const [optionsRead, setOptions] = useState<Read<EnlistmentOptionsResponse> | null>(null);
  const [slotsRead, setSlots] = useState<Read<EnlistSlotsRead> | null>(null);
  const [failure, setFailure] = useState<{ epoch: number; error: Error } | null>(null);
  const [pickState, setPick] = useState<Choice>({ epoch, mode: 'NATION', candidate: null, slot: null });

  useEffect(() => {
    const controller = new AbortController();
    const at = scope.current.epoch;
    const live = () => !controller.signal.aborted && scope.current.epoch === at;
    readEnlistOptions(generalId, controller.signal).then(
      data => { if (live()) setOptions({ epoch: at, gen, state: 'ready', data }); },
      error => { if (live()) setOptions({ epoch: at, gen, state: 'error', error: asError(error, '출사 정보를 불러오지 못했습니다.') }); });
    readEnlistSlots(generalId, controller.signal).then(
      data => { if (live()) setSlots({ epoch: at, gen, state: 'ready', data }); },
      error => { if (live()) setSlots({ epoch: at, gen, state: 'error', error: asError(error, '12순을 불러오지 못했습니다.') }); });
    return () => controller.abort();
  }, [owner, generalId, gen]);

  // Anything tagged with an older epoch is invisible from the first render of the new owner.
  const options = optionsRead?.epoch === epoch ? optionsRead : null;
  const slots = slotsRead?.epoch === epoch ? slotsRead : null;
  const pick: Choice = pickState.epoch === epoch ? pickState : { epoch, mode: 'NATION', candidate: null, slot: null };
  const submitFailure = failure?.epoch === epoch ? failure.error : null;
  const receipt = useSyncExternalStore(subscribe, () => receipts.get(owner) ?? null, () => null);

  const data = options?.state === 'ready' ? options.data : null;
  const ring = slots?.state === 'ready' ? slots.data : null;
  const slotViews = useMemo(() => ring
    ? withCalendar(fromReservedCommands({ result: true, generalId: ring.generalId, slots: [...ring.slots] }), ring.calendar)
    : null, [ring]);
  const slotsLoad: TurnSlotsLoad = slots?.state === 'error' ? { state: 'error', message: slots.error.message }
    : slotViews ? { state: 'ready', slots: slotViews } : { state: 'loading' };
  const candidates = useMemo(() => (data?.options ?? []).filter(row => row.mode === pick.mode), [data, pick.mode]);
  const option = pick.candidate == null ? undefined : data?.options.find(row => candidateKey(row) === pick.candidate);
  const turnIdx = slotViews ? effectiveSlot(slotViews, pick.slot) : null;
  // The first slot shown is frozen for this owner: a refresh that fills it blocks instead of moving the pick.
  if (pick.slot == null && turnIdx != null) setPick({ ...pick, slot: turnIdx });
  const slot = turnIdx == null ? undefined : slotViews?.[turnIdx];
  const ready = options?.state === 'ready' && options.gen === gen && slots?.state === 'ready' && slots.gen === gen;
  const availability = enlistAvailability({
    receipt, ready, option, candidateMissing: pick.candidate != null && !!data && !option, slot,
  });
  const loadError = options?.state === 'error' ? options.error : null;
  const denied = [loadError, slots?.state === 'error' ? slots.error : null, submitFailure].find(isEnlistDenied) ?? null;

  const locks = useRef(new Set<string>());
  async function reserve() {
    const key = owner;
    const at = scope.current.epoch;
    if (at !== epoch || locks.current.has(key) || receipts.has(key) || submitFailure || !ready || !ring
        || availability.status !== 'AVAILABLE' || !option || turnIdx == null) return;
    locks.current.add(key);
    const target = option;
    const shown = calendarKey(ring.calendar);
    const base = { id: ++receiptSeq, turnIdx, candidate: candidateKey(target) };
    const settle = (next: EnlistReceipt | null) => { if (receipts.get(key)?.id === base.id) putReceipt(key, next); };
    const live = () => mounted.current && scope.current.epoch === at && selectedEnlistServer() === server;
    let posted = false;
    putReceipt(key, { ...base, status: 'submitting' });
    try {
      const result = await submitCommandAndAwaitResult(() => sendEnlist(generalId, target, base.turnIdx, {
        preflight: fresh => {
          if (!live()) throw new EnlistScopeChanged();
          return calendarKey(fresh.calendar) === shown ? null : '순 시각이 바뀌었습니다. 12순을 다시 확인한 뒤 예약해 주세요.';
        },
        onPost: () => { posted = true; },
      }));
      // A result that arrives for an abandoned scope proves nothing; keep the slot guarded as unknown.
      if (!live()) { settle(posted ? { ...base, status: 'unknown' } : null); return; }
      if (result.status === 'reserved') {
        const proof = await readEnlistSlots(generalId).catch(() => null);
        const matched = live() && !!proof && reservationMatches(proof.slots, base.turnIdx, target);
        settle({ ...base, status: matched ? 'reserved' : 'unknown' });
        if (live()) reload();
        if (matched) onRefresh();
      } else if (result.status === 'applied') {
        settle({ ...base, status: 'applied' });
        onRefresh();
      } else if (result.status === 'pending') {
        settle({ ...base, status: 'pending' });
      } else {
        settle({ ...base, status: 'rejected', ...(result.reason ? { reason: result.reason } : {}), ...(result.code ? { code: result.code } : {}) });
      }
    } catch (error) {
      if (!live()) { settle(posted ? { ...base, status: 'unknown' } : null); return; }
      const failed = asError(error, '출사 예약에 실패했습니다.');
      if (posted && !isEnlistDenied(failed)) { settle({ ...base, status: 'unknown' }); return; }
      settle(null);
      setFailure({ epoch: at, error: failed });
    } finally {
      locks.current.delete(key);
    }
  }

  const editable = !receipt;
  const choose = (change: Partial<Choice>) => {
    if (editable) setPick(prev => ({ ...(prev.epoch === epoch ? prev : pick), ...change, epoch }));
  };
  return {
    loading: !options || !slots, loadError, denied, submitFailure, receipt, availability,
    options: data?.options ?? null, candidates, mode: pick.mode, selected: option ?? null, candidateKey: pick.candidate,
    slotsLoad, turnIdx, busy: receipt?.status === 'submitting',
    chooseMode: (mode: EnlistMode) => choose({ mode, candidate: null }),
    chooseCandidate: (key: string) => choose({ candidate: key }),
    chooseSlot: (next: number) => choose({ slot: next }),
    reserve,
    retry: () => { setFailure(null); reload(); },
    /** Explicit acknowledgement of a rejection; pending, unknown and success receipts are never cleared here. */
    acknowledge: () => { if (receipts.get(owner)?.status === 'rejected') putReceipt(owner, null); setFailure(null); reload(); },
  };
}
