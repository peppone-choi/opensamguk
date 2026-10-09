import { fireEvent, render, screen, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { CaptivesScreen } from '../components/people/CaptivesScreen';
import { api } from '../lib/api';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, isCampaignWorld: true, frontInfo: null }) }));
vi.mock('../lib/api', async () => ({
    // Intake guards stay real so release results go through the actual submit path.
    ...await vi.importActual<typeof import('../lib/api')>('../lib/api'),
    api: { peopleOptions: vi.fn(), captives: vi.fn(), releaseCaptive: vi.fn(), campaignRetinue: vi.fn(), campaignYuedan: vi.fn() },
}));
vi.mock('../components/campaign/HelpedInputAction', async () => {
    const { InputAction } = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
    return { HelpedInputAction: InputAction };
});

const hrefs = { flowBase: '/game/pep', yuedan: '/game/pep/retinue/yuedan' };
let viewport: ReturnType<typeof installViewport> | null = null;
const setMobile = (on: boolean) => { viewport?.restore(); viewport = installViewport(on ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });
beforeEach(() => {
    vi.clearAllMocks();
    setMobile(false);
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 30, costSum: 10, overCapacity: false, people: [], units: [] } as never);
    vi.mocked(api.campaignYuedan).mockResolvedValue({ status: 'READY', stamp: null, ranking: [], self: null } as never);
    vi.mocked(api.captives).mockResolvedValue({ available: true, targets: [] } as never);
    vi.mocked(api.peopleOptions).mockImplementation(async (inputId: string) => (inputId === 'action.employ'
        ? { inputId, available: true, targets: [{ generalId: 41, name: '석도', available: true }] }
        : { inputId, available: false, reason: '이미 이번 순에 인재탐색을 넣었습니다.', undiscoveredCount: 2, targets: [] }) as never);
});

test('데스크톱 — 인재 · 포로 두 칸, 등용은 대상 미리 채운 명령 흐름, 인재탐색 막힘은 서버 사유', async () => {
    render(<CaptivesScreen hrefs={hrefs} />);
    const talent = await screen.findByRole('region', { name: '등용할 수 있는 인재' });
    expect(talent).toHaveTextContent('찾지 못한 인물 2명');
    expect(within(talent).getByRole('button', { name: /인재탐색/ })).toHaveAttribute('aria-disabled', 'true');
    expect(talent).toHaveTextContent('이미 이번 순에 인재탐색을 넣었습니다.');
    fireEvent.click(within(talent).getByRole('option', { name: /석도/ }));
    fireEvent.click(within(talent).getByRole('button', { name: '석도 등용 — 명령 목록에 넣기' }));
    expect(push).toHaveBeenCalledWith('/game/pep?do=action.employ&target=general%3A41');
    const captive = screen.getByRole('region', { name: '잡은 포로' });
    expect(captive).toHaveTextContent('현재 확인된 포로가 없습니다.');
    expect(within(captive).queryByRole('button', { name: /석방/ })).toBeNull();
});

test('데스크톱 포로 — 화면은 구금 위치 이름, 설득 · 석방은 장수 ID 로 보내고 거절 뒤 다시 읽은 서버 행을 그린다', async () => {
    const row = { generalId: 52, name: '진궁', nationId: 2, nationName: '여포', heldProvinceId: 'P-31', actualProvinceId: 'P-31',
        capturedAt: { year: 198, month: 12, phase: 1 }, expiry: 'NONE', persuadeAvailable: true, releaseAvailable: true };
    let serverRow: Record<string, unknown> = { ...row, heldProvinceName: ' 하비 ' };
    vi.mocked(api.captives).mockImplementation(async () => ({ available: true, targets: [serverRow] }) as never);
    vi.mocked(api.releaseCaptive).mockImplementation(async () => {
        // The server moved the captive and no longer reports a name for the new custody province.
        serverRow = { ...row, heldProvinceId: 'P-40', actualProvinceId: 'P-40', heldProvinceName: null };
        return { status: 'BLOCKED', reason: '이미 처분이 진행 중인 포로입니다.' } as never;
    });
    render(<CaptivesScreen hrefs={hrefs} />);
    const captive = await screen.findByRole('region', { name: '잡은 포로' });
    const card = await within(captive).findByRole('region', { name: '진궁 포로 처분' });
    expect(card).toHaveTextContent('구금 위치 하비 · 포획 198년 12월 1순');
    expect(card).not.toHaveTextContent('P-31');

    fireEvent.click(within(card).getByRole('button', { name: /설득 — 순 고르기/ }));
    expect(push).toHaveBeenCalledWith('/game/pep?do=action.persuadeCaptive&target=general%3A52');
    fireEvent.click(within(card).getByRole('button', { name: '석방' }));
    expect(api.releaseCaptive).toHaveBeenCalledWith(7, 52);

    expect(await screen.findByText('이미 처분이 진행 중인 포로입니다.')).toBeVisible();
    const refreshedCaptive = screen.getByRole('region', { name: '잡은 포로' });
    expect(refreshedCaptive).not.toHaveTextContent('포로를 석방했습니다.');
    const reloaded = await within(refreshedCaptive).findByText(/구금 위치 이름 확인 불가/);
    expect(reloaded).not.toHaveTextContent('하비');
    expect(refreshedCaptive).not.toHaveTextContent('P-40');
    expect(api.releaseCaptive).toHaveBeenCalledTimes(1);
});

test('모바일 — 인재 · 포로 세그먼트, 등용 옵션 실패는 그 칸만', async () => {
    setMobile(true);
    vi.mocked(api.peopleOptions).mockImplementation(async (inputId: string) => {
        if (inputId === 'action.employ') throw new Error('500: x');
        return { inputId, available: true, targets: [] } as never;
    });
    render(<CaptivesScreen hrefs={hrefs} />);
    expect(await screen.findByText('등용할 인재를 불러오지 못했습니다')).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('radiogroup', { name: '보기' })).getByRole('radio', { name: /포로/ }));
    expect(await screen.findByText('현재 확인된 포로가 없습니다.')).toBeInTheDocument();
});

test('모바일 — 인재를 누르면 하단 시트 「{이름} — 등용」(코스트 서버 대기), 등용은 명령 흐름에서 순 고르기', async () => {
    setMobile(true);
    render(<CaptivesScreen hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('option', { name: /석도/ }));
    const sheet = await screen.findByRole('dialog', { name: '석도 — 등용' });
    expect(sheet).toHaveTextContent('서버 대기');
    // 확률은 서버가 주면 숫자, 안 주면 칸 없음(설계서 P-R05)
    expect(sheet).not.toHaveTextContent('확률');
    fireEvent.click(within(sheet).getByRole('button', { name: '등용 — 명령 흐름에서 순 고르기' }));
    expect(push).toHaveBeenCalledWith('/game/pep?do=action.employ&target=general%3A41');
});
