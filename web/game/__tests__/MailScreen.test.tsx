// 서신(P-Q02) — 방향 표식 · 볼 수 없음 ≠ 빈 목록 · NPC는 보이되 서버 대기로 막힘 · 개인 서신 보내기 · 지우기 확인 · 재야 세력 탭 없음.
// 외교 서신(P-K02 칸, tabs 로 붙임) — 가린 행 · 외교권자만 쓰기 · 받는 세력 서신함으로 보내기 · 종전 제의 응답.
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
        messageAccept: vi.fn(), messageDecline: vi.fn(),
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
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'applied', result: { result: { msgType: 'private', recipientId: 2, recipientName: '가' } } } as never; });
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

/** 받는 사람 목록은 받는 사람 칸에 처음 초점이 갈 때 읽는다 — 시험은 찾기 칸에 초점을 줘서 연다. */
const openPeople = (compose: HTMLElement) => fireEvent.focus(within(compose).getByRole('searchbox', { name: '이름 · 초성으로 찾기' }));

describe('받는 사람 목록은 받는 사람 칸을 처음 쓸 때 읽는다', () => {
    test('초점 · 누름 전에는 /generals 를 읽지 않고 「불러오는 중」 대신 안내만 — 찾기 칸에 초점이 가면 읽는다', async () => {
        render(<MailScreen me={me} />);
        const compose = await screen.findByRole('region', { name: '서신 쓰기' });
        await screen.findByRole('list', { name: '개인 서신' });
        await new Promise((r) => setTimeout(r, 0));
        expect(api.generalsList).not.toHaveBeenCalled();
        expect(within(compose).getByText('찾기 칸을 누르면 받을 사람 목록이 나옵니다.')).toBeInTheDocument();
        expect(within(compose).getByRole('searchbox', { name: '이름 · 초성으로 찾기' })).toBeInTheDocument();
        openPeople(compose);
        expect(await within(compose).findByText('가')).toBeInTheDocument();
        expect(api.generalsList).toHaveBeenCalledTimes(1);
        expect(within(compose).queryByText('찾기 칸을 누르면 받을 사람 목록이 나옵니다.')).toBeNull();
        fireEvent.focus(within(compose).getByRole('searchbox', { name: '이름 · 초성으로 찾기' }));
        expect(api.generalsList).toHaveBeenCalledTimes(1); // 한 번만
    });

    test('받는 사람 칸을 누르기만 해도(초점 없이 탭 · 클릭) 읽는다', async () => {
        render(<MailScreen me={me} />);
        const compose = await screen.findByRole('region', { name: '서신 쓰기' });
        fireEvent.pointerDown(within(compose).getByText('찾기 칸을 누르면 받을 사람 목록이 나옵니다.'));
        expect(await within(compose).findByText('가')).toBeInTheDocument();
        expect(api.generalsList).toHaveBeenCalledTimes(1);
    });

    test('처음 고른 받는 사람이 있으면(인물 카드 「서신」) 이름을 맞추려고 바로 읽는다', async () => {
        render(<MailScreen me={me} initialRecipientId={2} />);
        const compose = await screen.findByRole('region', { name: '서신 쓰기' });
        expect(await within(compose).findByText('— 가')).toBeInTheDocument();
        expect(api.generalsList).toHaveBeenCalledTimes(1);
        expect(within(compose).queryByText('찾기 칸을 누르면 받을 사람 목록이 나옵니다.')).toBeNull();
    });
});

test('받는 사람에 NPC가 보이되 서버 대기 사유로 막히고, 사람을 고르면 그 사람에게 보낸다', async () => {
    render(<MailScreen me={me} />);
    const compose = await screen.findByRole('region', { name: '서신 쓰기' });
    openPeople(compose);
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

test('탭 묶음(tablist)에는 탭만 — 「서신 쓰기」 단추는 묶음 밖(aria-required-children)', async () => {
    render(<MailScreen me={me} />);
    const tabs = await screen.findByRole('tablist', { name: '서신 묶음' });
    expect(Array.from(tabs.children).every((el) => el.getAttribute('role') === 'tab')).toBe(true);
    expect(within(tabs).queryByRole('button', { name: '서신 쓰기' })).toBeNull();
    expect(screen.getByRole('button', { name: '서신 쓰기' })).toBeInTheDocument();
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
        vi.mocked(api.mailboxRecent).mockClear();
        const { unmount: unmount2 } = render(<MailScreen me={{ generalId: 1, nationId: 0 }} tabs={['diplomacy']} />);
        // 재야가 외교 칸만 열면 개인 서신으로 떨어지지 않고, 아무 서신함도 읽지 않는다.
        expect(screen.getByText('소속 세력이 없어 외교 서신이 없습니다')).toBeInTheDocument();
        expect(screen.queryByRole('list')).toBeNull();
        expect(api.mailboxRecent).not.toHaveBeenCalled();
        unmount2();
        render(<MailScreen me={{ generalId: 1, nationId: 0 }} tabs={['private', 'diplomacy']} />);
        expect(await screen.findByRole('list', { name: '개인 서신' })).toBeInTheDocument();
        expect(screen.queryByRole('tablist')).toBeNull(); // 개인 하나만 남아 탭 줄이 없다
        const compose = screen.getByRole('region', { name: '서신 쓰기' });
        openPeople(compose);
        expect(await within(compose).findByText('가')).toBeInTheDocument();
    });

    test('모든 행이 가려졌으면(권한 없음) 목록 대신 「군주 · 외교권자만 봅니다」, 쓰기도 막는다', async () => {
        withDiplomacy([
            { id: 60, msgType: 'diplomacy', src: other(8, '상대'), dest: nationOnly(3, '[세력]'), text: '(외교 메시지입니다)', option: { invalid: true }, time: now },
            // 답한 제의도 서버가 가린다(used 를 보지 않는다) — 이 행 때문에 「한 줄」 안내가 깨지면 안 된다.
            { id: 59, msgType: 'diplomacy', src: other(8, '상대'), dest: nationOnly(3, '[세력]'), text: '(외교 메시지입니다)', option: { action: 'stop_war', used: true, invalid: true }, time: now },
        ]);
        vi.mocked(api.contacts).mockResolvedValue(contacts(0) as never);
        render(<MailScreen me={me} tabs={['diplomacy']} />);
        expect(await screen.findByText('외교 서신은 군주 · 외교권자만 봅니다')).toBeInTheDocument();
        expect(screen.queryByRole('list', { name: '외교 서신' })).toBeNull();
        const compose = await screen.findByRole('region', { name: '외교 서신 쓰기' });
        expect(await within(compose).findByText('외교 서신은 군주 · 외교권자만 씁니다')).toBeInTheDocument();
        expect(within(compose).queryByRole('button', { name: '보내기' })).toBeNull();
        expect(screen.queryByText('(외교 메시지입니다)')).toBeNull();
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

    test('종전 제의는 수락·거절하고 답한 제의는 다시 답하지 않는다', async () => {
        withDiplomacy([
            { id: 70, msgType: 'diplomacy', src: other(8, '상대'), dest: nationOnly(3, '[세력]'), text: '종전합시다', option: { action: 'stop_war' }, time: now },
            { id: 71, msgType: 'diplomacy', src: other(8, '상대'), dest: nationOnly(3, '[세력]'), text: '종전합시다', option: { action: 'stop_war', used: true, invalid: true }, time: now },
        ]);
        vi.mocked(api.messageAccept).mockResolvedValue({ status: 'AVAILABLE', requestId: 'accept-70' } as never);
        vi.mocked(api.messageDecline).mockResolvedValue({ status: 'AVAILABLE', requestId: 'decline-70' } as never);
        vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'applied', result: { result: {} } } as never; });
        vi.mocked(api.contacts).mockResolvedValue(contacts(4) as never);
        render(<MailScreen me={me} tabs={['diplomacy']} variant="drawer" />);
        const list = await screen.findByRole('list', { name: '외교 서신' });
        const [open, done] = within(list).getAllByRole('article');
        expect(open).toHaveTextContent('종전 제의');
        expect(open).toHaveTextContent('[원소] 상대 → 우리 세력');
        expect(within(open).getByRole('button', { name: '종전 수락' })).toBeInTheDocument();
        expect(within(open).getByRole('button', { name: '종전 거절' })).toBeInTheDocument();
        fireEvent.click(within(open).getByRole('button', { name: '종전 수락' }));
        await waitFor(() => expect(api.messageAccept).toHaveBeenCalledWith(70, 1));
        expect(api.messageDecline).not.toHaveBeenCalled();
        expect(await screen.findByText('종전 제의를 수락했습니다. 양 세력의 교전이 끝났습니다.')).toBeInTheDocument();
        expect(done).toHaveTextContent('답함');
        expect(done).toHaveTextContent('종전합시다');
        expect(within(done).queryByRole('button', { name: /수락|거절/ })).toBeNull();
    });

    test('거절은 외교 관계 변경 안내 없이 처리하고, 서버 거절은 성공으로 말하지 않는다', async () => {
        withDiplomacy([{ id: 72, msgType: 'diplomacy', src: other(8, '상대'), dest: nationOnly(3, '[세력]'), text: '종전합시다', option: { action: 'stop_war' }, time: now }]);
        vi.mocked(api.messageDecline).mockResolvedValue({ status: 'AVAILABLE', requestId: 'decline-72' } as never);
        vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'rejected', reason: '제의 기한이 지났습니다' } as never; });
        render(<MailScreen me={me} tabs={['diplomacy']} variant="drawer" />);
        const card = await screen.findByRole('article', { name: /받은 서신/ });
        fireEvent.click(within(card).getByRole('button', { name: '종전 거절' }));
        await waitFor(() => expect(api.messageDecline).toHaveBeenCalledWith(72, 1));
        expect(await screen.findByText('제의 기한이 지났습니다')).toBeInTheDocument();
        expect(screen.queryByText('종전 제의를 거절했습니다.')).toBeNull();
    });
});

// 옛 메일함(app/game/mailbox) 시험이 잠그던 것 — 새 화면에서도 같게(MailboxPage.command · .delete · EditorMailDiplomacySurface).
describe('옛 메일함 잠금 옮김', () => {
    const pickAndWrite = async (text: string) => {
        const compose = await screen.findByRole('region', { name: '서신 쓰기' });
        openPeople(compose);
        fireEvent.click((await within(compose).findByText('가')).closest('button')!);
        fireEvent.change(within(compose).getByLabelText('서신 내용'), { target: { value: text } });
        return compose;
    };

    test('개인 서신 결과의 유형 · id · 이름이 하나라도 다르면 성공이라 하지 않고 글을 남긴다', async () => {
        for (const result of [{ msgType: 'national', recipientId: 2, recipientName: '가' }, { msgType: 'private', recipientId: 2, recipientName: '다른 이' }, {}]) {
            vi.mocked(submitCommandAndAwaitResult).mockImplementationOnce(async (submit) => { await submit(); return { status: 'applied', result: { result } } as never; });
            const { unmount } = render(<MailScreen me={me} />);
            const compose = await pickAndWrite('<p>확인</p>');
            fireEvent.click(within(compose).getByRole('button', { name: '보내기' }));
            expect(await within(compose).findByText('보냈지만 받는 사람을 확인하지 못했습니다 — 서신함을 확인해 주세요')).toBeInTheDocument();
            expect(within(compose).getByLabelText('서신 내용')).toHaveValue('<p>확인</p>');
            unmount();
        }
    });

    test('결과가 늦으면(pending) 성공이라 하지 않고 글을 남긴다', async () => {
        vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'pending', reason: '대기' } as never; });
        render(<MailScreen me={me} />);
        const compose = await pickAndWrite('<p>늦음</p>');
        fireEvent.click(within(compose).getByRole('button', { name: '보내기' }));
        expect(await within(compose).findByText('처리가 늦어지고 있습니다 — 잠시 뒤 서신함을 확인해 주세요')).toBeInTheDocument();
        expect(within(compose).getByLabelText('서신 내용')).toHaveValue('<p>늦음</p>');
    });

    test('개인 받는 사람 id를 세력 · 전체 서신함 주소로 다시 쓰지 않는다', async () => {
        vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'applied', result: { result: {} } } as never; });
        render(<MailScreen me={me} />);
        await pickAndWrite('<p>모두에게</p>');
        for (const [tab, mailbox] of [['전체', 9999], ['세력', 9003]] as const) {
            fireEvent.click(screen.getByRole('tab', { name: tab }));
            const compose = await screen.findByRole('region', { name: '서신 쓰기' });
            fireEvent.change(within(compose).getByLabelText('서신 내용'), { target: { value: `<p>${tab}</p>` } });
            fireEvent.click(within(compose).getByRole('button', { name: '보내기' }));
            await waitFor(() => expect(api.commands.sendMessage).toHaveBeenLastCalledWith({ mailbox, text: `<p>${tab}</p>` }, 1));
        }
        expect(api.commands.sendMessage).not.toHaveBeenCalledWith(expect.objectContaining({ mailbox: 2 }), 1);
    });

    test('보이는 글자 500자를 넘으면 보내지 않는다(서식 태그는 세지 않는다)', async () => {
        render(<MailScreen me={me} />);
        const compose = await pickAndWrite(`<p><b>${'가'.repeat(500)}</b></p>`);
        expect(within(compose).getByRole('button', { name: '보내기' })).not.toHaveAttribute('aria-disabled');
        fireEvent.change(within(compose).getByLabelText('서신 내용'), { target: { value: `<p>${'가'.repeat(501)}</p>` } });
        const send = within(compose).getByRole('button', { name: '보내기' });
        expect(send).toHaveAttribute('aria-disabled', 'true');
        fireEvent.click(send);
        expect(api.commands.sendMessage).not.toHaveBeenCalled();
        expect(within(compose).getAllByText('500자까지 쓸 수 있습니다').length).toBeGreaterThan(0);
    });

    test('지우기를 서버가 거절하면 그 사유를 그대로 보이고 다시 읽지 않는다 · 늦으면 받았다고만 하고 다시 읽는다', async () => {
        vi.mocked(submitCommandAndAwaitResult).mockImplementationOnce(async (submit) => { await submit(); return { status: 'rejected', reason: '5분이 지나 지울 수 없습니다.' } as never; });
        render(<MailScreen me={me} />);
        const list = await screen.findByRole('list', { name: '개인 서신' });
        fireEvent.click(within(list).getAllByRole('button')[0]);
        const card = await screen.findByRole('article', { name: '보낸 서신 — 가' });
        const reads = vi.mocked(api.mailboxRecent).mock.calls.length;
        fireEvent.click(within(card).getByRole('button', { name: '지우기' }));
        fireEvent.click((await screen.findAllByRole('button', { name: '지우기' })).at(-1)!);
        expect(await screen.findByText('5분이 지나 지울 수 없습니다.')).toBeInTheDocument();
        expect(vi.mocked(api.mailboxRecent).mock.calls.length).toBe(reads);

        vi.mocked(submitCommandAndAwaitResult).mockImplementationOnce(async (submit) => { await submit(); return { status: 'pending', reason: '대기' } as never; });
        fireEvent.click(within(await screen.findByRole('article', { name: '보낸 서신 — 가' })).getByRole('button', { name: '지우기' }));
        fireEvent.click((await screen.findAllByRole('button', { name: '지우기' })).at(-1)!);
        expect(await screen.findByText('지우기를 받았습니다 — 곧 목록에서 사라집니다')).toBeInTheDocument();
        await waitFor(() => expect(vi.mocked(api.mailboxRecent).mock.calls.length).toBeGreaterThan(reads));
    });

    test('저장된 서식은 살리고 위험한 표식은 지운다', async () => {
        vi.mocked(api.mailboxRecent).mockResolvedValue({ ...envelope, private: [
            { id: 90, msgType: 'private', src: party(2, '가'), dest: party(1, '나'), text: '<p><strong>서식 서신</strong><img src=x onerror=alert(1)></p>', option: null, time: now },
        ] } as never);
        render(<MailScreen me={me} variant="drawer" />);
        const card = await screen.findByRole('article', { name: '받은 서신 — 가' });
        // SafeHtml 은 마운트 뒤 effect 에서 정리한다(첫 그림은 글자 그대로 이스케이프) — 옛 시험처럼 기다린다.
        await waitFor(() => expect(card.querySelector('strong')).toHaveTextContent('서식 서신'));
        expect(card.querySelector('img')).toBeNull();
    });
});
