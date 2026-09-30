// 서신(P-Q02) — 방향 표식 · 볼 수 없음 ≠ 빈 목록 · NPC는 보이되 서버 대기로 막힘 · 개인 서신 보내기 · 지우기 확인 · 재야 세력 탭 없음.
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { MailScreen } from '../components/mail/MailScreen';
import { api } from '../lib/api';
import { submitCommandAndAwaitResult } from '../lib/commandSubmit';

vi.mock('../lib/api', () => ({
    api: {
        mailboxRecent: vi.fn(), mailboxOld: vi.fn(), generalsList: vi.fn(), dispatchPending: vi.fn(), politicalConsentOptions: vi.fn(),
        commands: { sendMessage: vi.fn(), deleteMessage: vi.fn(), readLatestMessage: vi.fn() },
    },
}));
vi.mock('../lib/commandSubmit', () => ({ submitCommandAndAwaitResult: vi.fn() }));
vi.mock('../components/RichTextEditor', () => ({
    RichTextEditor: ({ value, onChange, ariaLabel }: { value: string; onChange: (v: string) => void; ariaLabel: string }) =>
        <textarea aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} />,
}));

const me = { generalId: 1, nationId: 3 };
const party = (id: number, name: string) => ({ id, name, nation_id: 3, nation: '[세력]', color: '#123456' });
const now = new Date().toISOString();
const envelope = {
    private: [
        { id: 11, msgType: 'private', src: party(1, '나'), dest: party(2, '가'), text: '<b>보낸 글</b>', option: null, time: now },
        { id: 12, msgType: 'private', src: party(2, '가'), dest: party(1, '나'), text: '받은 글', option: null, time: now },
        { id: 13, msgType: 'private', src: party(2, '가'), dest: party(1, '나'), text: 'req_del_msg', option: null, time: now },
    ],
    public: [], national: [], sequence: 1,
};
const general = (generalId: number, name: string, npc = 0) => ({ generalId, name, nationId: 3, nationName: '[세력]', nationColor: '#111', npc, officerLevel: 1, cityName: '허현', picture: null, imageServer: 0 });

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.mailboxRecent).mockResolvedValue(envelope as never);
    vi.mocked(api.generalsList).mockResolvedValue([general(1, '나'), general(2, '가'), general(3, '나무', 2)] as never);
    vi.mocked(api.commands.readLatestMessage).mockResolvedValue({ status: 'AVAILABLE' } as never);
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'applied', result: { result: { recipientId: 2, recipientName: '가' } } } as never; });
});

test('받은 · 보낸 서신을 방향으로 가르고, 엔진 지우기 표식 행은 빼며, 받은 서신까지 읽음 표시한다', async () => {
    render(<MailScreen me={me} />);
    const list = await screen.findByRole('list', { name: '개인 서신' });
    const rows = within(list).getAllByRole('button');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('보냄');
    expect(rows[1]).toHaveTextContent('받음');
    await waitFor(() => expect(api.commands.readLatestMessage).toHaveBeenCalledWith({ type: 'private', msgID: 12 }, 1));
    fireEvent.click(rows[0]);
    expect(await screen.findByRole('article', { name: '보낸 서신 — 가' })).toHaveTextContent('나 → 가');
});

test('볼 수 없음(403)은 빈 서신함과 다른 모양이다', async () => {
    vi.mocked(api.mailboxRecent).mockRejectedValue(new Error('403: Forbidden'));
    render(<MailScreen me={me} />);
    expect(await screen.findByText('이 서신함은 볼 수 없습니다')).toBeInTheDocument();
    expect(screen.queryByText('서신이 없습니다')).toBeNull();
});

test('받는 사람에 NPC가 보이되 서버 대기 사유로 막히고, 사람을 고르면 그 사람에게 보낸다', async () => {
    render(<MailScreen me={me} />);
    const compose = await screen.findByRole('region', { name: '서신 쓰기' });
    const npc = await within(compose).findByText('나무');
    expect(npc.closest('[aria-disabled="true"]')).not.toBeNull();
    expect(within(compose).getAllByText('NPC에게 보내는 서신은 서버 준비 중입니다').length).toBeGreaterThan(0);
    const send = () => within(compose).getByRole('button', { name: '보내기' });
    expect(send()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(within(compose).getByText('가').closest('button')!);
    expect(send()).toHaveAttribute('aria-disabled', 'true'); // 아직 글이 없다
    fireEvent.change(within(compose).getByLabelText('서신 내용'), { target: { value: '<p>안녕하세요</p>' } });
    expect(send()).not.toHaveAttribute('aria-disabled');
    fireEvent.click(send());
    await waitFor(() => expect(api.commands.sendMessage).toHaveBeenCalledWith({ mailbox: 2, text: '<p>안녕하세요</p>' }, 1));
    expect(await within(compose).findByText('가에게 보냈습니다')).toBeInTheDocument();
});

test('지우기는 한 번 묻고, 확인하면 그 서신 id로 보낸다', async () => {
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'applied', result: { result: {} } } as never; });
    render(<MailScreen me={me} />);
    const list = await screen.findByRole('list', { name: '개인 서신' });
    fireEvent.click(within(list).getAllByRole('button')[0]);
    const card = await screen.findByRole('article', { name: '보낸 서신 — 가' });
    fireEvent.click(within(card).getByRole('button', { name: '지우기' }));
    expect(await screen.findByText('이 서신을 지웁니다')).toBeInTheDocument();
    expect(api.commands.deleteMessage).not.toHaveBeenCalled();
    fireEvent.click(screen.getAllByRole('button', { name: '지우기' }).at(-1)!);
    await waitFor(() => expect(api.commands.deleteMessage).toHaveBeenCalledWith({ msgID: 11 }, 1));
});

test('재야는 세력 탭을 그리지 않는다', async () => {
    render(<MailScreen me={{ generalId: 1, nationId: 0 }} />);
    const tabs = await screen.findByRole('tablist', { name: '서신 묶음' });
    expect(within(tabs).getAllByRole('tab').map((t) => t.textContent)).toEqual(['개인', '전체', '요청']);
});
