// 계책 덱(P-S01) 모델 — `GET /api/commands/stratagem-hand`만 읽는다. K6 설계서 §3.2.
//
// 서버가 주는 카드는 지금 견벽(FORTIFY) · 간파(INSIGHT) 둘이다. 카드 설명은 계책 카드 카탈로그 초안의 사용 방식
// (지금 손패 화면과 같은 문구)만 쓰고, 비용 · 사거리는 서버가 주기 전엔 그리지 않는다(계약판 K6-10).
// 카드 그림은 opensamguk-images 정본 export(public/stratagem-cards, manifest.json)만 쓴다 — 없는 종류는 그림 없이.
// 카드 쓰기 · 걸기(stratagem.play)는 원장 PLANNED — 단추는 「준비 중」(availabilityOf).
import type { StratagemHand } from '../campaign-reads';

export type CardMode = '즉시' | '설치' | '대응';
export const CARD_MODES: readonly CardMode[] = ['즉시', '설치', '대응'];

export interface HandCardView {
    readonly instanceId: number;
    readonly label: string;
    /** 모르는 종류면 null — 칩 · 설명을 그리지 않는다. */
    readonly mode: CardMode | null;
    readonly effect: string | null;
    /** 카드 그림 경로(public 절대경로). 정본 export가 없는 종류면 null. */
    readonly art: string | null;
}

const KNOWN: Readonly<Record<string, { mode: CardMode; effect: string }>> = {
    FORTIFY: { mode: '대응', effect: '공격받으면 방비가 오르고, 대신 쌀을 더 쓴다.' },
    INSIGHT: { mode: '대응', effect: '상대 계책 한 장을 무효로 한다.' },
};

/** 서버 카드 종류 → 정본 export 파일(public/stratagem-cards/manifest.json과 같다 — __tests__/StratagemDeck.test.tsx가 대조). */
export const CARD_ART: Readonly<Record<string, string>> = {
    FORTIFY: 'gyeonbyeok.webp',
    INSIGHT: 'ganpa.webp',
};
export const CARD_ART_BASE = '/stratagem-cards/';

export type HandView =
    | { readonly state: 'loading' }
    | { readonly state: 'error'; readonly message: string; readonly code?: string }
    /** 아직 첫 손패를 받지 않았다(NOT_READY). */
    | { readonly state: 'first-draw' }
    | { readonly state: 'ready'; readonly cards: readonly HandCardView[]; readonly limit: number };

export function toHandView(read: { data: StratagemHand | null; error: string | null; loading: boolean }): HandView {
    if (read.loading && !read.data) return { state: 'loading' };
    if (read.error) return { state: 'error', message: '손패를 불러오지 못했습니다' };
    const hand = read.data;
    if (!hand) return { state: 'loading' };
    const status = hand.status as string;
    if (status === 'NOT_READY') return { state: 'first-draw' };
    if (status !== 'READY') return { state: 'error', message: '이 서버에서는 손패를 읽을 수 없습니다', code: status };
    return {
        state: 'ready',
        limit: hand.handLimit,
        cards: hand.cards.map((c) => {
            const known = KNOWN[c.type];
            const art = CARD_ART[c.type];
            return { instanceId: c.instanceId, label: c.label, mode: known?.mode ?? null, effect: known?.effect ?? null, art: art ? CARD_ART_BASE + art : null };
        }),
    };
}

/** 카드를 거는 곳의 단추 글자 — 대응 = 「걸기」, 나머지 = 「쓰기」. */
export function actionLabel(card: HandCardView): string {
    return card.mode === '대응' ? `${card.label} — 대응 칸에 걸기` : `${card.label} — 쓰기`;
}
