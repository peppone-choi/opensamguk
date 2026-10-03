import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { installViewport } from '@opensamguk/ui';
import { YuedanScreen } from '../components/yuedan/YuedanScreen';
import { api } from '../lib/api';

vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, isCampaignWorld: true, frontInfo: null }) }));
vi.mock('../lib/api', () => ({ api: { campaignYuedan: vi.fn(), campaignRetinue: vi.fn() } }));

const row = (rank: number, generalId: number, name: string) => ({ rank, generalId, name, nationId: 1, nationName: '조조', nationColor: '#123', renown: 50 - rank });
let viewport: ReturnType<typeof installViewport> | null = null;
const setMobile = (on: boolean) => { viewport?.restore(); viewport = installViewport(on ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });
beforeEach(() => {
    vi.clearAllMocks();
    setMobile(false);
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 30, costSum: 36, overCapacity: true, units: [],
        people: [{ retainerId: 3, generalId: null, name: '무명 공조', picture: null, imageServer: 0, loyalty: 40, roleLabel: null, taskLabel: null,
            stats: null, cost: 13, aptitudes: null, bonds: [], departureOrder: 1, locationCityId: null }] } as never);
});

test('데스크톱 — 제목(도장 형식 밖이면 「이번 달」), 순위 · 경로 · 내 명망 · 이탈 순서', async () => {
    vi.mocked(api.campaignYuedan).mockResolvedValue({ status: 'READY', stamp: 'weird', ranking: [row(1, 1, '조조'), row(2, 7, '하후돈')],
        self: { generalId: 7, renown: 30, retinueCost: 36, overCapacity: true } } as never);
    render(<YuedanScreen retinueHref="/game/pep/retinue" />);
    expect(await screen.findByRole('heading', { name: '이번 달 월단평' })).toBeInTheDocument();
    expect(screen.getAllByRole('row')[2]).toHaveAttribute('data-me', 'true');
    expect(screen.getByTestId('my-renown')).toHaveTextContent('코스트 초과 — 이탈 판정 대상');
    expect(within(screen.getByRole('region', { name: '이탈 판정 순서' })).getByRole('listitem')).toHaveTextContent('무명 공조');
});

test('첫 월단평 전 · 순위 0 · 실패는 서로 다른 모양', async () => {
    vi.mocked(api.campaignYuedan).mockResolvedValueOnce({ status: 'NOT_ASSESSED', stamp: null, ranking: [], self: null } as never);
    const { unmount } = render(<YuedanScreen retinueHref="/r" />);
    expect(await screen.findByText('아직 첫 월단평이 없습니다')).toBeInTheDocument();
    unmount();
    vi.mocked(api.campaignYuedan).mockResolvedValueOnce({ status: 'READY', stamp: '0200-03', ranking: [], self: null } as never);
    const second = render(<YuedanScreen retinueHref="/r" />);
    expect(await screen.findByRole('heading', { name: '200년 3월 월단평' })).toBeInTheDocument();
    expect(screen.getByText('이번 달 순위에 오른 장수가 없습니다.')).toBeInTheDocument();
    second.unmount();
    vi.mocked(api.campaignYuedan).mockRejectedValueOnce(new Error('500: x')).mockResolvedValue({ status: 'READY', stamp: '0200-03', ranking: [row(1, 1, '조조')], self: null } as never);
    render(<YuedanScreen retinueHref="/r" />);
    expect(await screen.findByText('월단평을 불러오지 못했습니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('heading', { name: '200년 3월 월단평' })).toBeInTheDocument();
});

test('모바일 — 내 명망 먼저, 순위 · 이탈 순서 세그먼트', async () => {
    setMobile(true);
    vi.mocked(api.campaignYuedan).mockResolvedValue({ status: 'READY', stamp: '0200-03', ranking: [row(1, 7, '하후돈')],
        self: { generalId: 7, renown: 30, retinueCost: 36, overCapacity: true } } as never);
    render(<YuedanScreen retinueHref="/game/pep/retinue" />);
    const seg = await screen.findByRole('radiogroup', { name: '보기' });
    expect(screen.getByTestId('my-renown')).toHaveTextContent('코스트 초과');
    expect(screen.getByRole('list', { name: '월단평 순위' })).toBeInTheDocument();
    // 모바일도 오르는 · 떨어지는 경로를 본다(데스크톱과 같은 부품).
    expect(screen.getByText('오르는 경로')).toBeInTheDocument();
    expect(screen.getByText('떨어지는 경로')).toBeInTheDocument();
    fireEvent.click(within(seg).getByRole('radio', { name: '이탈 순서' }));
    expect(screen.getByRole('list', { name: '이탈 판정 순서' })).toHaveTextContent('무명 공조');
});

test('부 읽기 실패 — 이탈 판정 순서는 「상한 안」 같은 빈 상태가 아니라 한국어 오류 + 다시 시도, 영어 원문 없음', async () => {
    vi.mocked(api.campaignYuedan).mockResolvedValue({ status: 'READY', stamp: '0200-03', ranking: [row(1, 7, '하후돈')],
        self: { generalId: 7, renown: 30, retinueCost: 36, overCapacity: true } } as never);
    vi.mocked(api.campaignRetinue).mockRejectedValue(new TypeError('Failed to fetch'));
    render(<YuedanScreen retinueHref="/game/pep/retinue" />);
    const dep = await screen.findByRole('region', { name: '이탈 판정 순서' });
    expect(await within(dep).findByText('이탈 판정 순서를 불러오지 못했습니다')).toBeInTheDocument();
    expect(dep).not.toHaveTextContent('상한 안');
    expect(dep).not.toHaveTextContent('받지 못했습니다.');
    expect(document.body).not.toHaveTextContent('Failed to fetch');
    expect(within(dep).queryByRole('button', { name: /오류 번호/ })).toBeNull();
    expect(within(dep).getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
});

test('월단평 읽기 실패의 오류 번호는 HTTP 세 자리만 — 「Not Found」 원문은 화면에 없다', async () => {
    vi.mocked(api.campaignYuedan).mockRejectedValue(new Error('404: Not Found'));
    render(<YuedanScreen retinueHref="/r" />);
    expect(await screen.findByText('월단평을 불러오지 못했습니다')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Not Found');
    expect(screen.getByRole('button', { name: '오류 번호 404 복사' })).toBeInTheDocument();
});
