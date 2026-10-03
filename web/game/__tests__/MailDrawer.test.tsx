// 머리줄 서신 서랍(P-Q02, 보드 V31K6MailDrawer) — 머리(서신 · 요청 수 · 전체 화면 · 닫기), 주소 탭(?mail=)과 맞물림,
// 요청 탭의 「조정에서 모두 보기」, 서식 없는 「짧은 서신」(글이 태그로 읽히지 않게 글자로 바꿔 보냄), 서랍 안 Esc 닫기.
import { configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import MailDrawer, { mailTabOf, mailViewOf } from '../components/mail/MailDrawer';
import { api } from '../lib/api';
import { submitCommandAndAwaitResult } from '../lib/commandSubmit';

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }));
const nav = vi.hoisted(() => ({ pathname: '/game/pep/retinue', search: 'mail=personal&person=3' }));
vi.mock('next/navigation', () => ({
    usePathname: () => nav.pathname,
    useSearchParams: () => new URLSearchParams(nav.search),
    useRouter: () => router,
}));
vi.mock('next/link', () => ({
    default: ({ href, prefetch, scroll, children, ...rest }: { href: string; prefetch?: boolean; scroll?: boolean; children: React.ReactNode }) => (
        <a href={href} data-scroll={scroll === undefined ? undefined : String(scroll)} {...rest}>{children}</a>
    ),
}));
vi.mock('../lib/campaign-session', () => ({
    useGameSession: () => ({ serverId: 'pep', generalId: 1, frontInfo: { general: { generalId: 1, nationId: 3, hasGeneral: true } } }),
}));
const requests = vi.hoisted(() => ({ waiting: 2 }));
vi.mock('../lib/requests', async (importActual) => ({
    ...(await importActual<typeof import('../lib/requests')>()),
    useRequests: () => ({
        load: { state: 'ready', requests: [], partial: false }, waiting: requests.waiting,
        respond: vi.fn(), busyKey: null, rejected: {}, reload: vi.fn(),
    }),
}));
vi.mock('../lib/api', () => ({
    api: {
        mailboxRecent: vi.fn(), mailboxOld: vi.fn(), generalsList: vi.fn(), contacts: vi.fn(),
        commands: { sendMessage: vi.fn(), deleteMessage: vi.fn(), readLatestMessage: vi.fn() },
    },
}));
vi.mock('../lib/commandSubmit', () => ({ submitCommandAndAwaitResult: vi.fn() }));
vi.mock('../components/RichTextEditor', () => ({
    RichTextEditor: () => <div data-testid="rich-editor" />,
}));

const CLOSE = '/game/pep/retinue?person=3';

beforeEach(() => {
    vi.clearAllMocks();
    requests.waiting = 2;
    vi.mocked(api.mailboxRecent).mockResolvedValue({ private: [], public: [], national: [], sequence: 1 } as never);
    vi.mocked(api.commands.readLatestMessage).mockResolvedValue({ status: 'AVAILABLE' } as never);
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'reserved' } as never; });
});

test('주소 값 ↔ 서신 탭 — 모르는 값은 개인', () => {
    expect(mailTabOf('requests')).toBe('requests');
    expect(mailTabOf('nation')).toBe('national');
    expect(mailTabOf('all')).toBe('public');
    expect(mailTabOf('없는값')).toBe('private');
    expect(mailTabOf(null)).toBe('private');
    expect(mailViewOf('public')).toBe('all');
    expect(mailViewOf('requests')).toBe('requests');
});

test('머리: 「서신」 · 받은 요청 수 · 「전체 화면」(/game/mail) · 닫기(?mail= 뺀 주소)', async () => {
    render(<MailDrawer view="personal" closeHref={CLOSE} />);
    const drawer = screen.getByTestId('mail-drawer');
    expect(within(drawer).getByRole('heading', { name: '서신', level: 2 })).toBeInTheDocument();
    expect(within(drawer).getByText('요청 2', { selector: '.os-chip' })).toBeInTheDocument();
    expect(within(drawer).getByRole('link', { name: '전체 화면' })).toHaveAttribute('href', '/game/pep/mail');
    const close = within(drawer).getByRole('link', { name: '서랍 닫기' });
    expect(close).toHaveAttribute('href', CLOSE);
    expect(close).toHaveAttribute('data-scroll', 'false');
    // 서랍 판에는 옛 「서신 쓰기」(전체 쓰기 화면) 단추 대신 아래 「짧은 서신」이 늘 있다.
    expect(within(drawer).queryByRole('button', { name: '서신 쓰기' })).toBeNull();
    expect(await within(drawer).findByRole('region', { name: '짧은 서신' })).toBeInTheDocument();
    expect(within(drawer).getByRole('link', { name: '서신에서 쓰기' })).toHaveAttribute('href', '/game/pep/mail');
});

test('받은 요청이 없으면 요청 수 칩을 그리지 않는다(지어낸 수 없음)', () => {
    requests.waiting = 0;
    render(<MailDrawer view="personal" closeHref={CLOSE} />);
    expect(screen.queryByText(/^요청 \d/)).toBeNull();
});

test('?mail=requests 로 열면 요청 탭 — 「조정에서 모두 보기」가 조정 발령 탭으로 간다, 짧은 서신은 개인 서신', async () => {
    render(<MailDrawer view="requests" closeHref={CLOSE} />);
    expect(screen.getByRole('tab', { name: '요청 2' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('link', { name: '조정에서 모두 보기 →' })).toHaveAttribute('href', '/game/pep/court?tab=orders');
    const compose = await screen.findByRole('region', { name: '짧은 서신' });
    expect(within(compose).getByRole('searchbox', { name: '이름 · 초성으로 찾기' })).toBeInTheDocument();
});

test('탭을 바꾸면 주소의 ?mail= 을 replace 로 맞춘다(다른 쿼리는 둔다)', async () => {
    render(<MailDrawer view="personal" closeHref={CLOSE} />);
    fireEvent.click(screen.getByRole('tab', { name: '전체' }));
    expect(router.replace).toHaveBeenLastCalledWith('/game/pep/retinue?mail=all&person=3', { scroll: false });
    fireEvent.click(screen.getByRole('tab', { name: '요청 2' }));
    expect(router.replace).toHaveBeenLastCalledWith('/game/pep/retinue?mail=requests&person=3', { scroll: false });
});

test('짧은 서신(세력) — 서식 없는 글을 글자로 바꿔 우리 세력 서신함으로 보낸다(꺾쇠가 태그로 읽히지 않게)', async () => {
    render(<MailDrawer view="nation" closeHref={CLOSE} />);
    const compose = await screen.findByRole('region', { name: '짧은 서신' });
    expect(within(compose).getByText('우리 세력 모두에게')).toBeInTheDocument();
    expect(screen.queryByTestId('rich-editor')).toBeNull();
    fireEvent.change(within(compose).getByRole('textbox', { name: '서신 내용' }), { target: { value: '<b>모이자</b>\n내일' } });
    fireEvent.click(within(compose).getByRole('button', { name: '보내기' }));
    await waitFor(() => expect(api.commands.sendMessage).toHaveBeenCalledWith({ mailbox: 9003, text: '&lt;b&gt;모이자&lt;/b&gt;<br>내일' }, 1));
    await waitFor(() => expect(within(compose).getByRole('textbox', { name: '서신 내용' })).toHaveValue(''));
});

test('짧은 서신(개인) — 받는 사람을 고르기 전에는 보내지 않는다', async () => {
    render(<MailDrawer view="personal" closeHref={CLOSE} />);
    const compose = await screen.findByRole('region', { name: '짧은 서신' });
    fireEvent.change(within(compose).getByRole('textbox', { name: '서신 내용' }), { target: { value: '안녕' } });
    const send = within(compose).getByRole('button', { name: '보내기' });
    expect(send).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(send);
    await new Promise((r) => setTimeout(r, 0));
    expect(api.commands.sendMessage).not.toHaveBeenCalled();
});

test('서랍 안 Esc 는 서랍을 닫는다(?mail= 뺀 주소) — 한글 조합 중 Esc 는 닫지 않는다', () => {
    render(<MailDrawer view="personal" closeHref={CLOSE} />);
    const drawer = screen.getByTestId('mail-drawer');
    fireEvent.keyDown(drawer, { key: 'Escape', isComposing: true });
    expect(router.push).not.toHaveBeenCalled();
    fireEvent.keyDown(drawer, { key: 'Escape' });
    expect(router.push).toHaveBeenCalledWith(CLOSE, { scroll: false });
});
