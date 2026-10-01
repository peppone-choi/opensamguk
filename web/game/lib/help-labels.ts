// 도움말 화면 말 — 입력 원장 코드를 쉬운 말로(설계서 2026-09-30-k7-design-spec.md 부록 A).
// 코드 자체는 화면에 쓰지 않는다. 모르는 코드는 빈 문자열로 두고 그 줄을 숨긴다(지어내지 않는다).
import type { CostSchema, InputContract, InputKind } from './help';
import { HELP_INDEX, type HelpIndexEntry } from './help-index';

export const KIND_LABEL: Record<InputKind, string> = {
    GENERAL_ACTION: '직접 행동',
    PLACEMENT: '배치',
    POLICY: '방침',
    WORK: '공사',
    STRATAGEM: '계책',
    COURT_DECISION: '조정 결정',
};

export const ACTOR_LABEL: Record<string, string> = {
    GENERAL: '장수 본인',
    LORD: '주공',
    RULER: '군주',
    OFFICE_HOLDER: '그 관할의 관직자',
};

export const AUTHORITY_LABEL: Record<string, string> = {
    SUBJECT_OWNER: '내 장수',
    DECISION_AUTHORITY: '결정권자',
    CARD_OWNER: '카드 주인',
    DIRECT_RETAINER_OWNER: '직접 거느린 주인',
    JURISDICTION_OFFICE: '관할 관직자',
};

/** 명령 목록 12순에 넣는 단계(설계 §5.1). */
export const SLOT_PHASE_LABEL: Record<string, string> = {
    POLITICS: '정치',
    MOVE: '이동',
    SIEGE: '공성',
    FIELD: '현장 행동',
};

const OTHER_PHASE_LABEL: Record<string, string> = {
    DECISION_TURN: '결정권자의 턴에',
    NEXT_CARD_TURN: '맡긴 인물의 다음 턴부터',
    NEXT_PHASE_BOUNDARY: '다음 순 경계부터',
    CARD_TRIGGER: '카드 조건이 맞을 때',
};

export const SCOPE_LABEL: Record<string, string> = {
    ACTOR_LOCATION: '내가 있는 곳',
    DECISION_TARGET: '결정 대상',
    CARD_TARGET: '카드 대상',
    TARGET_COUNTY: '고른 현',
    DIRECT_RETAINER: '직접 거느린 인물',
    ASSIGNED_JURISDICTION: '맡은 관할',
};

export function timingLabel(timing: InputContract['timing']): string {
    const phase = timing.phase ?? '';
    if (phase in SLOT_PHASE_LABEL) return `명령 목록 12순 · 한 순에 하나 · ${SLOT_PHASE_LABEL[phase]} 단계`;
    return OTHER_PHASE_LABEL[phase] ?? '';
}

export function whoLabel(input: Pick<InputContract, 'actor' | 'authorityRule'>): string {
    return [ACTOR_LABEL[input.actor], AUTHORITY_LABEL[input.authorityRule]].filter(Boolean).join(' · ');
}

export function targetLabel(input: Pick<InputContract, 'targetSchema'>): string {
    return input.targetSchema.status === 'PLANNED' ? '준비 중' : '명령 화면에서 고릅니다';
}

export const RESOURCES = [
    { key: 'money', label: '금' },
    { key: 'grain', label: '쌀' },
    { key: 'iron', label: '철' },
    { key: 'timber', label: '목재' },
    { key: 'horses', label: '말' },
] as const;

/** 자원 한 칸: 0 = 들지 않음, 수 = 그 값, null · 없음 = 미확정(무료가 아니다). */
export function costValue(cost: CostSchema, key: (typeof RESOURCES)[number]['key']): string {
    const v = cost[key];
    if (v === 0) return '들지 않음';
    if (typeof v === 'number') return v.toLocaleString('ko-KR');
    return '상황에 따라';
}

// ── 이름 ───────────────────────────────────────────────────────────────────────
/** 2026-09-30 사용자 승인 새 이름. 원장 displayName · 도움말 원문이 바뀌면(C7) 이 표를 지운다. */
export const RENAMED_INPUTS: Readonly<Record<string, string>> = {
    'action.convertProficiency': '병종 바꿔 익히기',
    'action.tradeGrain': '쌀 사고팔기',
};

/**
 * 도움말 원문(C7 소유, 초안) 속 옛 말을 화면에서만 바꾸는 임시 치환표 — 원문 교체는 C7(계약판 K7-COPY-06 · 07).
 * 들어갈 수 있는 것은 두 가지뿐이다(K0 2026-10-01):
 *  ① 2026-09-30 승인된 새 명령 이름(원문에 옛 이름이 남은 것만)
 *  ② 뜻이 하나뿐인 행정 한자 표기의 한글 읽기(설계 v3 규칙 한글 우선)
 * 「휘하 → 부」처럼 문맥에 따라 뜻이 갈리는 말은 기계로 바꾸지 않는다.
 * 원문에서 옛 말이 사라지면 __tests__/help-lib.test.ts 가 이 표의 그 줄을 지우라고 빨개진다.
 */
export const APPROVED_RENAMES: readonly (readonly [string, string])[] = [
    ['숙련전환', '병종 바꿔 익히기'],
    ['군량매매', '쌀 사고팔기'],
];
export const HANJA_READINGS: readonly (readonly [string, string])[] = [];
export const OLD_WORDS: readonly (readonly [string, string])[] = [...APPROVED_RENAMES, ...HANJA_READINGS];

/** 끝 글자에 받침이 있는지(한글이 아니면 없다고 본다). 받침이 ㄹ이면 'ㄹ'. */
function coda(word: string): 'none' | 'rieul' | 'other' {
    const code = word.charCodeAt(word.length - 1) - 0xac00;
    if (code < 0 || code > 11171) return 'none';
    const jong = code % 28;
    return jong === 0 ? 'none' : jong === 8 ? 'rieul' : 'other';
}

/** 조사를 새 말의 받침에 맞춘다(을/를 · 이/가 · 은/는 · 과/와 · 으로/로). */
function particle(word: string, p: string): string {
    const c = coda(word);
    const has = c !== 'none';
    switch (p) {
        case '을': case '를': return has ? '을' : '를';
        case '이': case '가': return has ? '이' : '가';
        case '은': case '는': return has ? '은' : '는';
        case '과': case '와': return has ? '과' : '와';
        case '으로': case '로': return c === 'other' ? '으로' : '로';
        default: return p;
    }
}

// 조사(1번 묶음)와 서술격 「이」(2번 — 이다 · 이란 · 이며처럼 뒤에 한글이 이어진다)를 가른다. 「이」만 뜻이 둘이다.
// 다른 조사는 뒤에 한글이 와도 조사다(으로써 · 과의 · 은커녕) — 한글이 이어지는지로 일괄 거르면 그쪽이 틀어진다.
const PARTICLE = '(?:(으로|로|을|를|이(?![가-힣])|가|은|는|과|와)|(이)(?=[가-힣]))?';

/** 서버가 준 도움말 글에서 옛 이름만 새 이름으로 바꾼다(바로 뒤 조사는 받침에 맞춘다). 다른 글자는 건드리지 않는다. */
export function helpText(text: string): string {
    let out = text;
    for (const [from, to] of OLD_WORDS) {
        out = out.replace(new RegExp(`${from}${PARTICLE}`, 'g'), (_m, p?: string, copula?: string) => {
            if (p) return to + particle(to, p);
            // 서술격 「이」는 받침 없는 말 뒤에서 줄어든다(익히기이다 → 익히기다, 익히기이란 → 익히기란).
            if (copula) return coda(to) === 'none' ? to : to + copula;
            return to;
        });
    }
    return out;
}

const BY_INPUT = new Map<string, HelpIndexEntry>(HELP_INDEX.map((e) => [e.inputId, e]));

export function indexEntry(inputId: string): HelpIndexEntry | undefined {
    return BY_INPUT.get(inputId);
}

/** 입력 이름 — 새 이름 → 색인(원장 displayName · 주제 제목) → 서버가 준 이름. */
export function inputName(inputId: string, fallback?: string | null): string {
    return RENAMED_INPUTS[inputId] ?? BY_INPUT.get(inputId)?.name ?? helpText(fallback ?? '');
}
