import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import NationFinancePage from '@/app/game/nation-finance/page';
import { __resetTurnListeners, deliverTurnCompleted } from '@/lib/turnEvents';

const mocks = vi.hoisted(() => ({
    command: vi.fn(),
    frontInfo: vi.fn(),
    nationFinance: vi.fn(),
    submitCommandAndAwaitResult: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
    api: {
        command: mocks.command,
        frontInfo: mocks.frontInfo,
        nationFinance: mocks.nationFinance,
    },
}));

vi.mock('@/lib/commandSubmit', () => ({
    submitCommandAndAwaitResult: mocks.submitCommandAndAwaitResult,
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/components/GameTable', () => ({
    default: () => <table />,
}));

vi.mock('@/components/StatusBadge', () => ({
    default: ({ children }: { children: ReactNode }) => <span>{children}</span>,
}));

// OPENSAM-196 — 페이지는 더 이상 자기 EventSource를 열지 않는다. Shell의 연결 하나가 받은
// 턴 신호를 [lib/turnEvents]가 화면에 나눠 주므로, 테스트도 그 신호를 직접 흘린다.
const emitTurnCompleted = deliverTurnCompleted;

const financeResponse = {
    result: true,
    editable: true,
    nationId: 1,
    officerLevel: 5,
    year: 200,
    month: 1,
    nationMsg: '<p>한😀</p>',
    scoutMsg: '<p>천하</p>',
    gold: 100,
    rice: 100,
    income: {
        gold: { city: 1, war: 1 },
        rice: { city: 1, wall: 1 },
    },
    outcome: 1,
    policy: {
        rate: 15,
        bill: 100,
        secretLimit: 3,
        blockScout: false,
        blockWar: false,
    },
    warSettingCnt: {
        remain: 1,
        inc: 2,
        max: 10,
    },
};

describe('NationFinancePage rich-text messages', () => {
    beforeEach(() => {
        __resetTurnListeners();
        mocks.command.mockReset().mockResolvedValue({ status: 'AVAILABLE', requestId: 'editor-1' });
        mocks.frontInfo.mockReset().mockResolvedValue({
            general: {
                generalId: 10,
                nationId: 1,
                permission: 4,
            },
        });
        mocks.nationFinance.mockReset().mockResolvedValue(financeResponse);
        mocks.submitCommandAndAwaitResult.mockReset();
    });

    it('uses the PHP notice code-point limit while editing and returns to the safe renderer after an applied result', async () => {
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return { status: 'applied', result: {} };
        });
        render(<NationFinancePage />);

        await screen.findByRole('button', { name: '국가방침 수정' });
        expect(screen.queryByRole('textbox', { name: '국가 방침' })).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: '국가방침 수정' }));

        expect(await screen.findByRole('textbox', { name: '국가 방침' })).toHaveAttribute('contenteditable', 'true');
        expect(screen.getByText('9 / 16384')).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: '저장' }));

        await waitFor(() => {
            expect(mocks.command).toHaveBeenCalledWith('setNotice', { msg: '<p>한😀</p>' }, 10);
        });

        await waitFor(() => {
            expect(screen.queryByRole('textbox', { name: '국가 방침' })).not.toBeInTheDocument();
        });
    });

    it('uses the PHP scout-message code-point limit while editing', async () => {
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '임관 권유문 수정' }));

        expect(await screen.findByRole('textbox', { name: '임관 권유문' })).toHaveAttribute('contenteditable', 'true');
        expect(screen.getByText('9 / 1000')).toBeInTheDocument();
    });

    it('allows the exact scout HTML limit and disables save at max plus one using Unicode code points', async () => {
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '임관 권유문 수정' }));
        const editor = await screen.findByRole('textbox', { name: '임관 권유문' });
        const save = screen.getByRole('button', { name: '저장' });

        editor.innerHTML = `<p>${'😀'.repeat(993)}</p>`;
        fireEvent.input(editor);
        await waitFor(() => expect(screen.getByText('1000 / 1000')).toBeInTheDocument());
        expect(save).toBeEnabled();

        editor.innerHTML = `<p>${'😀'.repeat(994)}</p>`;
        fireEvent.input(editor);
        await waitFor(() => expect(screen.getByText('1001 / 1000')).toBeInTheDocument());
        expect(save).toBeDisabled();
        expect(mocks.command).not.toHaveBeenCalled();
    });

    it('allows the exact notice HTML limit and disables save at max plus one', async () => {
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '국가방침 수정' }));
        const editor = screen.getByRole('textbox', { name: '국가 방침' });
        const save = screen.getByRole('button', { name: '저장' });

        editor.innerHTML = `<p>${'가'.repeat(16377)}</p>`;
        fireEvent.input(editor);
        await waitFor(() => expect(screen.getByText('16384 / 16384')).toBeInTheDocument());
        expect(save).toBeEnabled();

        editor.innerHTML = `<p>${'가'.repeat(16378)}</p>`;
        fireEvent.input(editor);
        await waitFor(() => expect(screen.getByText('16385 / 16384')).toBeInTheDocument());
        expect(save).toBeDisabled();
    });

    it('keeps the draft editor open when the command result is only reserved', async () => {
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return { status: 'reserved', reason: '명령이 예약되었습니다.' };
        });
        render(<NationFinancePage />);

        await screen.findByText('천하');
        fireEvent.click(screen.getByRole('button', { name: '임관 권유문 수정' }));
        fireEvent.click(screen.getByRole('button', { name: '저장' }));

        await waitFor(() => {
            expect(mocks.command).toHaveBeenCalledWith('setScoutMsg', { msg: '<p>천하</p>' }, 10);
        });
        await waitFor(() => {
            expect(screen.getByRole('textbox', { name: '임관 권유문' })).toHaveAttribute('contenteditable', 'true');
        });
    });

    it.each([
        { status: 'pending' as const, reason: '처리 지연' },
        { status: 'rejected' as const, reason: '명령을 실행할 수 없습니다.' },
    ])('keeps the editor open without refreshing when the result is $status', async (outcome) => {
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return outcome;
        });
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '임관 권유문 수정' }));
        fireEvent.click(screen.getByRole('button', { name: '저장' }));

        expect(await screen.findByRole('status')).toHaveTextContent(outcome.reason);
        expect(screen.getByRole('textbox', { name: '임관 권유문' })).toBeInTheDocument();
        expect(mocks.nationFinance).toHaveBeenCalledTimes(1);
        fireEvent.click(screen.getByRole('button', { name: '취소' }));
        fireEvent.click(await screen.findByRole('button', { name: '임관 권유문 수정' }));
        expect(await screen.findByRole('textbox', { name: '임관 권유문' })).toHaveTextContent('천하');
    });

    it('restores the fetched scout-message draft when the user cancels', async () => {
        render(<NationFinancePage />);

        await screen.findByText('천하');
        fireEvent.click(screen.getByRole('button', { name: '임관 권유문 수정' }));
        const editor = await screen.findByRole('textbox', { name: '임관 권유문' });
        editor.innerHTML = '<p>새문안</p>';
        fireEvent.input(editor);

        await waitFor(() => expect(screen.getByText('10 / 1000')).toBeInTheDocument());
        fireEvent.click(screen.getByRole('button', { name: '취소' }));

        expect(await screen.findByText('천하')).toBeInTheDocument();
        expect(screen.queryByText('새문안')).not.toBeInTheDocument();
    });

    it('keeps a second editor disabled while its own save is still pending', async () => {
        let resolveNotice: (outcome: { status: 'applied'; result: Record<string, never> }) => void = () => undefined;
        let resolveScout: (outcome: { status: 'applied'; result: Record<string, never> }) => void = () => undefined;
        mocks.submitCommandAndAwaitResult
            .mockImplementationOnce(() => new Promise(resolve => { resolveNotice = resolve; }))
            .mockImplementationOnce(() => new Promise(resolve => { resolveScout = resolve; }));
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '국가방침 수정' }));
        fireEvent.click(screen.getByRole('button', { name: '임관 권유문 수정' }));
        const saveButtons = screen.getAllByRole('button', { name: '저장' });
        fireEvent.click(saveButtons[0]);
        await waitFor(() => expect(saveButtons[0]).toBeDisabled());
        expect(saveButtons[1]).toBeEnabled();
        fireEvent.click(saveButtons[1]);

        await waitFor(() => {
            expect(saveButtons[0]).toBeDisabled();
            expect(saveButtons[1]).toBeDisabled();
        });
        await act(async () => {
            resolveNotice({ status: 'applied', result: {} });
        });

        await waitFor(() => {
            expect(screen.getByRole('textbox', { name: '임관 권유문' })).toHaveAttribute('contenteditable', 'false');
        });
        await act(async () => {
            resolveScout({ status: 'applied', result: {} });
        });
        await screen.findByRole('button', { name: '임관 권유문 수정' });
    });

    it('preserves a modified sibling draft when the other editor applies and refreshes', async () => {
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return { status: 'applied', result: {} };
        });
        mocks.nationFinance
            .mockResolvedValueOnce(financeResponse)
            .mockResolvedValue({ ...financeResponse, nationMsg: '<p>서버 방침</p>', scoutMsg: '<p>서버 구문안</p>' });
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '국가방침 수정' }));
        fireEvent.click(screen.getByRole('button', { name: '임관 권유문 수정' }));
        const scoutEditor = screen.getByRole('textbox', { name: '임관 권유문' });
        scoutEditor.innerHTML = '<p>저장하지 않은 새문안</p>';
        fireEvent.input(scoutEditor);

        fireEvent.click(screen.getAllByRole('button', { name: '저장' })[0]);
        await waitFor(() => expect(mocks.nationFinance).toHaveBeenCalledTimes(2));
        const refreshedScoutEditor = await screen.findByRole('textbox', { name: '임관 권유문' });
        await waitFor(() => expect(refreshedScoutEditor).toHaveTextContent('저장하지 않은 새문안'));
        expect(refreshedScoutEditor).not.toHaveTextContent('서버 구문안');
    });

    it('preserves a pending scout draft across a turn refresh and reserved result', async () => {
        let resolveScout: (outcome: { status: 'reserved'; reason: string }) => void = () => undefined;
        mocks.submitCommandAndAwaitResult.mockImplementation(
            () => new Promise(resolve => { resolveScout = resolve; }),
        );
        mocks.nationFinance
            .mockResolvedValueOnce(financeResponse)
            .mockResolvedValue({ ...financeResponse, scoutMsg: '<p>새 서버 구문안</p>' });
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '임관 권유문 수정' }));
        const editor = screen.getByRole('textbox', { name: '임관 권유문' });
        editor.innerHTML = '<p>예약 대기 문안</p>';
        fireEvent.input(editor);
        fireEvent.click(screen.getByRole('button', { name: '저장' }));

        await act(async () => emitTurnCompleted());
        await waitFor(() => expect(mocks.nationFinance).toHaveBeenCalledTimes(2));
        await act(async () => resolveScout({ status: 'reserved', reason: '명령이 예약되었습니다.' }));

        const refreshedEditor = await screen.findByRole('textbox', { name: '임관 권유문' });
        await waitFor(() => expect(refreshedEditor).toHaveTextContent('예약 대기 문안'));
        expect(refreshedEditor).not.toHaveTextContent('새 서버 구문안');
    });

    it('keeps an active editor mounted while a background turn refresh is pending', async () => {
        let resolveRefresh: (value: typeof financeResponse) => void = () => undefined;
        mocks.nationFinance
            .mockResolvedValueOnce(financeResponse)
            .mockImplementationOnce(() => new Promise(resolve => { resolveRefresh = resolve; }));
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '임관 권유문 수정' }));
        const editor = screen.getByRole('textbox', { name: '임관 권유문' });

        act(() => { emitTurnCompleted(); });
        await waitFor(() => expect(mocks.nationFinance).toHaveBeenCalledTimes(2));
        expect(screen.getByRole('textbox', { name: '임관 권유문' })).toBe(editor);

        await act(async () => resolveRefresh(financeResponse));
    });

    it('keeps the active editor mounted when a background turn refresh fails', async () => {
        mocks.nationFinance
            .mockResolvedValueOnce(financeResponse)
            .mockRejectedValueOnce(new Error('offline'));
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '임관 권유문 수정' }));
        const editor = screen.getByRole('textbox', { name: '임관 권유문' });

        await act(async () => emitTurnCompleted());

        expect(screen.getByRole('textbox', { name: '임관 권유문' })).toBe(editor);
        expect(screen.queryByText('내무부 정보를 불러올 수 없습니다.')).not.toBeInTheDocument();
    });

    it('does not let a turn refresh supersede the pending initial load', async () => {
        let resolveInitial: (value: typeof financeResponse) => void = () => undefined;
        mocks.nationFinance
            .mockImplementationOnce(() => new Promise(resolve => { resolveInitial = resolve; }))
            .mockRejectedValueOnce(new Error('offline'));
        render(<NationFinancePage />);

        await waitFor(() => expect(mocks.nationFinance).toHaveBeenCalledTimes(1));
        act(() => { emitTurnCompleted(); });
        expect(mocks.nationFinance).toHaveBeenCalledTimes(1);

        await act(async () => resolveInitial(financeResponse));

        expect(await screen.findByRole('button', { name: '임관 권유문 수정' })).toBeInTheDocument();
        expect(screen.queryByText('내무부 정보를 불러올 수 없습니다.')).not.toBeInTheDocument();
    });

    it('does not let a turn refresh supersede a pending foreground retry', async () => {
        let resolveRetry: (value: typeof financeResponse) => void = () => undefined;
        mocks.nationFinance
            .mockRejectedValueOnce(new Error('initial offline'))
            .mockImplementationOnce(() => new Promise(resolve => { resolveRetry = resolve; }))
            .mockRejectedValueOnce(new Error('event offline'));
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }));
        await waitFor(() => expect(mocks.nationFinance).toHaveBeenCalledTimes(2));
        act(() => { emitTurnCompleted(); });
        expect(mocks.nationFinance).toHaveBeenCalledTimes(2);

        await act(async () => resolveRetry(financeResponse));

        expect(await screen.findByRole('button', { name: '임관 권유문 수정' })).toBeInTheDocument();
        expect(screen.queryByText('내무부 정보를 불러올 수 없습니다.')).not.toBeInTheDocument();
    });

    it('closes active editors when refreshed permissions become read-only', async () => {
        mocks.nationFinance
            .mockResolvedValueOnce(financeResponse)
            .mockResolvedValueOnce({ ...financeResponse, editable: false });
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '국가방침 수정' }));
        fireEvent.click(screen.getByRole('button', { name: '임관 권유문 수정' }));
        expect(screen.getAllByRole('textbox')).toHaveLength(2);

        await act(async () => emitTurnCompleted());

        await waitFor(() => {
            expect(screen.queryByText('로딩 중...')).not.toBeInTheDocument();
            expect(screen.queryByRole('button', { name: '국가방침 수정' })).not.toBeInTheDocument();
            expect(screen.queryByRole('button', { name: '임관 권유문 수정' })).not.toBeInTheDocument();
        });
        expect(screen.queryAllByRole('textbox')).toHaveLength(0);
    });

    it('discards both drafts when a refresh moves the general to another editable nation', async () => {
        mocks.frontInfo
            .mockResolvedValueOnce({ general: { generalId: 10, nationId: 1, permission: 4 } })
            .mockResolvedValueOnce({ general: { generalId: 10, nationId: 2, permission: 4 } });
        mocks.nationFinance
            .mockResolvedValueOnce(financeResponse)
            .mockResolvedValueOnce({
                ...financeResponse,
                nationId: 2,
                nationMsg: '<p>새 국가 방침</p>',
                scoutMsg: '<p>새 국가 권유문</p>',
            });
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '국가방침 수정' }));
        fireEvent.click(screen.getByRole('button', { name: '임관 권유문 수정' }));
        const editors = screen.getAllByRole('textbox');
        editors[0].innerHTML = '<p>이전 국가 방침 초안</p>';
        fireEvent.input(editors[0]);
        editors[1].innerHTML = '<p>이전 국가 권유문 초안</p>';
        fireEvent.input(editors[1]);

        await act(async () => emitTurnCompleted());

        await waitFor(() => expect(screen.queryAllByRole('textbox')).toHaveLength(0));
        fireEvent.click(screen.getByRole('button', { name: '국가방침 수정' }));
        expect(screen.getByRole('textbox', { name: '국가 방침' })).toHaveTextContent('새 국가 방침');
        expect(screen.getByRole('textbox', { name: '국가 방침' })).not.toHaveTextContent('이전 국가 방침 초안');
    });

    it('keeps stale nation controls blocked when the new nation refresh fails', async () => {
        mocks.frontInfo
            .mockResolvedValueOnce({ general: { generalId: 10, nationId: 1, permission: 4 } })
            .mockResolvedValueOnce({ general: { generalId: 10, nationId: 2, permission: 4 } })
            .mockResolvedValueOnce({ general: { generalId: 10, nationId: 2, permission: 4 } });
        mocks.nationFinance
            .mockResolvedValueOnce(financeResponse)
            .mockRejectedValueOnce(new Error('new nation offline'))
            .mockResolvedValueOnce({
                ...financeResponse,
                nationId: 2,
                nationMsg: '<p>새 국가 방침</p>',
                scoutMsg: '<p>새 국가 권유문</p>',
            });
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '국가방침 수정' }));
        fireEvent.click(screen.getByRole('button', { name: '임관 권유문 수정' }));
        const editors = screen.getAllByRole('textbox');
        editors[0].innerHTML = '<p>이전 국가 방침 초안</p>';
        fireEvent.input(editors[0]);
        editors[1].innerHTML = '<p>이전 국가 권유문 초안</p>';
        fireEvent.input(editors[1]);

        await act(async () => emitTurnCompleted());

        await waitFor(() => expect(screen.queryAllByRole('textbox')).toHaveLength(0));
        expect(screen.queryByRole('button', { name: '국가방침 수정' })).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '임관 권유문 수정' })).not.toBeInTheDocument();
        expect(screen.queryByText('한😀')).not.toBeInTheDocument();
        expect(screen.queryByText('천하')).not.toBeInTheDocument();

        await act(async () => emitTurnCompleted());

        fireEvent.click(await screen.findByRole('button', { name: '국가방침 수정' }));
        expect(screen.getByRole('textbox', { name: '국가 방침' })).toHaveTextContent('새 국가 방침');
        expect(screen.getByRole('textbox', { name: '국가 방침' })).not.toHaveTextContent('이전 국가 방침 초안');
    });

    it('keeps an older successful background refresh when a queued newer refresh fails', async () => {
        let resolveOlderRefresh: (value: typeof financeResponse) => void = () => undefined;
        mocks.nationFinance
            .mockResolvedValueOnce(financeResponse)
            .mockImplementationOnce(() => new Promise(resolve => { resolveOlderRefresh = resolve; }))
            .mockRejectedValueOnce(new Error('newer offline'));
        render(<NationFinancePage />);

        await screen.findByRole('button', { name: '국가방침 수정' });
        act(() => { emitTurnCompleted(); });
        await waitFor(() => expect(mocks.nationFinance).toHaveBeenCalledTimes(2));
        act(() => { emitTurnCompleted(); });
        expect(mocks.nationFinance).toHaveBeenCalledTimes(2);

        await act(async () => resolveOlderRefresh({
            ...financeResponse,
            nationMsg: '<p>성공한 갱신</p>',
        }));

        await waitFor(() => expect(mocks.nationFinance).toHaveBeenCalledTimes(3));
        expect(await screen.findByText('성공한 갱신')).toBeInTheDocument();
        expect(screen.queryByText('내무부 정보를 불러올 수 없습니다.')).not.toBeInTheDocument();
    });

    it('applies the latest queued message refresh after serializing overlap', async () => {
        let resolveOlderRefresh: (value: typeof financeResponse) => void = () => undefined;
        let resolveLatestRefresh: (value: typeof financeResponse) => void = () => undefined;
        let resolveScoutSave: (outcome: { status: 'applied'; result: Record<string, never> }) => void = () => undefined;
        mocks.submitCommandAndAwaitResult
            .mockImplementationOnce(async (submit: () => Promise<unknown>) => {
                await submit();
                return { status: 'applied', result: {} };
            })
            .mockImplementationOnce(async (submit: () => Promise<unknown>) => {
                await submit();
                return new Promise(resolve => { resolveScoutSave = resolve; });
            });
        mocks.nationFinance
            .mockResolvedValueOnce(financeResponse)
            .mockImplementationOnce(() => new Promise(resolve => { resolveOlderRefresh = resolve; }))
            .mockImplementationOnce(() => new Promise(resolve => { resolveLatestRefresh = resolve; }));
        render(<NationFinancePage />);

        fireEvent.click(await screen.findByRole('button', { name: '국가방침 수정' }));
        fireEvent.click(screen.getByRole('button', { name: '임관 권유문 수정' }));
        const saveButtons = screen.getAllByRole('button', { name: '저장' });
        fireEvent.click(saveButtons[0]);
        fireEvent.click(saveButtons[1]);

        await waitFor(() => expect(mocks.nationFinance).toHaveBeenCalledTimes(2));
        await act(async () => {
            resolveScoutSave({ status: 'applied', result: {} });
        });
        expect(mocks.nationFinance).toHaveBeenCalledTimes(2);
        await act(async () => {
            resolveOlderRefresh({
                ...financeResponse,
                nationMsg: '<p>이전 국가 방침</p>',
                scoutMsg: '<p>이전 임관 권유문</p>',
            });
        });
        await waitFor(() => expect(mocks.nationFinance).toHaveBeenCalledTimes(3));
        await act(async () => {
            resolveLatestRefresh({
                ...financeResponse,
                nationMsg: '<p>최신 국가 방침</p>',
                scoutMsg: '<p>최신 임관 권유문</p>',
            });
        });
        expect(await screen.findByText('최신 국가 방침')).toBeInTheDocument();
        expect(screen.getByText('최신 임관 권유문')).toBeInTheDocument();
        expect(screen.queryByText('이전 국가 방침')).not.toBeInTheDocument();
        expect(screen.queryByText('이전 임관 권유문')).not.toBeInTheDocument();
    });
});
