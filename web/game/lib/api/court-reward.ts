// 상사 선택지 읽기(`GET /api/court/reward-options?generalId=[&retainerId=][&money=]`, #789 · #892) — 요청과 응답 검증.
// 화면은 useCourtReward 훅으로만 읽는다(D105 층).
//
// 계약: docs/development/court-reward-options-read.md(백엔드 PR #1586).
//  - 200: 루트 8키 · 카드 8키 · 자금 6키 · 미리 보기 9키를 null 까지 명시한다. 빠진 키 · 모르는 키 · 모르는 값은 실패다.
//  - 401 AUTH_REQUIRED · 400 INVALID_GENERAL_ID/INVALID_RETAINER_ID/PREVIEW_TARGET_REQUIRED · 403 FORBIDDEN.
//  - 서버 공개 상태(403 SERVER_NOT_PUBLIC · 503 SERVER_ADMISSION_UNAVAILABLE)는 공용 admissionOf 로 가른다.
//  - 요청의 장수 · 카드 · 금액을 되돌려준다 — 다르면 다른 요청의 응답이므로 실패로 돌린다(fail closed).
// 창고 금 · 차감량 · 잔액 · revision 은 십진 문자열 그대로 둔다. 비교가 필요하면 BigInt 로만 잰다.
import { fetchGame, GameHttpError } from '@/lib/api';
import { admissionOf } from '@/lib/server-admission';
import type {
    RewardCard, RewardDebit, RewardFunding, RewardOptions, RewardOptionsQuery, RewardOptionsRead, RewardPreview,
    RewardQueued, RewardReadFailure, RewardRule, RewardSnapshot, RewardUnchecked, RewardVerdict,
} from '@/lib/court-reward-types';

const STATUSES = ['READY', 'UNAVAILABLE', 'WRONG_RULE_PROFILE', 'UNSUPPORTED_WORLD_FORMAT'] as const;
const REASONS = ['WORLD_UNAVAILABLE', 'WORLD_DATE_INVALID', 'ARTIFACTS_UNAVAILABLE', 'ROSTER_INVALID'] as const;
const QUEUE = ['NONE', 'QUEUED', 'UNAVAILABLE'] as const;
const SCOPES = ['NETWORK', 'ISOLATED', 'NONE'] as const;
const NONE_REASONS = ['PAYER_LANDLESS', 'LOCATION_FOREIGN', 'LOCATION_NEUTRAL'] as const;
const UNAVAILABLE_REASONS = ['RECIPIENT_MISSING', 'LOCATION_UNKNOWN', 'PAYER_NATION_MISSING', 'WAREHOUSE_MALFORMED', 'TOTAL_OVERFLOW'] as const;
const VERDICTS: readonly RewardVerdict[] = [
    'INVALID_AMOUNT', 'CARD_UNAVAILABLE', 'NO_AMOUNT', 'TOO_SMALL', 'REWARD_OVER_CAP',
    'FUNDING_UNAVAILABLE', 'INSUFFICIENT_STOCK', 'COVERED_AT_SNAPSHOT',
];
const REJECTED: readonly RewardVerdict[] = VERDICTS.slice(0, 5);
/** 늘 이 네 값이 이 순서로 온다. */
export const REWARD_NOT_CHECKED: readonly RewardUnchecked[] = ['QUEUE_ADMISSION', 'REWARD_HISTORY', 'CONCURRENT_DEBITS', 'STATE_AFTER_SNAPSHOT'];

const ROOT_KEYS = ['status', 'reason', 'generalId', 'snapshot', 'rule', 'queued', 'cards', 'preview'];
const SNAPSHOT_KEYS = ['year', 'month', 'phase'];
const RULE_KEYS = ['moneyPerLoyalty', 'maxLoyaltyGainPerReward', 'minimumMoney', 'inputMaximumMoney', 'loyaltyCap'];
const QUEUED_KEYS = ['status', 'retainerId', 'money'];
const CARD_KEYS = ['retainerId', 'recipientGeneralId', 'name', 'loyalty', 'loyaltyRoom', 'maximumMoney', 'locationCityId', 'funding'];
const FUNDING_KEYS = ['status', 'scope', 'noneReason', 'unavailableReason', 'usableMoney', 'warehouseCount'];
const PREVIEW_KEYS = ['retainerId', 'money', 'verdict', 'loyaltyGain', 'loyaltyAfter', 'moneyWithoutGain', 'usableMoney', 'debitPlan', 'notChecked'];
const DEBIT_KEYS = ['cityId', 'isCapital', 'take', 'balance', 'revision'];

type Rec = Record<string, unknown>;
const isRec = (v: unknown): v is Rec => typeof v === 'object' && v !== null && !Array.isArray(v);
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v);
const isPos = (v: unknown): v is number => isInt(v) && v > 0;
const between = (v: unknown, lo: number, hi: number): v is number => isInt(v) && v >= lo && v <= hi;
const oneOf = <T extends string>(v: unknown, set: readonly T[]): v is T => typeof v === 'string' && (set as readonly string[]).includes(v);
const isDecimal = (v: unknown): v is string => typeof v === 'string' && /^(0|[1-9][0-9]*)$/.test(v);
/** 계약은 null 을 「명시」한다 — 키 집합이 정확히 같아야 한다(빠진 키를 null 로 채우지 않는다). */
const exactKeys = (o: Rec, keys: readonly string[]) => {
    const own = Object.keys(o);
    return own.length === keys.length && keys.every((k) => Object.prototype.hasOwnProperty.call(o, k));
};
const record = (v: unknown, keys: readonly string[]): Rec | null => (isRec(v) && exactKeys(v, keys) ? v : null);

function parseSnapshot(v: unknown): RewardSnapshot | null {
    const o = record(v, SNAPSHOT_KEYS);
    // 백엔드는 year > 0 일 때만 READY 를 낸다(아니면 UNAVAILABLE WORLD_DATE_INVALID).
    if (!o || !isPos(o.year) || !between(o.month, 1, 12) || !between(o.phase, 1, 3)) return null;
    return { year: o.year, month: o.month, phase: o.phase };
}

function parseRule(v: unknown): RewardRule | null {
    const o = record(v, RULE_KEYS);
    if (!o || !RULE_KEYS.every((k) => isPos(o[k]))) return null;
    const rule = o as unknown as RewardRule;
    if (rule.minimumMoney > rule.inputMaximumMoney || rule.maxLoyaltyGainPerReward > rule.loyaltyCap) return null;
    return { ...rule };
}

function parseQueued(v: unknown, rule: RewardRule): RewardQueued | null {
    const o = record(v, QUEUED_KEYS);
    if (!o || !oneOf(o.status, QUEUE)) return null;
    const { retainerId, money } = o;
    if (o.status !== 'QUEUED') return retainerId === null && money === null ? { status: o.status, retainerId: null, money: null } : null;
    if (retainerId !== null && !isPos(retainerId)) return null;
    if (money !== null && !between(money, 1, rule.inputMaximumMoney)) return null;
    return { status: 'QUEUED', retainerId: retainerId as number | null, money: money as number | null };
}

function parseFunding(v: unknown): RewardFunding | null {
    const o = record(v, FUNDING_KEYS);
    if (!o) return null;
    const { status, scope, noneReason, unavailableReason, usableMoney, warehouseCount } = o;
    if (status === 'UNAVAILABLE') {
        if (scope !== null || noneReason !== null || usableMoney !== null || warehouseCount !== null) return null;
        return oneOf(unavailableReason, UNAVAILABLE_REASONS)
            ? { status, scope: null, noneReason: null, unavailableReason, usableMoney: null, warehouseCount: null } : null;
    }
    if (status !== 'KNOWN' || !oneOf(scope, SCOPES) || unavailableReason !== null) return null;
    if (scope === 'NONE') {
        return oneOf(noneReason, NONE_REASONS) && usableMoney === '0' && warehouseCount === 0
            ? { status, scope, noneReason, unavailableReason: null, usableMoney: '0', warehouseCount: 0 } : null;
    }
    if (noneReason !== null || !isDecimal(usableMoney) || !isInt(warehouseCount) || warehouseCount < 0) return null;
    // 창고가 없으면 합계는 0 이다.
    if (warehouseCount === 0 && usableMoney !== '0') return null;
    return { status, scope, noneReason: null, unavailableReason: null, usableMoney, warehouseCount };
}

function parseCard(v: unknown, rule: RewardRule): RewardCard | null {
    const o = record(v, CARD_KEYS);
    if (!o || !isPos(o.retainerId) || !isPos(o.recipientGeneralId)) return null;
    if (o.name !== null && (typeof o.name !== 'string' || o.name.trim().length === 0)) return null;
    if (!between(o.loyalty, 0, rule.loyaltyCap) || !between(o.loyaltyRoom, 0, rule.maxLoyaltyGainPerReward)) return null;
    if (!between(o.maximumMoney, rule.minimumMoney, rule.inputMaximumMoney)) return null;
    if (o.locationCityId !== null && !isPos(o.locationCityId)) return null;
    const funding = parseFunding(o.funding);
    if (!funding) return null;
    // 받는 인물 행이 없을 때만 이름이 null 이고, 그때 위치도 null 이다.
    const missing = funding.unavailableReason === 'RECIPIENT_MISSING';
    if (missing !== (o.name === null) || (missing && o.locationCityId !== null)) return null;
    return {
        retainerId: o.retainerId, recipientGeneralId: o.recipientGeneralId, name: o.name as string | null,
        loyalty: o.loyalty, loyaltyRoom: o.loyaltyRoom, maximumMoney: o.maximumMoney,
        locationCityId: o.locationCityId as number | null, funding,
    };
}

function parseDebits(v: unknown, money: number): RewardDebit[] | null {
    if (!Array.isArray(v) || v.length === 0) return null;
    const debits: RewardDebit[] = [];
    let sum = BigInt(0);
    for (const item of v) {
        const o = record(item, DEBIT_KEYS);
        if (!o || !isPos(o.cityId) || typeof o.isCapital !== 'boolean') return null;
        if (!isDecimal(o.take) || !isDecimal(o.balance) || !isDecimal(o.revision)) return null;
        const take = BigInt(o.take);
        if (take <= BigInt(0) || take > BigInt(o.balance)) return null;
        sum += take;
        debits.push({ cityId: o.cityId, isCapital: o.isCapital, take: o.take, balance: o.balance, revision: o.revision });
    }
    if (new Set(debits.map((d) => d.cityId)).size !== debits.length || debits.filter((d) => d.isCapital).length > 1) return null;
    // 부분 차감 계획은 없다 — 전액을 낼 때만 온다.
    return sum === BigInt(money) ? debits : null;
}

/** 8판정 × 5효과 필드의 null 표. 앞의 다섯은 모두 null, 그 뒤는 충성 3필드를 갖고 usable · debit 이 차례로 붙는다. */
function previewFields(o: Rec, verdict: RewardVerdict, rule: RewardRule): Pick<RewardPreview, 'loyaltyGain' | 'loyaltyAfter' | 'moneyWithoutGain' | 'usableMoney' | 'debitPlan'> | null {
    const { loyaltyGain, loyaltyAfter, moneyWithoutGain, usableMoney, debitPlan, money } = o;
    if (REJECTED.includes(verdict)) {
        return [loyaltyGain, loyaltyAfter, moneyWithoutGain, usableMoney, debitPlan].every((f) => f === null)
            ? { loyaltyGain: null, loyaltyAfter: null, moneyWithoutGain: null, usableMoney: null, debitPlan: null } : null;
    }
    if (!isPos(money) || !between(loyaltyGain, 0, rule.maxLoyaltyGainPerReward) || !between(loyaltyAfter, 0, rule.loyaltyCap)) return null;
    if (!between(moneyWithoutGain, 0, money) || loyaltyGain * rule.moneyPerLoyalty + moneyWithoutGain !== money) return null;
    const effect = { loyaltyGain, loyaltyAfter, moneyWithoutGain };
    if (verdict === 'FUNDING_UNAVAILABLE') return usableMoney === null && debitPlan === null ? { ...effect, usableMoney: null, debitPlan: null } : null;
    if (!isDecimal(usableMoney)) return null;
    const covered = BigInt(usableMoney) >= BigInt(money);
    if (verdict === 'INSUFFICIENT_STOCK') return !covered && debitPlan === null ? { ...effect, usableMoney, debitPlan: null } : null;
    const debits = covered ? parseDebits(debitPlan, money) : null;
    return debits ? { ...effect, usableMoney, debitPlan: debits } : null;
}

/** 판정이 같은 응답의 카드 · 규칙과 어긋나지 않는지. 값을 카드에서 지어 채우지는 않는다. */
function consistentWithCard(p: RewardPreview, card: RewardCard | undefined, rule: RewardRule): boolean {
    if (p.verdict === 'INVALID_AMOUNT' || p.verdict === 'CARD_UNAVAILABLE') return true;
    if (!card || card.funding.unavailableReason === 'RECIPIENT_MISSING') return false;
    if (p.verdict === 'NO_AMOUNT') return true;
    const money = p.money as number;
    if (p.verdict === 'TOO_SMALL') return money < rule.minimumMoney;
    if (p.verdict === 'REWARD_OVER_CAP') return money >= rule.minimumMoney && money > card.maximumMoney;
    if (money < rule.minimumMoney || money > card.maximumMoney || p.loyaltyAfter !== card.loyalty + (p.loyaltyGain as number)) return false;
    if (p.verdict === 'FUNDING_UNAVAILABLE') return card.funding.status === 'UNAVAILABLE';
    return card.funding.status === 'KNOWN' && card.funding.usableMoney === p.usableMoney;
}

function parsePreview(v: unknown, query: RewardOptionsQuery, rule: RewardRule, cards: readonly RewardCard[]): RewardPreview | null {
    const o = record(v, PREVIEW_KEYS);
    if (!o || o.retainerId !== query.retainerId || !oneOf(o.verdict, VERDICTS)) return null;
    const notChecked = o.notChecked;
    if (!Array.isArray(notChecked) || notChecked.length !== REWARD_NOT_CHECKED.length
        || !REWARD_NOT_CHECKED.every((k, i) => notChecked[i] === k)) return null;
    // 금액 되돌림: 문법 실패만 null, 보내지 않았으면 null, 그 밖은 보낸 정규화 금액과 같다.
    if (o.verdict === 'INVALID_AMOUNT') {
        if (query.money === null || o.money !== null) return null;
    } else if (o.money !== query.money) return null;
    if ((query.money === null) !== (o.verdict === 'NO_AMOUNT' || (o.verdict === 'CARD_UNAVAILABLE' && o.money === null))) return null;
    const fields = previewFields(o, o.verdict, rule);
    if (!fields) return null;
    const preview = { retainerId: query.retainerId as number, money: o.money as number | null, verdict: o.verdict, ...fields,
        notChecked: REWARD_NOT_CHECKED } as RewardPreview;
    return consistentWithCard(preview, cards.find((c) => c.retainerId === preview.retainerId), rule) ? preview : null;
}

/** 응답 본문 검증. 계약 밖 모양이거나 요청과 다른 응답이면 null. */
export function parseRewardOptions(body: unknown, query: RewardOptionsQuery): RewardOptions | null {
    const o = record(body, ROOT_KEYS);
    if (!o || !oneOf(o.status, STATUSES) || o.generalId !== query.generalId) return null;
    if (o.status !== 'READY') {
        if (o.snapshot !== null || o.rule !== null || o.queued !== null || o.cards !== null || o.preview !== null) return null;
        // UNAVAILABLE 사유는 계약의 넷. 규칙 · 형식 불일치는 사유가 상태 이름 그대로다(RewardOptionsReader).
        const reasonOk = o.status === 'UNAVAILABLE' ? oneOf(o.reason, REASONS) : o.reason === o.status;
        return reasonOk ? { status: o.status, reason: o.reason as string, generalId: query.generalId,
            snapshot: null, rule: null, queued: null, cards: null, preview: null } : null;
    }
    if (o.reason !== null || !Array.isArray(o.cards)) return null;
    const snapshot = parseSnapshot(o.snapshot);
    const rule = parseRule(o.rule);
    if (!snapshot || !rule) return null;
    const queued = parseQueued(o.queued, rule);
    const cards = o.cards.map((c) => parseCard(c, rule));
    if (!queued || cards.some((c) => c === null)) return null;
    const ready = cards as RewardCard[];
    if (new Set(ready.map((c) => c.retainerId)).size !== ready.length) return null;
    let preview: RewardPreview | null = null;
    if (query.retainerId === null) {
        if (o.preview !== null) return null;
    } else {
        preview = parsePreview(o.preview, query, rule, ready);
        if (!preview) return null;
    }
    return { status: 'READY', reason: null, generalId: query.generalId, snapshot, rule, queued, cards: ready, preview };
}

/** 조회 주소 — money 는 카드가 있을 때만, 정규화된 양의 정수 그대로(앞자리 0 원문을 보내지 않는다). */
export function rewardOptionsPath(query: RewardOptionsQuery): string {
    const params = new URLSearchParams({ generalId: String(query.generalId) });
    if (query.retainerId !== null) {
        params.set('retainerId', String(query.retainerId));
        if (query.money !== null) params.set('money', String(query.money));
    }
    return `/api/court/reward-options?${params.toString()}`;
}

const HTTP_CODES: Readonly<Record<number, Readonly<Record<string, RewardReadFailure>>>> = {
    401: { AUTH_REQUIRED: 'AUTH_REQUIRED' },
    400: { INVALID_GENERAL_ID: 'BAD_REQUEST', INVALID_RETAINER_ID: 'BAD_REQUEST', PREVIEW_TARGET_REQUIRED: 'BAD_REQUEST' },
    403: { FORBIDDEN: 'FORBIDDEN' },
};

async function errorCodeOf(res: Response): Promise<string | null> {
    try {
        const body: unknown = await res.json();
        const error = isRec(body) ? body.error : null;
        return isRec(error) && typeof error.code === 'string' ? error.code : null;
    } catch {
        return null;
    }
}

const REVOKED: readonly RewardReadFailure[] = ['AUTH_REQUIRED', 'FORBIDDEN', 'ADMISSION_NOT_PUBLIC', 'ADMISSION_UNAVAILABLE'];
/**
 * 로그인 · 소유권 · 서버 공개 상태로 막힌 실패 — 받아 둔 선택지(인물 · 충성 · 창고 금)도 더는 보이면 안 된다.
 * 상태 번호가 아니라 갈린 실패 이름으로 잰다(코드 없는 503 은 HTTP, 일시 오류다). 연결 · 그 밖 HTTP · 계약 실패는 아니다.
 */
export const isRewardReadRevoked = (failure: RewardReadFailure): boolean => REVOKED.includes(failure);

function failureOf(status: number, code: string | null): RewardReadFailure {
    const admission = admissionOf(new GameHttpError(status, code, `${status}`));
    if (admission) return admission === 'not-public' ? 'ADMISSION_NOT_PUBLIC' : 'ADMISSION_UNAVAILABLE';
    return (code !== null ? HTTP_CODES[status]?.[code] : undefined) ?? 'HTTP';
}

/** 200 만 본문을 검증해 넘기고, 그 밖의 상태 · 모르는 본문 · 끊긴 연결은 실패로 돌린다. 끊은 요청(AbortError)만 던진다. */
export async function readRewardOptions(query: RewardOptionsQuery, signal?: AbortSignal): Promise<RewardOptionsRead> {
    let res: Response;
    try {
        res = await fetchGame(rewardOptionsPath(query), { cache: 'no-store', signal });
    } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') throw e;
        return { ok: false, failure: 'NETWORK', httpStatus: null };
    }
    if (res.status !== 200) return { ok: false, failure: failureOf(res.status, await errorCodeOf(res)), httpStatus: res.status };
    let body: unknown;
    try {
        body = await res.json();
    } catch {
        return { ok: false, failure: 'CONTRACT', httpStatus: res.status };
    }
    const options = parseRewardOptions(body, query);
    return options ? { ok: true, options } : { ok: false, failure: 'CONTRACT', httpStatus: res.status };
}
