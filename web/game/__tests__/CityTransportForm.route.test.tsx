import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
import type MapViewer from '@/components/game/MapViewer';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CityTransportForm from '@/components/v2/CityTransportForm';

const mocks = vi.hoisted(() => ({ post: vi.fn(), submit: vi.fn(), serverId: 'pep', mapProps: null as ComponentProps<typeof MapViewer> | null }));
vi.mock('@/lib/api', () => ({ api: { post: mocks.post } }));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: mocks.submit }));
vi.mock('@/components/v2/CityLedgerPanel', () => ({ default: () => null }));
vi.mock('@/lib/serverGameUrl', () => ({ readServerCookie: () => mocks.serverId }));
vi.mock('@/components/game/MapViewer', () => ({ default: (props: ComponentProps<typeof MapViewer>) => {
    mocks.mapProps = props;
    return <div data-testid="transport-map" />;
} }));
vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: React.ReactNode }) => <section>{children}</section>,
}));

const path = {
    nodeKeys: ['land:45098', 'land:45022'], edgeIds: ['land-45022-45098'], modes: ['LAND'],
    totalCost: 1, capacity: 2147483647, topologyRevision: 'han-water-topology-v1',
    topologyHash: 'a'.repeat(64), pathHash: 'b'.repeat(64),
};
const binding = { worldId: 8, mapCode: 'han-world-v3' as const,
    topologyRevision: path.topologyRevision, topologyHash: path.topologyHash,
    baseTilesSha256: 'c'.repeat(64), cols: 4, rows: 3 };

function fillForm() {
    fireEvent.change(screen.getByLabelText('장수 ID'), { target: { value: '7' } });
    fireEvent.change(screen.getByLabelText('출발 도시 ID'), { target: { value: '273' } });
    fireEvent.change(screen.getByLabelText('도착 도시 ID'), { target: { value: '781' } });
    fireEvent.change(screen.getByLabelText('금'), { target: { value: '100' } });
}

describe('CityTransportForm authoritative route', () => {
    beforeEach(() => {
        mocks.post.mockReset();
        mocks.submit.mockReset();
        mocks.serverId = 'pep';
        mocks.mapProps = null;
        mocks.submit.mockImplementation(async (send: () => Promise<unknown>) => {
            await send();
            return { status: 'applied' };
        });
    });

    it('previews on the server and submits that exact revision and path hash', async () => {
        mocks.post.mockResolvedValueOnce({ status: 'AVAILABLE', worldId: 8, route: path }).mockResolvedValueOnce({});
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        await waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(2));
        const amounts = { fromCityId: 273, toCityId: 781, gold: 100, rice: 0, garrison: 0 };
        expect(mocks.post).toHaveBeenNthCalledWith(1, '/api/v2/city-transport/route?generalId=7', amounts);
        expect(mocks.post).toHaveBeenNthCalledWith(2, '/api/v2/city-transport?generalId=7&expectedWorldId=8', {
            ...amounts, topologyRevision: path.topologyRevision, routePathHash: path.pathHash,
        });
        expect(await screen.findByRole('status')).toHaveTextContent('수송이 완료되었습니다.');
        expect(screen.getByText(/서버 경로: 육로/)).toHaveTextContent('비용 1');
        expect(screen.getByTestId('transport-map')).toBeInTheDocument();
        expect(mocks.mapProps?.selectedServerRoute).toEqual({ ...path, worldId: 8, serverId: 'pep' });
        fireEvent.change(screen.getByLabelText('금'), { target: { value: '200' } });
        expect(mocks.mapProps?.selectedServerRoute).toBeNull();
    });

    it('does not reserve a route rejected by the server', async () => {
        mocks.post.mockResolvedValue({ status: 'BLOCKED', code: 'RIVER_CROSSING_REQUIRED', reason: '강을 건널 통과점이 없습니다.' });
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        expect(await screen.findByRole('status')).toHaveTextContent('강을 건널 통과점이 없습니다.');
        expect(mocks.submit).not.toHaveBeenCalled();
        expect(mocks.post).toHaveBeenCalledTimes(1);
    });

    it('keeps explicitly legacy server routes compatible without inventing a hash', async () => {
        mocks.post.mockResolvedValueOnce({ status: 'AVAILABLE', worldId: 8, route: null }).mockResolvedValueOnce({});
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        await waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(2));
        expect(mocks.post.mock.calls[1][1]).not.toHaveProperty('routePathHash');
        expect(mocks.post.mock.calls[1][1]).not.toHaveProperty('topologyRevision');
    });

    it('fails closed on an incomplete non-legacy route response', async () => {
        mocks.post.mockResolvedValue({ status: 'AVAILABLE', worldId: 8, route: { ...path, pathHash: '' } });
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        expect(await screen.findByRole('status')).toHaveTextContent('경로 확인 응답이 올바르지 않습니다.');
        expect(mocks.submit).not.toHaveBeenCalled();
    });

    it('does not submit when preview fails or a city ID is empty', async () => {
        render(<CityTransportForm />);
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        expect(await screen.findByRole('status')).toHaveTextContent('올바르게 입력');
        expect(mocks.post).not.toHaveBeenCalled();
        fillForm();
        mocks.post.mockRejectedValue(new Error('경로 조회 실패'));
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        expect(await screen.findByRole('status')).toHaveTextContent('경로 조회 실패');
        expect(mocks.submit).not.toHaveBeenCalled();
    });

    it('rejects a V3 preview without authoritative world identity', async () => {
        mocks.post.mockResolvedValue({ status: 'AVAILABLE', route: path });
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        expect(await screen.findByRole('status')).toHaveTextContent('경로 확인 응답이 올바르지 않습니다.');
        expect(mocks.submit).not.toHaveBeenCalled();
        expect(mocks.mapProps?.selectedServerRoute).toBeNull();
    });

    it('never submits or retags a preview resolved after the selected server changes', async () => {
        let resolve!: (value: unknown) => void;
        mocks.post.mockReturnValue(new Promise(value => { resolve = value; }));
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        mocks.serverId = 'other';
        await act(async () => resolve({ status: 'AVAILABLE', worldId: 8, route: path }));
        expect(mocks.submit).not.toHaveBeenCalled();
        expect(mocks.mapProps?.selectedServerRoute).toBeNull();
    });

    it('does not submit a late preview after an input change or unmount', async () => {
        let resolve!: (value: unknown) => void;
        mocks.post.mockImplementation(() => new Promise(value => { resolve = value; }));
        const rendered = render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        fireEvent.change(screen.getByLabelText('금'), { target: { value: '200' } });
        await act(async () => resolve({ status: 'AVAILABLE', worldId: 8, route: path }));
        expect(mocks.submit).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        rendered.unmount();
        await act(async () => resolve({ status: 'AVAILABLE', worldId: 8, route: path }));
        expect(mocks.submit).not.toHaveBeenCalled();
    });

    it('rejects a preview which differs from the already verified map binding', async () => {
        mocks.post.mockResolvedValue({ status: 'AVAILABLE', worldId: 9, route: path });
        render(<CityTransportForm />);
        act(() => mocks.mapProps?.onStrategicBindingChange?.(binding));
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        expect(await screen.findByRole('status')).toHaveTextContent('세계 또는 지도가 변경');
        expect(mocks.submit).not.toHaveBeenCalled();
        expect(mocks.mapProps?.selectedServerRoute).toBeNull();
    });

    it('invalidates pending previews when a known map world changes', async () => {
        let resolve!: (value: unknown) => void;
        mocks.post.mockReturnValue(new Promise(value => { resolve = value; }));
        render(<CityTransportForm />);
        act(() => mocks.mapProps?.onStrategicBindingChange?.(binding));
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        act(() => mocks.mapProps?.onStrategicBindingChange?.({ ...binding, worldId: 9 }));
        await act(async () => resolve({ status: 'AVAILABLE', worldId: 8, route: path }));
        expect(mocks.submit).not.toHaveBeenCalled();
        expect(mocks.mapProps?.selectedServerRoute).toBeNull();
    });

    it('does not reuse results from a previously selected server after switching away and back', async () => {
        let resolve!: (value: unknown) => void;
        mocks.post.mockReturnValue(new Promise(value => { resolve = value; }));
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        mocks.serverId = 'other';
        fireEvent(window, new Event('focus'));
        mocks.serverId = 'pep';
        fireEvent(window, new Event('focus'));
        await act(async () => resolve({ status: 'AVAILABLE', worldId: 8, route: path }));
        expect(mocks.submit).not.toHaveBeenCalled();
        expect(mocks.mapProps?.selectedServerRoute).toBeNull();
    });

    it('drops a displayed route on server changes without attaching a new origin', async () => {
        mocks.post.mockResolvedValueOnce({ status: 'AVAILABLE', worldId: 8, route: path }).mockResolvedValueOnce({});
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        await screen.findByText(/수송이 완료/);
        mocks.serverId = 'other';
        fireEvent(window, new Event('focus'));
        expect(mocks.mapProps?.selectedServerRoute).toBeNull();
        expect(screen.queryByText(/서버 경로:/)).not.toBeInTheDocument();
    });

    it('drops a route when the map reports another world or topology, even on the same server', async () => {
        mocks.post.mockResolvedValueOnce({ status: 'AVAILABLE', worldId: 8, route: path }).mockResolvedValueOnce({});
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        await screen.findByText(/수송이 완료/);
        act(() => mocks.mapProps?.onStrategicBindingChange?.({ worldId: 9, mapCode: 'han-world-v3',
            topologyRevision: path.topologyRevision, topologyHash: path.topologyHash,
            baseTilesSha256: 'c'.repeat(64), cols: 4, rows: 3 }));
        expect(mocks.mapProps?.selectedServerRoute).toBeNull();
    });

    it('keeps queued acknowledgement separate from final execution', async () => {
        mocks.post.mockResolvedValueOnce({ status: 'AVAILABLE', worldId: 8, route: path });
        mocks.submit.mockResolvedValue({ status: 'pending' });
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        expect(await screen.findByRole('status')).toHaveTextContent('폴링 시간 초과');
        expect(screen.queryByText('수송이 완료되었습니다.')).not.toBeInTheDocument();
    });

    it('clears paths denied at intake or execution instead of displaying them as available', async () => {
        mocks.post.mockResolvedValue({ status: 'AVAILABLE', worldId: 8, route: path });
        mocks.submit.mockResolvedValue({ status: 'rejected', reason: '경로가 변경되었습니다.' });
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        expect(await screen.findByRole('status')).toHaveTextContent('경로가 변경되었습니다.');
        expect(mocks.mapProps?.selectedServerRoute).toBeNull();
    });
});
