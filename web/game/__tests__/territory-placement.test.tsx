import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { PlacementList, PlacementSheet } from '../components/territory/PlacementParts';
import type { PlacementCard, Posts } from '../lib/campaign-reads';
import { placementBody, placementRows, postKindChoices, targetCandidates } from '../lib/territory-view';

const card = (cardId: number, over: Partial<PlacementCard> = {}): PlacementCard => ({
    cardId, generalId: 100 + cardId, name: `인물${cardId}`, relation: 'LIEUTENANT', provinceId: 'p-12', placeable: true, blocked: null,
    active: null, pending: null, ...over,
});
const posts: Posts = {
    status: 'READY',
    cards: [
        card(1, { name: '허저', active: { post: 'CORPS_COMMANDER', postLabel: '군단장', target: { label: '영천 군단' }, state: 'MOVING' } }),
        card(2, { name: '이전', pending: { post: 'NONE', postLabel: '해제', target: { label: null } } }),
        card(3, { name: '무명 공조', placeable: false, blocked: { code: 'CARD_DEPLOYED', reason: '출전 중인 카드입니다.' }, provinceId: null }),
    ],
    posts: [
        { post: 'MAGISTRATE', label: '현령', available: true, blocked: null, targets: [
            { countyId: 129, name: '양성현', commanderyName: '영천군', occupied: false },
            { countyId: 130, name: '허현', commanderyName: '영천군', occupied: true },
        ] },
        { post: 'ENVOY', label: '사자', available: false, blocked: { code: 'NO_ENVOY_SLOT', reason: '사자 자리가 없습니다.' }, targets: [] },
        { post: 'CORPS_COMMANDER', label: '군단장', available: false, blocked: null, targets: null },
        { post: 'SCOUT', label: '정찰', available: true, blocked: null, targets: null },
        { post: 'NONE', label: '해제', available: true, blocked: null, targets: null },
    ],
};

test('보기 모델 — 부임 중 · 대기(「해제」는 「자리에서 풀기」) · 불가 자리도 사유와 함께', () => {
    const rows = placementRows(posts);
    expect(rows[0]).toMatchObject({ now: '군단장 · 영천 군단', moving: true, pending: null });
    expect(rows[1].pending).toBe('자리에서 풀기');
    const kinds = postKindChoices(posts);
    expect(kinds.map((k) => [k.label, k.available, k.reason, k.need])).toEqual([
        ['현령', true, null, 'county'],
        ['사자', false, '사자 자리가 없습니다.', 'nation'],
        ['군단장', false, '사유를 받지 못했습니다', null],
        ['정찰', true, null, 'here'],
        ['자리에서 풀기', true, null, null],
    ]);
    expect(targetCandidates(posts.posts[0]).map((c) => [c.targetId, c.available, c.reason ?? null])).toEqual([
        ['129', true, null], ['130', false, '다른 인물이 맡고 있습니다'],
    ]);
});

test('입력 몸통 — 현령은 countyId, 정찰은 카드 위치(모르면 보내지 않음), 풀기는 자리만', () => {
    expect(placementBody(posts.cards[0], 'MAGISTRATE', '129')).toEqual({ body: { cardId: 1, post: 'MAGISTRATE', countyId: 129 } });
    expect(placementBody(posts.cards[0], 'MAGISTRATE', null)).toEqual({ error: '맡길 현을 고르세요.' });
    expect(placementBody(posts.cards[0], 'SCOUT', null)).toEqual({ body: { cardId: 1, post: 'SCOUT', provinceId: 'p-12' } });
    expect(placementBody(posts.cards[2], 'SCOUT', null)).toEqual({ error: '카드의 지금 위치를 알 수 없어 정찰을 보낼 수 없습니다.' });
    expect(placementBody(posts.cards[0], 'NONE', null)).toEqual({ body: { cardId: 1, post: 'NONE' } });
});

test('배치 목록 — 불가 카드의 「바꾸기」는 점선 + 서버 사유, 비면 안내 한 줄', () => {
    const rows = placementRows(posts);
    const onChange = vi.fn();
    const avail = (r: (typeof rows)[number]) => r.placeable
        ? { inputId: 'placement.assign', status: 'AVAILABLE' as const }
        : { inputId: 'placement.assign', status: 'BLOCKED' as const, code: r.blocked?.code, reason: r.blocked?.reason };
    const { rerender } = render(<PlacementList rows={rows} availabilityOf={avail} onChange={onChange} courtHref="/game/pep/court" />);
    const items = screen.getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('부임 중');
    expect(items[1]).toHaveTextContent('대기 — 다음 턴부터 자리에서 풀기');
    expect(within(items[2]).getByRole('button', { name: /바꾸기/ })).toHaveAttribute('aria-disabled', 'true');
    expect(items[2]).toHaveTextContent('출전 중인 카드입니다.');
    fireEvent.click(within(items[0]).getByRole('button', { name: '바꾸기' }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ cardId: 1 }));
    expect(screen.getByRole('link', { name: '조정에서 발령 →' })).toHaveAttribute('href', '/game/pep/court');
    rerender(<PlacementList rows={[]} availabilityOf={avail} onChange={onChange} />);
    expect(screen.getByRole('status')).toHaveTextContent('배치할 NPC 인물이 없습니다');
});

test('배치 시트 — 현령 → 현 고르기(맡은 현은 사유) → 「이 자리로」가 몸통을 보낸다, 고르기 전엔 막힘', () => {
    const onSubmit = vi.fn();
    render(<PlacementSheet card={posts.cards[0]} posts={posts} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    const kinds = screen.getByRole('listbox', { name: '자리 종류' });
    expect(within(kinds).getByRole('option', { name: /사자/ })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('button', { name: '이 자리로' })).toHaveAttribute('aria-disabled', 'true');

    fireEvent.click(within(kinds).getByRole('option', { name: '현령' }));
    const list = screen.getByRole('listbox', { name: '맡길 현' });
    expect(within(list).getByRole('option', { name: /허현/ })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('button', { name: '이 자리로' })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(within(list).getByRole('option', { name: /양성현/ }));
    fireEvent.click(screen.getByRole('button', { name: '이 자리로' }));
    expect(onSubmit).toHaveBeenCalledWith({ cardId: 1, post: 'MAGISTRATE', countyId: 129 });
});
