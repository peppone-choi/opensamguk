// 회의실 · 기밀실(P-Q01) — 옛 게시판 API(`/api/board` + board 명령) 위의 보기 모델 한 겹. 옛 화면 시험(board-council ·
// board-rich-text · board-rich-text-lifecycle · board-auction-deep-links)이 지키던 것을 새 화면에서 다시 본다.
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { BoardResponse } from '@/types/game';
import { councilFromBoard } from '@/lib/council-board-adapter';
import { outcomeNotice } from '@/components/council/CouncilScreen';

const mocks = vi.hoisted(() => ({
    board: vi.fn(),
    command: vi.fn(),
    submit: vi.fn(),
    replace: vi.fn(),
    search: '',
    viewport: null as string | null,
}));
vi.mock('@/lib/api', () => ({ api: { board: mocks.board, command: mocks.command } }));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: (send: () => Promise<unknown>) => { void send(); return mocks.submit(); } }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, frontInfo: { nation: { id: 1, name: '조조', color: '#4f7fbf' } } }) }));
vi.mock('@/hooks/useTurnRefresh', () => ({ useTurnRefresh: () => undefined }));
vi.mock('next/navigation', () => ({
    useSearchParams: () => new URLSearchParams(mocks.search),
    useRouter: () => ({ replace: mocks.replace }),
    usePathname: () => '/game/pep/council',
}));
vi.mock('@opensamguk/ui', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@opensamguk/ui')>()),
    useViewportClass: () => mocks.viewport,
}));
vi.mock('@/components/RichTextEditor', () => ({
    RichTextEditor: ({ value, onChange, ariaLabel }: { value: string; onChange: (v: string) => void; ariaLabel: string }) => (
        <textarea aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} />
    ),
}));

import CouncilScreen from '@/components/council/CouncilScreen';

const person = (id: number, name: string) => ({ generalId: id, name, picture: null, imageServer: 0, officerLevelText: '군주' });
const MEETING: BoardResponse = {
    result: true, secret: false, title: '회의실', blockedReason: null, myGeneralId: 7, myPermission: 0, chiefCount: 1,
    participants: [{ ...person(1, '조조'), active: true, chief: true }, { ...person(7, '하후돈'), active: false, chief: false }],
    articles: [
        { id: 11, nationId: 1, authorGeneralId: 1, authorName: '조조', title: '허현으로 도읍을 옮긴 뒤의 일', contentHtml: '<p>창고를 <strong>영음현</strong>으로 모은다.</p>', date: '2026-09-30T12:10:00Z', comments: [{ id: 1, authorGeneralId: 7, authorName: '하후돈', text: '<em>알겠습니다</em>', date: '2026-09-30T12:32:00Z' }], kind: 'notice' },
        { id: 12, nationId: 1, authorGeneralId: 7, authorName: '하후돈', title: '군단 쌀이 두 순 치뿐입니다', contentHtml: '<p>보내 주십시오.</p>', date: '2026-09-30T13:17:00Z', comments: [], kind: 'general' },
        { id: 13, nationId: 1, authorGeneralId: 1, authorName: '조조', title: '옛 표결', contentHtml: '<p>표결</p>', date: '2026-09-29T13:17:00Z', comments: [], kind: 'vote', vote: null },
    ],
};
const SECRET: BoardResponse = {
    ...MEETING, secret: true, title: '기밀실', myPermission: 2,
    articles: [
        { id: 21, nationId: 1, authorGeneralId: 1, authorName: '조조', title: '원소 본대의 남하 시점', contentHtml: '<p>수뇌 밖으로 내지 말 것.</p>', date: '2026-09-30T14:40:00Z', comments: [], kind: 'operation', readers: { read: [person(1, '조조')], total: 3 } },
        { id: 22, nationId: 1, authorGeneralId: 1, authorName: '조조', title: '이미 읽은 글', contentHtml: '<p>x</p>', date: '2026-09-30T14:00:00Z', comments: [], kind: 'general', readers: { read: [person(1, '조조'), person(7, '하후돈')], total: 3 } },
    ],
};

async function settle() {
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
}

beforeEach(() => {
    for (const fn of [mocks.board, mocks.command, mocks.submit, mocks.replace]) fn.mockReset();
    mocks.search = '';
    mocks.viewport = null;
    mocks.board.mockImplementation(async (secret: boolean) => (secret ? SECRET : MEETING));
    mocks.command.mockResolvedValue({ status: 'AVAILABLE', requestId: 'r' });
    mocks.submit.mockResolvedValue({ status: 'applied', result: {} });
});

describe('보기 모델(옛 게시판 → 회의실)', () => {
    it('종류 · 열람 · 참여 · 권한을 옮기고 옛 직책 글자는 버린다', () => {
        const v = councilFromBoard(SECRET);
        expect(v.room).toBe('SECRET');
        expect(v.articles.map((a) => a.kind)).toEqual(['OPERATION', 'GENERAL']);
        expect(v.articles[0].readers).toEqual({ read: [{ generalId: 1, name: '조조', picture: null, imageServer: 0 }], total: 3 });
        expect(v.members.map((m) => [m.name, m.active, m.inSecret])).toEqual([['조조', true, true], ['하후돈', false, false]]);
        expect(v.access).toEqual({ canRead: true, canWrite: true, canNotice: true, reason: null });
        expect(JSON.stringify(v)).not.toContain('officerLevelText');
        expect(councilFromBoard(MEETING).articles[2]).toMatchObject({ kind: 'GENERAL', legacyVote: true });
    });
    it('재야(myPermission -1)는 세력 없음, 기밀실 차단은 읽기 닫힘', () => {
        expect(councilFromBoard({ ...MEETING, myPermission: -1, blockedReason: '소속 세력이 없어 회의실을 이용할 수 없습니다.', articles: [] }).noAffiliation).toBe(true);
        expect(councilFromBoard({ ...SECRET, myPermission: 0, blockedReason: '권한이 부족합니다.', articles: [] }).access.canRead).toBe(false);
    });
    it('쓰기 결과 → 결과 띠', () => {
        expect(outcomeNotice({ status: 'applied', result: {} as never })).toEqual({ tone: 'ok', text: '등록되었습니다.' });
        expect(outcomeNotice({ status: 'pending', reason: '처리 지연' })).toEqual({ tone: 'ok', text: '접수됨 — 반영 대기' });
        expect(outcomeNotice({ status: 'rejected', reason: '국가에 소속되어있지 않습니다.' })).toEqual({ tone: 'err', text: '국가에 소속되어있지 않습니다.' });
    });
});

describe('회의실', () => {
    it('머리(세력 · 방 · 같은 세력 장수 수) · 종류 거르기 · 본문과 댓글은 SafeHtml 서식 그대로 · 옛 표결은 본문만', async () => {
        render(<CouncilScreen />);
        await settle();
        expect(mocks.board).toHaveBeenCalledWith(false);
        expect(screen.getByText('조조 · 회의실')).toBeInTheDocument();
        expect(screen.getByText('같은 세력 장수 2명')).toBeInTheDocument();
        const notice = screen.getByRole('article', { name: '허현으로 도읍을 옮긴 뒤의 일' });
        expect(notice.querySelector('strong')).toHaveTextContent('영음현');
        expect(within(notice).getByRole('list', { name: '댓글' }).querySelector('em')).toHaveTextContent('알겠습니다');
        expect(screen.getByText('옛 표결 글입니다 — 표결은 보이지 않습니다.')).toBeInTheDocument();
        fireEvent.click(within(screen.getByRole('radiogroup', { name: '글 종류' })).getByRole('radio', { name: /공지/ }));
        expect(screen.getAllByRole('article')).toHaveLength(1);
        expect(screen.getByRole('region', { name: '회의실 참여' })).toHaveTextContent('활동 1 · 침묵 1');
    });

    it('새 글 — 종류를 함께 보내고 서식 HTML 을 그대로, 공지는 권한 없으면 사유 · 빈 본문은 막는다', async () => {
        render(<CouncilScreen />);
        await settle();
        fireEvent.click(screen.getByRole('button', { name: '새 글 쓰기' }));
        const sheet = screen.getByRole('dialog', { name: '새 글 쓰기' });
        const notice = within(sheet).getByRole('button', { name: '공지' });
        expect(notice).toHaveAttribute('aria-disabled', 'true');
        fireEvent.change(within(sheet).getByRole('textbox', { name: '제목' }), { target: { value: '작전 공유' } });
        fireEvent.change(within(sheet).getByRole('textbox', { name: '본문' }), { target: { value: '<p>&nbsp;</p>' } });
        expect(within(sheet).getByRole('button', { name: '등록' })).toHaveAttribute('aria-disabled', 'true');
        fireEvent.change(within(sheet).getByRole('textbox', { name: '본문' }), { target: { value: '<p><strong>북쪽</strong>을 막는다</p>' } });
        fireEvent.click(within(sheet).getByRole('button', { name: '작전' }));
        fireEvent.click(within(sheet).getByRole('button', { name: '등록' }));
        await act(async () => { fireEvent.click(within(within(sheet).getByRole('group', { name: '등록 확인' })).getByRole('button', { name: '등록' })); });
        await settle();
        expect(mocks.command).toHaveBeenCalledWith('boardArticle', { isSecret: false, title: '작전 공유', text: '<p><strong>북쪽</strong>을 막는다</p>', kind: 'operation' }, 7);
        expect(screen.getByRole('status')).toHaveTextContent('등록되었습니다.');
        expect(mocks.board).toHaveBeenCalledTimes(2);
    });

    it('댓글 — 비면 사유, 쓰면 확인 뒤 boardComment, 거절은 서버 사유 그대로', async () => {
        mocks.submit.mockResolvedValue({ status: 'rejected', reason: '국가에 소속되어있지 않습니다.' });
        render(<CouncilScreen />);
        await settle();
        const card = screen.getByRole('article', { name: '군단 쌀이 두 순 치뿐입니다' });
        expect(within(card).getByRole('button', { name: '등록' })).toHaveAttribute('aria-disabled', 'true');
        fireEvent.change(within(card).getByRole('textbox'), { target: { value: '곧 보냅니다' } });
        fireEvent.click(within(card).getByRole('button', { name: '등록' }));
        await act(async () => { fireEvent.click(within(screen.getByRole('dialog', { name: '댓글' })).getByRole('button', { name: '등록' })); });
        await settle();
        expect(mocks.command).toHaveBeenCalledWith('boardComment', { articleNo: 12, text: '곧 보냅니다' }, 7);
        expect(screen.getByRole('status')).toHaveTextContent('국가에 소속되어있지 않습니다.');
        expect(within(card).getByRole('textbox')).toHaveValue('곧 보냅니다');
    });

    it('같은 방 다시 읽기가 실패해도 받은 글과 쓰던 댓글은 남는다(옛 화면 background 실패 규칙)', async () => {
        render(<CouncilScreen />);
        await settle();
        const card = screen.getByRole('article', { name: '군단 쌀이 두 순 치뿐입니다' });
        fireEvent.change(within(card).getByRole('textbox'), { target: { value: '쓰던 댓글' } });
        mocks.board.mockRejectedValueOnce(new Error('503: Service Unavailable'));
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: '새로고침' })); });
        await settle();
        expect(screen.getByRole('article', { name: '군단 쌀이 두 순 치뿐입니다' })).toBeInTheDocument();
        expect(within(screen.getByRole('article', { name: '군단 쌀이 두 순 치뿐입니다' })).getByRole('textbox')).toHaveValue('쓰던 댓글');
        expect(screen.getByRole('status')).toHaveTextContent('새로 읽지 못했습니다');
        expect(screen.queryByText('회의실을 불러오지 못했습니다')).toBeNull();
    });

    it('방 바꾸기는 주소(?room=secret)로', async () => {
        render(<CouncilScreen />);
        await settle();
        fireEvent.click(screen.getByRole('tab', { name: '기밀실' }));
        expect(mocks.replace).toHaveBeenCalledWith('/game/pep/council?room=secret', { scroll: false });
    });
});

describe('기밀실', () => {
    it('옛 주소 ?secret=1 도 첫 화면에서 기밀실 · 안 읽은 글만 열람 기록 · 열람한 사람 시트', async () => {
        mocks.search = 'secret=1';
        render(<CouncilScreen />);
        await settle();
        expect(mocks.board).toHaveBeenCalledWith(true);
        expect(mocks.board).not.toHaveBeenCalledWith(false);
        expect(screen.getByText('참여자만')).toBeInTheDocument();
        await settle();
        const reads = mocks.command.mock.calls.filter((c) => c[0] === 'boardRead');
        expect(reads).toEqual([['boardRead', { articleNo: 21 }, 7]]);
        expect(screen.getByRole('region', { name: '기밀실 참여' })).toHaveTextContent('조조');
        fireEvent.click(within(screen.getByRole('article', { name: '원소 본대의 남하 시점' })).getByRole('button', { name: /열람 1 \/ 3/ }));
        expect(within(screen.getByRole('dialog', { name: '열람한 사람' })).getByRole('list')).toHaveTextContent('조조');
    });

    it('열람 기록이 거절되면 다음 다시 읽기에 또 남긴다(완료로 치지 않는다)', async () => {
        mocks.search = 'room=secret';
        mocks.submit.mockResolvedValueOnce({ status: 'rejected', reason: '거절' });
        render(<CouncilScreen />);
        await settle();
        await settle();
        expect(mocks.command.mock.calls.filter((c) => c[0] === 'boardRead')).toHaveLength(1);
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: '새로고침' })); });
        await settle();
        await settle();
        expect(mocks.command.mock.calls.filter((c) => c[0] === 'boardRead')).toEqual([['boardRead', { articleNo: 21 }, 7], ['boardRead', { articleNo: 21 }, 7]]);
    });

    it('권한 없음 · 재야는 막힘 화면', async () => {
        mocks.search = 'room=secret';
        mocks.board.mockResolvedValue({ ...SECRET, myPermission: 0, blockedReason: '권한이 부족합니다. 수뇌부가 아닙니다.', articles: [] });
        const { unmount } = render(<CouncilScreen />);
        await settle();
        expect(screen.getByText('기밀실 참여자만 볼 수 있습니다')).toBeInTheDocument();
        expect(screen.queryByText(/수뇌부/)).toBeNull();
        unmount();
        mocks.board.mockResolvedValue({ ...MEETING, myPermission: -1, blockedReason: '소속 세력이 없어 회의실을 이용할 수 없습니다.', articles: [] });
        render(<CouncilScreen />);
        await settle();
        expect(screen.getByText('세력에 속하면 회의실을 쓸 수 있습니다')).toBeInTheDocument();
    });
});
