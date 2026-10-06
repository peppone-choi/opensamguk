// 로비 서버 카드의 진입 판정과 거르기(설계서 P-G04 §2.4 판정 표). 페이지 파일은 default/metadata 만 export 할 수 있어 여기 둔다.
//
// 서버 기본 정보(/api/server-basic-info)는 아직 옛 형태다 — 새 형태(계약판 K3-03: status · 열림 시각 · season · creation ·
// npcCount · me.affiliation)가 오기 전에는 지금 있는 칸을 같은 뜻으로 쓴다:
//   season.state ≠ RUNNING  ← isUnited 2 · 3(통일 · 이벤트 끝)
//   creation.allowed        ← 장수 생성 금지 비트(blockGeneralCreate & 1)가 없고 사람 수가 정원 미만
// K3-03 이 오면 이 파일의 `lobbyVerdict` 만 바꾼다. 화면은 판정(LobbyVerdict)만 본다.

export interface BasicInfoGame {
    readonly status?: string;
    readonly year: number;
    readonly month: number;
    readonly turnPhaseText?: string | null;
    readonly scenario: string;
    readonly maxUserCnt: number;
    readonly userCnt: number;
    readonly npcCnt: number;
    readonly nationCnt: number;
    readonly turnTerm: number;
    readonly isUnited: number;
    readonly blockGeneralCreate: number;
    readonly catchUp?: { readonly active: boolean; readonly multiplier: number } | null;
}

export interface BasicInfoMe {
    readonly name: string;
    readonly picture: string | null;
    readonly imageServer: number;
}

export interface BasicInfo {
    readonly game: BasicInfoGame | null;
    readonly me: BasicInfoMe | null;
}

/**
 * 판정 표 1–8(위에서부터 처음 맞는 줄). 끝난 서버(isUnited 2 · 3)는 닫혀 있어도 점검(2)으로 보지 않는다 —
 * 게임 셸(K3 P-W05 · #1325 `isMaintenance`)과 같다(CEO 10-05: 끝난 서버는 점검이 아님). 나머지 순서는 표 그대로.
 */
export type LobbyVerdict =
    | { readonly kind: 'loading' }
    | { readonly kind: 'noResponse' }
    | { readonly kind: 'maintenance' }
    | { readonly kind: 'preOpen' }
    | { readonly kind: 'joined'; readonly me: BasicInfoMe }
    | { readonly kind: 'seasonEnded'; readonly unified: boolean }
    | { readonly kind: 'full'; readonly reason: string }
    | { readonly kind: 'recruiting' };

export function lobbyVerdict(loading: boolean, info: BasicInfo | null): LobbyVerdict {
    if (loading) return { kind: 'loading' };
    const game = info?.game ?? null;
    if (!game) return { kind: 'noResponse' };
    const ended = game.isUnited === 2 || game.isUnited === 3;
    // 끝난 서버는 닫혀 있어도 점검이 아니다(셸 P-W05 와 같다). 나머지 순서(준비 중 → 참가 중 → 끝난 서버)는 판정 표 그대로 —
    // 내 장수가 있으면 끝난 서버에서도 「참가 중」(입장 링크)이다.
    if (game.status === 'CLOSED' && !ended) return { kind: 'maintenance' };
    if (game.status === 'PRE_OPEN') return { kind: 'preOpen' };
    if (info?.me?.name) return { kind: 'joined', me: info.me };
    if (ended) return { kind: 'seasonEnded', unified: game.isUnited === 2 };
    if ((game.blockGeneralCreate & 1) !== 0) return { kind: 'full', reason: '이 서버는 지금 장수를 만들 수 없습니다' };
    if (game.userCnt >= game.maxUserCnt) return { kind: 'full', reason: `사람 장수 ${game.userCnt} / ${game.maxUserCnt}` };
    return { kind: 'recruiting' };
}

export type LobbyFilter = 'all' | 'joined' | 'available' | 'ended' | 'closed';

export const LOBBY_FILTERS: readonly { readonly key: LobbyFilter; readonly label: string }[] = [
    { key: 'all', label: '전체' },
    { key: 'joined', label: '참가 중' },
    { key: 'available', label: '참가 가능' },
    { key: 'ended', label: '끝난 서버' },
    { key: 'closed', label: '닫힘' },
];

/** 거르기: 참가 중 = 5, 참가 가능 = 8만(마감 · 생성 금지는 빼기), 끝난 서버 = 6, 닫힘 = 2 · 3 · 4. 불러오는 중은 어디서나 보인다. */
export function matchesFilter(verdict: LobbyVerdict, filter: LobbyFilter): boolean {
    if (filter === 'all' || verdict.kind === 'loading') return true;
    switch (filter) {
        case 'joined': return verdict.kind === 'joined';
        case 'available': return verdict.kind === 'recruiting';
        case 'ended': return verdict.kind === 'seasonEnded';
        case 'closed': return verdict.kind === 'noResponse' || verdict.kind === 'maintenance' || verdict.kind === 'preOpen';
        default: return true;
    }
}
