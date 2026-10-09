'use client';

// 12순 — 작전실 12순 열(P-W01, K4가 붙임)과 명령 흐름 순 띠(P-W02, K6)가 같이 쓰는 한 모델 · 한 읽기(K0 결정 2026-10-01).
//
// 정본 읽기는 계약판 K4-02 `GET /api/turn-slots`(순마다 날짜 · 시각 · displayName · argsSummary · 상태 · 효력 표식)다.
// 서버에 아직 없어서(NOT_STARTED) 지금은 `/api/reserved-commands` 예약 링을 같은 모양으로 편다 — 링이 주지 않는
// 날짜 · 시각 · 효력 표식은 null · 빈 목록으로 둔다. 명령 문장은 저장 arg와 서버 이름 목록으로 푼다. K4-02가 오면 fromTurnSlots를 더하고
// useTurnSlots가 그쪽을 읽는다. 화면은 이 모양만 본다.
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';
import { api } from './api';
import { readServerCookie } from './serverGameUrl';
import { flowCommand } from './command-flow/catalog';
import { EMPTY_COMMAND_NAMES, EMPLOY_INPUT, employTargetNames, reservedCommandText, reservedEmployTarget, reservedEquipmentInput, reservedInputId, type ReservedCommandNames } from './command-flow/reserved-command-view';
import type { ReservedCommandsResponse, ReservedSlot, TravelActionId } from './types';

export const SLOT_COUNT = 12;

export interface TurnSlotView {
    /** 0–11. 01순 = 0 = 다음에 실행될 순. */
    readonly turnIdx: number;
    /** K4-02 state(EMPTY · RESERVED · BLOCKED). 링에는 막힘이 없어 empty · reserved 둘뿐이다. */
    readonly state: 'empty' | 'reserved' | 'blocked';
    readonly inputId: string | null;
    /** 저장 인자와 서버 이름으로 푼 명령 문장. 빈 순은 null. */
    readonly name: string | null;
    /** 이 순에 실제로 저장된 인자. 빈 순에는 없다. */
    readonly arg?: Readonly<Record<string, unknown>>;
    /** 대상 한 줄(K4-02 argsSummary). 링에는 없다. */
    readonly summary: string | null;
    /** 「3월 하순」(K4-02 phaseLabel). 링에는 없다. */
    readonly when: string | null;
    /** 「22:40」(K4-02 scheduledAt). 링에는 없다. */
    readonly at: string | null;
    readonly blockedCode: string | null;
    /** 배치 · 방침 효력 표식(K4-02 standingMarkers). 링에는 없다. */
    readonly markers: readonly ('placement' | 'policy')[];
}

export type TurnSlotsLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'error'; readonly message: string }
    | { readonly state: 'ready'; readonly slots: readonly TurnSlotView[] };

const empty = (turnIdx: number): TurnSlotView => ({
    turnIdx, state: 'empty', inputId: null, name: null, summary: null, when: null, at: null, blockedCode: null, markers: [],
});

/** 예약 링(`/api/reserved-commands`) → 12칸. 0–11 밖의 순은 버린다. */
export function fromReservedCommands(res: ReservedCommandsResponse | null | undefined, names = EMPTY_COMMAND_NAMES): TurnSlotView[] {
    const byIdx = new Map<number, ReservedSlot>();
    for (const s of res?.slots ?? []) {
        if (Number.isInteger(s.turnIdx) && s.turnIdx >= 0 && s.turnIdx < SLOT_COUNT) byIdx.set(s.turnIdx, s);
    }
    return Array.from({ length: SLOT_COUNT }, (_, turnIdx) => {
        const s = byIdx.get(turnIdx);
        if (!s) return empty(turnIdx);
        return {
            ...empty(turnIdx),
            state: 'reserved',
            inputId: reservedInputId(s.action, s.brief),
            name: reservedCommandText(s, names),
            arg: { ...s.arg },
        };
    });
}

export function filledSet(slots: readonly TurnSlotView[]): Set<number> {
    return new Set(slots.filter((s) => s.state !== 'empty').map((s) => s.turnIdx));
}

export function filledCount(slots: readonly TurnSlotView[]): number {
    return slots.filter((s) => s.state !== 'empty').length;
}

/** 「이번 순에 할 일」이 여는 순 — 첫 빈 순. 12순이 다 찼으면 null(흐름은 01순을 열고 「다 찼습니다」를 보인다). */
export function firstEmpty(slots: readonly TurnSlotView[]): number | null {
    return slots.find((s) => s.state === 'empty')?.turnIdx ?? null;
}

/** 「04순 — 빈 순」 · 「01순 — 농지개간」. 읽는 이름표(칸이 좁아 보이는 글자와 따로). */
export function slotLabel(slot: TurnSlotView): string {
    const no = String(slot.turnIdx + 1).padStart(2, '0');
    return `${no}순 — ${slotText(slot)}`;
}

export function slotText(slot: TurnSlotView): string {
    return slot.state === 'empty' ? '빈 순' : slot.summary ?? slot.name ?? '명령';
}

// ── 한 읽기: 예약하면 마운트된 모든 사용처(12순 열 · 순 띠 · 부 명부)가 다시 읽는다 ─────────────
const listeners = new Set<() => void>();
export function announceTurnSlotsChanged() { for (const l of [...listeners]) l(); }

type ProvinceRows = readonly { provinceId: string; name: string }[];
const pendingProvinceReads = new Map<string, { read: Promise<ProvinceRows>; users: number }>();

/** Names use the selected server and persisted slot inputs; failures never empty the reservation ring. */
function useReservedCommandNames(generalId: number | null, serverId: string | undefined, response: ReservedCommandsResponse | null, refreshKey: unknown, generation: number): ReservedCommandNames {
    const scope = JSON.stringify([serverId, generalId, refreshKey]);
    const [loaded, setLoaded] = useState<{ scope: string; names: ReservedCommandNames } | null>(null);
    const inputs = [...new Set((response?.generalId === generalId ? response.slots : []).flatMap(slot => {
        if (typeof slot.arg?.destinationProvinceId !== 'string' || !slot.arg.destinationProvinceId.trim()) return [];
        const cmd = flowCommand(reservedInputId(slot.action, slot.brief));
        return cmd && (cmd.category === '이동' || cmd.inputId === 'action.deploy') ? [cmd.inputId] : [];
    }))].sort();
    const inputsKey = JSON.stringify(inputs);
    const generationKey = JSON.stringify([scope, generation]);
    const readKey = JSON.stringify([generationKey, inputsKey]);
    const cached = useRef<{ generation: string; reads: Map<string, Promise<ProvinceRows>> }>({ generation: generationKey, reads: new Map() });
    if (cached.current.generation !== generationKey) cached.current = { generation: generationKey, reads: new Map() };
    const [provinceRead, setProvinceRead] = useState<{ key: string; names: Record<string, string> } | null>(null);
    const equipmentInput = response?.generalId === generalId
        ? response.slots.map(reservedEquipmentInput).find(input => input != null) ?? null : null;
    const hasEquipment = equipmentInput != null;
    const equipmentKey = JSON.stringify([generationKey, hasEquipment]);
    const [equipmentRead, setEquipmentRead] = useState<{ key: string; names: Record<string, string> } | null>(null);
    useEffect(() => {
        if (generalId == null || equipmentInput == null) return undefined;
        let alive = true;
        void Promise.resolve().then(() => api.legacyDirectOptions(equipmentInput, generalId)).then(options => {
            if (!alive || readServerCookie() !== serverId) return;
            const entries = options.inputId === equipmentInput ? Object.entries(options.equipmentNames ?? {}) : [];
            const names = Object.fromEntries(entries.filter(([id, name]) => id.startsWith('equipment:') &&
                id.length > 'equipment:'.length && typeof name === 'string' && name.trim()).map(([id, name]) => [id, name.trim()]));
            setEquipmentRead({ key: equipmentKey, names });
        }).catch(() => { if (alive && readServerCookie() === serverId) setEquipmentRead({ key: equipmentKey, names: {} }); });
        return () => { alive = false; };
    }, [generalId, serverId, equipmentInput, equipmentKey]);
    // Employ names come only from this actor's employ-options, limited to the stored targets.
    const employIds = response?.generalId === generalId
        ? [...new Set(response.slots.map(reservedEmployTarget).filter((id): id is number => id != null))].sort((a, b) => a - b) : [];
    const employKey = JSON.stringify([generationKey, employIds]);
    // Each scope change opens a new lookup epoch, so a reused key (A→B→A) never revives names from an earlier lookup.
    const employEpoch = useRef({ key: employKey, epoch: 0 });
    if (employEpoch.current.key !== employKey) employEpoch.current = { key: employKey, epoch: employEpoch.current.epoch + 1 };
    const epoch = employEpoch.current.epoch;
    const [employRead, setEmployRead] = useState<{ epoch: number; names: Record<string, string> } | null>(null);
    useEffect(() => {
        const ids = JSON.parse(employKey)[1] as number[];
        if (generalId == null || ids.length === 0) return undefined;
        let alive = true;
        const settle = (names: Record<string, string>) => {
            if (alive && employEpoch.current.epoch === epoch && readServerCookie() === serverId) setEmployRead({ epoch, names });
        };
        void Promise.resolve().then(() => api.peopleOptions(EMPLOY_INPUT, generalId))
            .then(options => employTargetNames(options, ids)).catch((): Record<string, string> => ({})).then(settle);
        return () => { alive = false; };
    }, [generalId, serverId, employKey, epoch]);
    useEffect(() => {
        if (generalId == null) return undefined;
        let alive = true;
        const mapNames = Promise.resolve().then(() => api.mapPreview()).then(preview => ({
            cities: Object.fromEntries(preview.cities.map(city => [String(city.id), city.displayName?.trim() || city.name])),
            nations: Object.fromEntries((preview.nations ?? []).map(nation => [String(nation.id), nation.name])),
        })).catch(() => ({ cities: {}, nations: {} }));
        const units = Promise.resolve().then(() => api.gameConst()).then(bundle => Object.fromEntries(
            (bundle.gameUnitConst ?? []).map(unit => [String(unit.id), unit.name]),
        )).catch(() => ({}));
        void Promise.all([mapNames, units]).then(([map, unitNames]) => {
            if (alive && readServerCookie() === serverId) setLoaded({ scope, names: { ...map, units: unitNames } });
        });
        return () => { alive = false; };
    }, [generalId, serverId, refreshKey, scope]);
    useEffect(() => {
        if (generalId == null || inputsKey === '[]') return undefined;
        let alive = true;
        const ids = JSON.parse(inputsKey) as string[];
        const subscriptions: { key: string; entry: { read: Promise<ProvinceRows>; users: number } }[] = [];
        const reads = ids.map(inputId => {
            const pendingKey = JSON.stringify([generationKey, inputId]);
            let entry = pendingProvinceReads.get(pendingKey);
            let read = cached.current.reads.get(inputId) ?? entry?.read;
            if (!read) {
                read = Promise.resolve().then(async () => {
                    const active = pendingProvinceReads.get(pendingKey);
                    if (readServerCookie() !== serverId || !active?.users || active.read !== read) return [];
                    if (inputId === 'action.deploy') return (await api.deployOptions(generalId)).destinations;
                    const options = await api.travelOptions(inputId as TravelActionId, generalId);
                    return options.inputId === inputId ? options.destinations : [];
                }).catch(() => []);
            }
            if (!entry || entry.read !== read) {
                entry = { read, users: 0 };
                pendingProvinceReads.set(pendingKey, entry);
                const active = entry;
                void read.finally(() => { if (pendingProvinceReads.get(pendingKey) === active) pendingProvinceReads.delete(pendingKey); });
            }
            entry.users++;
            subscriptions.push({ key: pendingKey, entry });
            cached.current.reads.set(inputId, read);
            return read;
        });
        void Promise.all(reads).then(groups => {
            const values = new Map<string, string | null>();
            for (const rows of groups) for (const row of rows) {
                if (typeof row.provinceId !== 'string' || !row.provinceId || typeof row.name !== 'string' || !row.name.trim()) continue;
                const previous = values.get(row.provinceId);
                values.set(row.provinceId, previous === undefined || previous === row.name.trim() ? row.name.trim() : null);
            }
            if (alive && readServerCookie() === serverId) setProvinceRead({ key: readKey, names: Object.fromEntries([...values].filter((entry): entry is [string, string] => entry[1] != null)) });
        });
        return () => {
            alive = false;
            for (const { key, entry } of subscriptions) {
                entry.users--;
                if (entry.users === 0 && pendingProvinceReads.get(key) === entry) pendingProvinceReads.delete(key);
            }
        };
    }, [generalId, serverId, inputsKey, generationKey, readKey]);
    return { ...(loaded?.scope === scope ? loaded.names : EMPTY_COMMAND_NAMES), provinces: provinceRead?.key === readKey ? provinceRead.names : {}, equipment: equipmentRead?.key === equipmentKey ? equipmentRead.names : {}, people: employRead?.epoch === epoch ? employRead.names : {} };
}

/** generalId가 없으면 부르지 않는다. 턴 갱신 신호 · refreshKey · 다른 곳의 예약에 다시 읽는다. */
export function useTurnSlots(generalId: number | null, refreshKey = 0): { load: TurnSlotsLoad; reload: () => void; names: ReservedCommandNames } {
    const serverId = readServerCookie();
    const [load, setLoad] = useState<Exclude<TurnSlotsLoad, { state: 'ready' }> | { state: 'ready'; response: ReservedCommandsResponse }>({ state: 'loading' });
    const identity = JSON.stringify([serverId, generalId]);
    const [loadedFor, setLoadedFor] = useState(identity);
    if (loadedFor !== identity) {
        setLoadedFor(identity);
        setLoad({ state: 'loading' });
    }
    const [seq, setSeq] = useState(0);
    const names = useReservedCommandNames(generalId, serverId, load.state === 'ready' ? load.response : null, refreshKey, seq);
    const reload = useCallback(() => setSeq((n) => n + 1), []);
    useTurnRefresh(reload);
    useEffect(() => {
        listeners.add(reload);
        return () => { listeners.delete(reload); };
    }, [reload]);
    useEffect(() => {
        if (generalId == null) return undefined;
        let alive = true;
        api.reservedCommands(generalId)
            .then((res) => { if (alive && readServerCookie() === serverId) setLoad({ state: 'ready', response: res }); })
            .catch((e: unknown) => { if (alive && readServerCookie() === serverId) setLoad({ state: 'error', message: e instanceof Error ? e.message : '12순을 불러오지 못했습니다' }); });
        return () => { alive = false; };
    }, [generalId, serverId, refreshKey, seq]);
    return { load: load.state === 'ready' ? { state: 'ready', slots: fromReservedCommands(load.response, names) } : load, reload, names };
}
