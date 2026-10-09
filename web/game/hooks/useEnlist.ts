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
type Latest = { readonly gen: number; readonly options: Read<EnlistmentOptionsResponse> | null; readonly slots: Read<EnlistSlotsRead> | null };

const CALENDAR_CHANGED = '순 시각이 바뀌었습니다. 12순을 다시 확인한 뒤 예약해 주세요.';
const REFRESHING = '12순과 후보를 새로 불러오는 중이라 예약하지 않았습니다. 다시 확인한 뒤 예약해 주세요.';

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

  // The latest reads live in a ref as well, so a preflight already in flight sees a refresh the moment it starts
  // instead of after React re-renders. This is read freshness only; receipts keep following the owner epoch.
  const latest = useRef<Latest>({ gen: 0, options: null, slots: null });
  const [gen, setGen] = useState(0);
  const reload = useCallback(() => {
    latest.current = { ...latest.current, gen: latest.current.gen + 1 };
    setGen(latest.current.gen);
  }, []);
  useTurnRefresh(reload);
  const [optionsRead, setOptions] = useState<Read<EnlistmentOptionsResponse> | null>(null);
  const [slotsRead, setSlots] = useState<Read<EnlistSlotsRead> | null>(null);
  const [failure, setFailure] = useState<{ epoch: number; error: Error } | null>(null);
  const [pickState, setPick] = useState<Choice>({ epoch, mode: 'NATION', candidate: null, slot: null });

  useEffect(() => {
    const controller = new AbortController();
    const at = scope.current.epoch;
    const live = () => !controller.signal.aborted && scope.current.epoch === at;
    const putOptions = (read: Read<EnlistmentOptionsResponse>) => { latest.current = { ...latest.current, options: read }; setOptions(read); };
    const putSlots = (read: Read<EnlistSlotsRead>) => { latest.current = { ...latest.current, slots: read }; setSlots(read); };
    readEnlistOptions(generalId, controller.signal).then(
      data => { if (live()) putOptions({ epoch: at, gen, state: 'ready', data }); },
      error => { if (live()) putOptions({ epoch: at, gen, state: 'error', error: asError(error, '출사 정보를 불러오지 못했습니다.') }); });
    readEnlistSlots(generalId, controller.signal).then(
      data => { if (live()) putSlots({ epoch: at, gen, state: 'ready', data }); },
      error => { if (live()) putSlots({ epoch: at, gen, state: 'error', error: asError(error, '12순을 불러오지 못했습니다.') }); });
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
    if (at !== epoch || latest.current.gen !== gen || locks.current.has(key) || receipts.has(key) || submitFailure || !ready || !ring
        || availability.status !== 'AVAILABLE' || !option || turnIdx == null) return;
    locks.current.add(key);
    const target = option;
    const shown = calendarKey(ring.calendar);
    const base = { id: ++receiptSeq, turnIdx, candidate: candidateKey(target) };
    const settle = (next: EnlistReceipt | null) => { if (receipts.get(key)?.id === base.id) putReceipt(key, next); };
    const live = () => mounted.current && scope.current.epoch === at && selectedEnlistServer() === server;
    // The same gate the screen shows, re-run on the newest generation right before the POST. A refresh that has
    // started but not finished stops the send; nothing is migrated to another candidate or slot.
    const stillAvailable = (): string | null => {
      const now = latest.current;
      const current = <T>(read: Read<T> | null) => read?.epoch === at && read.gen === now.gen ? read : null;
      const optionsNow = current(now.options);
      const slotsNow = current(now.slots);
      if (!optionsNow || !slotsNow) return REFRESHING;
      if (optionsNow.state === 'error') return optionsNow.error.message;
      if (slotsNow.state === 'error') return slotsNow.error.message;
      if (calendarKey(slotsNow.data.calendar) !== shown) return CALENDAR_CHANGED;
      const row = optionsNow.data.options.find(candidate => candidateKey(candidate) === base.candidate);
      const views = fromReservedCommands({ result: true, generalId: slotsNow.data.generalId, slots: [...slotsNow.data.slots] });
      const gate = enlistAvailability({ receipt: null, ready: true, option: row, candidateMissing: !row, slot: views[base.turnIdx] });
      return gate.status === 'AVAILABLE' ? null : gate.reason || '출사를 예약할 수 없습니다.';
    };
    let posted = false;
    putReceipt(key, { ...base, status: 'submitting' });
    try {
      const result = await submitCommandAndAwaitResult(() => sendEnlist(generalId, target, base.turnIdx, {
        preflight: fresh => {
          if (!live()) throw new EnlistScopeChanged();
          return calendarKey(fresh.calendar) === shown ? stillAvailable() : CALENDAR_CHANGED;
        },
        onPost: () => { posted = true; },
      }));
      // A result that arrives for an abandoned scope proves nothing; keep the slot guarded as unknown.
      if (!live()) { settle(posted ? { ...base, status: 'unknown' } : null); return; }
      if (result.status === 'reserved') {
        let proof: EnlistSlotsRead | null = null;
        let readFailure: unknown = null;
        try { proof = await readEnlistSlots(generalId); } catch (error) { readFailure = error; }
        const matched = live() && !!proof && reservationMatches(proof.slots, base.turnIdx, target);
        // Without proof the order stays unknown and keeps blocking a second send, even when the read-back was denied.
        settle({ ...base, status: matched ? 'reserved' : 'unknown' });
        if (live() && isEnlistDenied(readFailure)) setFailure({ epoch: at, error: readFailure });
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
      // Once the POST left, a failure — 401/403 included — cannot prove it was not taken: keep it unknown.
      // Denial is still shown for this owner; only a pre-POST failure clears the receipt.
      if (posted) settle({ ...base, status: 'unknown' }); else settle(null);
      if (!posted || isEnlistDenied(failed)) setFailure({ epoch: at, error: failed });
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
