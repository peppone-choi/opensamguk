import { expect, test } from 'vitest';
import type { PlacementCard, Posts } from '../lib/campaign-reads';
import { CORPS_COMMANDER_UNKNOWN_REASON, MISSING_REASON, postKindChoices } from '../lib/territory-view';

const card = (cardId: number, over: Partial<PlacementCard> = {}): PlacementCard => ({
    cardId, generalId: 100 + cardId, name: `인물${cardId}`, relation: 'LIEUTENANT', provinceId: 'p-12', placeable: true, blocked: null,
    active: null, pending: null, ...over,
});
// The current server lists the commander post as open for everyone; only the card verdict decides.
const posts = (cards: PlacementCard[], commanderOpen = true): Posts => ({
    status: 'READY',
    cards,
    posts: [
        { post: 'MAGISTRATE', label: '현령', available: true, blocked: null, targets: [] },
        { post: 'CORPS_COMMANDER', label: '군단장', available: commanderOpen,
            blocked: commanderOpen ? null : { code: 'NOT_LORD', reason: '군주만 군단장을 앉힙니다.' }, targets: null },
        { post: 'NONE', label: '해제', available: true, blocked: null, targets: null },
    ],
});
const commander = (p: Posts, c: PlacementCard | null) => {
    const k = postKindChoices(p, c).find((x) => x.post === 'CORPS_COMMANDER')!;
    return [k.available, k.reason, k.code];
};

test('군단장은 고른 카드의 판정을 따른다 — 같은 관계 · 같은 공통 목록이라도 카드마다 다르다', () => {
    const ok = card(1, { corpsCommander: { available: true, blocked: null } });
    const no = card(2, { corpsCommander: { available: false, blocked: { code: 'ALREADY_COMMANDER', reason: '이미 군단을 이끌고 있습니다.' } } });
    const p = posts([ok, no]);
    expect(commander(p, ok)).toEqual([true, null, null]);
    expect(commander(p, no)).toEqual([false, '이미 군단을 이끌고 있습니다.', 'ALREADY_COMMANDER']);
    // Other posts keep the common verdict for both cards.
    for (const c of [ok, no]) {
        expect(postKindChoices(p, c).filter((k) => k.post !== 'CORPS_COMMANDER').map((k) => k.available)).toEqual([true, true]);
    }
});

test('옛 서버(판정 없음) · null 판정 · 카드 없음은 군단장을 닫고 중립 사유를 보인다', () => {
    const old = card(3);
    const nulled = card(4, { corpsCommander: null });
    const p = posts([old, nulled]);
    expect(commander(p, old)).toEqual([false, CORPS_COMMANDER_UNKNOWN_REASON, null]);
    expect(commander(p, nulled)).toEqual([false, CORPS_COMMANDER_UNKNOWN_REASON, null]);
    expect(commander(p, null)).toEqual([false, CORPS_COMMANDER_UNKNOWN_REASON, null]);
});

test('불가 판정에 사유가 없으면 사유를 짓지 않는다', () => {
    const c = card(5, { corpsCommander: { available: false, blocked: null } });
    expect(commander(posts([c]), c)).toEqual([false, MISSING_REASON, null]);
});

test('공통 거절이 카드 허용보다 먼저다', () => {
    const c = card(6, { corpsCommander: { available: true, blocked: null } });
    expect(commander(posts([c], false), c)).toEqual([false, '군주만 군단장을 앉힙니다.', 'NOT_LORD']);
});
