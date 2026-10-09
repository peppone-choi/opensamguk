import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { PlacementList, PlacementSheet } from '../components/territory/PlacementParts';
import { TerritoryScreen } from '../components/territory/TerritoryScreen';
import { api } from '../lib/api';
import type { PlacementCard, Posts } from '../lib/campaign-reads';
import { placementBody, placementRows, postKindChoices, targetCandidates } from '../lib/territory-view';

// 결정 단추가 도움말 고리(useReasonHelp → useOpenHelp)를 쓴다 — 지금 경로 · 쿼리 · router 흉내.
vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/territory', useSearchParams: () => new URLSearchParams(), useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }) }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, frontInfo: null }) }));
vi.mock('../lib/api', () => ({
    api: { campaignPosts: vi.fn(), campaignPolicies: vi.fn(), campaignWorks: vi.fn(), warehouses: vi.fn(), roadForts: vi.fn(), campaignRetinue: vi.fn(), campaignDomestic: vi.fn() },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

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

let viewport: ReturnType<typeof installViewport> | null = null;
beforeEach(() => {
    vi.clearAllMocks();
    viewport = installViewport(1440);
    vi.mocked(api.campaignPosts).mockResolvedValue(posts);
    vi.mocked(api.campaignPolicies).mockResolvedValue({ status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: null, counties: [], corps: [] } as never);
    vi.mocked(api.campaignWorks).mockResolvedValue({ status: 'READY', counties: [] } as never);
    vi.mocked(api.warehouses).mockResolvedValue({ status: 'READY', warehouses: [] } as never);
    vi.mocked(api.roadForts).mockResolvedValue({ status: 'READY', roadMode: true, forts: [], gates: [] } as never);
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', people: [], units: [] } as never);
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'AVAILABLE' } as never);
});
afterEach(() => { viewport?.restore(); viewport = null; });

test('보기 모델 — 부임 중 · 대기(「해제」는 「자리에서 풀기」) · 불가 자리도 사유와 함께', () => {
    const rows = placementRows(posts);
    expect(rows[0]).toMatchObject({ now: '군단장 · 영천 군단', moving: true, pending: null });
    expect(rows[1].pending).toBe('자리에서 풀기');
    const kinds = postKindChoices(posts, posts.cards[0]);
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

test('사람 장수 카드(K4-18 true)는 「바꾸기」 대신 조정 발령 고리, null 은 추정하지 않고 서버 사유 그대로', () => {
    const rows = placementRows({ ...posts, cards: [card(5, { name: '순욱', isHuman: true }), card(6, { name: '미상', isHuman: null, placeable: false,
        blocked: { code: 'HUMAN_CARD', reason: '사람 장수는 조정에서 발령합니다.' } })] });
    render(<PlacementList rows={rows} courtHref="/game/pep/court" onChange={() => {}}
        availabilityOf={(r) => r.placeable ? { inputId: 'placement.assign', status: 'AVAILABLE' } : { inputId: 'placement.assign', status: 'BLOCKED', reason: r.blocked?.reason }} />);
    const [human, unknown] = screen.getAllByRole('listitem');
    expect(within(human).getByRole('link', { name: '조정에서 발령 →' })).toHaveAttribute('href', '/game/pep/court');
    expect(within(human).queryByRole('button', { name: /바꾸기/ })).toBeNull();
    expect(within(unknown).getByRole('button', { name: /바꾸기/ })).toHaveAttribute('aria-disabled', 'true');
    expect(unknown).toHaveTextContent('사람 장수는 조정에서 발령합니다.');
});

const hrefs = { supply: '/game/pep/territory/supply', court: '/game/pep/court' };
async function choosePerson() {
    const list = await within(screen.getByRole('region', { name: '배치' })).findByRole('list');
    fireEvent.click(within(within(list).getAllByRole('listitem')[0]).getByRole('button', { name: '바꾸기' }));
    return screen.findByRole('region', { name: '허저 배치' });
}

test('현 상세의 허용 현은 인물을 고른 뒤 시트와 실제 제출에 유지하며 자동 제출하지 않는다', async () => {
    render(<TerritoryScreen hrefs={hrefs} initialView="placement" initialCountyId={129} />);
    expect(screen.queryByRole('region', { name: '허저 배치' })).toBeNull();
    expect(api.campaignDomestic).not.toHaveBeenCalled();
    const sheet = await choosePerson();
    expect(within(sheet).getByRole('option', { name: '현령' })).toHaveAttribute('aria-selected', 'true');
    expect(within(sheet).getByRole('option', { name: /양성현/ })).toHaveAttribute('aria-selected', 'true');
    expect(api.campaignDomestic).not.toHaveBeenCalled();
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    await waitFor(() => expect(api.campaignDomestic).toHaveBeenCalledWith(7, 'placement', { cardId: 1, post: 'MAGISTRATE', countyId: 129 }));
    await waitFor(() => expect(api.campaignPosts).toHaveBeenCalledTimes(2));
    expect(api.campaignDomestic).toHaveBeenCalledTimes(1);
});

test.each([130, 999])('점유 현 또는 서버 미후보 %s는 미리 선택하지 않고 정상 수동 선택을 보존한다', async (countyId) => {
    render(<TerritoryScreen hrefs={hrefs} initialView="placement" initialCountyId={countyId} />);
    const sheet = await choosePerson();
    expect(within(sheet).getByRole('option', { name: /양성현/ })).toHaveAttribute('aria-selected', 'false');
    expect(within(sheet).getByRole('option', { name: /허현/ })).toHaveAttribute('aria-disabled', 'true');
    expect(sheet).toHaveTextContent('다른 인물이 맡고 있습니다');
    const submit = within(sheet).getByRole('button', { name: '이 자리로' });
    expect(submit).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(submit);
    expect(api.campaignDomestic).not.toHaveBeenCalled();
    fireEvent.click(within(sheet).getByRole('option', { name: /양성현/ }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    await waitFor(() => expect(api.campaignDomestic).toHaveBeenCalledWith(7, 'placement', { cardId: 1, post: 'MAGISTRATE', countyId: 129 }));
});

test('현 값 부재는 자리와 현의 기존 수동 선택을 유지한다', async () => {
    render(<TerritoryScreen hrefs={hrefs} initialView="placement" initialCountyId={null} />);
    const sheet = await choosePerson();
    expect(within(sheet).getByRole('option', { name: '현령' })).toHaveAttribute('aria-selected', 'false');
    expect(within(sheet).queryByRole('listbox', { name: '맡길 현' })).toBeNull();
    fireEvent.click(within(sheet).getByRole('option', { name: '현령' }));
    fireEvent.click(within(sheet).getByRole('option', { name: /양성현/ }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    await waitFor(() => expect(api.campaignDomestic).toHaveBeenCalledWith(7, 'placement', { cardId: 1, post: 'MAGISTRATE', countyId: 129 }));
});

// ── 군단장 — 카드별 판정(PlacementCard.corpsCommander) ──────────────────
const ALREADY = { code: 'ALREADY_COMMANDER', reason: '이미 군단을 이끌고 있습니다.' };
const withCommander = (cards: PlacementCard[]): Posts => ({
    ...posts, cards, posts: posts.posts.map((p) => (p.post === 'CORPS_COMMANDER' ? { ...p, available: true } : p)),
});
const able = card(11, { name: '악진', corpsCommander: { available: true, blocked: null } });
const unable = card(12, { name: '우금', corpsCommander: { available: false, blocked: ALREADY } });
const commanderOption = (root: HTMLElement) => within(within(root).getByRole('listbox', { name: '자리 종류' })).getByRole('option', { name: /^군단장/ });
const submitButton = () => screen.getByRole('button', { name: '이 자리로' });

test('카드를 바꾸면 군단장 가능 여부가 카드 판정을 따르고, 앞 카드의 선택은 넘어오지 않는다', () => {
    const onSubmit = vi.fn();
    const both = withCommander([able, unable]);
    const { container, rerender } = render(<PlacementSheet card={able} posts={both} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    fireEvent.click(commanderOption(container));
    expect(commanderOption(container)).toHaveAttribute('aria-selected', 'true');
    expect(submitButton()).not.toHaveAttribute('aria-disabled');

    rerender(<PlacementSheet card={unable} posts={both} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    expect(commanderOption(container)).toHaveAttribute('aria-disabled', 'true');
    expect(container).toHaveTextContent('이미 군단을 이끌고 있습니다.');
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(commanderOption(container));
    fireEvent.click(submitButton());
    expect(onSubmit).not.toHaveBeenCalled();

    // Back on the eligible card the earlier pick does not silently come back.
    rerender(<PlacementSheet card={able} posts={both} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    expect(commanderOption(container)).toHaveAttribute('aria-selected', 'false');
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(commanderOption(container));
    fireEvent.click(submitButton());
    expect(onSubmit).toHaveBeenCalledWith({ cardId: 11, post: 'CORPS_COMMANDER' });
});

test('시트를 연 채 다시 읽어 그 카드가 군단장을 못 하게 되면 옛 카드 객체로 제출하지 않는다', () => {
    const onSubmit = vi.fn();
    const { container, rerender } = render(<PlacementSheet card={able} posts={withCommander([able])} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    fireEvent.click(commanderOption(container));
    expect(submitButton()).not.toHaveAttribute('aria-disabled');

    // Same stale card prop, but the reread now carries a blocked verdict for card 11.
    const reread = withCommander([{ ...able, pending: { post: 'CORPS_COMMANDER', postLabel: '군단장', target: { label: null } },
        corpsCommander: { available: false, blocked: { code: 'PENDING_ORDER', reason: '이미 다음 턴 배치가 잡혀 있습니다.' } } }]);
    rerender(<PlacementSheet card={able} posts={reread} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    expect(commanderOption(container)).toHaveAttribute('aria-disabled', 'true');
    expect(container).toHaveTextContent('이미 다음 턴 배치가 잡혀 있습니다.');
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(submitButton());
    expect(onSubmit).not.toHaveBeenCalled();
});

test('시트를 연 채 다시 읽어 고른 현이 점유되면 그 현으로 보내지 않는다', () => {
    const onSubmit = vi.fn();
    const { container, rerender } = render(<PlacementSheet card={posts.cards[0]} posts={posts} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    fireEvent.click(within(container).getByRole('option', { name: '현령' }));
    fireEvent.click(within(container).getByRole('option', { name: /양성현/ }));
    expect(submitButton()).not.toHaveAttribute('aria-disabled');
    const taken = { ...posts, posts: posts.posts.map((p) => (p.post === 'MAGISTRATE'
        ? { ...p, targets: p.targets!.map((t) => ({ ...t, occupied: true })) } : p)) };
    rerender(<PlacementSheet card={posts.cards[0]} posts={taken} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(submitButton());
    expect(onSubmit).not.toHaveBeenCalled();
});

test('점유로 버린 현은 다시 비어도 저절로 되살아나지 않고, 다시 골라야 보낸다', () => {
    const onSubmit = vi.fn();
    const sheet = (p: Posts) => <PlacementSheet card={posts.cards[0]} posts={p} busy={false} onSubmit={onSubmit} onCancel={() => {}} />;
    const { container, rerender } = render(sheet(posts));
    fireEvent.click(within(container).getByRole('option', { name: '현령' }));
    fireEvent.click(within(container).getByRole('option', { name: /양성현/ }));
    expect(submitButton()).not.toHaveAttribute('aria-disabled');
    const taken = { ...posts, posts: posts.posts.map((p) => (p.post === 'MAGISTRATE'
        ? { ...p, targets: p.targets!.map((t) => ({ ...t, occupied: true })) } : p)) };
    rerender(sheet(taken));
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');

    // The county is free again in a later read, but the dropped pick must not come back by itself.
    rerender(sheet({ ...posts }));
    expect(within(container).getByRole('option', { name: '현령' })).toHaveAttribute('aria-selected', 'true');
    expect(within(container).getByRole('option', { name: /양성현/ })).toHaveAttribute('aria-selected', 'false');
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(submitButton());
    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.click(within(container).getByRole('option', { name: /양성현/ }));
    fireEvent.click(submitButton());
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith({ cardId: 1, post: 'MAGISTRATE', countyId: 129 });
});

// ── 카드 판정(placeable) — false · 없음은 어느 자리도 열지 않는다 ─────────────
const lead = card(21, { name: '전위', corpsCommander: { available: true, blocked: null } });
const withoutPlaceable = (c: PlacementCard) => Object.fromEntries(Object.entries(c).filter(([k]) => k !== 'placeable')) as unknown as PlacementCard;
const denials: [string, PlacementCard, string][] = [
    ['사람 장수', { ...lead, placeable: false, blocked: { code: 'HUMAN_CARD', reason: '사람 장수는 배치가 아니라 발령으로 자리에 앉힙니다.' } },
        '사람 장수는 배치가 아니라 발령으로 자리에 앉힙니다.'],
    ['출전', { ...lead, placeable: false, blocked: { code: 'CARD_DEPLOYED', reason: '출전 중인 지휘 카드는 배치를 바꿀 수 없습니다.' } },
        '출전 중인 지휘 카드는 배치를 바꿀 수 없습니다.'],
    ['조우', { ...lead, placeable: false, blocked: { code: 'CARD_IN_BATTLE', reason: '조우 처리가 끝나야 배치를 바꿀 수 있습니다.' } },
        '조우 처리가 끝나야 배치를 바꿀 수 있습니다.'],
    ['사유 없는 거절', { ...lead, placeable: false, blocked: null }, '사유를 받지 못했습니다'],
    ['판정 없음', withoutPlaceable(lead), '사유를 받지 못했습니다'],
];
const kindOption = (root: HTMLElement, label: RegExp) => within(within(root).getByRole('listbox', { name: '자리 종류' })).getByRole('option', { name: label });

test.each(denials)('처음 연 카드가 %s면 군단장 판정이 열려 있어도 어느 자리도 고르거나 보낼 수 없다', (_, denied, reason) => {
    const onSubmit = vi.fn();
    const { container } = render(<PlacementSheet card={lead} posts={withCommander([denied])} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    for (const label of [/^현령/, /^군단장/, /^정찰/, /^자리에서 풀기/]) {
        expect(kindOption(container, label)).toHaveAttribute('aria-disabled', 'true');
        fireEvent.click(kindOption(container, label));
    }
    expect(container).toHaveTextContent(reason);
    expect(within(container).queryByRole('listbox', { name: '맡길 현' })).toBeNull();
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(submitButton());
    expect(onSubmit).not.toHaveBeenCalled();
});

test.each(denials)('군단장을 고른 뒤 다시 읽어 카드가 %s면 제출을 막고, 다시 열려도 다시 골라야 한다', (_, denied, reason) => {
    const onSubmit = vi.fn();
    const sheet = (c: PlacementCard) => <PlacementSheet card={lead} posts={withCommander([c])} busy={false} onSubmit={onSubmit} onCancel={() => {}} />;
    const { container, rerender } = render(sheet(lead));
    fireEvent.click(commanderOption(container));
    expect(submitButton()).not.toHaveAttribute('aria-disabled');

    rerender(sheet(denied));
    expect(commanderOption(container)).toHaveAttribute('aria-disabled', 'true');
    expect(container).toHaveTextContent(reason);
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(submitButton());
    // Re-rendering the same denied read stays stable and closed.
    rerender(sheet(denied));
    rerender(sheet(denied));
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    expect(onSubmit).not.toHaveBeenCalled();

    rerender(sheet(lead));
    expect(commanderOption(container)).toHaveAttribute('aria-selected', 'false');
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(submitButton());
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.click(commanderOption(container));
    fireEvent.click(submitButton());
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith({ cardId: 21, post: 'CORPS_COMMANDER' });
});

test.each(denials)('현령과 현을 고른 뒤 다시 읽어 카드가 %s면 제출을 막고, 다시 열려도 자리와 현을 다시 골라야 한다', (_, denied, reason) => {
    const onSubmit = vi.fn();
    const sheet = (c: PlacementCard) => <PlacementSheet card={lead} posts={{ ...posts, cards: [c] }} busy={false} onSubmit={onSubmit} onCancel={() => {}} />;
    const { container, rerender } = render(sheet(lead));
    fireEvent.click(kindOption(container, /^현령/));
    fireEvent.click(within(container).getByRole('option', { name: /양성현/ }));
    expect(submitButton()).not.toHaveAttribute('aria-disabled');

    rerender(sheet(denied));
    expect(kindOption(container, /^현령/)).toHaveAttribute('aria-disabled', 'true');
    expect(container).toHaveTextContent(reason);
    expect(within(container).queryByRole('listbox', { name: '맡길 현' })).toBeNull();
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(submitButton());
    rerender(sheet(denied));
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    expect(onSubmit).not.toHaveBeenCalled();

    rerender(sheet(lead));
    expect(kindOption(container, /^현령/)).toHaveAttribute('aria-selected', 'false');
    expect(within(container).queryByRole('listbox', { name: '맡길 현' })).toBeNull();
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(kindOption(container, /^현령/));
    expect(within(container).getByRole('option', { name: /양성현/ })).toHaveAttribute('aria-selected', 'false');
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(submitButton());
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.click(within(container).getByRole('option', { name: /양성현/ }));
    fireEvent.click(submitButton());
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith({ cardId: 21, post: 'MAGISTRATE', countyId: 129 });
});

test('같은 유효 조회를 거듭 다시 그려도 고른 자리 · 현이 유지되고 한 번만 보낸다', () => {
    const onSubmit = vi.fn();
    const sheet = () => <PlacementSheet card={lead} posts={{ ...posts, cards: [{ ...lead }] }} busy={false} onSubmit={onSubmit} onCancel={() => {}} />;
    const { container, rerender } = render(sheet());
    fireEvent.click(kindOption(container, /^현령/));
    fireEvent.click(within(container).getByRole('option', { name: /양성현/ }));
    for (let i = 0; i < 3; i += 1) rerender(sheet());
    expect(kindOption(container, /^현령/)).toHaveAttribute('aria-selected', 'true');
    expect(within(container).getByRole('option', { name: /양성현/ })).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(submitButton());
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith({ cardId: 21, post: 'MAGISTRATE', countyId: 129 });
});

test('옛 서버(카드 판정 없음)는 공통 목록이 열어도 군단장을 닫고, 다른 자리는 그대로 보낸다', () => {
    const onSubmit = vi.fn();
    const old = card(13, { name: '이전' });
    const { container } = render(<PlacementSheet card={old} posts={withCommander([old])} busy={false} onSubmit={onSubmit} onCancel={() => {}} />);
    expect(commanderOption(container)).toHaveAttribute('aria-disabled', 'true');
    expect(container).toHaveTextContent('군단장을 맡을 수 있는지 서버가 알려 주지 않았습니다');
    fireEvent.click(commanderOption(container));
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(within(container).getByRole('option', { name: '자리에서 풀기' }));
    fireEvent.click(submitButton());
    expect(onSubmit).toHaveBeenCalledWith({ cardId: 13, post: 'NONE' });
});

test('군단장 접수는 완료가 아니라 대기다 — 다시 읽은 카드 판정이 닫히면 다시 열어도 고를 수 없다', async () => {
    const lead = card(1, { name: '허저', corpsCommander: { available: true, blocked: null } });
    vi.mocked(api.campaignPosts)
        .mockResolvedValueOnce(withCommander([lead]))
        .mockResolvedValue(withCommander([{ ...lead, pending: { post: 'CORPS_COMMANDER', postLabel: '군단장', target: { label: null } },
            corpsCommander: { available: false, blocked: { code: 'PENDING_ORDER', reason: '이미 다음 턴 배치가 잡혀 있습니다.' } } }]));
    render(<TerritoryScreen hrefs={hrefs} initialView="placement" />);
    const sheet = await choosePerson();
    fireEvent.click(commanderOption(sheet));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    await waitFor(() => expect(api.campaignDomestic).toHaveBeenCalledWith(7, 'placement', { cardId: 1, post: 'CORPS_COMMANDER' }));
    expect(await screen.findByText('배치를 접수했습니다 — 카드의 다음 턴부터 부임합니다.')).toBeInTheDocument();
    const region = screen.getByRole('region', { name: '배치' });
    expect(await within(region).findByText('대기 — 다음 턴부터 군단장')).toBeInTheDocument();
    expect(region).not.toHaveTextContent('군단장 · ');

    const again = await choosePerson();
    expect(commanderOption(again)).toHaveAttribute('aria-disabled', 'true');
    expect(again).toHaveTextContent('이미 다음 턴 배치가 잡혀 있습니다.');
    fireEvent.click(within(again).getByRole('button', { name: '이 자리로' }));
    expect(api.campaignDomestic).toHaveBeenCalledTimes(1);
});

test('현 값이 있어도 서버의 현령 권한 거절은 초기 선택으로 우회하지 않는다', async () => {
    vi.mocked(api.campaignPosts).mockResolvedValue({ ...posts, posts: posts.posts.map((p) => p.post === 'MAGISTRATE'
        ? { ...p, available: false, blocked: { code: 'NOT_LORD', reason: '군주만 현령을 배치합니다.' } } : p) });
    render(<TerritoryScreen hrefs={hrefs} initialView="placement" initialCountyId={129} />);
    const sheet = await choosePerson();
    expect(within(sheet).getByRole('option', { name: /^현령/ })).toHaveAttribute('aria-disabled', 'true');
    expect(sheet).toHaveTextContent('군주만 현령을 배치합니다.');
    expect(within(sheet).queryByRole('listbox', { name: '맡길 현' })).toBeNull();
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    expect(api.campaignDomestic).not.toHaveBeenCalled();
});
