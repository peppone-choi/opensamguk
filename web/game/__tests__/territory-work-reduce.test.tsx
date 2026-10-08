import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { installViewport } from '@opensamguk/ui';
import { TerritoryScreen } from '../components/territory/TerritoryScreen';
import { WorkReductionSheet } from '../components/territory/WorkParts';
import { api } from '../lib/api';
import type { CountyWorks, Works } from '../lib/campaign-reads';
import { workRows } from '../lib/territory-view';

const clock = vi.hoisted(() => ({ phase: 1 }));
vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/territory', useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }) }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7,
    frontInfo: { global: { year: 200, month: 1, turnPhase: clock.phase } } }) }));
vi.mock('../lib/api', () => ({
    api: { campaignPosts: vi.fn(), campaignPolicies: vi.fn(), campaignWorks: vi.fn(), warehouses: vi.fn(),
        roadForts: vi.fn(), campaignRetinue: vi.fn(), campaignDomestic: vi.fn() },
    isIntakeQueued: (out: { status: string }) => out.status === 'AVAILABLE',
    isIntakeDenied: (out: { status: string }) => out.status === 'BLOCKED' || out.status === 'UNKNOWN',
}));

const hrefs = { supply: '/game/pep/territory/supply', court: '/game/pep/court' };
const county: CountyWorks = { countyId: 129, provinceId: null, provinceIds: [], name: '양성현', commanderyName: '영천군',
    warehouse: { money: 1, grain: 2, iron: 3, timber: 4, horses: 5 }, active: null,
    completed: [{ work: 'FORTIFICATION', label: '성방', edgeId: null }], startable: [], reducible: true, reduceBlocked: null };
const works = (over: Partial<CountyWorks> = {}): Works => ({ status: 'READY', counties: [{ ...county, ...over }] });
let viewport: ReturnType<typeof installViewport> | null = null;
afterEach(() => { viewport?.restore(); viewport = null; });
beforeEach(() => {
    vi.clearAllMocks(); clock.phase = 1;
    vi.mocked(api.campaignPosts).mockResolvedValue({ status: 'UNAVAILABLE' } as never);
    vi.mocked(api.campaignPolicies).mockResolvedValue({ status: 'UNAVAILABLE' } as never);
    vi.mocked(api.warehouses).mockResolvedValue({ status: 'UNAVAILABLE' } as never);
    vi.mocked(api.roadForts).mockResolvedValue({ status: 'UNAVAILABLE' } as never);
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'UNAVAILABLE' } as never);
    vi.mocked(api.campaignWorks).mockResolvedValue(works());
});

test.each([1440, 390])('actual county selection submits reduction then reloads pending and applied results at %ipx', async (width) => {
    viewport = installViewport(width);
    const pending = { requestId: 'reduce-129', status: 'PENDING' as const, requestedAt: { year: 200, month: 1, phase: 1 }, resolvedAt: null, reason: null };
    let resolveReload!: (value: Works) => void;
    const reload = new Promise<Works>((resolve) => { resolveReload = resolve; });
    vi.mocked(api.campaignDomestic).mockImplementation(async () => {
        vi.mocked(api.campaignWorks).mockReturnValueOnce(reload);
        return { status: 'AVAILABLE', requestId: pending.requestId } as never;
    });
    const { rerender } = render(<TerritoryScreen hrefs={hrefs} initialView="work" />);
    fireEvent.click(await screen.findByRole('button', { name: '성방 허물기' }));
    const sheet = await screen.findByRole('region', { name: '양성현 성방 감축' });
    expect(sheet).toHaveTextContent('다음 순 경계부터');
    fireEvent.click(within(sheet).getByRole('button', { name: '이 성방 허물기' }));
    await waitFor(() => expect(api.campaignDomestic).toHaveBeenCalledWith(7, 'reduce', { countyId: 129, work: 'FORTIFICATION' }));
    await waitFor(() => expect(screen.queryByRole('region', { name: '양성현 성방 감축' })).toBeNull());
    await waitFor(() => expect(api.campaignWorks).toHaveBeenCalledTimes(2));
    // Closing the sheet does not wait for the authoritative works reload.
    const pendingReadback = waitFor(() => {
        expect(screen.getByRole('button', { name: '성방 허물기' })).toHaveAttribute('data-input-status', 'BLOCKED');
        expect(within(screen.getByRole('list', { name: '공사' })).getByRole('status'))
            .toHaveTextContent('성방 감축을 접수했습니다 — 다음 순 경계부터 적용합니다.');
    });
    resolveReload(works({ reducible: false,
        reduceBlocked: { code: 'WORK_IN_PROGRESS', reason: '감축을 접수했습니다.' }, reduction: pending }));
    await pendingReadback;
    vi.mocked(api.campaignWorks).mockResolvedValue(works({ completed: [], reducible: false,
        reduceBlocked: { code: 'WORK_NOT_COMPLETED', reason: '감축할 성방이 없습니다.' },
        reduction: { ...pending, status: 'APPLIED', resolvedAt: { year: 200, month: 1, phase: 2 } } }));
    clock.phase = 2;
    rerender(<TerritoryScreen hrefs={hrefs} initialView="work" />);
    expect(await screen.findByText('성방 감축을 완료했습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '성방 허물기' })).toBeNull();
    expect(api.campaignDomestic).toHaveBeenCalledTimes(1);
});

test('denied and busy sheets never submit, and a road fort is not a county fort option', () => {
    const submit = vi.fn();
    const { rerender } = render(<WorkReductionSheet county={{ ...county, reducible: false,
        reduceBlocked: { code: 'NOT_COUNTY_AUTHORITY', reason: '이 현의 권한이 없습니다.' } }} busy={false} onSubmit={submit} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: '이 성방 허물기' }));
    expect(submit).not.toHaveBeenCalled();
    rerender(<WorkReductionSheet county={county} busy onSubmit={submit} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: '보내는 중' }));
    expect(submit).not.toHaveBeenCalled();
    expect(workRows(works({ completed: [{ work: 'FORTIFICATION', label: '보루', edgeId: 'road' }] }))[0].hasFortification).toBe(false);
});
