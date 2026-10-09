// 영지 방침 칸의 군단 문맥 — 군단 탭 · 고른 군단 줄 표시만(편집 · 제출 자동 없음) · 사유(권한 · 소멸 · 읽기 실패 · 잘못된 주소) ·
// 주소 · 장수 변경마다 열린 편집 무효화(A→B→A 포함) · 늦은 응답 · 편집 · 제출 직전 최신 줄 확인. 시험 대역(api 흉내)만 쓴다.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { installViewport } from '@opensamguk/ui';
import { TerritoryScreen } from '../components/territory/TerritoryScreen';
import { api } from '../lib/api';
import type { TerritoryPolicyQuery } from '../lib/territory/corps-policy-link';
import { CORPS_CONTEXT_TEXT, STALE_POLICY_TARGET } from '../lib/territory/corps-policy-view';

vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/territory', useSearchParams: () => new URLSearchParams(), useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }) }));

const session = vi.hoisted(() => ({ generalId: 7 as number | null, frontInfo: null as unknown }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => session }));
vi.mock('../lib/api', () => ({
    api: { campaignPosts: vi.fn(), campaignPolicies: vi.fn(), campaignWorks: vi.fn(), warehouses: vi.fn(), roadForts: vi.fn(), campaignRetinue: vi.fn(), campaignDomestic: vi.fn() },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const OPAQUE = 'ord/7 a&b=c';
const hrefs = { supply: '/game/pep/territory/supply', court: '/game/pep/court' };
const corpsRow = (orderId: string, name: string, settable = true, reason?: string) => ({
    orderId, commanderName: name, active: null, pending: null, settable, blocked: settable ? null : { code: 'NOT_OWNER', reason: reason ?? '' },
});
const policies = (corps: unknown[]) => ({
    status: 'READY', countyOptions: [{ code: 'FARM', label: '농업' }], corpsOptions: [{ code: 'DRILL', label: '조련' }], defaultPolicy: null, corps,
    counties: [{ countyId: 129, name: '양성현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: null, settable: true, blocked: null }],
});
const target = (orderId: string): TerritoryPolicyQuery => ({ kind: 'corps', orderId });

let viewport: ReturnType<typeof installViewport> | null = null;
const setMobile = (on: boolean) => { viewport?.restore(); viewport = installViewport(on ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });

beforeEach(() => {
    vi.clearAllMocks();
    session.generalId = 7;
    session.frontInfo = null;
    setMobile(false);
    vi.mocked(api.campaignPosts).mockResolvedValue({ status: 'READY', cards: [], posts: [] } as never);
    vi.mocked(api.campaignPolicies).mockResolvedValue(policies([corpsRow(OPAQUE, '하후돈'), corpsRow('O-2', '조인')]) as never);
    vi.mocked(api.campaignWorks).mockResolvedValue({ status: 'READY', counties: [] } as never);
    vi.mocked(api.warehouses).mockResolvedValue({ status: 'READY', warehouses: [] } as never);
    vi.mocked(api.roadForts).mockResolvedValue({ status: 'READY', roadMode: false, forts: [], gates: [] } as never);
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 1, costSum: 0, overCapacity: false, people: [], units: [] } as never);
});

const policyRegion = () => screen.getByRole('region', { name: '방침' });
const corpsTab = () => within(policyRegion()).getByRole('radio', { name: /^군단/ });

test('고른 내 군단 — 군단 탭을 열고 그 줄만 「고른 군단」으로 표시, 편집 시트 · 제출은 저절로 일어나지 않는다', async () => {
    render(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target(OPAQUE)} contextKey="pep|A" />);
    expect(await within(policyRegion()).findByText('하후돈 군단 — 「바꾸기」를 눌러 방침을 고르세요.')).toHaveAttribute('role', 'status');
    expect(corpsTab()).toHaveAttribute('aria-checked', 'true');
    const marked = within(policyRegion()).getAllByRole('listitem').filter((li) => li.getAttribute('aria-current') === 'true');
    expect(marked).toHaveLength(1);
    expect(marked[0]).toHaveAttribute('data-target-id', OPAQUE);
    expect(marked[0]).toHaveTextContent('고른 군단');
    expect(screen.queryByRole('region', { name: '하후돈 군단 방침' })).toBeNull();
    expect(api.campaignDomestic).not.toHaveBeenCalled();

    // 키보드 — 표시된 줄의 「바꾸기」로 열고, 고른 뒤 「이 방침으로」를 눌러야 불투명 id 그대로 보낸다
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'AVAILABLE' } as never);
    fireEvent.click(within(marked[0]).getByRole('button', { name: '바꾸기' }));
    const sheet = await screen.findByRole('region', { name: '하후돈 군단 방침' });
    fireEvent.click(within(sheet).getByRole('option', { name: '조련' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 방침으로' }));
    await waitFor(() => expect(api.campaignDomestic).toHaveBeenCalledWith(7, 'policy', { scope: 'CORPS', orderId: OPAQUE, policy: 'DRILL' }));
});

test('권한 없음 — 줄은 표시하되 서버 사유, 「바꾸기」는 막힘', async () => {
    vi.mocked(api.campaignPolicies).mockResolvedValue(policies([corpsRow('O-1', '하후돈', false, '군단 주인만 방침을 정합니다')]) as never);
    render(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target('O-1')} contextKey="pep|A" />);
    expect(await within(policyRegion()).findByText('하후돈 군단 — 방침을 바꿀 수 없습니다: 군단 주인만 방침을 정합니다')).toBeInTheDocument();
    const row = within(policyRegion()).getAllByRole('listitem').find((li) => li.getAttribute('aria-current') === 'true')!;
    expect(within(row).getByRole('button', { name: /바꾸기/ })).toHaveAttribute('aria-disabled', 'true');
});

test('대상 소멸 — 사유만, 다른 군단 · 현으로 대신 표시하지 않는다', async () => {
    render(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target('GONE')} contextKey="pep|A" />);
    expect(await within(policyRegion()).findByText(CORPS_CONTEXT_TEXT.missing)).toBeInTheDocument();
    expect(corpsTab()).toHaveAttribute('aria-checked', 'true');
    expect(within(policyRegion()).getAllByRole('listitem').some((li) => li.getAttribute('aria-current') === 'true')).toBe(false);
});

test('방침 읽기 실패 — 대상을 확인하지 못했다는 안내와 칸의 다시 시도', async () => {
    vi.mocked(api.campaignPolicies).mockRejectedValue(new Error('500: x'));
    render(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target('O-1')} contextKey="pep|A" />);
    expect(await within(policyRegion()).findByText(CORPS_CONTEXT_TEXT.failed)).toBeInTheDocument();
    expect(within(policyRegion()).getByText('방침을 불러오지 못했습니다')).toBeInTheDocument();
});

test('잘못된 주소 · 목록 문맥 — 군단 탭과 안내, 표시하는 줄 없음', async () => {
    const view = render(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={{ kind: 'invalid' }} contextKey="pep|bad" />);
    expect(await within(policyRegion()).findByText(CORPS_CONTEXT_TEXT.invalid)).toBeInTheDocument();
    expect(await within(policyRegion()).findByRole('radio', { name: /^군단/ })).toHaveAttribute('aria-checked', 'true');
    view.rerender(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={{ kind: 'corpsList' }} contextKey="pep|list" />);
    expect(await within(policyRegion()).findByText(CORPS_CONTEXT_TEXT.list)).toBeInTheDocument();
    expect(within(policyRegion()).getAllByRole('listitem').some((li) => li.getAttribute('aria-current') === 'true')).toBe(false);
});

test('문맥 없음 — 기존 기본(현 탭 · 안내 없음)', async () => {
    render(<TerritoryScreen hrefs={hrefs} />);
    await within(policyRegion()).findByText('양성현');
    expect(within(policyRegion()).getByRole('radio', { name: /^현/ })).toHaveAttribute('aria-checked', 'true');
    expect(within(policyRegion()).queryByText(CORPS_CONTEXT_TEXT.list)).toBeNull();
});

test('모바일 — 방침 세그먼트 · 군단 탭 · 대상 표시', async () => {
    setMobile(true);
    render(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target(OPAQUE)} contextKey="pep|A" />);
    const seg = await screen.findByRole('radiogroup', { name: '보기' });
    expect(within(seg).getByRole('radio', { name: '방침' })).toHaveAttribute('aria-checked', 'true');
    expect(await screen.findByText('하후돈 군단 — 「바꾸기」를 눌러 방침을 고르세요.')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /^군단/ })).toHaveAttribute('aria-checked', 'true');
});

async function openSheetFor(name: string) {
    const row = (await within(policyRegion()).findAllByRole('listitem')).find((li) => li.textContent?.includes(name))!;
    fireEvent.click(within(row).getByRole('button', { name: '바꾸기' }));
    return screen.findByRole('region', { name: `${name} 방침` });
}

test('주소가 A→B→A 로 바뀌면 A 에서 연 편집은 돌아와도 되살아나지 않고, 표시는 지금 주소의 대상', async () => {
    const view = render(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target(OPAQUE)} contextKey="pep|A" />);
    await openSheetFor('하후돈 군단');
    view.rerender(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target('O-2')} contextKey="pep|B" />);
    expect(screen.queryByRole('region', { name: '하후돈 군단 방침' })).toBeNull();
    expect(await within(policyRegion()).findByText('조인 군단 — 「바꾸기」를 눌러 방침을 고르세요.')).toBeInTheDocument();
    view.rerender(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target(OPAQUE)} contextKey="pep|A" />);
    expect(screen.queryByRole('region', { name: '하후돈 군단 방침' })).toBeNull();
    expect(within(policyRegion()).getByText('하후돈 군단 — 「바꾸기」를 눌러 방침을 고르세요.')).toBeInTheDocument();
    expect(api.campaignDomestic).not.toHaveBeenCalled();
});

test('탭 서버 · 장수가 바뀌면 열린 편집을 버린다', async () => {
    const view = render(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target(OPAQUE)} contextKey="pep|A" />);
    await openSheetFor('하후돈 군단');
    view.rerender(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target(OPAQUE)} contextKey="other|A" />);
    expect(screen.queryByRole('region', { name: '하후돈 군단 방침' })).toBeNull();
    await openSheetFor('하후돈 군단');
    session.generalId = 8;
    view.rerender(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target(OPAQUE)} contextKey="other|A" />);
    expect(screen.queryByRole('region', { name: '하후돈 군단 방침' })).toBeNull();
});

test('보낸 뒤 주소가 바뀌고 늦게 온 응답 — 새 문맥에 접수 알림을 쓰지 않는다', async () => {
    let resolve: (v: unknown) => void = () => {};
    vi.mocked(api.campaignDomestic).mockReturnValue(new Promise((r) => { resolve = r; }) as never);
    const view = render(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target(OPAQUE)} contextKey="pep|A" />);
    const sheet = await openSheetFor('하후돈 군단');
    fireEvent.click(within(sheet).getByRole('option', { name: '조련' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 방침으로' }));
    expect(api.campaignDomestic).toHaveBeenCalledTimes(1);
    view.rerender(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target('O-2')} contextKey="pep|B" />);
    await act(async () => { resolve({ status: 'AVAILABLE' }); });
    expect(screen.queryByText('방침을 접수했습니다 — 다음 턴부터 적용합니다.')).toBeNull();
    expect(screen.queryByRole('region', { name: '하후돈 군단 방침' })).toBeNull();
});

test('제출 직전 최신 READY 줄을 다시 본다 — 그사이 막힌 군단은 옛 줄로 보내지 않는다', async () => {
    session.frontInfo = { global: { year: 200, month: 3, turnPhase: 1 } };
    const view = render(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target(OPAQUE)} contextKey="pep|A" />);
    await openSheetFor('하후돈 군단');
    // 다음 순 — 같은 장수 · 같은 주소에서 방침을 다시 읽었더니 이 군단은 이제 바꿀 수 없다
    vi.mocked(api.campaignPolicies).mockResolvedValue(policies([corpsRow(OPAQUE, '하후돈', false, '군단 주인만 방침을 정합니다')]) as never);
    session.frontInfo = { global: { year: 200, month: 3, turnPhase: 2 } };
    view.rerender(<TerritoryScreen hrefs={hrefs} initialView="policy" policyQuery={target(OPAQUE)} contextKey="pep|A" />);
    expect(await within(policyRegion()).findByText('하후돈 군단 — 방침을 바꿀 수 없습니다: 군단 주인만 방침을 정합니다')).toBeInTheDocument();
    const sheet = await screen.findByRole('region', { name: '하후돈 군단 방침' });
    fireEvent.click(within(sheet).getByRole('option', { name: '조련' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 방침으로' }));
    expect(api.campaignDomestic).not.toHaveBeenCalled();
    expect(screen.getByText(STALE_POLICY_TARGET)).toHaveAttribute('role', 'status');
    expect(screen.queryByRole('region', { name: '하후돈 군단 방침' })).toBeNull();
});
