import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BoardPage from '@/app/game/board/page';
import type { BoardResponse } from '@/lib/types';

const nav = vi.hoisted(() => ({ query: 'secret=1' }));
const modalSpy = vi.hoisted(() => ({ latest: null as null | { pinnedCommand: string; extraArgs?: Record<string, unknown> } }));
const mocks = vi.hoisted(() => ({
    board: vi.fn(),
    frontInfo: vi.fn(),
    command: vi.fn(),
    votes: vi.fn(),
    submit: vi.fn(),
}));

vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams(nav.query) }));
vi.mock('@/components/Shell', () => ({ default: ({ children }: { children: ReactNode }) => <main>{children}</main> }));
vi.mock('@/components/RichTextEditor', () => ({
    RichTextEditor: ({ value, onChange, ariaLabel }: { value: string; onChange: (v: string) => void; ariaLabel: string }) => (
        <textarea aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} />
    ),
}));
vi.mock('@/components/CommandModal', () => ({
    default: (props: { pinnedCommand: string; extraArgs?: Record<string, unknown> }) => {
        modalSpy.latest = props;
        return <div data-testid="command-modal">{props.pinnedCommand}</div>;
    },
}));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: mocks.submit }));
vi.mock('@/lib/api', () => ({ api: { board: mocks.board, frontInfo: mocks.frontInfo, command: mocks.command, votes: mocks.votes } }));
vi.mock('@/hooks/useTurnRefresh', () => ({ useTurnRefresh: vi.fn() }));

const person = (generalId: number, name: string) => ({ generalId, name, picture: null, imageServer: 0, officerLevelText: '장군' });

const SECRET: BoardResponse = {
    result: true,
    secret: true,
    title: '기밀실',
    blockedReason: null,
    myGeneralId: 77,
    myPermission: 2,
    chiefCount: 3,
    participants: [
        { generalId: 77, name: '하후돈', picture: null, imageServer: 0, officerLevelText: '장군', active: true, chief: true },
        { generalId: 10, name: '순욱', picture: null, imageServer: 0, officerLevelText: '승상', active: false, chief: true },
        { generalId: 11, name: '악진', picture: null, imageServer: 0, officerLevelText: '교위', active: true, chief: false },
    ],
    articles: [
        {
            id: 5, nationId: 1, authorGeneralId: 10, authorName: '순욱', title: '원소 불가침, 회신 미루자', contentHtml: '<p>낙양 결과 본다</p>',
            date: '0200-03-11T10:00:00Z', comments: [], kind: 'notice', readers: { read: [person(10, '순욱')], total: 3 },
            authorPicture: null, authorImageServer: 0, authorOfficerLevelText: '승상',
        },
        {
            id: 6, nationId: 1, authorGeneralId: 77, authorName: '하후돈', title: '양양 확보를 병행할 것인가', contentHtml: '<p>수운 2순</p>',
            date: '0200-03-10T10:00:00Z', comments: [{ id: 1, authorGeneralId: 10, authorName: '순욱', text: '현지 징발로 충분', date: '0200-03-10T11:00:00Z' }],
            kind: 'vote', voteId: 3,
            vote: {
                voteId: 3, title: '양양 확보 병행', endDate: null, closed: false, myVote: null, voterCount: 2, eligibleCount: 3,
                options: [{ index: 0, text: '찬성', count: 2, voters: [person(10, '순욱'), person(11, '악진')] }, { index: 1, text: '반대', count: 0, voters: [] }],
            },
            readers: { read: [person(77, '하후돈')], total: 3 },
        },
    ],
};

describe('BoardPage (14 회의실·기밀실)', () => {
    beforeEach(() => {
        modalSpy.latest = null;
        mocks.board.mockReset();
        mocks.frontInfo.mockReset();
        mocks.command.mockReset();
        mocks.votes.mockReset();
        mocks.submit.mockReset();
        nav.query = 'secret=1';
        mocks.frontInfo.mockResolvedValue({ general: { generalId: 77, officerLevelText: '장군' }, nation: { name: '조조', color: '#3f6fb5' } });
        mocks.board.mockResolvedValue(SECRET);
        mocks.command.mockResolvedValue({ status: 'QUEUED' });
        mocks.submit.mockImplementation(async (submit: () => Promise<unknown>) => { await submit(); return { status: 'applied', result: {} }; });
    });

    it('filters by kind tabs, shows readers, and records a 기밀실 read only for unread articles', async () => {
        render(<BoardPage />);
        expect(await screen.findByRole('heading', { name: '기밀실' })).toBeInTheDocument();
        expect(screen.getByText('원소 불가침, 회신 미루자')).toBeInTheDocument();
        const kinds = screen.getByRole('tablist', { name: '글 종류' });
        expect(within(kinds).getByRole('tab', { name: /전체/ })).toHaveTextContent('2');
        expect(within(kinds).getByRole('tab', { name: /공지/ })).toHaveTextContent('1');
        // 열람 기록 — 읽은 사람 / 수뇌부 정원
        // 두 글 모두 열람 1/3(읽은 사람 1 / 수뇌부 정원 3)
        expect(screen.getAllByText('열람 1/3', { exact: false })).toHaveLength(2);
        // 내(77)가 아직 안 읽은 글(5)만 boardRead 를 한 번 인테이크, 이미 읽은 글(6)은 하지 않는다.
        await waitFor(() => expect(mocks.command).toHaveBeenCalledWith('boardRead', { articleNo: 5 }, 77));
        expect(mocks.command).toHaveBeenCalledTimes(1);
        // 적용되면 열람 수를 다시 읽는다(202 ≠ 성공 — 결과 뒤 재조회).
        await waitFor(() => expect(mocks.board).toHaveBeenCalledTimes(2));
        fireEvent.click(within(kinds).getByRole('tab', { name: /표결/ }));
        expect(screen.queryByText('원소 불가침, 회신 미루자')).not.toBeInTheDocument();
        expect(screen.getByText('양양 확보를 병행할 것인가')).toBeInTheDocument();
        // 우측 레일 — 참여 스택과 활동/침묵 집계, 기밀실 안내
        expect(screen.getByText('활동 2 · 침묵 1 · NPC 제외')).toBeInTheDocument();
        expect(screen.getByText(/URL 직접 입력으로 우회할 수 없습니다/)).toBeInTheDocument();
    });

    it('casts a vote from the 표결 card through the voteCast intake', async () => {
        render(<BoardPage />);
        const card = await screen.findByLabelText('표결 양양 확보 병행');
        expect(within(card).getByText('찬성 2 · 반대 0 · 미표 1')).toBeInTheDocument();
        fireEvent.click(within(card).getByRole('button', { name: '반대' }));
        await waitFor(() => expect(screen.getByTestId('command-modal')).toHaveTextContent('voteCast'));
        expect(modalSpy.latest?.extraArgs).toEqual({ voteId: 3, selection: [1] });
    });

    it('sends kind with the article and gates 공지 to 수뇌부', async () => {
        nav.query = '';
        mocks.board.mockResolvedValue({ ...SECRET, secret: false, title: '회의실', articles: [], myPermission: 1 });
        render(<BoardPage />);
        expect(await screen.findByRole('heading', { name: '회의실' })).toBeInTheDocument();
        const kind = screen.getByLabelText('종류') as HTMLSelectElement;
        expect((within(kind).getByRole('option', { name: /공지/ }) as HTMLOptionElement).disabled).toBe(true);
        fireEvent.change(kind, { target: { value: 'operation' } });
        fireEvent.change(screen.getByPlaceholderText('제목'), { target: { value: '낙양 공략' } });
        fireEvent.click(screen.getByRole('button', { name: '등록' }));
        await waitFor(() => expect(screen.getByTestId('command-modal')).toHaveTextContent('boardArticle'));
        expect(modalSpy.latest?.extraArgs).toEqual({ isSecret: false, title: '낙양 공략', text: '', kind: 'operation' });
        expect(mocks.command).not.toHaveBeenCalled();
    });
});
