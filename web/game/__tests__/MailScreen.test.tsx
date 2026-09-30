// 서신(P-Q02) — 방향 표식 · 볼 수 없음 ≠ 빈 목록 · NPC는 보이되 서버 대기로 막힘 · 개인 서신 보내기 · 지우기 확인 · 재야 세력 탭 없음.
// 외교 서신(P-K02 칸, tabs 로 붙임) — 가린 행 · 외교권자만 쓰기 · 받는 세력 서신함으로 보내기 · 제의 응답은 서버 대기.
import { configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { MailScreen } from '../components/mail/MailScreen';
import { api } from '../lib/api';
import { submitCommandAndAwaitResult } from '../lib/commandSubmit';

// jsdom에서 부품 · 목록을 그리고 가짜 서버 응답을 기다린다 — CI · 로컬 병렬 부하에서 기본 1초 대기 창 · 5초 한도가 모자란다
// (부하 평균 557에서 「찾을 수 없음」으로 재현, 응답을 1.2초 늦추면 같은 실패가 나고 창을 5초로 늘리면 통과 — 2026-10-01).
configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
vi.mock('../lib/api', () => ({
    api: {
        mailboxRecent: vi.fn(), mailboxOld: vi.fn(), generalsList: vi.fn(), contacts: vi.fn(), dispatchPending: vi.fn(), politicalConsentOptions: vi.fn(),
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

describe('외교 서신', () => {
    const nationOnly = (nation_id: number, nation: string) => ({ id: 0, name: '', nation_id, nation, color: '#654321' });
    const other = (id: number, name: string) => ({ id, name, nation_id: 5, nation: '[원소]', color: '#654321' });
    const contacts = (myFlags: number) => ({ nation: [
        { mailbox: 9003, name: '[세력]', color: '#123456', general: [[1, '나', myFlags]] },
        { mailbox: 9005, name: '[원소]', color: '#654321', general: [[8, '상대', 1]] },
    ] });
    const withDiplomacy = (diplomacy: unknown[]) => vi.mocked(api.mailboxRecent).mockResolvedValue({ ...envelope, diplomacy } as never);

    test('외교 탭은 붙인 곳에서만 · 재야는 빠진다', async () => {
        const { unmount } = render(<MailScreen me={me} tabs={['private', 'national', 'public', 'diplomacy', 'requests']} />);
        const tabs = await screen.findByRole('tablist', { name: '서신 묶음' });
        expect(within(tabs).getAllByRole('tab').map((t) => t.textContent)).toEqual(['개인', '세력', '전체', '외교', '요청']);
        unmount();
        render(<MailScreen me={{ generalId: 1, nationId: 0 }} tabs={['private', 'diplomacy']} />);
        expect(await screen.findByRole('list', { name: '개인 서신' })).toBeInTheDocument();
        expect(screen.queryByRole('tablist')).toBeNull(); // 개인 하나만 남아 탭 줄이 없다
        expect(await within(screen.getByRole('region', { name: '서신 쓰기' })).findByText('가')).toBeInTheDocument();
    });

    test('모든 행이 가려졌으면(권한 없음) 목록 대신 「군주 · 외교권자만 봅니다」, 쓰기도 막는다', async () => {
        withDiplomacy([{ id: 60, msgType: 'diplomacy', src: other(8, '상대'), dest: nationOnly(3, '[세력]'), text: '(외교 메시지입니다)', option: { invalid: true }, time: now }]);
        vi.mocked(api.contacts).mockResolvedValue(contacts(0) as never);
        render(<MailScreen me={me} tabs={['diplomacy']} />);
        expect(await screen.findByText('외교 서신은 군주 · 외교권자만 봅니다')).toBeInTheDocument();
        expect(screen.queryByRole('list', { name: '외교 서신' })).toBeNull();
        const compose = await screen.findByRole('region', { name: '외교 서신 쓰기' });
        expect(await within(compose).findByText('외교 서신은 군주 · 외교권자만 씁니다')).toBeInTheDocument();
        expect(within(compose).queryByRole('button', { name: '보내기' })).toBeNull();
        await waitFor(() => expect(api.commands.readLatestMessage).toHaveBeenCalledWith({ type: 'diplomacy', msgID: 60 }, 1));
    });

    test('외교권자는 받는 세력을 골라 그 세력 서신함으로 보내고, 엔진이 외교 서신으로 적었는지 본다', async () => {
        withDiplomacy([]);
        vi.mocked(api.contacts).mockResolvedValue(contacts(4) as never);
        vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'applied', result: { result: { msgType: 'diplomacy' } } } as never; });
        render(<MailScreen me={me} tabs={['diplomacy']} />);
        const compose = await screen.findByRole('region', { name: '외교 서신 쓰기' });
        const nations = await within(compose).findByRole('listbox', { name: '받는 세력' });
        expect(within(nations).getAllByRole('option').map((o) => o.textContent)).toEqual(['[원소]']); // 우리 세력은 뺀다
        fireEvent.change(within(compose).getByLabelText('서신 내용'), { target: { value: '<p>동맹을 청합니다</p>' } });
        const send = () => within(compose).getByRole('button', { name: '보내기' });
        expect(send()).toHaveAttribute('aria-disabled', 'true'); // 아직 세력을 안 골랐다
        fireEvent.click(within(nations).getByRole('option', { name: '[원소]' }));
        expect(send()).not.toHaveAttribute('aria-disabled');
        fireEvent.click(send());
        await waitFor(() => expect(api.commands.sendMessage).toHaveBeenCalledWith({ mailbox: 9005, text: '<p>동맹을 청합니다</p>' }, 1));
        expect(await within(compose).findByText('[원소]에 외교 서신을 보냈습니다')).toBeInTheDocument();
    });

    test('엔진이 세력 서신으로 적으면 성공이라 하지 않는다', async () => {
        withDiplomacy([]);
        vi.mocked(api.contacts).mockResolvedValue(contacts(4) as never);
        vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'applied', result: { result: { msgType: 'national' } } } as never; });
        render(<MailScreen me={me} tabs={['diplomacy']} />);
        const compose = await screen.findByRole('region', { name: '외교 서신 쓰기' });
        fireEvent.click(await within(compose).findByRole('option', { name: '[원소]' }));
        fireEvent.change(within(compose).getByLabelText('서신 내용'), { target: { value: '글' } });
        fireEvent.click(within(compose).getByRole('button', { name: '보내기' }));
        expect(await within(compose).findByText('보냈지만 외교 서신으로 갔는지 확인하지 못했습니다 — 외교 서신을 확인해 주세요')).toBeInTheDocument();
    });

    test('받은 제의는 서버 대기 안내만 — 수락 · 거절 단추를 그리지 않는다, 답한 제의는 「답함」', async () => {
        withDiplomacy([
            { id: 70, msgType: 'diplomacy', src: other(8, '상대'), dest: nationOnly(3, '[세력]'), text: '불가침을 청합니다', option: { action: 'no_aggression' }, time: now },
            { id: 71, msgType: 'diplomacy', src: other(8, '상대'), dest: nationOnly(3, '[세력]'), text: '종전합시다', option: { action: 'stop_war', used: true, invalid: true }, time: now },
        ]);
        vi.mocked(api.contacts).mockResolvedValue(contacts(4) as never);
        render(<MailScreen me={me} tabs={['diplomacy']} variant="drawer" />);
        const list = await screen.findByRole('list', { name: '외교 서신' });
        const [open, done] = within(list).getAllByRole('article');
        expect(open).toHaveTextContent('불가침 제의');
        expect(open).toHaveTextContent('[원소] 상대 → 우리 세력');
        expect(within(open).getByText('제의에 답하기는 서버 준비 중입니다')).toBeInTheDocument();
        expect(within(open).queryByRole('button', { name: /수락|거절/ })).toBeNull();
        expect(done).toHaveTextContent('답함');
        expect(done).toHaveTextContent('종전합시다');
        expect(within(done).queryByText('제의에 답하기는 서버 준비 중입니다')).toBeNull();
    });
});
