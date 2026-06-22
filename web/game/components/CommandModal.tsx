'use client';

// CommandModal — the modal-first command reservation flow (spec §5). EXTENDED from the original hardcoded
// 23-item grid to be DRIVEN by api.availableCommands(generalId): category tabs → command grid filtered by
// category → an arg sub-form rendered IN THE MODAL (no v_processing.php page nav, per the locked F2
// decision). Mirrors legacy CommandSelectForm.vue (compensation ▲/▼ tag, possible→strikethrough-red,
// title-as-subtext) + the four processing field-types (SelectCity/General/Nation/Amount).
//
// Submission contract (matches game-api CommandController.kt): POST /api/command/{code}?generalId&turnIdx
// with the collected args as the JSON body. generalId is the caller's OWN id (front-info.general.
// generalId); turnIdx is the target reservation slot. Responses:
//   - 202 {status:"AVAILABLE", requestId, turnIdx}  → success toast, close.
//   - 200 {status:"BLOCKED"|"UNKNOWN", reason}       → render the PHP-faithful reason as INFO (not error).
//
// No-arg commands reserve instantly on click; reqArg commands open the relevant field sub-form first.
// If availableCommands() is absent/empty, surface the failure instead of fabricating a local command list.

import { useEffect, useMemo, useState } from 'react';
import { api } from '../lib/api';
import { inferArgType, argFieldName } from '../lib/command-arg-types';
import type {
    AvailableCommand,
    AvailableCommandCategory,
    AvailableCommandsResponse,
    CommandArgType,
} from '../types/game';
import SelectCityField from './command/SelectCityField';
import SelectGeneralField from './command/SelectGeneralField';
import SelectNationField from './command/SelectNationField';
import SelectAmountField from './command/SelectAmountField';
import SelectFoundingField from './command/SelectFoundingField';
import SelectRecruitField from './command/SelectRecruitField';

interface CommandModalProps {
    onClose: () => void;
    onToast: (msg: string, type: 'success' | 'error' | 'info') => void;
    /** The caller's own general id (front-info.general.generalId). Required by CommandController. */
    generalId: number;
    /** The caller's own nation id — used to exclude self from the nation picker. */
    nationId?: number;
    /** Target reservation slot (defaults to 0 = next reservable turn). */
    turnIdx?: number;
    /** Bump front-info after a successful reserve (soft refresh). */
    onReserved?: () => void;
    // ── F4 C1 retrofit (action-page direct-launch) ──────────────────────────────
    // When an action page (auction/betting/diplomacy/inherit) opens the modal for ONE
    // already-known command, it pins the command code + a display label + (optionally)
    // a forced arg sub-form, and merges page-fixed extra args (auctionId/bettingId/…) into
    // every submit body. This bypasses the catalog/category grid (we already KNOW the cmd)
    // but reuses the EXACT same submit contract (api.command + BLOCKED-as-info handling).
    /** Pin the modal to a single command code, skipping the catalog grid. */
    pinnedCommand?: string;
    /** Display caption for the pinned command (verbatim Korean). */
    pinnedLabel?: string;
    /** Force the arg sub-form for the pinned command (overrides suffix inference). null = no-arg. */
    pinnedArgType?: CommandArgType | null;
    /** min/max/guide forwarded to the amount sub-form (legacy clamp/quick-adjust). */
    amountMin?: number;
    amountMax?: number;
    amountGuide?: number[];
    /** Page-fixed args merged into every submit body (e.g. {auctionId}, {bettingId}, {isUnique}). */
    extraArgs?: Record<string, unknown>;
    /** When true, submits via `POST /api/command/nation/bulk` (nation_turn) instead of the personal `/api/command/{code}` (general_turn). */
    isNationCommand?: boolean;
}

type CommandArgBody = Record<string, unknown> | null;

function normalize(res: AvailableCommandsResponse | null): AvailableCommandCategory[] {
    if (!res) return [];
    if (res.commandTable && res.commandTable.length) return res.commandTable;
    if (res.commands && res.commands.length) {
        // Flat list → single-category bucket (the endpoint did not group).
        return [{ category: '명령', values: res.commands }];
    }
    return [];
}

function resolveArgType(cmd: AvailableCommand): CommandArgType | null {
    if (!cmd.reqArg) return null;
    return cmd.argType ?? inferArgType(cmd.value);
}

export default function CommandModal({
    onClose,
    onToast,
    generalId,
    nationId,
    turnIdx = 0,
    onReserved,
    pinnedCommand,
    pinnedLabel,
    pinnedArgType,
    amountMin,
    amountMax,
    amountGuide,
    extraArgs,
    isNationCommand,
}: CommandModalProps) {
    // Pinned command (F4 C1): synthesize a one-item AvailableCommand and pre-select it so the modal
    // opens straight on the arg sub-form. `reqArg` follows whether an argType was supplied.
    const pinned: AvailableCommand | null = pinnedCommand
        ? {
              value: pinnedCommand,
              simpleName: pinnedLabel ?? pinnedCommand,
              title: pinnedLabel ?? pinnedCommand,
              compensation: 0,
              possible: true,
              reqArg: pinnedArgType != null,
              argType: pinnedArgType ?? undefined,
          }
        : null;

    const [catalog, setCatalog] = useState<AvailableCommandCategory[]>([]);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [cat, setCat] = useState<string>('');
    const [selected, setSelected] = useState<AvailableCommand | null>(pinned);
    const [argValue, setArgValue] = useState<number | null>(null);
    const [argBody, setArgBody] = useState<CommandArgBody>(null);
    const [loading, setLoading] = useState(false);
    const [blockedReason, setBlockedReason] = useState<string | null>(null);

    useEffect(() => {
        // Pinned mode skips the catalog fetch entirely — we already know the single command.
        if (pinnedCommand) return;
        let on = true;
        api.availableCommands<AvailableCommandsResponse>(generalId)
            .then((res) => {
                if (!on) return;
                const norm = normalize(res);
                if (norm.length) {
                    setCatalog(norm);
                    setCat(norm[0].category);
                    setLoadError(null);
                } else {
                    setCatalog([]);
                    setCat('');
                    setLoadError('명령 목록을 불러오지 못했습니다.');
                }
            })
            .catch(() => {
                if (!on) return;
                setCatalog([]);
                setCat('');
                setLoadError('명령 목록을 불러오지 못했습니다.');
            });
        return () => {
            on = false;
        };
    }, [generalId, pinnedCommand]);

    const categories = useMemo(() => catalog.map((c) => c.category), [catalog]);
    const filtered = useMemo(() => catalog.find((c) => c.category === cat)?.values ?? [], [catalog, cat]);
    const argType = selected ? resolveArgType(selected) : null;

    function pick(cmd: AvailableCommand) {
        setBlockedReason(null);
        if (!cmd.reqArg) {
            // No-arg → reserve instantly (legacy reqArg===false path).
            void submit(cmd, {});
            return;
        }
        setSelected(cmd);
        setArgValue(null);
        setArgBody(null);
    }

    async function submit(cmd: AvailableCommand, body: Record<string, unknown>) {
        setLoading(true);
        setBlockedReason(null);
        try {
            // Page-fixed args (auctionId/bettingId/nationId/isUnique …) merge BENEATH the picked arg
            // so an explicit user pick wins on a key collision.
            const fullBody = { ...(extraArgs ?? {}), ...body };
            let res: { status: string; reason?: string };
            if (isNationCommand) {
                res = await api.commandQueue.nationBulk(generalId, [
                    { action: cmd.value, turnList: [turnIdx], arg: fullBody },
                ]);
            } else {
                res = await api.command<{ status: string; reason?: string }>(
                    cmd.value,
                    fullBody,
                    generalId,
                    turnIdx,
                );
            }
            if (res.status === 'AVAILABLE') {
                onToast(`${cmd.simpleName} 명령이 예약되었습니다.`, 'success');
                onReserved?.();
                onClose();
            } else {
                // BLOCKED / UNKNOWN → render the PHP-faithful reason as info (NOT an error).
                setBlockedReason(res.reason ?? '명령을 예약할 수 없습니다.');
            }
        } catch (e) {
            onToast(e instanceof Error ? e.message : '명령 실패', 'error');
        } finally {
            setLoading(false);
        }
    }

    function submitWithArg() {
        if (!selected || !argType) return;
        if (argBody != null) {
            void submit(selected, argBody);
            return;
        }
        if (argValue == null) {
            setBlockedReason('대상을 선택해 주세요.');
            return;
        }
        const fieldName = argFieldName(argType);
        if (!fieldName) {
            setBlockedReason('대상을 선택해 주세요.');
            return;
        }
        void submit(selected, { [fieldName]: argValue });
    }

    function compensationTag(c: number) {
        if (c > 0) return <span className="cmd-comp-pos">▲</span>;
        if (c < 0) return <span className="cmd-comp-neg">▼</span>;
        return null;
    }

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div className="modal-content" onClick={(e) => e.stopPropagation()}>
                <div className="modal-header">
                    <h2>명령</h2>
                    <button onClick={onClose} aria-label="닫기">×</button>
                </div>

                {loadError && <p className="cmd-flag">{loadError}</p>}

                {!selected ? (
                    <>
                        {categories.length > 0 && (
                            <div className="cmd-cats">
                                {categories.map((c) => (
                                    <button key={c} className={cat === c ? 'active' : ''} onClick={() => setCat(c)}>
                                        {c}
                                    </button>
                                ))}
                            </div>
                        )}
                        {filtered.length > 0 ? (
                            <div className="cmd-grid">
                                {filtered.map((cmd) => (
                                    <button
                                        key={cmd.value}
                                        className="cmd-item"
                                        title={cmd.title}
                                        onClick={() => pick(cmd)}
                                        disabled={loading}
                                    >
                                        <span className={cmd.possible ? '' : 'cmd-impossible'}>
                                            {cmd.simpleName} {compensationTag(cmd.compensation)}
                                        </span>
                                        {cmd.title && cmd.title !== cmd.simpleName && (
                                            <small className="cmd-item-sub">
                                                {cmd.title.startsWith(cmd.simpleName)
                                                    ? cmd.title.substring(cmd.simpleName.length)
                                                    : cmd.title}
                                            </small>
                                        )}
                                    </button>
                                ))}
                            </div>
                        ) : (
                            <div className="cmd-empty">표시할 명령이 없습니다.</div>
                        )}
                    </>
                ) : (
                    <>
                        <button
                            className="cmd-back"
                            onClick={() => {
                                setBlockedReason(null);
                                // Pinned mode has no catalog to return to → close the modal.
                                if (pinnedCommand) onClose();
                                else {
                                    setSelected(null);
                                    setArgValue(null);
                                    setArgBody(null);
                                }
                            }}
                        >
                            ← 뒤로
                        </button>
                        <h3>{selected.simpleName}</h3>
                        {selected.title && selected.title !== selected.simpleName && (
                            <p className="cmd-item-sub">{selected.title}</p>
                        )}
                        <div className="cmd-form">
                            {argType === 'city' && (
                                <SelectCityField
                                    commandKey={selected.value}
                                    commandName={selected.simpleName}
                                    value={argValue}
                                    onChange={setArgValue}
                                />
                            )}
                            {argType === 'general' && (
                                <SelectGeneralField value={argValue} onChange={setArgValue} ownGeneralId={generalId} />
                            )}
                            {argType === 'nation' && (
                                <SelectNationField value={argValue} onChange={setArgValue} ownNationId={nationId} />
                            )}
                            {argType === 'amount' && (
                                <SelectAmountField
                                    value={argValue}
                                    onChange={setArgValue}
                                    min={amountMin}
                                    max={amountMax}
                                    guide={amountGuide}
                                />
                            )}
                            {argType === 'founding' && <SelectFoundingField onChange={setArgBody} />}
                            {argType === 'recruit' && <SelectRecruitField onChange={setArgBody} />}
                            {/* reqArg but unknown argType → free numeric target input (never crash). */}
                            {selected.reqArg && !argType && (
                                <label>
                                    <span>대상</span>
                                    <input
                                        type="number"
                                        value={argValue ?? ''}
                                        onChange={(e) => setArgValue(Number(e.target.value))}
                                    />
                                </label>
                            )}
                            <button
                                className="cmd-submit"
                                onClick={() => (selected.reqArg ? submitWithArg() : void submit(selected, {}))}
                                disabled={loading}
                            >
                                {loading ? '처리 중...' : '예약'}
                            </button>
                        </div>
                    </>
                )}

                {/* BLOCKED / UNKNOWN reason — info, not error (PHP-faithful deny string). */}
                {blockedReason && <p className="cmd-blocked">{blockedReason}</p>}
            </div>
        </div>
    );
}
