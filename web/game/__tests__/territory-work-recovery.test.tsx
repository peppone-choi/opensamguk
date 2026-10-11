import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { installViewport } from '@opensamguk/ui';
import { TerritoryScreen } from '../components/territory/TerritoryScreen';
import { api } from '../lib/api';
import type { CountyWorks, RoadForts, Works } from '../lib/campaign-reads';

const clock = vi.hoisted(() => ({ phase: 1 }));
vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/territory',
    useSearchParams: () => new URLSearchParams(), useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }) }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7,
    frontInfo: { global: { year: 200, month: 1, turnPhase: clock.phase } } }) }));
vi.mock('../lib/api', () => ({
    api: { campaignPosts: vi.fn(), campaignPolicies: vi.fn(), campaignWorks: vi.fn(), warehouses: vi.fn(),
        roadForts: vi.fn(), campaignRetinue: vi.fn(), campaignDomestic: vi.fn() },
    isIntakeQueued: (out: { status: string }) => out.status === 'AVAILABLE',
    isIntakeDenied: (out: { status: string }) => out.status === 'BLOCKED' || out.status === 'UNKNOWN',
}));

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const hrefs = { supply: '/game/pep/territory/supply', court: '/game/pep/court' };
const county: CountyWorks = { countyId: 129, provinceId: 'p-yang', provinceIds: ['p-yang'], name: '양성현',
    commanderyName: '영천군', warehouse: zero, active: null, completed: [], startable: [] };
const works = (over: Partial<CountyWorks>): Works => ({ status: 'READY', counties: [{ ...county, ...over }] });
const roads = (active: boolean): RoadForts => ({ status: 'READY', roadMode: true, forts: [], gates: [
    { edgeId: 'e-yang-xu', fromProvinceId: 'p-yang', toProvinceId: 'p-xu', active, buildable: true,
        historicalRouteIds: [], fortCells: [{ row: 5, col: 6, provinceId: 'p-yang' }] },
] });
let viewport: ReturnType<typeof installViewport> | null = null;
afterEach(() => { viewport?.restore(); viewport = null; });
beforeEach(() => {
    vi.resetAllMocks(); clock.phase = 1;
    vi.mocked(api.campaignPosts).mockResolvedValue({ status: 'UNAVAILABLE' } as never);
    vi.mocked(api.campaignPolicies).mockResolvedValue({ status: 'UNAVAILABLE' } as never);
    vi.mocked(api.warehouses).mockResolvedValue({ status: 'UNAVAILABLE' } as never);
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'UNAVAILABLE' } as never);
});

test.each([
    [1440, 'ROAD', '도로'], [390, 'ROAD', '도로'],
    [1440, 'FORTIFICATION', '성방'], [390, 'FORTIFICATION', '성방'],
] as const)('mocked %ipx %s flow recovers target read, submits once, then reads phase progress and completion', async (width, work, label) => {
    viewport = installViewport(width);
    const choice = { work, label, available: true, blocked: null, cost: zero, estimatedPhases: 2 };
    vi.mocked(api.campaignWorks).mockResolvedValue(works({ startable: [choice] }));
    vi.mocked(api.roadForts).mockRejectedValueOnce(new Error('503: private upstream detail'));
    const { rerender } = render(<TerritoryScreen hrefs={hrefs} initialView="work" provinceName={(id) => id === 'p-yang' ? '양성 북' : '허현 서'} />);
    fireEvent.click(await screen.findByRole('button', { name: '새 공사' }));
    const sheet = await screen.findByRole('region', { name: '양성현 새 공사' });
    fireEvent.click(within(sheet).getByRole('option', { name: new RegExp(label) }));
    expect(within(sheet).getByRole('button', { name: '이 공사로' })).toHaveAttribute('aria-disabled', 'true');
    expect(api.campaignDomestic).not.toHaveBeenCalled();
    expect(sheet).not.toHaveTextContent('private upstream detail');
    let resolveTargets!: (out: RoadForts) => void;
    vi.mocked(api.roadForts).mockReturnValueOnce(new Promise((resolve) => { resolveTargets = resolve; }));
    fireEvent.click(within(sheet).getByRole('button', { name: '도로 · 보루 자리 다시 시도' }));
    await waitFor(() => expect(api.roadForts).toHaveBeenCalledTimes(2));
    expect(within(sheet).getByRole('button', { name: '이 공사로' })).toHaveAttribute('aria-disabled', 'true');
    expect(api.campaignWorks).toHaveBeenCalledTimes(1);
    resolveTargets(roads(work === 'FORTIFICATION'));
    vi.mocked(api.roadForts).mockResolvedValue(roads(work === 'FORTIFICATION'));
    fireEvent.click(await within(sheet).findByRole('option', { name: /양성 북 ↔ 허현 서 접경/ }));
    const active = { work, label, percent: 0, remainingPhases: 2, remainingCost: zero,
        stopReasonText: null, startsAtNextBoundary: true };
    vi.mocked(api.campaignDomestic).mockImplementation(async () => {
        vi.mocked(api.campaignWorks).mockResolvedValue(works({ active }));
        return { status: 'AVAILABLE', requestId: 'work-129' } as never;
    });
    fireEvent.click(within(sheet).getByRole('button', { name: '이 공사로' }));
    await waitFor(() => expect(api.campaignDomestic).toHaveBeenCalledWith(7, 'work', {
        countyId: 129, work, edgeId: 'e-yang-xu', ...(work === 'FORTIFICATION' ? { row: 5, col: 6 } : {}),
    }));
    await waitFor(() => expect(screen.queryByRole('region', { name: '양성현 새 공사' })).toBeNull());
    expect(await screen.findByRole('meter', { name: `양성현 ${label} 진척` })).toHaveAttribute('aria-valuenow', '0');
    expect(screen.getByText('공사를 접수했습니다 — 다음 순 경계부터 진척합니다.')).toHaveAttribute('role', 'status');
    vi.mocked(api.campaignWorks).mockResolvedValue(works({ active: { ...active, percent: 50, remainingPhases: 1,
        startsAtNextBoundary: false } }));
    clock.phase = 2;
    // A real session updates its clock after the engine turn; HTTP/turn data here is deliberately mocked.
    rerender(<TerritoryScreen hrefs={hrefs} initialView="work" />);
    await waitFor(() => expect(screen.getByRole('meter', { name: `양성현 ${label} 진척` })).toHaveAttribute('aria-valuenow', '50'));
    vi.mocked(api.campaignWorks).mockResolvedValue(works({ completed: [{ work, label, edgeId: 'e-yang-xu',
        completedAt: { year: 200, month: 1, phase: 3 } }] }));
    clock.phase = 3;
    rerender(<TerritoryScreen hrefs={hrefs} initialView="work" />);
    expect(await screen.findByText(`${label} · 200년 1월 하순`)).toBeInTheDocument();
    expect(screen.queryByRole('meter')).toBeNull();
    expect(api.campaignDomestic).toHaveBeenCalledTimes(1);
});
