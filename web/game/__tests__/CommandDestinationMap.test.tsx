import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CommandDestinationMap from '../components/command-flow/CommandDestinationMap';
import { destinationMapCandidates } from '../lib/command-flow/destination-map-view';
import { fromTravel } from '../lib/command-flow/options';
import type { MapPreviewResponse } from '../lib/types';

const state = vi.hoisted(() => ({ map: null as unknown, targets: [] as { targetId: string; name: string; cell?: unknown }[] }));
vi.mock('../lib/campaign-map', () => ({ useCampaignWorldMap: () => state.map }));
vi.mock('@opensamguk/ui/map/topdown', async importOriginal => {
    const actual = await importOriginal<typeof import('@opensamguk/ui/map/topdown')>();
    return { ...actual,
        loadBakePlaces: vi.fn(async () => ({ provinceCount: 3 })),
        loadBakeProvinceCenters: vi.fn(async () => [{ col: 10.8, row: 20.2 }, { col: 30, row: 40 }, { col: 50, row: 60 }]),
        TopdownMap: () => <div data-testid="destination-map" />,
        MapTargetLayer: ({ candidates, picker, onBlocked }: { candidates: typeof state.targets; picker: { pick: (id: string) => boolean }; onBlocked: (c: unknown) => void }) => {
            state.targets = candidates;
            return <div>{candidates.filter(c => c.cell).map(c => <button type="button" key={c.targetId} aria-label={`지도 ${c.name}`}
                onClick={() => { if (!picker.pick(c.targetId)) onBlocked(c); }}>{c.targetId}</button>)}</div>;
        },
    };
});
const field = fromTravel({ inputId: 'action.move', available: true, destinations: [
    { provinceId: 'P-1', name: '영천', available: true, reachability: 'THIS_TURN', arrivesThisTurn: true, distanceMm: 10_000_000, costMm: 20_000_000, estimatedTurns: 1 },
    { provinceId: 'P-2', name: '진류', available: true, reachability: 'MULTI_TURN', arrivesThisTurn: false, distanceMm: 80_000_000, costMm: 160_000_000, estimatedTurns: 3 },
    { provinceId: 'P-3', name: '양적', available: false, code: 'PASSAGE_DENIED', reason: '통행할 수 없습니다', reachability: 'UNAVAILABLE', arrivesThisTurn: false },
] }).fields[0];
const preview = { topdownBakeId: '0'.repeat(64), cities: [], nations: [], provinceOccupancy: [
    { provinceRecordId: 'P-1', provinceIndex: 0, nationId: 1 },
    { provinceRecordId: 'P-2', provinceIndex: 1, nationId: 1 },
    { provinceRecordId: 'P-3', provinceIndex: 2, nationId: 2 },
] } as unknown as MapPreviewResponse;
let viewport: ReturnType<typeof installViewport> | null = null;
afterEach(() => { viewport?.restore(); viewport = null; });
beforeEach(() => { state.map = { kind: 'preview', preview, legend: [] }; state.targets = []; });

describe('지도와 목록의 실제 목적지 범위', () => {
    it.each([390, 1440])('%spx: 정상/합법 다턴/불법 목적지가 동일하며 지도 선택도 같은 인자다', async width => {
        viewport = installViewport(width);
        const confirm = vi.fn();
        render(<CommandDestinationMap field={field} refreshKey={0} onConfirm={confirm} onClose={vi.fn()} />);
        const dialog = screen.getByRole('dialog', { name: '목적지 지도에서 고르기' });
        const list = within(dialog).getByRole('listbox', { name: '어디로' });
        expect(within(list).getByRole('option', { name: /영천/ })).not.toHaveAttribute('aria-disabled');
        expect(within(list).getByRole('option', { name: /진류/ })).not.toHaveAttribute('aria-disabled');
        expect(within(list).getByRole('option', { name: /양적/ })).toHaveAttribute('aria-disabled', 'true');
        await waitFor(() => expect(state.targets).toHaveLength(3));
        expect(state.targets[1]).toMatchObject({ targetId: 'P-2', name: '진류 · 다턴 이동 · 이번 턴 미도착', cell: { col: 30, row: 40 } });
        fireEvent.click(within(dialog).getByRole('button', { name: '지도 양적 · 주문 불가' }));
        expect(confirm).not.toHaveBeenCalled();
        expect(within(dialog).getByRole('status')).toHaveTextContent('통행할 수 없습니다');
        fireEvent.click(within(dialog).getByRole('button', { name: '지도 진류 · 다턴 이동 · 이번 턴 미도착' }));
        fireEvent.click(within(dialog).getByRole('button', { name: '이 목적지 선택' }));
        expect(confirm).toHaveBeenCalledExactlyOnceWith('P-2');
    });
    it('지도 조회 실패는 확인불가를 표시하며 서버 옵션 목록의 실제 합법 주문은 유지한다', async () => {
        state.map = { kind: 'error', message: '503' };
        const confirm = vi.fn();
        render(<CommandDestinationMap field={field} refreshKey={0} onConfirm={confirm} onClose={vi.fn()} />);
        expect(screen.getByRole('status')).toHaveTextContent('지도의 목적지 위치를 확인하지 못했습니다');
        expect(screen.queryByTestId('destination-map')).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('option', { name: /진류/ }));
        fireEvent.click(screen.getByRole('button', { name: '이 목적지 선택' }));
        expect(confirm).toHaveBeenCalledExactlyOnceWith('P-2');
    });
    it('실제 구역 binding 없는/충돌한 후보는 이름이나 현 번호로 좌표를 추정하지 않는다', () => {
        const centers = [{ col: 10.8, row: 20.2 }, { col: 30, row: 40 }];
        const mismatched = { ...preview, provinceOccupancy: [
            { provinceRecordId: 'P-1', provinceIndex: 0, nationId: 1 },
            { provinceRecordId: 'P-1', provinceIndex: 1, nationId: 1 },
            { provinceRecordId: '2', provinceIndex: 1, nationId: 1 },
        ] };
        expect(destinationMapCandidates(field, mismatched, centers).every(c => c.cell === undefined)).toBe(true);
        expect(destinationMapCandidates(field, preview, centers)[0].cell).toEqual({ col: 10, row: 20 });
        expect(destinationMapCandidates(field, preview, null).every(c => c.cell === undefined)).toBe(true);
    });
});
