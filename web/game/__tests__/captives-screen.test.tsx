import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { setViewport } from './helpers/viewport';
import { CaptivesScreen } from '../components/people/CaptivesScreen';
import { api } from '../lib/api';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, isCampaignWorld: true, frontInfo: null }) }));
vi.mock('../lib/api', () => ({ api: { peopleOptions: vi.fn(), campaignRetinue: vi.fn(), campaignYuedan: vi.fn() } }));

const hrefs = { flowBase: '/game/pep', yuedan: '/game/pep/retinue/yuedan' };
const setMobile = (on: boolean) => setViewport(on ? 'mobile' : 'desktop');
beforeEach(() => {
    vi.clearAllMocks();
    setMobile(false);
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 30, costSum: 10, overCapacity: false, people: [], units: [] } as never);
    vi.mocked(api.campaignYuedan).mockResolvedValue({ status: 'READY', stamp: null, ranking: [], self: null } as never);
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
    expect(captive).toHaveTextContent('포로 목록 — 준비 중');
    expect(within(captive).queryByRole('button', { name: /석방/ })).toBeNull();
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
    expect(screen.getByText('포로 목록 — 준비 중')).toBeInTheDocument();
});
