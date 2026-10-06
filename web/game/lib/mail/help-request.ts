// 서신 「도움 요청」(D68 · 보드 V31K6HelpRequest · MHelpRequest · MHelpStatus, 원장 D111) — 보기 모델.
//
// 서버 API 는 아직 없다(요구 2026-10-03-k6-direct-command-front-requirements.md §7 「이름 미정」, 계약판 H01: C3 실제 지원 handler ·
// C1/C5 대상 권한 보강 뒤). 그래서 wire 해석기 · 지어낸 enum 은 두지 않고, 화면이 그릴 꼴만 정한다. 서버가 들어오는 PR 이
// 응답을 이 꼴로 옮기는 어댑터를 붙인다. 고정 자료는 시험에만 쓴다(제품 화면에 가짜 요청 0).
// - 상태는 서버가 준 단계만 그린다(§6.2). 「수락」만으로 도움이 왔다고 그리지 않는다 — 실제 출발 · 이전 사건이 와야 「출발함」.
// - 날짜 · 순 · 양 같은 값은 서버가 만든 글자를 그대로 받는다(단위를 화면이 지어내지 않는다). 없으면 「서버 대기」.

/** 도움 종류(D68 「병력 · 자원」). */
export type HelpKind = 'troops' | 'resource';
export const HELP_KINDS: readonly HelpKind[] = ['troops', 'resource'];
export const HELP_KIND_LABEL: Readonly<Record<HelpKind, string>> = { troops: '병력', resource: '자원' };

/** 보낸 도움 요청의 단계(§6.2 표의 여섯 줄). 화면 이름이다 — 서버 이름은 어댑터가 맞춘다. */
export type HelpState = 'deciding' | 'waiting' | 'accepted' | 'departed' | 'rejected' | 'closed';
export const HELP_STATES: readonly HelpState[] = ['deciding', 'waiting', 'accepted', 'departed', 'rejected', 'closed'];

export const HELP_STATE_LABEL: Readonly<Record<HelpState, string>> = {
    deciding: '판단 대기',
    waiting: '대기',
    accepted: '수락',
    departed: '출발함',
    rejected: '거절',
    closed: '기한 지남 · 취소됨',
};

/** 상태 칩 색(보드 STATES) — os-chip 꼬리 이름. 빈 글자는 기본 칩. */
export const HELP_STATE_TONE: Readonly<Record<HelpState, '' | 'info' | 'bronze' | 'moss' | 'rust'>> = {
    deciding: 'info',
    waiting: '',
    accepted: 'bronze',
    departed: 'moss',
    rejected: 'rust',
    closed: '',
};

export const SERVER_WAIT = '서버 대기';
/** 서버가 사유 없이 대기 · 거절을 줬을 때 — 사유를 지어내지 않는다. */
export const MISSING_REASON = '사유를 받지 못했습니다';

/** 보낸 도움 요청 한 건 — 서버가 준 값만(글자는 서버가 만든 그대로). */
export interface HelpRequestView {
    readonly id: string;
    /** 받는 사람 이름. */
    readonly to: string;
    readonly kind: HelpKind;
    /** 청한 것 — 서버 글자(예: 자원 이름 · 양, 부곡 묶음). */
    readonly asked: string | null;
    /** 보낼 곳 구역 이름. */
    readonly target: string | null;
    /** 기한 — 서버 글자. */
    readonly deadline: string | null;
    readonly state: HelpState;
    /** 다음 판단 때 — 서버 글자(판단 대기에서). */
    readonly nextDecision: string | null;
    /** 대기 · 거절 사유 — 서버 사유 글자. */
    readonly reason: string | null;
    /** 도착 예정 — 서버 글자(출발함에서). */
    readonly arrival: string | null;
    /** 실제 출발 · 이전 사건 고리(출발함에서). */
    readonly eventHref: string | null;
}

/** 카드 한 줄 무엇 — 「청한 것 · 보낼 곳」. 서버가 아직 안 준 칸은 「서버 대기」. */
export function helpWhat(v: HelpRequestView): string {
    return `${HELP_KIND_LABEL[v.kind]} ${v.asked ?? SERVER_WAIT} · 보낼 곳 ${v.target ?? SERVER_WAIT}`;
}

/** 단계마다 보낸 쪽에 보이는 한 줄(§6.2). */
export function helpStateLine(v: HelpRequestView): string {
    switch (v.state) {
        case 'deciding': return `${v.to} — 자기 순에 정해진 규칙으로 판단합니다 · 다음 판단 ${v.nextDecision ?? SERVER_WAIT}`;
        case 'waiting': return `아직 정하지 않았습니다 — ${v.reason ?? MISSING_REASON}`;
        case 'accepted': return '수락했습니다 — 아직 출발 전이라 도움이 온 것이 아닙니다';
        case 'departed': return `출발했습니다 · 도착 예정 ${v.arrival ?? SERVER_WAIT}`;
        case 'rejected': return `거절했습니다 — ${v.reason ?? MISSING_REASON}`;
        default: return '닫혔습니다';
    }
}
