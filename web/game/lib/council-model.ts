// 회의실 · 기밀실(P-Q01) 보기 모델 — 화면은 이 모양만 본다(원장 D105 한 겹).
// 모양은 새 회의실 API 초안(계약판 K5-12, C1 #1246 — `GET /api/council` 의 room · access · members · articles · readers)을 따른다.
// 지금은 main 의 옛 게시판(`GET /api/board`)을 council-board-adapter 가 이 모양으로 옮긴다. #1246 이 오면 어댑터만 바꾼다.

export type CouncilRoom = 'MEETING' | 'SECRET';
export type CouncilKind = 'GENERAL' | 'OPERATION' | 'NOTICE';

export interface CouncilPerson {
    readonly generalId: number;
    readonly name: string;
    readonly picture: string | null;
    readonly imageServer: number | null;
}

export interface CouncilComment {
    readonly id: number;
    readonly author: CouncilPerson;
    readonly text: string;
    /** 현실 시각(ISO) — 게임 날짜는 API 에 없어 지어내지 않는다. */
    readonly createdAt: string;
}

export interface CouncilArticle {
    readonly id: number;
    readonly kind: CouncilKind;
    readonly title: string;
    /** 서버가 정제한 HTML(화면이 한 번 더 거른다). */
    readonly contentHtml: string;
    readonly author: CouncilPerson;
    readonly createdAt: string;
    readonly operationId: number | null;
    /** 기밀실 글에만 — 열람한 사람 · 참여 정원. */
    readonly readers: { readonly read: readonly CouncilPerson[]; readonly total: number | null } | null;
    readonly comments: readonly CouncilComment[];
    /** 옛 표결 글(새로 만들 수 없음) — 본문만 보이고 표결은 그리지 않는다(설계서 Q13d). */
    readonly legacyVote: boolean;
}

export interface CouncilMember extends CouncilPerson {
    /** 최근 한 순 안에 움직였는지. 모르면 null. */
    readonly active: boolean | null;
    /** 기밀실 참여자인지. 모르면 null. */
    readonly inSecret: boolean | null;
}

export interface CouncilAccess {
    readonly canRead: boolean;
    readonly canWrite: boolean;
    readonly canNotice: boolean;
    /** 닫혔을 때 서버 사유(문장 그대로). */
    readonly reason: string | null;
}

export interface CouncilView {
    readonly room: CouncilRoom;
    readonly access: CouncilAccess;
    /** 재야 등 세력이 없어 닫힘. */
    readonly noAffiliation: boolean;
    readonly members: readonly CouncilMember[];
    /** 기밀실 참여 정원(모르면 null). */
    readonly secretMemberCount: number | null;
    readonly articles: readonly CouncilArticle[];
    readonly myGeneralId: number | null;
}

export const KIND_LABEL: Readonly<Record<CouncilKind, string>> = { GENERAL: '일반', OPERATION: '작전', NOTICE: '공지' };
export const ROOM_LABEL: Readonly<Record<CouncilRoom, string>> = { MEETING: '회의실', SECRET: '기밀실' };
