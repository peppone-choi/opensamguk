import { fireEvent, render, screen, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { SupplyScreen } from '../components/territory/SupplyScreen';
import { api } from '../lib/api';

const push = vi.fn();
let nation: unknown = { id: 1, name: '조조' };
// 결정 단추가 도움말 고리(useReasonHelp → useOpenHelp)를 쓴다 — 지금 경로 · 쿼리 · router 흉내.
vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/territory/supply', useSearchParams: () => new URLSearchParams(), useRouter: () => ({ push, replace: vi.fn(), back: vi.fn() }) }));
vi.mock('../lib/campaign-session', () => ({
    useGameSession: () => ({ generalId: 7, isCampaignWorld: true, frontInfo: { global: { year: 200, month: 3, turnPhase: 1 }, general: { name: '하후돈' }, nation } }),
}));
vi.mock('../lib/api', () => ({ api: { warehouses: vi.fn() } }));

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const hrefs = { transport: '/game/pep?do=action.transport' };
let viewport: ReturnType<typeof installViewport> | null = null;
const setMobile = (on: boolean) => { viewport?.restore(); viewport = installViewport(on ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });
beforeEach(() => { vi.clearAllMocks(); setMobile(false); nation = { id: 1, name: '조조' }; });

test('데스크톱 — 재고 표 · 끊긴 곳 · 녹봉 대기 · 물자조달(명령 흐름으로)', async () => {
    vi.mocked(api.warehouses).mockResolvedValue({ status: 'READY', warehouses: [
        { cityId: 3, name: '허현', commanderyName: null, isCapital: true, supplied: true, stock: { ...zero, money: 900 } },
        { cityId: 9, name: '윤씨현', commanderyName: null, isCapital: false, supplied: false, stock: zero },
    ] } as never);
    render(<SupplyScreen hrefs={hrefs} />);
    const table = await screen.findByRole('region', { name: '창고별 재고' });
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('허현수도900');
    expect(screen.getByRole('region', { name: '끊긴 곳' })).toHaveTextContent('윤씨현은 수도와 끊겨 제 창고만 씁니다.');
    expect(screen.getByText('지급 전망 — 준비 중')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '물자조달 — 명령 목록에 넣기' }));
    expect(push).toHaveBeenCalledWith(hrefs.transport);
});

test('비었을 때 — 재야는 「지금 선 현 창고만」 안내, 서버 알림은 빈 목록이 아니라 알림', async () => {
    nation = null;
    vi.mocked(api.warehouses).mockResolvedValueOnce({ status: 'READY', warehouses: [] } as never);
    const first = render(<SupplyScreen hrefs={hrefs} />);
    expect(await screen.findByText('창고가 있는 현이 없습니다')).toBeInTheDocument();
    expect(screen.getByText('소속이 없으면 지금 선 현 창고만 보입니다.')).toBeInTheDocument();
    first.unmount();
    vi.mocked(api.warehouses).mockResolvedValueOnce({ status: 'UNSUPPORTED_WORLD_FORMAT', warehouses: [] } as never);
    render(<SupplyScreen hrefs={hrefs} />);
    expect(await screen.findByText('이 서버는 지금 게임 규칙과 맞지 않습니다.')).toBeInTheDocument();
    expect(screen.queryByText('창고가 있는 현이 없습니다')).toBeNull();
});

test('모바일 — 창고 · 끊긴 곳 · 위험 세그먼트', async () => {
    setMobile(true);
    vi.mocked(api.warehouses).mockResolvedValue({ status: 'READY', warehouses: [
        { cityId: 9, name: '윤씨현', commanderyName: null, isCapital: false, supplied: false, stock: zero },
    ] } as never);
    render(<SupplyScreen hrefs={hrefs} />);
    const seg = await screen.findByRole('radiogroup', { name: '보기' });
    expect(within(seg).getAllByRole('radio').map((r) => r.textContent)).toEqual(['창고1', '끊긴 곳1', '위험']);
    fireEvent.click(within(seg).getByRole('radio', { name: /위험/ }));
    expect(screen.getByText('지급 전망 — 준비 중')).toBeInTheDocument();
});

test('첫 읽기 실패 — 공용 오류 번호만(서버 원문 0) · 다시 시도, 지도 고리는 주소가 없으면 그리지 않는다', async () => {
    vi.mocked(api.warehouses).mockRejectedValueOnce(new Error('503: Service Unavailable'))
        .mockResolvedValueOnce({ status: 'READY', warehouses: [
            { cityId: 9, name: '윤씨현', commanderyName: null, isCapital: false, supplied: false, stock: zero },
        ] } as never);
    render(<SupplyScreen hrefs={hrefs} />);
    expect(await screen.findByText('창고를 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '오류 번호 503 복사' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Service Unavailable');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('region', { name: '끊긴 곳' })).toHaveTextContent('윤씨현');
    expect(screen.queryByRole('link', { name: /지도에서 보기/ })).toBeNull();
});
