import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { expect, test, vi, beforeEach } from 'vitest';
import { LastTurnsDrawer } from '../components/campaign/LastTurnsDrawer';
import { api } from '../lib/api';

vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, frontInfo: { global: { year: 200, month: 3, turnPhase: 2 } } }) }));
vi.mock('../lib/api', () => ({ api: { campaignLastTurns: vi.fn() } }));

const hrefs = { court: '/game/pep/court?tab=orders', yuedan: '/game/pep/retinue/yuedan', records: '/game/pep/records' };
const data = {
    status: 'READY',
    turns: [
        { year: 200, month: 3, phase: 2, phaseLabel: '중순', entries: [{ kind: 'court.dispatchReceived', text: '관도 방면 군단장 발령이 왔습니다.' }, { kind: 'yuedan.assessed', text: '월단평 점수가 매겨졌습니다.' }] },
        { year: 200, month: 3, phase: 1, phaseLabel: '상순', entries: [] },
    ],
    nationSummary: [{ year: 200, month: 3, phase: 1, phaseLabel: '상순', kind: 'county.ownerChanged', text: '원소가 진류현을 차지했습니다.' }],
};

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.campaignLastTurns).mockResolvedValue(data as never);
});

test('데스크톱 — 손잡이(최근 순 기록 수) → 서랍: 내 12순 묶음 · 빈 순 한 줄 · 바로가기, Esc 로 닫고 손잡이로 초점', async () => {
    const onOpenChange = vi.fn();
    render(<LastTurnsDrawer mobile={false} hrefs={hrefs} onOpenChange={onOpenChange} />);
    const handle = await screen.findByRole('button', { name: '지난 순 — 최근 순 기록 2' });
    fireEvent.click(handle);
    expect(onOpenChange).toHaveBeenLastCalledWith(true);
    const drawer = screen.getByRole('region', { name: '지난 순' });
    expect(drawer).toHaveTextContent('200년 3월 상순 – 중순');
    const mine = within(drawer).getByRole('list', { name: '내 12순' });
    expect(mine).toHaveTextContent('200년 3월 중순');
    expect(mine).toHaveTextContent('3월 상순 — 기록 없음');
    expect(within(drawer).getByRole('link', { name: '조정에서 보기 →' })).toHaveAttribute('href', hrefs.court);
    expect(within(drawer).getByRole('link', { name: '월단평 열기 →' })).toHaveAttribute('href', hrefs.yuedan);
    expect(within(drawer).getByRole('link', { name: '기록 전체 보기 →' })).toHaveAttribute('href', hrefs.records);
    expect(within(drawer).getByRole('button', { name: '서랍 닫기(Esc)' })).toHaveFocus();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('region', { name: '지난 순' })).toBeNull();
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    await waitFor(() => expect(screen.getByRole('button', { name: '지난 순 — 최근 순 기록 2' })).toHaveFocus());
});

test('범위 · 분류 — 부 · 세력은 세력 요약(날짜), 분류를 고르면 그 분류만 · 없으면 그 분류 빈 문장', async () => {
    render(<LastTurnsDrawer mobile={false} hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('button', { name: /지난 순 — 최근 순 기록/ }));
    const drawer = screen.getByRole('region', { name: '지난 순' });
    fireEvent.click(within(within(drawer).getByRole('radiogroup', { name: '범위' })).getByRole('radio', { name: '부 · 세력' }));
    expect(within(drawer).getByRole('list', { name: '부 · 세력' })).toHaveTextContent('현 주인 바뀜 · 3월 상순');
    const filters = within(drawer).getByRole('group', { name: '분류' });
    fireEvent.click(within(filters).getByRole('button', { name: '조정' }));
    expect(within(filters).getByRole('button', { name: '조정' })).toHaveAttribute('aria-pressed', 'true');
    expect(drawer).toHaveTextContent('이 분류에는 최근 12순에 남은 기록이 없습니다.');
    fireEvent.click(within(within(drawer).getByRole('radiogroup', { name: '범위' })).getByRole('radio', { name: '전체' }));
    expect(within(drawer).getByRole('list', { name: '전체' })).toHaveTextContent('발령 도착');
    expect(within(drawer).getByRole('list', { name: '전체' })).not.toHaveTextContent('현 주인 바뀜');
});

test('오류 · 빈 — 첫 읽기 실패는 공용 오류 번호(원문 0) · 다시 시도, 기록이 없으면 첫 명령 안내', async () => {
    vi.mocked(api.campaignLastTurns).mockRejectedValueOnce(new Error('503: Service Unavailable'));
    const view = render(<LastTurnsDrawer mobile={false} hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('button', { name: '지난 순' }));
    expect(await screen.findByText('지난 순을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '오류 번호 503 복사' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Service Unavailable');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await within(screen.getByRole('region', { name: '지난 순' })).findByRole('list', { name: '내 12순' })).toBeInTheDocument();
    view.unmount();

    vi.mocked(api.campaignLastTurns).mockResolvedValueOnce({ status: 'READY', turns: [{ year: 200, month: 3, phase: 2, phaseLabel: '중순', entries: [] }], nationSummary: [] } as never);
    render(<LastTurnsDrawer mobile={false} hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('button', { name: '지난 순 — 최근 순 기록 0' }));
    expect(screen.getByRole('region', { name: '지난 순' })).toHaveTextContent('최근 12순에 남은 기록이 없습니다 — 첫 명령을 넣으면 여기에 결과가 남습니다.');
});

test('모바일 — 「지난 순 n」 칩 → 하단 시트(대화상자), 닫기', async () => {
    render(<LastTurnsDrawer mobile hrefs={hrefs} />);
    const chip = await screen.findByRole('button', { name: '지난 순 — 최근 순 기록 2' });
    expect(chip).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(chip);
    const sheet = await screen.findByRole('dialog', { name: '지난 순' });
    expect(within(sheet).getByRole('list', { name: '내 12순' })).toHaveTextContent('발령 도착');
    fireEvent.click(within(sheet).getByRole('button', { name: '닫기' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});
