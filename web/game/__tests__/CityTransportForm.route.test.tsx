import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CityTransportForm from '@/components/v2/CityTransportForm';

const mocks = vi.hoisted(() => ({ post: vi.fn(), submit: vi.fn() }));
vi.mock('@/lib/api', () => ({ api: { post: mocks.post } }));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: mocks.submit }));
vi.mock('@/components/v2/CityLedgerPanel', () => ({ default: () => null }));
vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: React.ReactNode }) => <section>{children}</section>,
}));

const path = {
    nodeKeys: ['land:45098', 'land:45022'], edgeIds: ['land-45022-45098'], modes: ['LAND'],
    totalCost: 1, capacity: 2147483647, topologyRevision: 'han-water-topology-v1',
    topologyHash: 'topology-hash', pathHash: 'server-path-hash',
};

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
        mocks.submit.mockImplementation(async (send: () => Promise<unknown>) => {
            await send();
            return { status: 'applied' };
        });
    });

    it('previews on the server and submits that exact revision and path hash', async () => {
        mocks.post.mockResolvedValueOnce({ status: 'AVAILABLE', route: path }).mockResolvedValueOnce({});
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        await waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(2));
        const amounts = { fromCityId: 273, toCityId: 781, gold: 100, rice: 0, garrison: 0 };
        expect(mocks.post).toHaveBeenNthCalledWith(1, '/api/v2/city-transport/route?generalId=7', amounts);
        expect(mocks.post).toHaveBeenNthCalledWith(2, '/api/v2/city-transport?generalId=7', {
            ...amounts, topologyRevision: path.topologyRevision, routePathHash: path.pathHash,
        });
        expect(await screen.findByRole('status')).toHaveTextContent('수송이 완료되었습니다.');
        expect(screen.getByText(/서버 경로: 육로/)).toHaveTextContent('비용 1');
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
        mocks.post.mockResolvedValueOnce({ status: 'AVAILABLE', route: null }).mockResolvedValueOnce({});
        render(<CityTransportForm />);
        fillForm();
        fireEvent.click(screen.getByRole('button', { name: '수송' }));
        await waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(2));
        expect(mocks.post.mock.calls[1][1]).not.toHaveProperty('routePathHash');
        expect(mocks.post.mock.calls[1][1]).not.toHaveProperty('topologyRevision');
    });

    it('fails closed on an incomplete non-legacy route response', async () => {
        mocks.post.mockResolvedValue({ status: 'AVAILABLE', route: { ...path, pathHash: '' } });
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
});
