// 외교 페이지(/game/global-diplomacy) — 옛 「중원 정보」 대신 외교 칸 · 관계 지도 자리 · 외교 서신(외교권자만 쓰기) · 군주 아니면 「보기만」.
import { configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import DiplomacyPage from '@/app/game/global-diplomacy/page';
import { api } from '@/lib/api';

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
vi.mock('@/lib/api', () => ({
    api: {
        frontInfo: vi.fn(), diplomacyConflict: vi.fn(), mailboxRecent: vi.fn(), mailboxOld: vi.fn(), contacts: vi.fn(), generalsList: vi.fn(),
        commands: { sendMessage: vi.fn(), deleteMessage: vi.fn(), readLatestMessage: vi.fn() },
    },
}));
// main 의 옛 <Shell> 은 라우터를 쓰는 머리줄을 그린다(셸 #1107 뒤에는 통과 감싸기) — 감싸기만 흉내 낸다.
vi.mock('@/components/Shell', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/RichTextEditor', () => ({
    RichTextEditor: ({ value, onChange, ariaLabel }: { value: string; onChange: (v: string) => void; ariaLabel: string }) =>
        <textarea aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} />,
}));

const nation = (id: number, name: string, cities: string[] = []) => ({ nation: id, name, color: '#123456', type: '', level: 1, capital: 0, gennum: 1, cities, power: 0 });
const info = (general: Record<string, unknown>) => ({ general: { hasGeneral: true, generalId: 1, nationId: 1, officerLevel: 1, ...general } });

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.frontInfo).mockResolvedValue(info({}) as never);
    vi.mocked(api.diplomacyConflict).mockResolvedValue({
        result: true, conflict: [], myNationID: 1,
        nations: [nation(1, '[우리]', ['허현']), nation(2, '[갑]', ['진류'])],
        diplomacyList: { 1: { 2: 0 } },
    } as never);
    vi.mocked(api.mailboxRecent).mockResolvedValue({ private: [], public: [], national: [], diplomacy: [], sequence: 0 } as never);
    vi.mocked(api.commands.readLatestMessage).mockResolvedValue({ status: 'AVAILABLE' } as never);
});

test('제목 「외교」 · 관계 표 · 관계 지도 자리 · 옛 분쟁 현황은 없다', async () => {
    render(<DiplomacyPage />);
    expect(screen.getByRole('heading', { name: '외교', level: 1 })).toBeInTheDocument();
    const rows = await screen.findByRole('list', { name: '세력별 관계' });
    expect(within(rows).getByText('[갑]')).toBeInTheDocument();
    expect(within(rows).getByText('교전')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: '관계 지도' })).getByRole('link', { name: '천하 지도 보기' })).toHaveAttribute('href', '/game/map');
    expect(screen.queryByText('분쟁 현황')).toBeNull();
    expect(screen.queryByText('중원 정보')).toBeNull();
});

test('군주가 아니면 「보기만」 안내 · 외교권이 없으면 외교 서신 쓰기 대신 안내', async () => {
    vi.mocked(api.contacts).mockResolvedValue({ nation: [
        { mailbox: 9001, name: '[우리]', color: '#1', general: [[1, '나', 0]] },
        { mailbox: 9002, name: '[갑]', color: '#2', general: [[5, '상대', 1]] },
    ] } as never);
    render(<DiplomacyPage />);
    expect(await screen.findByRole('note')).toHaveTextContent('외교는 군주가 합니다');
    fireEvent.click(screen.getByRole('tab', { name: '외교 서신' }));
    fireEvent.click(await screen.findByRole('button', { name: '외교 서신 쓰기' }));
    expect(await screen.findByText('외교 서신은 군주 · 외교권자만 씁니다')).toBeInTheDocument();
    await waitFor(() => expect(api.mailboxRecent).toHaveBeenCalled());
});

test('외교권자(군주)는 안내 없이, 외교 서신 칸에서 받는 세력을 골라 쓸 수 있다', async () => {
    vi.mocked(api.frontInfo).mockResolvedValue(info({ officerLevel: 12 }) as never);
    vi.mocked(api.contacts).mockResolvedValue({ nation: [
        { mailbox: 9001, name: '[우리]', color: '#1', general: [[1, '나', 5]] },
        { mailbox: 9002, name: '[갑]', color: '#2', general: [[5, '상대', 1]] },
    ] } as never);
    render(<DiplomacyPage />);
    await screen.findByRole('list', { name: '세력별 관계' });
    expect(screen.queryByRole('note')).toBeNull();
    fireEvent.click(screen.getByRole('tab', { name: '외교 서신' }));
    fireEvent.click(await screen.findByRole('button', { name: '외교 서신 쓰기' }));
    const nations = await screen.findByRole('listbox', { name: '받는 세력' });
    expect(within(nations).getAllByRole('option').map((o) => o.textContent)).toEqual(['[갑]']);
});

test('재야는 외교 칸 · 외교 서신 모두 「세력이 없어」 안내, 관계 읽기 실패는 다시 시도', async () => {
    vi.mocked(api.frontInfo).mockResolvedValue(info({ nationId: 0 }) as never);
    vi.mocked(api.diplomacyConflict).mockResolvedValueOnce({ result: true, conflict: [], myNationID: 0, nations: [], diplomacyList: {} } as never);
    const { unmount } = render(<DiplomacyPage />);
    expect(await screen.findByText('세력이 없어 외교를 할 수 없습니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: '외교 서신' }));
    expect(await screen.findByText('소속 세력이 없어 외교 서신이 없습니다')).toBeInTheDocument();
    expect(api.mailboxRecent).not.toHaveBeenCalled();
    unmount();

    vi.mocked(api.diplomacyConflict).mockRejectedValueOnce(new Error('500'));
    render(<DiplomacyPage />);
    fireEvent.click(await screen.findByRole('button', { name: /다시 시도/ }));
    expect(await screen.findByRole('list', { name: '세력별 관계' })).toBeInTheDocument();
});
