'use client';

// 상사(court.reward) 훅 — 상사 선택지 읽기(lib/api/court-reward)와 접수(api.courtReward)를 한 소유자(탭 서버 + 장수)에 묶는다.
//
// - 고른 인물 · 금액 원문 · 알림 · 보내는 중은 소유자 것이다. 소유자가 바뀌면 그 렌더에서 모두 비우고(A→B→A 도 새 수명),
//   옛 소유자의 늦은 읽기 · 접수 결과는 쓰지 않는다. 해제 뒤에도 마찬가지다.
// - 읽기는 (수명 · 카드 · 정규화 금액 · 다시 읽기 · 순) 열쇠에 묶인다. 열쇠가 바뀌면 그 렌더부터 옛 미리 보기를 숨긴다.
//   금액만 바뀌면 250ms 뒤에 읽고, 고르기 · 다시 시도 · 턴 끝 · 공개 상태 회복은 바로 읽는다. 새 읽기는 옛 요청을 끊고
//   세대를 올린다 — 끊기를 무시한 전송의 늦은 결과도 세대가 달라 버린다.
// - 서버 공개 상태가 막히면(admission) 읽지 않고 받은 값을 버린다.
// - 같은 소유자의 순 갱신은 다시 읽기만 한다 — 보내던 접수의 결과(알림)는 그대로 남는다.
// - 접수되면 onQueued 를 부른다(화면이 시트를 닫고 조정 읽기를 다시 한다). 지금 소유자 수명의 결과일 때만 — 옛 소유자 · 해제 뒤 결과는 부르지 않는다.
import { useCallback, useEffect, useRef, useState } from 'react';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { readRewardOptions } from '@/lib/api/court-reward';
import type { Read, Retinue } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import type { RewardOptions, RewardOptionsQuery, RewardOptionsRead } from '@/lib/court-reward-types';
import {
    REWARD_DENIED_FALLBACK, REWARD_QUEUED_TEXT, REWARD_SEND_FAILED, rewardMoney, rewardPanelView,
    type RewardPanelView, type RewardPreviewInput,
} from '@/lib/court-reward-view';
import { useTurnRefresh } from './useTurnRefresh';

export const REWARD_AMOUNT_DEBOUNCE_MS = 250;

export interface RewardNotice {
    readonly tone: 'ok' | 'error';
    readonly text: string;
}

export interface CourtReward {
    /** 서버 공개 상태가 막혀 읽지 않는다. */
    readonly paused: boolean;
    /** 상사 선택지 읽기 — 화면의 읽기 상태(불러오는 중 · 실패 · 서버 상태)에 쓴다. */
    readonly read: Read<RewardOptions>;
    /** READY 일 때만 — 규칙 · 카드 · 창고 금 · 미리 보기 · 막는 까닭. */
    readonly view: RewardPanelView | null;
    /** 받은 선택지가 있는데 다시 읽기가 실패했다. */
    readonly refreshFailed: boolean;
    readonly selected: number | null;
    readonly amount: string;
    readonly busy: boolean;
    readonly notice: RewardNotice | null;
    readonly select: (retainerId: number) => void;
    readonly setAmount: (raw: string) => void;
    readonly retry: () => void;
    readonly submit: () => void;
}

interface Owned {
    readonly owner: string;
    /** 소유자 수명 — 소유자가 바뀔 때마다 오른다(같은 소유자로 돌아와도 새 값). */
    readonly epoch: number;
    readonly selected: number | null;
    readonly raw: string;
    readonly notice: RewardNotice | null;
    readonly busy: boolean;
    /** 다시 읽기(다시 시도 · 턴 끝 · 접수 뒤). */
    readonly tick: number;
}

interface Landed {
    readonly epoch: number;
    readonly key: string;
    readonly result: RewardOptionsRead;
}

interface Reads {
    readonly latest: Landed | null;
    /** 이 수명에서 마지막으로 받은 선택지 — 다시 읽는 동안 목록을 유지한다(미리 보기는 열쇠가 같을 때만). */
    readonly options: { readonly epoch: number; readonly options: RewardOptions } | null;
}

const NO_READS: Reads = { latest: null, options: null };
const fresh = (owner: string, epoch: number): Owned => ({ owner, epoch, selected: null, raw: '', notice: null, busy: false, tick: 0 });

export function useCourtReward(retinue: Retinue | null, onQueued?: () => void): CourtReward {
    const { generalId, serverId, frontInfo, admission } = useGameSession();
    const owner = generalId == null ? '' : `${serverId ?? ''}|${generalId}`;
    const turnKey = frontInfo ? `${frontInfo.global.year}-${frontInfo.global.month}-${frontInfo.global.turnPhase ?? ''}` : '';
    const paused = admission != null;

    const [stored, setOwned] = useState<Owned>(() => fresh(owner, 0));
    let local = stored;
    // 소유자가 바뀌면 같은 렌더에서 고르기 · 금액 · 알림 · 보내는 중을 비운다.
    if (stored.owner !== owner) {
        local = fresh(owner, stored.epoch + 1);
        setOwned(local);
    }
    const [reads, setReads] = useState<Reads>(NO_READS);

    // 비동기 결과가 「지금」 수명과 같은지 재는 거울 — 렌더 시점에 바로 맞춘다(useTurnRefresh 와 같은 규약).
    const epochRef = useRef(local.epoch);
    epochRef.current = local.epoch;
    const aliveRef = useRef(true);
    useEffect(() => {
        aliveRef.current = true;
        return () => { aliveRef.current = false; };
    }, []);

    const generationRef = useRef(0);
    const controllerRef = useRef<AbortController | null>(null);
    const invalidate = useCallback(() => {
        generationRef.current += 1;
        controllerRef.current?.abort();
        controllerRef.current = null;
    }, []);

    const options = !paused && reads.options?.epoch === local.epoch ? reads.options.options : null;
    const ready = options?.status === 'READY' ? options : null;
    // 받은 카드에 없는 인물은 고른 것으로 치지 않는다(사라진 카드).
    const selected = options == null ? local.selected
        : ready && local.selected != null && ready.cards.some((c) => c.retainerId === local.selected) ? local.selected : null;
    const money = selected != null ? rewardMoney(local.raw) : null;
    const query: RewardOptionsQuery | null = generalId == null ? null : { generalId, retainerId: selected, money };
    const key = `${local.epoch}|${selected}|${money}|${local.tick}|${turnKey}`;
    const current = reads.latest?.epoch === local.epoch && reads.latest.key === key && !paused ? reads.latest : null;

    // 핸들러가 다음 요청이 바뀌는지 재는 거울.
    const requestRef = useRef({ selected, money });
    requestRef.current = { selected, money };

    const lastRef = useRef<{ base: string; money: number | null } | null>(null);
    useEffect(() => {
        if (query == null || paused) {
            invalidate();
            lastRef.current = null;
            if (paused) setReads(NO_READS);
            return undefined;
        }
        const base = `${local.epoch}|${selected}|${local.tick}|${turnKey}`;
        // 금액만 바뀐 읽기만 늦춘다.
        const debounce = lastRef.current?.base === base && lastRef.current.money !== money;
        lastRef.current = { base, money };
        invalidate();
        const generation = generationRef.current;
        const epoch = local.epoch;
        const controller = new AbortController();
        controllerRef.current = controller;
        const start = () => {
            readRewardOptions(query, controller.signal)
                .then((result) => {
                    if (controller.signal.aborted || generation !== generationRef.current || epochRef.current !== epoch || !aliveRef.current) return;
                    setReads((prev) => ({
                        latest: { epoch, key, result },
                        options: result.ok ? { epoch, options: result.options } : prev.options?.epoch === epoch ? prev.options : null,
                    }));
                    const o = result.ok ? result.options : null;
                    if (o && query.retainerId != null && (o.status !== 'READY' || !o.cards.some((c) => c.retainerId === query.retainerId))) {
                        setOwned((l) => (l.epoch === epoch && l.selected === query.retainerId ? { ...l, selected: null } : l));
                    }
                })
                .catch(() => undefined); // 끊은 요청(AbortError)만 여기 온다.
        };
        const timer = debounce ? setTimeout(start, REWARD_AMOUNT_DEBOUNCE_MS) : null;
        if (!debounce) start();
        return () => {
            if (timer != null) clearTimeout(timer);
            controller.abort();
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps -- 요청은 key(수명 · 카드 · 금액 · 다시 읽기 · 순)와 공개 상태로만 바뀐다
    }, [key, paused, generalId]);

    const select = useCallback((retainerId: number) => {
        if (retainerId !== requestRef.current.selected) invalidate();
        const epoch = epochRef.current;
        setOwned((l) => (l.epoch === epoch ? { ...l, selected: retainerId } : l));
    }, [invalidate]);

    const setAmount = useCallback((raw: string) => {
        const { selected: who, money: before } = requestRef.current;
        if (who != null && rewardMoney(raw) !== before) invalidate();
        const epoch = epochRef.current;
        setOwned((l) => (l.epoch === epoch ? { ...l, raw } : l));
    }, [invalidate]);

    const retry = useCallback(() => {
        invalidate();
        const epoch = epochRef.current;
        setOwned((l) => (l.epoch === epoch ? { ...l, tick: l.tick + 1 } : l));
    }, [invalidate]);
    useTurnRefresh(retry);

    const previewInput: RewardPreviewInput = selected == null || !ready ? { state: 'idle' }
        : current == null ? { state: 'loading' }
        : current.result.ok && current.result.options.status === 'READY' && current.result.options.preview
            ? { state: 'ready', preview: current.result.options.preview }
            : { state: 'error' };
    const view = ready ? rewardPanelView({ options: ready, retinue, selected, money: rewardMoney(local.raw), preview: previewInput }) : null;

    // 같은 수명 안에서 한 번에 하나만 보낸다 — 렌더를 기다리지 않고 바로 막는다.
    const submittingRef = useRef<number | null>(null);
    const onQueuedRef = useRef(onQueued);
    onQueuedRef.current = onQueued;
    const submit = () => {
        const epoch = epochRef.current;
        if (submittingRef.current === epoch || view?.blocked !== null || generalId == null || selected == null || money == null) return;
        submittingRef.current = epoch;
        const live = () => aliveRef.current && epochRef.current === epoch;
        const update = (fn: (l: Owned) => Owned) => { if (live()) setOwned((l) => (l.epoch === epoch ? fn(l) : l)); };
        update((l) => ({ ...l, busy: true }));
        api.courtReward(generalId, { retainerId: selected, money })
            .then((out) => {
                if (isIntakeQueued(out)) {
                    if (!live()) return;
                    update((l) => ({ ...l, notice: { tone: 'ok', text: REWARD_QUEUED_TEXT }, raw: '', tick: l.tick + 1 }));
                    onQueuedRef.current?.();
                } else if (isIntakeDenied(out)) update((l) => ({ ...l, notice: { tone: 'error', text: out.reason?.trim() || REWARD_DENIED_FALLBACK } }));
            })
            .catch(() => update((l) => ({ ...l, notice: { tone: 'error', text: REWARD_SEND_FAILED } })))
            .finally(() => {
                if (submittingRef.current === epoch) submittingRef.current = null;
                update((l) => ({ ...l, busy: false }));
            });
    };

    const failed = current && !current.result.ok ? current.result : null;
    const errorCode = failed && failed.failure !== 'CONTRACT' && failed.httpStatus != null ? String(failed.httpStatus) : null;
    const read: Read<RewardOptions> = {
        data: options,
        loading: options == null && failed == null && query != null && !paused,
        error: options == null && failed ? '상사 선택지를 불러오지 못했습니다' : null,
        errorCode,
    };
    return {
        paused,
        read,
        view,
        refreshFailed: options != null && failed != null,
        selected,
        amount: local.raw,
        busy: local.busy,
        notice: local.notice,
        select,
        setAmount,
        retry,
        submit,
    };
}
