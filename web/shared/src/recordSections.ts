// 기록 5분류 — 사건 종류 → 분류. 정본은 서버 `logic/.../record/EventKind.kt` 의 section 이다(K4 · K5 합의 2026-09-30).
// 화면은 다시 묶지 않는다: 지난 순 서랍(P-W04 · K4)과 기록(P-H01 · K5)이 이 표 하나를 쓴다. 문장 · 조사 · 날짜는 gameEvents.ts(K5).
// 서버에 종류가 늘면 `__tests__/recordSections.test.ts` 가 EventKind.kt 와 대조해 빨개진다.

export type RecordSection = 'PERSONAL' | 'RETINUE_NATION' | 'COURT' | 'BATTLE' | 'WORLD';

/** 화면 이름 — v3 기록 5분류 표식(`v3common.CATS`)과 같다. */
export const RECORD_SECTION_LABEL: Readonly<Record<RecordSection, string>> = {
    PERSONAL: '개인 행적',
    RETINUE_NATION: '부 · 세력',
    COURT: '조정 공문',
    BATTLE: '전장 보고',
    WORLD: '천하 정세',
};

export const RECORD_SECTION_ORDER: readonly RecordSection[] = ['PERSONAL', 'RETINUE_NATION', 'COURT', 'BATTLE', 'WORLD'];

/** EventKind.code → section. 서버 EventKind.kt 와 한 줄씩 같다. */
export const RECORD_KIND_SECTION: Readonly<Record<string, RecordSection>> = {
    'march.assignment': 'PERSONAL',
    'march.corps': 'BATTLE',
    'march.direct': 'BATTLE',
    'encounter.personal': 'BATTLE',
    'military.musterOrdered': 'BATTLE',
    'deploy.started': 'BATTLE',
    'encounter.pending': 'BATTLE',
    'encounter.disbanded': 'BATTLE',
    'court.dispatchIssued': 'COURT',
    'court.dispatchReceived': 'COURT',
    'court.dispatchAccepted': 'COURT',
    'court.dispatchRefused': 'COURT',
    'court.dispatchCancelled': 'COURT',
    'court.rewardReceived': 'PERSONAL',
    'enlist.joined': 'PERSONAL',
    'enlist.retainerJoined': 'RETINUE_NATION',
    'input.rejected': 'PERSONAL',
    'field.applied': 'RETINUE_NATION',
    'personal.applied': 'PERSONAL',
    'offlineDelegation.started': 'PERSONAL',
    'offlineDelegation.ended': 'PERSONAL',
    'people.searched': 'RETINUE_NATION',
    'people.joined': 'RETINUE_NATION',
    'people.resisted': 'RETINUE_NATION',
    'renown.event': 'PERSONAL',
    'yuedan.assessed': 'PERSONAL',
    'retinue.departureJudged': 'RETINUE_NATION',
    'retinue.departed': 'RETINUE_NATION',
    'roadFort.siege': 'BATTLE',
    'income.monthly': 'RETINUE_NATION',
    'county.captured': 'WORLD',
    'county.lost': 'WORLD',
    'roadFort.captured': 'WORLD',
    'yuedan.announced': 'WORLD',
    'server.catchUpFinished': 'WORLD',
    'county.ownerChanged': 'WORLD',
};

/** 모르는 종류는 null — 화면은 「전체」 거르기에서만 보이고 분류 칩을 달지 않는다(지어 넣지 않는다). */
export function recordSection(kind: string): RecordSection | null {
    return Object.prototype.hasOwnProperty.call(RECORD_KIND_SECTION, kind) ? RECORD_KIND_SECTION[kind] : null;
}
