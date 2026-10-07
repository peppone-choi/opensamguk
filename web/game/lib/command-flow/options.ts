// 명령 흐름 대상 후보 어댑터 — 지금 서버의 옵션 읽기 15종을 한 모양(필드 · 후보 · 미리보기)으로 편다.
//
// 서버가 준 값만 옮긴다. 후보 거리 · 경로(U-01) · 일괄 가능 여부(K6-01)는 서버에 없으므로 여기서 만들지 않는다.
// 사유 문장은 서버 reason을 그대로 쓴다. 사유가 없으면 null — 화면이 「지금은 고를 수 없습니다」만 쓴다.
// PLANNED(처리기 없음)는 부르지 않는다 — 화면은 「준비 중」.
import { api } from '../api';
import type { RoadForts, ScoutOptions, Sieges } from '../campaign-reads';
import type {
    DeployOptions, DirectActionId, DirectActionOptions, EnlistmentOptionsResponse, FieldActionId, FieldOptions,
    MilitaryActionId, MilitaryOptions, PeopleActionId, PeopleOptions, PersonalActionId, PersonalOptions,
    PoliticalActionId, PoliticalOption, TransferActionId, TransferOptions, TravelActionId, TravelOptions,
} from '../types';
import { flowCommand, type ArgKind } from './catalog';
import type { Draft } from './flow-state';
import { EMPTY_COMMAND_NAMES, reservedCommandText, type ReservedCommandNames } from './reserved-command-view';

export interface Candidate {
    /** 초안에 적는 값(문자열). 서버로 보낼 때 buildArgs가 숫자로 바꾼다. */
    readonly value: string;
    readonly label: string;
    readonly available: boolean;
    readonly reason: string | null;
    /** 둘째 줄 — 서버가 준 수치만(병력 · 남은 양). */
    readonly detail?: string | null;
    /** 수량 상한 — 이 후보를 고르면 수량 칸의 최댓값이 된다. */
    readonly max?: number | null;
    /** 선택지 후보(key = 'choice')가 서버로 보낼 인자 — 서버가 준 arguments 그대로. */
    readonly args?: Readonly<Record<string, string | number>>;
}

export interface ArgField {
    /** 서버 인자 키 그대로 — 같은 키는 명령을 바꿔도 이어받는다(flow-state CARRY_KEYS). */
    readonly key: string;
    readonly kind: ArgKind;
    readonly label: string;
    readonly candidates: readonly Candidate[];
    readonly multiple?: boolean;
    /** amount 칸 — 이 키에서 고른 후보의 max가 상한이다. */
    readonly maxFrom?: string;
}

export interface PreviewRow {
    readonly label: string;
    readonly now: number | null;
    readonly after: number | null;
}

export type CommandOptions =
    | { readonly state: 'PLANNED' }
    /** 읽기가 안 됨(규칙 밖 월드 · 준비 안 됨) — 서버 status를 그대로. */
    | { readonly state: 'UNREADABLE'; readonly status: string }
    | {
        readonly state: 'READY';
        readonly available: boolean;
        readonly code: string | null;
        readonly reason: string | null;
        readonly fields: readonly ArgField[];
        readonly preview: readonly PreviewRow[];
        /** 명령이 일어나는 곳(내정 · 군사의 「이 현」). 서버가 준 이름만. */
        readonly place: string | null;
    };

type Ready = Extract<CommandOptions, { state: 'READY' }>;

const RESOURCE_LABELS: Readonly<Record<string, string>> = { MONEY: '금', GRAIN: '쌀', IRON: '철', TIMBER: '목재', HORSES: '말' };
const STAT_LABELS: Readonly<Record<string, string>> = {
    leadership: '통솔', strength: '무력', intelligence: '지력', politics: '정치', charm: '매력',
};

const TRAVEL = new Set(['action.move', 'action.forcedMarch', 'action.return']);
const FIELD = new Set(['action.farm', 'action.commerce', 'action.fortify', 'action.repairWall', 'action.security',
    'action.settle', 'action.selectResidents', 'action.tour']);
const MILITARY = new Set(['action.conscript', 'action.raiseVolunteers', 'action.train', 'action.boostMorale',
    'action.muster', 'action.demobilize']);
const PERSONAL = new Set(['action.travel', 'action.selfTrain', 'action.recuperate', 'action.retire']);
const PEOPLE = new Set(['action.search', 'action.employ', 'action.persuadeCaptive']);
const POLITICAL = new Set(['action.resign', 'action.rise', 'action.foundState', 'action.independence',
    'action.dissolve', 'action.abdicate', 'action.oath']);
const TRANSFER = new Set(['action.gift', 'action.donate']);
const DIRECT = new Set(['action.convertProficiency', 'action.tradeEquipment', 'action.tradeGrain', 'action.transport']);
const SIEGE = new Set(['action.assault', 'action.demandSurrender']);

const n = (v: number | null | undefined): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const s = (v: string | null | undefined): string | null => (v == null || v === '' ? null : v);

function ready(p: { available: boolean; code?: string | null; reason?: string | null }, rest: Partial<Omit<Ready, 'state'>> = {}): Ready {
    return {
        state: 'READY', available: p.available, code: s(p.code), reason: s(p.reason),
        fields: [], preview: [], place: null, ...rest,
    };
}

// ── 옵션 응답 → 한 모양 (순수 함수 — __tests__에서 시험) ─────────────────────────

export function fromTravel(o: TravelOptions): Ready {
    if (o.inputId === 'action.return') return ready(o);
    return ready(o, {
        fields: [{
            key: 'destinationProvinceId', kind: 'province', label: '어디로',
            candidates: o.destinations.map(d => ({ value: d.provinceId, label: d.name, available: d.available, reason: s(d.reason) })),
        }],
    });
}

export function fromField(o: FieldOptions): Ready {
    return ready(o, { place: s(o.countyName) });
}

export function fromMilitary(o: MilitaryOptions): Ready {
    const rows: PreviewRow[] = [
        { label: '병력', now: n(o.troops), after: n(o.troopsAfter) },
        { label: '훈련', now: n(o.training), after: n(o.trainingAfter) },
        { label: '사기', now: n(o.morale), after: n(o.moraleAfter) },
        { label: '현 인구', now: null, after: n(o.populationAfter) },
        { label: '드는 쌀', now: null, after: n(o.grainCost) },
        { label: '드는 금', now: null, after: n(o.moneyCost) },
        { label: '모이는 부곡', now: null, after: n(o.gatheringCorps) },
    ];
    return ready(o, { place: s(o.countyName), preview: rows.filter(r => r.now != null || r.after != null) });
}

export function fromDeploy(o: DeployOptions): Ready {
    return ready(o, {
        fields: [
            {
                key: 'bugokIds', kind: 'units', label: '보낼 부곡', multiple: true,
                candidates: o.bugoks.map(b => ({ value: String(b.id), label: b.name, available: b.available, reason: s(b.reason), detail: `병력 ${b.troops}` })),
            },
            {
                key: 'destinationProvinceId', kind: 'province', label: '어디로',
                candidates: o.destinations.map(d => ({ value: d.provinceId, label: d.name, available: true, reason: null })),
            },
        ],
    });
}

export function fromPersonal(o: PersonalOptions): Ready {
    if (o.inputId === 'action.selfTrain') {
        return ready(o, {
            fields: [{
                key: 'stat', kind: 'choice', label: '익힐 능력',
                candidates: (o.stats ?? []).map(x => ({ value: x.stat, label: STAT_LABELS[x.stat] ?? x.stat, available: x.available, reason: s(x.reason) })),
            }],
        });
    }
    if (o.inputId === 'action.retire') {
        return ready(o, {
            fields: [{
                key: 'successorGeneralId', kind: 'person', label: '뒤를 이을 사람',
                candidates: (o.successors ?? []).map(x => ({ value: String(x.generalId), label: x.name, available: x.available, reason: s(x.reason) })),
            }],
        });
    }
    return ready(o);
}

export function fromPeople(o: PeopleOptions): Ready {
    if (o.inputId === 'action.search') {
        const left = n(o.undiscoveredCount);
        return ready(o, { preview: left == null ? [] : [{ label: '아직 못 찾은 인물', now: left, after: null }] });
    }
    return ready(o, {
        fields: [{
            key: 'targetGeneralId', kind: 'person', label: o.inputId === 'action.employ' ? '데려올 사람' : '설득할 사람',
            candidates: o.targets.map(t => ({ value: String(t.generalId), label: t.name, available: t.available, reason: s(t.reason) })),
        }],
    });
}

export function fromPolitical(list: readonly PoliticalOption[], inputId: string): CommandOptions {
    const o = list.find(x => x.inputId === inputId);
    const command = flowCommand(inputId);
    if (!o || !command) return { state: 'UNREADABLE', status: 'NO_ROW' };
    // The command's existing argument definition decides whether a person is required.
    // The server also sends an empty targets array for commands with no arguments.
    if (!command.args.includes('person')) return ready(o);
    return ready(o, {
        fields: [{
            key: 'targetGeneralId', kind: 'person', label: inputId === 'action.abdicate' ? '물려받을 사람' : '맹세할 상대',
            candidates: (o.targets ?? []).map(t => ({ value: String(t.generalId), label: t.name, available: t.available, reason: s(t.reason) })),
        }],
    });
}

export function fromTransfer(o: TransferOptions): Ready {
    const fields: ArgField[] = [];
    if (o.inputId === 'action.gift') {
        fields.push({
            key: 'targetGeneralId', kind: 'person', label: '받을 사람',
            candidates: o.targets.map(t => ({ value: String(t.generalId), label: t.name, available: t.available, reason: s(t.reason) })),
        });
    }
    fields.push(
        {
            key: 'resource', kind: 'resource', label: '무엇을',
            candidates: o.resources.map(r => ({
                value: r.resource, label: RESOURCE_LABELS[r.resource] ?? r.resource, available: r.available,
                reason: s(r.reason), detail: `최대 ${r.maxAmount}`, max: r.maxAmount,
            })),
        },
        { key: 'amount', kind: 'amount', label: '얼마나', candidates: [], maxFrom: 'resource' },
    );
    return ready(o, { fields });
}

/** 선택지형(병종 바꿔 익히기 · 쌀 사고팔기 · 장비 사고팔기 · 수송) — 후보 값은 순번, 인자는 서버가 준 arguments. */
export function fromDirect(o: DirectActionOptions, names: ReservedCommandNames = EMPTY_COMMAND_NAMES): Ready {
    const fields: ArgField[] = [{
        key: 'choice', kind: 'choice', label: '어떻게',
        candidates: o.choices.map((c, i) => ({
            value: String(i), label: o.inputId === 'action.convertProficiency'
                ? reservedCommandText({ action: o.inputId, brief: '', arg: c.arguments }, names) : c.label,
            available: c.available, reason: s(c.reason), args: c.arguments,
            max: n(c.maxAmount), detail: c.maxAmount == null ? null : `최대 ${c.maxAmount}`,
        })),
    }];
    if (o.inputId === 'action.transport') fields.push({ key: 'amount', kind: 'amount', label: '얼마나', candidates: [], maxFrom: 'choice' });
    return ready(o, { fields });
}

function enlistArgs(mode: string, targetId: number | undefined): Record<string, string | number> {
    return mode === 'RANDOM' || targetId == null ? { mode } : { mode, targetId };
}

export function fromEnlist(o: EnlistmentOptionsResponse): Ready {
    const any = o.options.some(x => x.availability.status === 'AVAILABLE');
    return ready({ available: o.result && any }, {
        fields: [{
            key: 'choice', kind: 'choice', label: '어디로',
            candidates: o.options.map(x => ({
                value: x.mode === 'RANDOM' ? 'RANDOM' : `${x.mode}:${x.targetId}`, label: x.label,
                available: x.availability.status === 'AVAILABLE', reason: s(x.availability.reason),
                args: enlistArgs(x.mode, x.targetId),
            })),
        }],
    });
}

export function fromScout(o: ScoutOptions): CommandOptions {
    if (o.status !== 'READY') return { state: 'UNREADABLE', status: o.status };
    return ready({ available: o.available ?? true, code: o.code, reason: o.reason }, {
        fields: [{
            key: 'commanderyId', kind: 'commandery', label: '살필 군',
            candidates: (o.options ?? []).map(x => ({
                value: x.id, label: x.name, available: x.available, reason: s(x.reason),
                detail: x.ageTurns == null ? null : `${x.ageTurns}순 전에 봄`,
            })),
        }],
    });
}

/** 강공은 서버가 허용한 실제 縣을 고르고, 항복 권고는 기존 무인자 계약을 유지한다. */
export function fromSieges(o: Sieges, generalId: number, inputId: 'action.assault' | 'action.demandSurrender'): CommandOptions {
    if (o.status !== 'READY') return { state: 'UNREADABLE', status: o.status };
    const mine = o.sieges.filter(x => x.status === 'ACTIVE' && x.besieger.generalId === generalId);
    if (inputId === 'action.assault') {
        const available = mine.some(x => x.canAssault);
        const blocked = available ? undefined : mine.find(x => !x.canAssault);
        return ready({
            available,
            code: blocked?.assaultCode,
            reason: mine.length === 0 ? '에워싼 성이 없습니다' : blocked?.assaultReason,
        }, {
            place: s(mine.length === 1 ? mine[0].countyName : null),
            fields: [{
                key: 'targetCountyId', kind: 'county', label: '공격할 현',
                candidates: mine.map(x => ({
                    value: String(x.countyId), label: s(x.countyName) ?? '이름 모를 현',
                    available: x.canAssault, reason: s(x.assaultReason),
                })),
            }],
        });
    }
    const actable = mine.find(x => x.canAct);
    return ready({ available: actable != null }, {
        place: s((actable ?? mine[0])?.countyName),
        reason: mine.length === 0 ? '에워싼 성이 없습니다' : null,
    });
}

export function fromRoadForts(o: RoadForts): CommandOptions {
    if (o.status !== 'READY') return { state: 'UNREADABLE', status: o.status };
    const forts = o.forts.filter(f => f.canBesiege);
    return ready({ available: forts.length > 0 }, {
        reason: forts.length === 0 ? '에울 수 있는 보루가 없습니다' : null,
        fields: [{
            key: 'fortId', kind: 'choice', label: '에울 보루',
            candidates: forts.map(f => ({ value: f.id, label: `보루 · ${f.provinceId}`, available: true, reason: null, detail: `성벽 ${f.wall} · 수비 ${f.garrison}` })),
        }],
    });
}

// ── 서버에서 읽기 ───────────────────────────────────────────────────────────

export async function fetchCommandOptions(inputId: string, generalId: number, signal?: AbortSignal): Promise<CommandOptions> {
    const cmd = flowCommand(inputId);
    if (!cmd || cmd.delivery === 'PLANNED') return { state: 'PLANNED' };
    if (TRAVEL.has(inputId)) return fromTravel(await api.travelOptions(inputId as TravelActionId, generalId));
    if (FIELD.has(inputId)) return fromField(await api.fieldOptions(inputId as FieldActionId, generalId));
    if (MILITARY.has(inputId)) return fromMilitary(await api.militaryOptions(inputId as MilitaryActionId, generalId));
    if (PERSONAL.has(inputId)) return fromPersonal(await api.personalOptions(inputId as PersonalActionId, generalId));
    if (PEOPLE.has(inputId)) return fromPeople(await api.peopleOptions(inputId as PeopleActionId, generalId));
    if (POLITICAL.has(inputId)) return fromPolitical(await api.politicalOptions(generalId), inputId as PoliticalActionId);
    if (TRANSFER.has(inputId)) return fromTransfer(await api.transferOptions(inputId as TransferActionId, generalId));
    if (DIRECT.has(inputId)) {
        const o = await api.legacyDirectOptions(inputId as DirectActionId, generalId);
        if (inputId !== 'action.convertProficiency') return fromDirect(o);
        const bundle = await api.gameConst().catch(() => null);
        return fromDirect(o, { cities: {}, units: Object.fromEntries((bundle?.gameUnitConst ?? []).map(u => [String(u.id), u.name])) });
    }
    if (SIEGE.has(inputId)) return fromSieges(await api.campaignSieges(generalId, signal), generalId,
        inputId as 'action.assault' | 'action.demandSurrender');
    switch (inputId) {
        case 'action.deploy': return fromDeploy(await api.deployOptions(generalId));
        case 'action.enlist': return fromEnlist(await api.enlistmentOptions(generalId));
        case 'action.scout': return fromScout(await api.campaignScoutOptions(generalId, signal));
        case 'action.siegeRoadFort': return fromRoadForts(await api.roadForts(generalId, signal));
    }
    return { state: 'UNREADABLE', status: 'NO_READER' };
}

// ── 초안 → 서버 인자 ────────────────────────────────────────────────────────

export type BuiltArgs =
    | { readonly ok: true; readonly args: Record<string, unknown> }
    | { readonly ok: false; readonly missing: readonly string[] };

/** 고른 후보의 수량 상한 — 없으면 null. */
export function amountMax(options: Ready, field: ArgField, draft: Draft): number | null {
    if (!field.maxFrom) return null;
    const from = options.fields.find(f => f.key === field.maxFrom);
    const picked = from?.candidates.find(c => c.value === draft[field.maxFrom!]);
    return picked?.max ?? null;
}

const NUMERIC_KEYS = new Set(['targetGeneralId', 'successorGeneralId', 'targetCountyId']);

/**
 * 초안을 서버 인자로 바꾼다. 빈 칸 · 못 고르는 후보 · 범위 밖 수량은 missing에 넣는다(보내지 않는다).
 * 인자 모양은 옛 명령 폼(지움)이 보내던 서버 인자 그대로다.
 */
export function buildArgs(options: CommandOptions, draft: Draft): BuiltArgs {
    if (options.state !== 'READY') return { ok: false, missing: ['options'] };
    const missing: string[] = [];
    const args: Record<string, unknown> = {};
    for (const field of options.fields) {
        const v = draft[field.key];
        if (field.kind === 'amount') {
            const max = amountMax(options, field, draft);
            if (typeof v !== 'number' || !Number.isInteger(v) || v < 1 || max == null || v > max) missing.push(field.key);
            else args.amount = v;
            continue;
        }
        if (field.multiple) {
            const ids = Array.isArray(v) ? (v as readonly number[]) : [];
            const ok = ids.length > 0 && ids.every(id => field.candidates.some(c => c.value === String(id) && c.available));
            if (!ok) missing.push(field.key);
            else args[field.key] = [...ids].sort((a, b) => a - b);
            continue;
        }
        const picked = typeof v === 'string' ? field.candidates.find(c => c.value === v && c.available) : undefined;
        if (!picked) { missing.push(field.key); continue; }
        if (field.key === 'choice') Object.assign(args, picked.args ?? {});
        else args[field.key] = NUMERIC_KEYS.has(field.key) ? Number(picked.value) : picked.value;
    }
    return missing.length ? { ok: false, missing } : { ok: true, args };
}
