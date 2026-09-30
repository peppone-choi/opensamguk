'use client';

// 명령 흐름(P-W02 「이번 순에 할 일」) — K6 설계서 §2.1 · §3.1, 보드 V31K6Command · CommandEdit · MCommand · MCommandArgs.
//
// 모달이 아니다: 작전실 오른쪽 붙박이 패널(데스크톱) · 겹친 패널(태블릿) · 하단 시트(모바일)에 담긴다 — 담는 틀은 부른 쪽.
// 명령을 바꿔도 명령별 초안이 남고, 예약에 성공하면 닫지 않고 다음 빈 순으로 간다.
// 서버에 없는 것(순별 가능 여부 일괄 · 순 비우기 · 옮기기 · 거리 · 경로)은 그리지 않는다 — 계약판 U-01 · U-02 · A1 대기.
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ConfirmDialog } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import { filterCommands, flowCommand, orderForPlace, type ArgKind } from '@/lib/command-flow/catalog';
import {
    afterReserved, currentDraft, dropInvalid, firstEmptySlot, initialFlow, selectCommand, selectSlot, setArg,
    type ArgValue, type Draft, type FlowState,
} from '@/lib/command-flow/flow-state';
import { buildArgs, fetchCommandOptions, type ArgField } from '@/lib/command-flow/options';
import type { FlowTarget } from '@/lib/command-flow/url';
import { TurnSlots } from '@/components/turn-slots/TurnSlots';
import { announceTurnSlotsChanged, filledSet, fromReservedCommands, useTurnSlots, type TurnSlotView } from '@/lib/turn-slots';
import ArgsPanel, { type FlowResult, type OptionsLoad } from './ArgsPanel';

import CommandList, { type ListCategory } from './CommandList';
import styles from './CommandFlow.module.css';

export interface CommandFlowProps {
    readonly generalId: number;
    readonly generalName?: string | null;
    readonly initialInputId?: string | null;
    /** 0–11. 없으면 다음 빈 순. */
    readonly initialSlot?: number | null;
    /** 「여기로 명령」 · 「이 사람에게」로 받은 대상 — 장소 칸 · 사람 칸을 미리 채운다. */
    readonly initialTarget?: FlowTarget | null;
    readonly refreshKey?: number;
    readonly onClose: () => void;
    /** 주소(?do · slot) 맞추기 — 부른 쪽이 router.replace 한다. */
    readonly onLocationChange?: (flow: { inputId: string | null; slot: number }) => void;
    readonly onReserved?: () => void;
    /** 지도 고르기 다리(K2 지도 층). 없으면 목록으로만 고른다. */
    readonly onMapPick?: (field: ArgField, commit: (value: string) => void) => void;
}

const TARGET_ARG: Partial<Record<FlowTarget['kind'], { key: string; kind: ArgKind }>> = {
    province: { key: 'destinationProvinceId', kind: 'province' },
    commandery: { key: 'commanderyId', kind: 'commandery' },
    general: { key: 'targetGeneralId', kind: 'person' },
};

export default function CommandFlow(props: CommandFlowProps) {
    const { generalId, generalName, initialInputId = null, initialSlot = null, initialTarget = null, refreshKey = 0,
        onClose, onLocationChange, onReserved, onMapPick } = props;

    const targetArg = initialTarget ? TARGET_ARG[initialTarget.kind] : undefined;
    const [flow, setFlow] = useState<FlowState>(() => {
        const seed: Draft = targetArg && initialTarget ? { [targetArg.key]: initialTarget.id } : {};
        const start = initialFlow(initialSlot ?? 0, null, seed);
        return initialInputId && flowCommand(initialInputId) ? selectCommand(start, initialInputId) : start;
    });
    const [slotChosen, setSlotChosen] = useState(initialSlot != null);
    // 12순 — 작전실 12순 열과 같은 한 읽기(lib/turn-slots). 예약하면 알림으로 다른 사용처도 다시 읽는다.
    const { load: slotsLoad, reload: reloadSlots } = useTurnSlots(generalId, refreshKey);
    const strip = slotsLoad.state === 'ready' ? slotsLoad.slots : null;
    const [optionsById, setOptionsById] = useState<Record<string, OptionsLoad>>({});
    const [category, setCategory] = useState<ListCategory>('전체');
    const [query, setQuery] = useState('');
    const [screen, setScreen] = useState<'list' | 'args'>(initialInputId ? 'args' : 'list');
    const [dropped, setDropped] = useState<string[]>([]);
    const [missing, setMissing] = useState<string[]>([]);
    const [submitting, setSubmitting] = useState(false);
    const [result, setResult] = useState<FlowResult | null>(null);
    const [confirmOverwrite, setConfirmOverwrite] = useState(false);
    // seq = 거절마다 새 번호 — 사유 시트가 거절될 때마다 열린 채로 뜬다(InputAction key).
    const [rejected, setRejected] = useState<{ seq: number; code?: string; reason?: string } | null>(null);
    const [pendingArgs, setPendingArgs] = useState<Record<string, unknown> | null>(null);

    // 순을 정하지 않고 열었으면 12순을 처음 읽은 뒤 다음 빈 순을 고른다(다 찼으면 01순 + 「다 찼습니다」).
    useEffect(() => {
        if (slotChosen || !strip) return;
        const first = firstEmptySlot(filledSet(strip));
        setFlow((f) => ({ ...selectSlot(f, first.slot), full: first.full }));
        setSlotChosen(true);
    }, [slotChosen, strip]);

    // 명령별 옵션 — 흐름 안에서 한 번 받는다(설계서 §2.1 옵션 재사용).
    const loadOptions = useCallback((inputId: string) => {
        setOptionsById((m) => ({ ...m, [inputId]: { state: 'loading' } }));
        fetchCommandOptions(inputId, generalId)
            .then((o) => setOptionsById((m) => ({ ...m, [inputId]: o })))
            .catch((e: unknown) => setOptionsById((m) => ({
                ...m, [inputId]: { state: 'error', message: e instanceof Error ? e.message : '선택지를 불러오지 못했습니다.' },
            })));
    }, [generalId]);
    useEffect(() => {
        if (flow.inputId && !optionsById[flow.inputId]) loadOptions(flow.inputId);
    }, [flow.inputId, optionsById, loadOptions]);

    // 이어받은 값이 새 명령의 후보에 없으면 비우고 한 줄 알린다.
    const options = flow.inputId ? optionsById[flow.inputId] : undefined;
    const loaded = options && options.state !== 'loading' && options.state !== 'error' ? options : null;
    useEffect(() => {
        if (!options || options.state !== 'READY' || flow.carried.length === 0) return;
        let next = flow;
        const gone: string[] = [];
        for (const key of flow.carried) {
            const field = options.fields.find((f) => f.key === key);
            if (!field) continue;
            const r = dropInvalid(next, key, (v) => field.candidates.some((c) => c.value === v && c.available));
            if (r.dropped) { next = r.state; gone.push(key); }
        }
        if (gone.length > 0) {
            setFlow({ ...next, carried: next.carried.filter((k) => !gone.includes(k)) });
            setDropped(gone);
        }
    }, [options, flow]);

    useEffect(() => { onLocationChange?.({ inputId: flow.inputId, slot: flow.slot }); }, [flow.inputId, flow.slot, onLocationChange]);

    useEffect(() => {
        const onKey = (e: KeyboardEvent) => {
            if (e.key !== 'Escape' || e.defaultPrevented || submitting || confirmOverwrite) return;
            onClose();
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [onClose, submitting, confirmOverwrite]);

    const commands = useMemo(() => {
        const list = filterCommands(category, query);
        return targetArg ? orderForPlace(list, targetArg.kind) : list;
    }, [category, query, targetArg]);

    const command = flow.inputId ? flowCommand(flow.inputId) ?? null : null;
    const current: TurnSlotView = strip?.[flow.slot] ?? fromReservedCommands(null)[flow.slot];
    const draft = currentDraft(flow);

    const choose = (inputId: string) => {
        setFlow((f) => selectCommand(f, inputId));
        setDropped([]); setMissing([]); setResult(null); setRejected(null);
        setScreen('args');
    };
    const onArg = (key: string, value: ArgValue) => {
        setFlow((f) => setArg(f, key, value));
        setMissing((m) => m.filter((k) => k !== key));
        setRejected(null);
    };

    const send = async (args: Record<string, unknown>) => {
        if (!command) return;
        setConfirmOverwrite(false); setPendingArgs(null);
        setSubmitting(true); setResult(null); setRejected(null);
        const slot = flow.slot;
        try {
            const r = await submitCommandAndAwaitResult(() => api.command(command.inputId, args, generalId, slot));
            if (r.status === 'rejected') {
                // 서버가 준 code · reason 그대로 — 제출 단추가 막히고 사유 시트에 보인다. 칸을 고치면 풀린다.
                setRejected({ seq: Date.now(), ...(r.code ? { code: r.code } : {}), ...(r.reason ? { reason: r.reason } : {}) });
            } else if (r.status === 'pending') {
                setResult({ kind: 'info', text: '처리가 늦어지고 있습니다 — 순 띠에서 결과를 확인해 주세요.' });
                announceTurnSlotsChanged();
            } else {
                const no = String(slot + 1).padStart(2, '0');
                setResult({ kind: 'ok', text: r.status === 'applied' ? `「${command.name}」 — 바로 처리했습니다.` : `「${command.name}」 — ${no}순에 예약했습니다.` });
                // 방금 채운 순은 afterReserved가 채운 것으로 친다 — 다시 읽기를 기다리지 않고 다음 빈 순으로 간다.
                setFlow((f) => afterReserved(f, strip ? filledSet(strip) : new Set([slot])));
                announceTurnSlotsChanged();
                onReserved?.();
            }
        } catch (e: unknown) {
            setResult({ kind: 'error', text: e instanceof Error ? e.message : '예약을 보내지 못했습니다.' });
        } finally {
            setSubmitting(false);
        }
    };

    const submit = () => {
        if (!command || submitting) return;
        let args: Record<string, unknown>;
        if (loaded?.state === 'READY') {
            const built = buildArgs(loaded, draft);
            if (!built.ok) { setMissing([...built.missing]); return; }
            args = built.args;
        } else if (command.args.length === 0) {
            // 옵션을 읽는 중이거나 못 읽었어도 인자 없는 명령은 보낸다 — 서버가 판정한다(K0 2026-09-30).
            args = {};
        } else {
            setResult({ kind: 'info', text: options?.state === 'loading' || !options
                ? '선택지를 불러오는 중입니다 — 잠시 뒤 다시 눌러 주세요.'
                : '선택지를 먼저 불러와야 합니다 — 「다시 시도」를 누르세요.' });
            return;
        }
        if (current.state !== 'empty') { setPendingArgs(args); setConfirmOverwrite(true); return; }
        void send(args);
    };

    const no = String(flow.slot + 1).padStart(2, '0');
    return (
        <section className={styles.flow} data-screen={screen} aria-label="이번 순에 할 일" data-testid="command-flow">
            <header className={styles.head}>
                <h2 className={styles.headTitle}>
                    이번 순에 할 일{generalName ? <span className={styles.headSub}>{generalName}</span> : null}
                </h2>
                <button
                    type="button"
                    className={`os-button os-button--ghost ${styles.iconButton}`}
                    aria-label="닫기"
                    aria-keyshortcuts="Escape"
                    aria-disabled={submitting || undefined}
                    onClick={() => { if (!submitting) onClose(); }}
                >
                    ×
                </button>
            </header>

            <div className={styles.stripWrap}>
                <TurnSlots
                    mode="strip"
                    load={slotsLoad}
                    current={flow.slot}
                    onSelect={(i) => { setFlow((f) => selectSlot(f, i)); setResult(null); setRejected(null); }}
                    onRetry={reloadSlots}
                />
            </div>
            {flow.full ? <div className={styles.band} role="status">12순이 다 찼습니다 — 채운 순을 눌러 바꾸세요.</div> : null}
            {current.state !== 'empty' ? (
                <div className={styles.band} data-testid="slot-reserved">
                    <span>{no}순 지금 예약: <strong>{current.name}</strong></span>
                    <span style={{ color: 'var(--muted)', fontSize: 12 }}>다른 명령을 고르고 예약하면 바꿉니다.</span>
                </div>
            ) : null}

            <div className={styles.body}>
                <CommandList
                    commands={commands}
                    category={category}
                    query={query}
                    selected={flow.inputId}
                    onCategory={setCategory}
                    onQuery={setQuery}
                    onSelect={choose}
                />
                <ArgsPanel
                    command={command}
                    slot={current}
                    options={options}
                    draft={draft}
                    carried={flow.carried}
                    dropped={dropped}
                    missing={missing}
                    submitting={submitting}
                    result={result}
                    rejected={rejected}
                    onArg={onArg}
                    onSubmit={submit}
                    onBack={() => setScreen('list')}
                    onRetry={() => { if (flow.inputId) loadOptions(flow.inputId); }}
                    onMapPick={onMapPick ? (field) => onMapPick(field, (value) => onArg(field.key, value)) : undefined}
                />
            </div>

            <ConfirmDialog
                open={confirmOverwrite}
                title={`${no}순을 바꿉니다`}
                message={`${no}순의 「${current.name ?? ''}」 대신 「${command?.name ?? ''}」을 예약합니다.`}
                confirmLabel="바꾸기"
                cancelLabel="그대로 두기"
                busy={submitting}
                onConfirm={() => { if (pendingArgs) void send(pendingArgs); }}
                onCancel={() => { setConfirmOverwrite(false); setPendingArgs(null); }}
            />
        </section>
    );
}
