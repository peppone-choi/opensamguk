// 받은 요청 — 모델(서버 값만) · 응답 뒤 같은 읽기를 쓰는 곳이 모두 다시 읽는지 · 요청 카드 모양(보드 request_card).
import { configure, act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { IncomingRequests } from '../components/requests/IncomingRequests';
import { RequestCard } from '../components/requests/RequestCard';
import { api } from '../lib/api';
import { submitCommandAndAwaitResult } from '../lib/commandSubmit';
import { fromConsents, fromDispatches, phaseLabel, requestKey } from '../lib/requests';

// jsdom에서 부품 · 목록을 그리고 가짜 서버 응답을 기다린다 — CI · 로컬 병렬 부하에서 기본 1초 대기 창 · 5초 한도가 모자란다
// (부하 평균 557에서 「찾을 수 없음」으로 재현, 응답을 1.2초 늦추면 같은 실패가 나고 창을 5초로 늘리면 통과 — 2026-10-01).
configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
vi.mock('../lib/api', () => ({
    api: { dispatchPending: vi.fn(), politicalConsentOptions: vi.fn(), courtDispatchReply: vi.fn(), courtPoliticalConsent: vi.fn() },
}));
vi.mock('../lib/commandSubmit', () => ({ submitCommandAndAwaitResult: vi.fn() }));

const phase = (year: number, month: number, p: number) => ({ year, month, phase: p });
const dispatch = (over: Partial<Parameters<typeof fromDispatches>[0]['dispatches'][number]> = {}) => ({
    dispatchId: 'D-1', issuerId: 7, targetId: 1, countyId: 30, issuerLabel: '[주공]', targetLabel: '[나]', countyLabel: '허현',
    issuedAt: phase(200, 3, 1), dueAt: phase(200, 3, 3), status: 'PENDING' as const, currentFailure: null, ...over,
});

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.dispatchPending).mockResolvedValue({ result: true, dispatches: [dispatch()] });
    vi.mocked(api.politicalConsentOptions).mockResolvedValue([
        { inputId: 'action.oath', issuerGeneralId: 9, issuerName: '[의형]', available: true, accepted: null },
    ]);
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'reserved', reason: '' } as never; });
});

describe('요청 모델', () => {
    it('나에게 온 발령만 — 기한은 서버 dueAt, 거절 결과는 발령 규칙', () => {
        const rows = fromDispatches({ result: true, dispatches: [dispatch(), dispatch({ dispatchId: 'D-2', targetId: 5 })] }, 1);
        expect(rows).toHaveLength(1);
        expect(rows[0]).toMatchObject({
            key: 'dispatch:D-1', label: '발령', from: { generalId: 7, name: '[주공]' }, what: '허현(으)로 가라는 발령입니다',
            due: '200년 3월 하순까지 · 넘기면 수락', consequence: '충성과 명망이 줄어듭니다', state: 'waiting',
            availability: { inputId: 'court.dispatchReply', status: 'AVAILABLE' },
        });
        expect(phaseLabel(phase(201, 1, 9))).toBe('201년 1월');
    });

    it('응답 조건이 깨졌으면 서버 코드로 막고, 끝난 요청은 단추가 없다', () => {
        const [blocked] = fromDispatches({ result: true, dispatches: [dispatch({ currentFailure: 'NOT_DIRECT_RETAINER' })] }, 1);
        expect(blocked.availability).toMatchObject({ status: 'BLOCKED', code: 'NOT_DIRECT_RETAINER' });
        const [done] = fromDispatches({ result: true, dispatches: [dispatch({ status: 'REFUSED' })] }, 1);
        expect(done).toMatchObject({ state: 'refused', availability: null });
    });

    it('정치 동의 — 서버 가능 여부 · 사유 그대로, 거절 결과는 지어내지 않는다', () => {
        const [c] = fromConsents([{ inputId: 'action.abdicate', issuerGeneralId: 3, issuerName: '[군주]', available: false, code: 'NOT_HEIR', reason: '물려받을 수 없습니다', accepted: null }]);
        expect(c).toMatchObject({ label: '선양 동의', due: null, consequence: null, availability: { status: 'BLOCKED', code: 'NOT_HEIR', reason: '물려받을 수 없습니다' } });
        expect(fromConsents([{ inputId: 'action.oath', issuerGeneralId: 3, issuerName: 'x', available: true, accepted: true }])[0].state).toBe('accepted');
    });
});

describe('요청 카드(보드 request_card)', () => {
    const base = { kind: '발령', from: { name: '[주공]' }, what: '허현으로', due: '200년 3월 하순까지', consequence: '충성과 명망이 줄어듭니다' };

    it('거절 결과 줄은 좁은 폭에서 뺀다', () => {
        const { rerender } = render(<RequestCard {...base} />);
        expect(screen.getByText('거절하면 — 충성과 명망이 줄어듭니다')).toBeInTheDocument();
        rerender(<RequestCard {...base} compact />);
        expect(screen.queryByText(/거절하면/)).toBeNull();
    });

    it('응답 뒤에는 단추 대신 「수락함 · 거절함」', () => {
        const { rerender } = render(<RequestCard {...base} state="accepted" answer={{ inputId: 'court.dispatchReply', availability: { inputId: 'court.dispatchReply', status: 'AVAILABLE' }, onAccept: vi.fn(), onRefuse: vi.fn() }} />);
        expect(screen.getByText('수락함')).toBeInTheDocument();
        expect(screen.queryByRole('button')).toBeNull();
        rerender(<RequestCard {...base} state="refused" />);
        expect(screen.getByText('거절함')).toBeInTheDocument();
    });

    it('원장에 없는 응답(행 없음)은 단추를 그리지 않고, 발(foot)을 바꿀 수 있다', () => {
        const { container, rerender } = render(<RequestCard {...base} answer={{ inputId: 'k8.officeOffer', availability: null, onAccept: vi.fn(), onRefuse: vi.fn() }} />);
        expect(container.querySelectorAll('button')).toHaveLength(0);
        rerender(<RequestCard {...base} foot={<p>응답 입력은 서버 준비 중입니다</p>} />);
        expect(screen.getByText('응답 입력은 서버 준비 중입니다')).toBeInTheDocument();
    });
});

describe('받은 요청 목록', () => {
    it('그 자리에서 수락하면 같은 읽기를 쓰는 다른 곳도 다시 읽는다', async () => {
        render(<><div data-testid="a"><IncomingRequests generalId={1} /></div><div data-testid="b"><IncomingRequests generalId={1} compact waitingOnly /></div></>);
        const a = within(await screen.findByTestId('a'));
        await a.findByText('허현(으)로 가라는 발령입니다');
        expect(api.dispatchPending).toHaveBeenCalledTimes(2);
        vi.mocked(api.dispatchPending).mockResolvedValue({ result: true, dispatches: [dispatch({ status: 'ACCEPTED' })] });
        const card = a.getByRole('article', { name: '발령 — [주공]' });
        await act(async () => { fireEvent.click(within(card).getByRole('button', { name: '수락' })); });
        await waitFor(() => expect(api.courtDispatchReply).toHaveBeenCalledWith(1, { dispatchId: 'D-1', accept: true }));
        await waitFor(() => expect(api.dispatchPending).toHaveBeenCalledTimes(4));
        expect(await a.findByText('수락함')).toBeInTheDocument();
        // 응답 대기만 보이는 곳에서는 사라진다.
        await waitFor(() => expect(within(screen.getByTestId('b')).queryByText('허현(으)로 가라는 발령입니다')).toBeNull());
    });

    it('서버가 응답을 거절하면 누른 쪽 사유 시트가 열린 채로 뜬다', async () => {
        vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'rejected', reason: '기한이 지났습니다', code: 'DISPATCH_EXPIRED' } as never; });
        const { container } = render(<IncomingRequests generalId={1} />);
        await screen.findByText('허현(으)로 가라는 발령입니다');
        const card = screen.getByRole('article', { name: '발령 — [주공]' });
        await act(async () => { fireEvent.click(within(card).getByRole('button', { name: '거절' })); });
        await waitFor(() => expect(container.querySelector('[data-reason-code="DISPATCH_EXPIRED"]')).not.toBeNull());
        expect(within(card).getAllByRole('dialog').some((d) => d.textContent?.includes('기한이 지났습니다'))).toBe(true);
    });

    it('기록 줄 밑에는 그 요청 한 장만 — 없으면 끝난 요청이라고 알린다', async () => {
        const { rerender } = render(<IncomingRequests generalId={1} onlyKey={requestKey({ kind: 'dispatch', dispatchId: 'D-1' })} compact />);
        expect(await screen.findByRole('article', { name: '발령 — [주공]' })).toBeInTheDocument();
        expect(screen.queryByRole('article', { name: /결의 동의/ })).toBeNull();
        rerender(<IncomingRequests generalId={1} onlyKey={requestKey({ kind: 'dispatch', dispatchId: 'D-9' })} compact />);
        expect(await screen.findByText('이 요청은 목록에 없습니다')).toBeInTheDocument();
    });

    it('두 읽기가 모두 실패하면 빈 목록과 다른 모양(다시 시도)', async () => {
        vi.mocked(api.dispatchPending).mockRejectedValue(new Error('x'));
        vi.mocked(api.politicalConsentOptions).mockRejectedValue(new Error('x'));
        render(<IncomingRequests generalId={1} />);
        expect(await screen.findByText('받은 요청을 불러오지 못했습니다')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /다시 시도/ })).toBeInTheDocument();
    });
});
