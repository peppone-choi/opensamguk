// 새 장수 만들기(P-E02) 보기 모델 — 생성 옵션 계약(K5-02, 서버 #1137 `GeneralCreationOptionsDto`) 값을 화면 말로 옮긴다. React 없음.
// 계약에 없는 것(역할 필드 · 역할별 한도 · 적성 계산 가중 · 처음 명망)은 만들지 않는다 — 화면이 서버 대기로 그린다.

import type { TargetCandidate } from '@opensamguk/ui';
import type { CreationCounty, CreationNameRule, CreationStatRule, CreationStats } from './creation-contract';

export const STAT_KEYS: readonly (keyof CreationStats)[] = ['leadership', 'strength', 'intel', 'politics', 'charm'];

/** 다섯 능력을 합이 total 이 되게 고르게 나눈다(남는 1점은 앞에서부터). 각 값은 min–max 안. */
export function evenStats(rule: CreationStatRule): CreationStats {
    const base = Math.floor(rule.total / STAT_KEYS.length);
    let extra = rule.total - base * STAT_KEYS.length;
    const out = {} as Record<keyof CreationStats, number>;
    for (const key of STAT_KEYS) {
        const v = base + (extra > 0 ? 1 : 0);
        if (extra > 0) extra -= 1;
        out[key] = Math.min(rule.max, Math.max(rule.min, v));
    }
    return out;
}

export function statSum(stats: CreationStats): number {
    return STAT_KEYS.reduce((sum, key) => sum + stats[key], 0);
}

/** 한 능력을 delta 만큼 — min–max 안으로 자른다. 합 제한은 두지 않는다(남은 점수 줄이 알린다). */
export function bumpStat(stats: CreationStats, key: keyof CreationStats, value: number, rule: CreationStatRule): CreationStats {
    const v = Number.isFinite(value) ? Math.round(value) : stats[key];
    return { ...stats, [key]: Math.min(rule.max, Math.max(rule.min, v)) };
}

/** 이름 규칙(서버 `nameRule`)으로 미리 본다 — 서버가 다시 검사한다. 모르는 글자 규칙이면 길이만 본다. */
export const KNOWN_NAME_CHARS = 'HANGUL_HAN_LATIN_LETTERS_INTERNAL_SINGLE_SPACE_OR_MIDDLE_DOT';
const NAME_LETTER = String.raw`[\p{Script=Hangul}\p{Script=Han}\p{Script=Latin}]`;
const NAME_PATTERN = new RegExp(String.raw`^${NAME_LETTER}+(?:[ ·]${NAME_LETTER}+)*$`, 'u');

export function nameProblem(raw: string, rule: CreationNameRule): string | null {
    const name = raw.normalize('NFC').trim();
    const length = [...name].length;
    if (length === 0) return '이름을 쓰세요.';
    if (length < rule.minimumCodePoints || length > rule.maximumCodePoints) {
        return `이름은 ${rule.minimumCodePoints}–${rule.maximumCodePoints}글자입니다.`;
    }
    if (rule.allowedCharacters === KNOWN_NAME_CHARS && !NAME_PATTERN.test(name)) {
        return '이름은 한글 · 한자 · 라틴 글자와, 글자 사이의 한 칸 또는 가운뎃점만 쓸 수 있습니다.';
    }
    return null;
}

export function nameHelp(rule: CreationNameRule): string {
    const chars = rule.allowedCharacters === KNOWN_NAME_CHARS ? ' · 한글 · 한자 · 라틴, 글자 사이 빈칸 · 가운뎃점 하나' : '';
    return `${rule.minimumCodePoints}–${rule.maximumCodePoints}글자${chars}`;
}

/** 서버 사유 코드 → 서버 문장과 같은 말(CreationErrorMessages). */
const REASON_TEXT: Readonly<Record<string, string>> = {
    INVALID_NATIVE_COUNTY: '시작할 수 없는 본관입니다. 다른 현을 선택해 주세요.',
    CREATION_POLICY_UNAVAILABLE: '장수 만들기가 아직 열리지 않았습니다. 잠시 후 다시 확인해 주세요.',
};
export function reasonText(code: string | null | undefined): string {
    return (code && REASON_TEXT[code]) || '지금 고를 수 없습니다.';
}

/** 본관 현 → 대상 고르기 후보(지도 칸이 있으면 같이). */
export function countyCandidate(county: CreationCounty, chosen = false): TargetCandidate {
    const sub = [county.commanderyName, county.provinceName].filter(Boolean).join(' · ');
    return {
        targetKind: 'place',
        targetId: String(county.cityId),
        cityId: String(county.cityId),
        ...(county.cellCol !== null && county.cellRow !== null ? { cell: { col: county.cellCol, row: county.cellRow } } : {}),
        available: county.available,
        ...(county.available ? {} : { reasonCode: county.reason ?? undefined, reason: reasonText(county.reason) }),
        name: county.name,
        sub: chosen ? `${sub} · 고름` : sub,
    } as TargetCandidate;
}

export interface CountyFilter {
    readonly province: string | null;
    readonly commandery: string | null;
    readonly q: string;
}

export function filterCounties(counties: readonly CreationCounty[], filter: CountyFilter): readonly CreationCounty[] {
    const q = filter.q.trim();
    return counties.filter((c) => (filter.province === null || c.provinceName === filter.province)
        && (filter.commandery === null || c.commanderyName === filter.commandery)
        && (q === '' || c.name.includes(q)));
}

export function provincesOf(counties: readonly CreationCounty[]): readonly string[] {
    return [...new Set(counties.map((c) => c.provinceName).filter((v): v is string => Boolean(v)))];
}

export function commanderiesOf(counties: readonly CreationCounty[], province: string | null): readonly string[] {
    return [...new Set(counties.filter((c) => province === null || c.provinceName === province)
        .map((c) => c.commanderyName).filter((v): v is string => Boolean(v)))];
}

export interface CreateDraft {
    readonly role: 'RETAINER' | 'PRE_LORD';
    readonly countyId: number | null;
    readonly name: string;
    readonly stats: CreationStats;
    readonly ideologyId: string | null;
    readonly traitId: string | null;
}

/** 「만들고 섬길 주공 고르기」를 막는 첫 사유(없으면 null). */
export function blockReason(draft: CreateDraft, rule: { readonly stat: CreationStatRule; readonly name: CreationNameRule }, counties: readonly CreationCounty[]): string | null {
    if (draft.role === 'PRE_LORD') return '예비 주공으로 시작하기는 서버가 아직 받지 않습니다. 「주공을 섬기며 시작」을 고르세요.';
    const county = counties.find((c) => c.cityId === draft.countyId);
    if (!county) return '본관 현을 고르세요.';
    if (!county.available) return reasonText(county.reason);
    const name = nameProblem(draft.name, rule.name);
    if (name) return name;
    const left = rule.stat.total - statSum(draft.stats);
    if (left > 0) return `${left}점이 남았습니다. 다섯 능력의 합이 ${rule.stat.total}이어야 합니다.`;
    if (left < 0) return `${-left}점이 넘칩니다. 다섯 능력의 합이 ${rule.stat.total}이어야 합니다.`;
    if (!draft.ideologyId) return '주의를 고르세요.';
    if (!draft.traitId) return '개성을 고르세요.';
    return null;
}
