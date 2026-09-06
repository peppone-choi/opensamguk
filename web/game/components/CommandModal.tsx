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
//   - intake `AVAILABLE` means queued only; wait for the terminal command result before showing success.
//   - terminal deny / pending renders the PHP-faithful reason or 처리 지연 as INFO and keeps the modal open.
//
// No-arg commands reserve instantly on click; reqArg commands open the relevant field sub-form first.
// If availableCommands() is absent/empty, surface the failure instead of fabricating a local command list.

import { Modal, Portrait, Tile } from '@opensamguk/ui';
import { useEffect, useMemo, useState } from 'react';
import { api } from '../lib/api';
import {
    inferArgType,
    argFieldName,
    commandForm,
    type CommandFieldSpec,
    type CommandFormSpec,
} from '../lib/command-arg-types';
import { submitCommandAndAwaitResult } from '../lib/commandSubmit';
import type { GameConstResponse } from '../lib/types';
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
    /** Resolve a pinned command's complete row from the server catalog before it can be submitted. */
    resolvePinnedFromCatalog?: boolean;
    /** min/max/guide forwarded to the amount sub-form (legacy clamp/quick-adjust). */
    amountMin?: number;
    amountMax?: number;
    amountGuide?: number[];
    /** Page-fixed args merged into every submit body (e.g. {auctionId}, {bettingId}, {isUnique}). */
    extraArgs?: Record<string, unknown>;
    /** When true, submits via `POST /api/command/nation/bulk` (nation_turn) instead of the personal `/api/command/{code}` (general_turn). */
    isNationCommand?: boolean;
    /** 모달 헤더의 히어로 초상(04 아트보드) — 조작 대상 장수. 없으면 헤더는 제목만. */
    hero?: { picture?: string | null; imageServer?: number | null; name?: string | null; nationColor?: string | null } | null;
}

type CommandArgBody = Record<string, unknown> | null;
type CommandFormValues = Record<string, unknown>;

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

const FIELD_LABELS: Record<string, string> = {
    amount: '금액',
    amountList: '금 / 쌀',
    buyRice: '군량 구입',
    colorType: '국가 색상',
    commandType: '전략 명령',
    destArmType: '전환 대상 병과',
    destCityID: '도시',
    destGeneralID: '장수',
    destNationID: '국가',
    isGold: '금 사용',
    itemCode: '장비',
    itemType: '장비 종류',
    month: '월',
    nationName: '국명',
    nationType: '국가 성향',
    srcArmType: '감소 대상 병과',
    year: '년',
};

function initialFieldValue(field: CommandFieldSpec): unknown {
    if (field.control === 'toggle') return true;
    if (field.control === 'amountList') return [field.min ?? 0, field.min ?? 0];
    if (field.control === 'amount') return field.min ?? 0;
    if (field.control === 'number') return field.min ?? null;
    if (field.control === 'text') return '';
    return null;
}

function initialFormValues(spec: CommandFormSpec): CommandFormValues {
    return Object.fromEntries(spec.fields.map((field) => [field.name, initialFieldValue(field)]));
}

function fieldComplete(field: CommandFieldSpec, value: unknown): boolean {
    if (!field.required) return true;
    if (field.control === 'amountList') {
        return Array.isArray(value) && value.length === 2 && value.every((item) =>
            typeof item === 'number'
            && Number.isFinite(item)
            && (field.min == null || item >= field.min)
            && (field.max == null || item <= field.max),
        );
    }
    if (field.valueType === 'string') {
        return typeof value === 'string'
            && value.trim().length > 0
            && (field.min == null || value.length >= field.min)
            && (field.max == null || value.length <= field.max);
    }
    if (field.valueType === 'bool') return typeof value === 'boolean';
    return typeof value === 'number'
        && Number.isFinite(value)
        && (field.min == null || value >= field.min)
        && (field.max == null || value <= field.max);
}

interface StructuredCommandFormProps {
    spec: CommandFormSpec;
    values: CommandFormValues;
    onChange: (name: string, value: unknown) => void;
    command: AvailableCommand;
    generalId: number;
    nationId?: number;
}

function StructuredCommandForm({
    spec,
    values,
    onChange,
    command,
    generalId,
    nationId,
}: StructuredCommandFormProps) {
    const [constants, setConstants] = useState<GameConstResponse | null>(null);

    useEffect(() => {
        if (!spec.fields.some((field) => field.control === 'select')) return;
        let active = true;
        api.gameConst()
            .then((result) => {
                if (active) setConstants(result);
            })
            .catch(() => {
                if (active) setConstants(null);
            });
        return () => {
            active = false;
        };
    }, [spec]);

    function selectOptions(field: CommandFieldSpec): Array<{ value: string | number; label: string }> {
        if (field.optionSource === 'armTypes') {
            return Array.from(new Set(constants?.gameUnitConst?.map((unit) => unit.armType) ?? []))
                .map((value) => ({ value, label: String(value) }));
        }
        if (field.optionSource === 'crewTypes') {
            return (constants?.gameUnitConst ?? []).map((unit) => ({ value: unit.id, label: unit.name }));
        }
        if (field.optionSource === 'nationColors') {
            const colors = constants?.gameConst?.nationColors ?? [];
            return colors.map((color, value) => ({ value, label: color }));
        }
        if (field.optionSource === 'nationTypes') {
            return (constants?.iAction?.nationType ?? []).map((item) => ({
                value: item.value,
                label: item.name ?? item.value,
            }));
        }
        if (field.optionSource === 'items') {
            return (constants?.iAction?.item ?? []).map((item) => ({
                value: item.value,
                label: item.name ?? item.value,
            }));
        }
        if (field.optionSource === 'itemTypes') {
            return [
                { value: 'horse', label: '명마' },
                { value: 'weapon', label: '무기' },
                { value: 'book', label: '서적' },
                { value: 'item', label: '도구' },
            ];
        }
        if (field.optionSource === 'strategyCommands') {
            const table = constants?.gameConst?.availableChiefCommand;
            if (!table || typeof table !== 'object' || Array.isArray(table)) return [];
            const strategy = (table as Record<string, unknown>)['전략'];
            if (!Array.isArray(strategy)) return [];
            return strategy.filter((value): value is string => typeof value === 'string')
                .map((value) => ({ value, label: value.replace(/^che_/, '') }));
        }
        return [];
    }

    return (
        <>
            {spec.fields.map((field) => {
                const value = values[field.name];
                const label = FIELD_LABELS[field.name] ?? field.name;
                if (field.optionSource === 'cities') {
                    return (
                        <label key={field.name}>
                            <span>{label}</span>
                            <SelectCityField
                                commandKey={command.value}
                                commandName={command.simpleName}
                                value={typeof value === 'number' ? value : null}
                                onChange={(next) => onChange(field.name, next)}
                            />
                        </label>
                    );
                }
                if (field.optionSource === 'generals') {
                    return (
                        <label key={field.name}>
                            <span>{label}</span>
                            <SelectGeneralField
                                value={typeof value === 'number' ? value : null}
                                onChange={(next) => onChange(field.name, next)}
                                ownGeneralId={generalId}
                            />
                        </label>
                    );
                }
                if (field.optionSource === 'nations') {
                    return (
                        <label key={field.name}>
                            <span>{label}</span>
                            <SelectNationField
                                value={typeof value === 'number' ? value : null}
                                onChange={(next) => onChange(field.name, next)}
                                ownNationId={nationId}
                            />
                        </label>
                    );
                }
                if (field.control === 'amount') {
                    return (
                        <label key={field.name}>
                            <span>{label}</span>
                            <SelectAmountField
                                value={typeof value === 'number' ? value : null}
                                onChange={(next) => onChange(field.name, next)}
                                min={field.min ?? undefined}
                                max={field.max ?? undefined}
                            />
                        </label>
                    );
                }
                if (field.control === 'amountList') {
                    const amounts = Array.isArray(value) ? value : [field.min ?? 0, field.min ?? 0];
                    return (
                        <fieldset key={field.name}>
                            <legend>{label}</legend>
                            {['금', '쌀'].map((resource, index) => (
                                <label key={resource}>
                                    <span>{resource}</span>
                                    <input
                                        type="number"
                                        value={typeof amounts[index] === 'number' ? amounts[index] : 0}
                                        min={field.min ?? undefined}
                                        max={field.max ?? undefined}
                                        onChange={(event) => {
                                            const next = [...amounts];
                                            next[index] = Number(event.target.value);
                                            onChange(field.name, next);
                                        }}
                                    />
                                </label>
                            ))}
                        </fieldset>
                    );
                }
                if (field.control === 'toggle') {
                    return (
                        <label key={field.name}>
                            <input
                                type="checkbox"
                                checked={value === true}
                                onChange={(event) => onChange(field.name, event.target.checked)}
                            />
                            <span>{label}</span>
                        </label>
                    );
                }
                if (field.control === 'select') {
                    const options = selectOptions(field);
                    return (
                        <label key={field.name}>
                            <span>{label}</span>
                            <select
                                value={value == null ? '' : String(value)}
                                onChange={(event) => {
                                    const next = field.valueType === 'int'
                                        ? Number(event.target.value)
                                        : event.target.value;
                                    onChange(field.name, next);
                                }}
                            >
                                <option value="">선택</option>
                                {options.map((option) => (
                                    <option key={option.value} value={option.value}>{option.label}</option>
                                ))}
                            </select>
                        </label>
                    );
                }
                return (
                    <label key={field.name}>
                        <span>{label}</span>
                        <input
                            type={field.control === 'number' ? 'number' : 'text'}
                            value={typeof value === 'string' || typeof value === 'number' ? value : ''}
                            min={field.min ?? undefined}
                            max={field.max ?? undefined}
                            minLength={field.control === 'text' ? field.min ?? undefined : undefined}
                            maxLength={field.control === 'text' ? field.max ?? undefined : undefined}
                            required={field.required}
                            onChange={(event) => {
                                const next = field.valueType === 'int'
                                    ? Number(event.target.value)
                                    : event.target.value;
                                onChange(field.name, next);
                            }}
                        />
                    </label>
                );
            })}
        </>
    );
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
    resolvePinnedFromCatalog = false,
    amountMin,
    amountMax,
    amountGuide,
    extraArgs,
    isNationCommand,
    hero = null,
}: CommandModalProps) {
    // Pinned command (F4 C1): synthesize a fallback one-item command so the modal opens straight on
    // an arg sub-form. Opt-in catalog resolution replaces it with the authoritative server row.
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
    const [formValues, setFormValues] = useState<CommandFormValues>({});
    const [loading, setLoading] = useState(false);
    const [blockedReason, setBlockedReason] = useState<string | null>(null);
    const [resolvingPinnedCommand, setResolvingPinnedCommand] = useState(
        pinnedCommand != null && resolvePinnedFromCatalog,
    );
    const [pinnedCatalogError, setPinnedCatalogError] = useState<string | null>(null);

    useEffect(() => {
        if (pinnedCommand) {
            if (!resolvePinnedFromCatalog) {
                setResolvingPinnedCommand(false);
                setPinnedCatalogError(null);
                return;
            }

            let on = true;
            setResolvingPinnedCommand(true);
            setPinnedCatalogError(null);
            api.availableCommands<AvailableCommandsResponse>(generalId)
                .then((res) => {
                    if (!on) return;
                    const matched = normalize(res)
                        .flatMap((category) => category.values)
                        .find((command) => command.value === pinnedCommand);
                    const matchedForm = matched ? commandForm(matched) : null;
                    if (!matched || !matchedForm) {
                        setPinnedCatalogError('명령 정보를 불러오지 못했습니다.');
                        return;
                    }
                    setSelected(matched);
                    setFormValues(initialFormValues(matchedForm));
                })
                .catch(() => {
                    if (on) setPinnedCatalogError('명령 정보를 불러오지 못했습니다.');
                })
                .finally(() => {
                    if (on) setResolvingPinnedCommand(false);
                });
            return () => {
                on = false;
            };
        }
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
    }, [generalId, pinnedCommand, resolvePinnedFromCatalog]);

    const categories = useMemo(() => catalog.map((c) => c.category), [catalog]);
    const filtered = useMemo(() => catalog.find((c) => c.category === cat)?.values ?? [], [catalog, cat]);
    const argType = selected ? resolveArgType(selected) : null;
    const formSpec = selected ? commandForm(selected) : null;

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
        setFormValues(commandForm(cmd) ? initialFormValues(commandForm(cmd)!) : {});
    }

    async function submit(cmd: AvailableCommand, body: Record<string, unknown>) {
        setLoading(true);
        setBlockedReason(null);
        try {
            // Page-fixed args (auctionId/bettingId/nationId/isUnique …) merge BENEATH the picked arg
            // so an explicit user pick wins on a key collision.
            const fullBody = { ...(extraArgs ?? {}), ...body };
            const terminalResult = await submitCommandAndAwaitResult(() => {
                if (isNationCommand) {
                    return api.commandQueue.nationBulk(generalId, [
                        { action: cmd.value, turnList: [turnIdx], arg: fullBody },
                    ]);
                }
                return api.command(cmd.value, fullBody, generalId, turnIdx);
            });
            if (terminalResult.status === 'applied') {
                onToast(`${cmd.simpleName} 명령이 실행되었습니다.`, 'success');
                onReserved?.();
                onClose();
            } else if (terminalResult.status === 'reserved') {
                onToast(`${cmd.simpleName} 명령이 예약되었습니다.`, 'success');
                onReserved?.();
                onClose();
            } else {
                setBlockedReason(terminalResult.reason ?? '명령을 예약할 수 없습니다.');
            }
        } catch (e) {
            onToast(e instanceof Error ? e.message : '명령 실패', 'error');
        } finally {
            setLoading(false);
        }
    }

    function submitWithArg() {
        if (!selected) return;
        if (argBody != null) {
            void submit(selected, argBody);
            return;
        }
        if (formSpec) {
            if (formSpec.fields.some((field) => !fieldComplete(field, formValues[field.name]))) {
                setBlockedReason('필수 값을 입력해 주세요.');
                return;
            }
            void submit(selected, formValues);
            return;
        }
        if (!argType) return;
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
        <Modal
            ariaLabel={pinnedLabel ? `명령: ${pinnedLabel}` : '명령'}
            className="modal-content"
            overlayClassName="modal-overlay"
            onClose={onClose}
        >
                <div className={`modal-header cmd-header${hero ? ' cmd-header--hero' : ''}`}>
                    {hero && (
                        <div className="cmd-header__hero" aria-hidden="true">
                            <Portrait picture={hero.picture} imageServer={hero.imageServer} size="hero" alt="" />
                        </div>
                    )}
                    <div className="cmd-header__text">
                        <h2 className="os-serif">명령</h2>
                        {hero?.name && <span className="cmd-header__who">{hero.name}{turnIdx != null ? ` · ${turnIdx + 1}순` : ''}</span>}
                    </div>
                    <button type="button" className="os-button os-button--ghost os-button--sm cmd-close" onClick={onClose} aria-label="닫기">×</button>
                </div>

                {loadError && <p className="cmd-flag">{loadError}</p>}

                {!selected ? (
                    <>
                        {categories.length > 0 && (
                            <div className="cmd-cats os-pill-tabs" role="tablist" aria-label="명령 분류">
                                {categories.map((c) => (
                                    <button key={c} type="button" role="tab" aria-selected={cat === c} className={cat === c ? 'active os-pill-tabs__on' : ''} onClick={() => setCat(c)}>
                                        {c}
                                    </button>
                                ))}
                            </div>
                        )}
                        {filtered.length > 0 ? (
                            <div className="cmd-grid">
                                {filtered.map((cmd) => {
                                    const sub = cmd.title && cmd.title !== cmd.simpleName
                                        ? (cmd.title.startsWith(cmd.simpleName) ? cmd.title.substring(cmd.simpleName.length) : cmd.title)
                                        : null;
                                    const name = (
                                        <span className={cmd.possible ? '' : 'cmd-impossible'}>
                                            {cmd.simpleName} {compensationTag(cmd.compensation)}
                                        </span>
                                    );
                                    // 명령 상태 4종(S1): 사용 가능 / 대상 필요 / 사용 불가 + 이유. 사용 불가는 점선으로 남기고 이유를 붙인다.
                                    if (!cmd.possible) {
                                        return (
                                            <Tile key={cmd.value} className="cmd-item" name={name} cost={sub} state="no" reason={cmd.info?.trim() || cmd.title || '지금은 사용할 수 없습니다'} />
                                        );
                                    }
                                    return (
                                        <Tile
                                            key={cmd.value}
                                            className="cmd-item"
                                            name={name}
                                            cost={sub}
                                            state={cmd.reqArg ? 'need' : 'ok'}
                                            title={cmd.title}
                                            onClick={() => pick(cmd)}
                                            {...(loading ? { disabled: true } : {})}
                                        />
                                    );
                                })}
                            </div>
                        ) : (
                            <div className="cmd-empty">표시할 명령이 없습니다.</div>
                        )}
                    </>
                ) : (
                    <>
                        <button
                            type="button"
                            className="cmd-back os-button os-button--ghost os-button--sm"
                            onClick={() => {
                                setBlockedReason(null);
                                // Pinned mode has no catalog to return to → close the modal.
                                if (pinnedCommand) onClose();
                                else {
                                    setSelected(null);
                                    setArgValue(null);
                                    setArgBody(null);
                                    setFormValues({});
                                }
                            }}
                        >
                            ← 뒤로
                        </button>
                        <h3>{selected.simpleName}</h3>
                        {selected.title && selected.title !== selected.simpleName && (
                            <p className="cmd-item-sub">{selected.title}</p>
                        )}
                        {resolvingPinnedCommand ? (
                            <p className="cmd-empty">명령 정보를 불러오는 중...</p>
                        ) : pinnedCatalogError ? (
                            <p className="cmd-flag">{pinnedCatalogError}</p>
                        ) : (
                            <div className="cmd-form">
                            {formSpec && argType !== 'founding' && argType !== 'recruit' && (
                                <StructuredCommandForm
                                    spec={formSpec}
                                    values={formValues}
                                    onChange={(name, value) => {
                                        setFormValues((current) => ({ ...current, [name]: value }));
                                        setBlockedReason(null);
                                    }}
                                    command={selected}
                                    generalId={generalId}
                                    nationId={nationId}
                                />
                            )}
                            {!formSpec && argType === 'city' && (
                                <SelectCityField
                                    commandKey={selected.value}
                                    commandName={selected.simpleName}
                                    value={argValue}
                                    onChange={setArgValue}
                                />
                            )}
                            {!formSpec && argType === 'general' && (
                                <SelectGeneralField value={argValue} onChange={setArgValue} ownGeneralId={generalId} />
                            )}
                            {!formSpec && argType === 'nation' && (
                                <SelectNationField value={argValue} onChange={setArgValue} ownNationId={nationId} />
                            )}
                            {!formSpec && argType === 'amount' && (
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
                            {selected.reqArg && !argType && !formSpec && (
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
                                type="button"
                                className="cmd-submit os-button os-button--primary"
                                onClick={() => (selected.reqArg ? submitWithArg() : void submit(selected, {}))}
                                disabled={loading}
                                title={loading ? '처리 중입니다' : undefined}
                            >
                                {loading ? '처리 중...' : '예약'}
                            </button>
                            </div>
                        )}
                    </>
                )}

                {/* BLOCKED / UNKNOWN reason — info, not error (PHP-faithful deny string). */}
                {blockedReason && <p className="cmd-blocked">{blockedReason}</p>}
        </Modal>
    );
}
