import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { installViewport } from '@opensamguk/ui';
import { CourtScreen } from '../components/court/CourtScreen';
import { api, fetchGame } from '../lib/api';
import { card, closedBody, fakeRewardServer, type FakeState } from './fixtures/court-reward';

// 탭 서버만 바꿔 상사 소유자를 바꿀 수 있게 한다(옛 소유자 접수 결과 시험).
const sessionMock = vi.hoisted(() => ({ serverId: 'alpha' }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, serverId: sessionMock.serverId, frontInfo: { global: { year: 200, month: 3, turnPhase: 2 }, nation: { id: 1, name: '조조', color: '#123', capitalCityId: 3 } } }) }));
vi.mock('../lib/api', () => ({
    api: {
        dispatchPending: vi.fn(), dispatchOptions: vi.fn(), politicalConsentOptions: vi.fn(), campaignRetinue: vi.fn(), legacyCourtOptions: vi.fn(),
        courtDispatch: vi.fn(), courtReward: vi.fn(), courtLegacy: vi.fn(), courtDispatchReply: vi.fn(), courtPoliticalConsent: vi.fn(), mapPreview: vi.fn(),
    },
    // 상사 선택지 읽기(lib/api/court-reward)는 실제 검증기를 거친다 — 전송만 대역.
    fetchGame: vi.fn(),
    GameHttpError: class GameHttpError extends Error {
        constructor(readonly status: number, readonly code: string | null, message: string) { super(message); }
    },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

/** 상사 선택지 대역 서버 — 서버 카드가 기준(부 인물 카드는 초상만 보탠다). */
let rewardState: FakeState;
const rewardCard = (loyalty: number) => card(31, loyalty, { recipientGeneralId: 55, name: '문관' });
const serveReward = (path: string) =>
    Promise.resolve(new Response(JSON.stringify(fakeRewardServer(rewardState)(new URL(path, 'http://localhost'))), { status: 200 }));
const json = (body: unknown, status: number) => Promise.resolve(new Response(JSON.stringify(body), { status }));
/**
 * fetchGame 은 주소별로 가른다 — 상사 선택지만 `reward` 가 답하고(실제 검증기를 거친다), 도움말(`/api/help/**`, 거절 사유 도움말 등)과
 * 그 밖의 주소는 명시 404. 상사 응답을 도움말 주소에 돌려주면 도움말 화면이 계약 밖 본문을 받는다.
 */
function routeFetch(reward: (path: string) => Promise<Response>) {
    vi.mocked(fetchGame).mockImplementation((path: string) => {
        if (path.startsWith('/api/court/reward-options?')) return reward(path);
        return json({ error: { code: 'NOT_FOUND', message: 'not found' } }, 404);
    });
}

const REWARD_QUEUED = '상사를 접수했습니다 — 다음 개인 턴에 처리합니다.';
const phase = { year: 200, month: 3, phase: 2 };
const hrefs = { territory: '/game/pep/territory', office: '/game/pep/court/offices', diplomacy: '/game/pep/court/diplomacy' };

let viewport: ReturnType<typeof installViewport> | null = null;
afterEach(() => { viewport?.restore(); viewport = null; });

beforeEach(() => {
    vi.clearAllMocks();
    sessionMock.serverId = 'alpha';
    viewport = installViewport(1440);
    vi.mocked(api.dispatchPending).mockResolvedValue({ result: true, dispatches: [
        { dispatchId: 'd1', issuerId: 1, targetId: 7, countyId: 2, issuerLabel: '조조', countyLabel: '양적현', issuedAt: phase, dueAt: phase, status: 'PENDING' },
        { dispatchId: 'd2', issuerId: 7, targetId: 21, countyId: 3, targetLabel: '순욱', countyLabel: '허현', issuedAt: phase, dueAt: phase, status: 'PENDING' },
    ] } as never);
    vi.mocked(api.politicalConsentOptions).mockResolvedValue([] as never);
    vi.mocked(api.dispatchOptions).mockImplementation(async (_g: number, target?: number) => (target == null
        ? { result: true, targets: [{ generalId: 21, label: '순욱' }], counties: [] }
        : { result: true, targets: [{ generalId: 21, label: '순욱' }], counties: [{ countyId: 129, label: '양성현', available: true }] }) as never);
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 1, costSum: 0, overCapacity: false, units: [], people: [
        { retainerId: 31, generalId: 55, name: '문관', picture: null, imageServer: 0, loyalty: 60 },
        { retainerId: 32, generalId: null, name: '무명 공조', picture: null, imageServer: 0, loyalty: 40 },
    ] } as never);
    vi.mocked(api.mapPreview).mockResolvedValue({ cities: [{ id: 3, name: '허현' }] } as never);
    vi.mocked(api.legacyCourtOptions).mockImplementation(async (inputId: string) => (inputId === 'court.moveCapital'
        ? { inputId, available: true, choices: [{ label: '진류현 (41)', arguments: { cityId: 41 }, available: true }] }
        : { inputId, available: false, reason: '군주만 할 수 있습니다.', choices: [] }) as never);
    rewardState = { cards: [rewardCard(60)] };
    routeFetch(serveReward);
});

/** 금액을 적고 서버 미리 보기(250ms 뒤 읽기)를 기다린다. */
async function typeAmount(reward: HTMLElement, value: string, expected: string) {
    fireEvent.change(within(reward).getByRole('textbox', { name: '상사 금액' }), { target: { value } });
    await waitFor(() => expect(reward).toHaveTextContent(expected));
}

test('데스크톱 — 받은 요청 띠(대기만) · 내린 발령 · 조정 결정(서버 사유), 천도는 시트에서 id 꼬리 없이 접수', async () => {
    vi.mocked(api.courtLegacy).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CourtScreen hrefs={hrefs} />);
    const band = await screen.findByRole('region', { name: '받은 요청' });
    await waitFor(() => expect(band).toHaveTextContent('조조'));
    expect(await screen.findByRole('list', { name: '내린 발령' })).toHaveTextContent('순욱 → 허현');
    const decisions = screen.getByRole('region', { name: '조정 결정' });
    await waitFor(() => expect(within(decisions).getByRole('button', { name: /현 포기/ })).toHaveAttribute('aria-disabled', 'true'));
    expect(decisions).toHaveTextContent('군주만 할 수 있습니다.');
    fireEvent.click(within(decisions).getByRole('button', { name: '천도 — 고르기' }));
    const sheet = await screen.findByRole('region', { name: '천도' });
    fireEvent.click(within(sheet).getByRole('option', { name: '진류현' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이대로 접수' }));
    await waitFor(() => expect(vi.mocked(api.courtLegacy)).toHaveBeenCalledWith('court.moveCapital', 7, { cityId: 41 }));
    expect(await screen.findByText('천도를 접수했습니다 — 다음 개인 턴에 처리합니다.')).toBeInTheDocument();
});

test('새 발령 — 사람을 고르면 그 사람 기준 현 후보를 다시 받아 보낸다', async () => {
    vi.mocked(api.courtDispatch).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CourtScreen hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('button', { name: '새 발령' }));
    fireEvent.click(await screen.findByRole('option', { name: /순욱/ }));
    const list = await screen.findByRole('listbox', { name: '발령할 현' });
    expect(vi.mocked(api.dispatchOptions)).toHaveBeenCalledWith(7, 21);
    fireEvent.click(within(list).getByRole('option', { name: /양성현/ }));
    fireEvent.click(screen.getByRole('button', { name: '이 현으로 발령' }));
    await waitFor(() => expect(vi.mocked(api.courtDispatch)).toHaveBeenCalledWith(7, { targetGeneralId: 21, countyId: 129 }));
});

test.each(['503: Service Unavailable', 'Failed to fetch'])('사람별 현 조회 %s — 실패 안내 후 같은 사람으로 재시도해 접수', async (message) => {
    const dispatchOptions = vi.mocked(api.dispatchOptions);
    const initial = { result: true, targets: [{ generalId: 21, label: '순욱' }], counties: [] };
    dispatchOptions.mockImplementation(async (_g, target) => {
        if (target == null) return initial;
        throw new TypeError(message);
    });
    vi.mocked(api.courtDispatch).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CourtScreen hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('button', { name: '새 발령' }));
    fireEvent.click(await screen.findByRole('option', { name: /순욱/ }));
    const sheet = screen.getByRole('dialog', { name: '새 발령' });
    expect(await within(sheet).findByText('현 후보를 불러오지 못했습니다')).toBeInTheDocument();
    expect(sheet).not.toHaveTextContent('이 묶음에 후보가 없습니다');
    expect(sheet).not.toHaveTextContent(message);
    expect(within(sheet).queryByRole('listbox', { name: '발령할 현' })).toBeNull();
    expect(within(sheet).getByRole('button', { name: '이 현으로 발령' })).toHaveAttribute('aria-disabled', 'true');
    expect(api.courtDispatch).not.toHaveBeenCalled();
    dispatchOptions.mockImplementation(async (_g, target) => target == null ? initial
        : { ...initial, counties: [{ countyId: 129, label: '양성현', available: true }] });
    fireEvent.click(within(sheet).getByRole('button', { name: '다시 시도' }));
    const counties = await within(sheet).findByRole('listbox', { name: '발령할 현' });
    expect(within(sheet).getByRole('option', { name: /순욱/ })).toHaveAttribute('aria-selected', 'true');
    expect(dispatchOptions.mock.calls.filter((args) => args[1] === 21)).toHaveLength(2);
    fireEvent.click(within(counties).getByRole('option', { name: /양성현/ }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 현으로 발령' }));
    await waitFor(() => expect(api.courtDispatch).toHaveBeenCalledWith(7, { targetGeneralId: 21, countyId: 129 }));
});

test.each(['직접 거느린 장수가 아닙니다.', null])('사람별 현 조회 거절 — 서버 사유 %s를 빈 후보와 구분', async (reason) => {
    vi.mocked(api.dispatchOptions).mockImplementation(async (_g, target) => target == null
        ? { result: true, targets: [{ generalId: 21, label: '순욱' }], counties: [] }
        : { result: false, code: 'NOT_DIRECT_RETAINER', reason, targets: [], counties: [] });
    render(<CourtScreen hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('button', { name: '새 발령' }));
    fireEvent.click(await screen.findByRole('option', { name: /순욱/ }));
    const sheet = screen.getByRole('dialog', { name: '새 발령' });
    expect(await within(sheet).findByText(reason ?? '이 사람에게 발령할 수 없습니다.')).toBeInTheDocument();
    expect(sheet).not.toHaveTextContent('이 묶음에 후보가 없습니다');
    expect(within(sheet).getByRole('button', { name: '이 현으로 발령' })).toHaveAttribute('aria-disabled', 'true');
    expect(within(sheet).getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
});

test('사람별 현 조회가 정상 빈 목록일 때만 빈 후보 안내', async () => {
    vi.mocked(api.dispatchOptions).mockResolvedValue({ result: true, targets: [{ generalId: 21, label: '순욱' }], counties: [] });
    render(<CourtScreen hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('button', { name: '새 발령' }));
    fireEvent.click(await screen.findByRole('option', { name: /순욱/ }));
    const sheet = screen.getByRole('dialog', { name: '새 발령' });
    expect(await within(sheet).findByText('이 묶음에 후보가 없습니다')).toBeInTheDocument();
    expect(within(sheet).queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(within(sheet).getByRole('button', { name: '이 현으로 발령' })).toHaveAttribute('aria-disabled', 'true');
});

test('현 조회 중 사람을 바꾸면 이전 사람의 늦은 응답으로 후보를 표시하지 않는다', async () => {
    let finishFirst!: (value: Awaited<ReturnType<typeof api.dispatchOptions>>) => void;
    const first = new Promise<Awaited<ReturnType<typeof api.dispatchOptions>>>((resolve) => { finishFirst = resolve; });
    const targets = [{ generalId: 21, label: '순욱' }, { generalId: 22, label: '순유' }];
    vi.mocked(api.dispatchOptions).mockImplementation(async (_g, target) => target == null
        ? { result: true, targets, counties: [] }
        : target === 21 ? first : { result: true, targets, counties: [{ countyId: 130, label: '허현', available: true }] });
    render(<CourtScreen hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('button', { name: '새 발령' }));
    fireEvent.click(await screen.findByRole('option', { name: /순욱/ }));
    const sheet = screen.getByRole('dialog', { name: '새 발령' });
    expect(await within(sheet).findByText('현 후보를 불러오는 중…')).toBeInTheDocument();
    expect(within(sheet).queryByRole('listbox', { name: '발령할 현' })).toBeNull();
    expect(within(sheet).getByRole('button', { name: '이 현으로 발령' })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(within(sheet).getByRole('option', { name: /순유/ }));
    expect(await within(sheet).findByRole('option', { name: /허현/ })).toBeInTheDocument();
    finishFirst({ result: true, targets, counties: [{ countyId: 129, label: '양성현', available: true }] });
    await first;
    await waitFor(() => {
        expect(within(sheet).getByRole('option', { name: /허현/ })).toBeInTheDocument();
        expect(within(sheet).queryByRole('option', { name: /양성현/ })).toBeNull();
    });
});

test('모바일 — 조정 결정 목록(받은 요청 대기 수 · 원장 행 없는 것은 없음), 누르면 받은 요청 시트', async () => {
    viewport?.restore();
    viewport = installViewport(390);
    render(<CourtScreen hrefs={hrefs} />);
    const list = await screen.findByRole('list', { name: '조정 결정' });
    await waitFor(() => expect(within(list).getAllByRole('listitem')[0]).toHaveTextContent('응답 대기 1'));
    expect(list).toHaveTextContent('외교');
    fireEvent.click(within(within(list).getAllByRole('listitem')[0]).getByRole('button'));
    expect(await screen.findByTestId('incoming-requests')).toHaveTextContent('조조');
    // 시트는 자기 이름 · 닫기를 가진다(바깥 누르기만으로 닫히면 모바일에서 길이 안 보인다).
    const sheet = screen.getByRole('dialog', { name: '받은 요청' });
    fireEvent.click(within(sheet).getByRole('button', { name: '닫기' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

test('천도 칸에 지금 수도(지도 미리보기 이름), 못 받으면 「불러오지 못했습니다」', async () => {
    render(<CourtScreen hrefs={hrefs} />);
    expect(await within(await screen.findByRole('region', { name: '천도' })).findByText('지금 수도 — 허현')).toBeInTheDocument();
});

test('상사 — 서버 카드만 대상(부 인물 카드로 늘리지 않음), 인물 · 금액을 골라 같은 정규화 금액으로 retainerId 접수', async () => {
    vi.mocked(api.courtReward).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    await waitFor(() => expect(within(reward).getByRole('option', { name: /문관/ })).toBeInTheDocument());
    expect(within(reward).queryByRole('option', { name: /무명 공조/ })).toBeNull();
    fireEvent.click(within(reward).getByRole('option', { name: /문관/ }));
    expect(reward.querySelector('[data-reward-usable="known"]')).toHaveTextContent('금 5,120');
    await typeAmount(reward, ' 0100', '조회 시점 창고로 지급 가능 · 실행 때 다시 확인');
    expect(vi.mocked(fetchGame).mock.calls.map(([path]) => path)).toContain('/api/court/reward-options?generalId=7&retainerId=31&money=100');
    expect(vi.mocked(fetchGame).mock.calls.every(([path]) => !String(path).includes('money=0'))).toBe(true);
    fireEvent.click(within(reward).getByRole('button', { name: '상사 — 접수' }));
    await waitFor(() => expect(vi.mocked(api.courtReward)).toHaveBeenCalledWith(7, { retainerId: 31, money: 100 }));
    expect(await within(reward).findByText(REWARD_QUEUED)).toBeInTheDocument();
    // 데스크톱은 상사 칸 안에만 — 화면 위에 같은 알림을 겹쳐 그리지 않는다.
    expect(screen.getAllByText(REWARD_QUEUED)).toHaveLength(1);
});

/** 모바일 조정 결정 목록에서 상사 시트를 열고 금액까지 적는다. */
async function openMobileReward() {
    viewport?.restore();
    viewport = installViewport(390);
    const view = render(<CourtScreen hrefs={hrefs} />);
    const list = await screen.findByRole('list', { name: '조정 결정' });
    const item = within(list).getAllByRole('listitem').find((li) => li.textContent?.includes('직속 인물에게'))!;
    fireEvent.click(within(item).getByRole('button'));
    const sheet = await screen.findByRole('dialog', { name: '포상' });
    const reward = within(sheet).getByRole('region', { name: '상사' });
    fireEvent.click(await within(reward).findByRole('option', { name: /문관/ }));
    await typeAmount(reward, '150', '조회 시점 창고로 지급 가능');
    await waitFor(() => expect(api.dispatchPending).toHaveBeenCalled());
    return { ...view, sheet, reward };
}

test('모바일 상사 접수 — 다른 접수처럼 시트를 닫고, 알림은 화면 위에 한 줄, 조정 읽기를 한 번 다시 한다', async () => {
    vi.mocked(api.courtReward).mockResolvedValue({ status: 'AVAILABLE' } as never);
    const { reward } = await openMobileReward();
    const pendingReads = vi.mocked(api.dispatchPending).mock.calls.length;
    const retinueReads = vi.mocked(api.campaignRetinue).mock.calls.length;
    fireEvent.click(within(reward).getByRole('button', { name: '상사 — 접수' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(api.courtReward).toHaveBeenCalledWith(7, { retainerId: 31, money: 150 });
    expect(screen.getAllByText(REWARD_QUEUED)).toHaveLength(1);
    expect(screen.getByText(REWARD_QUEUED)).toHaveAttribute('role', 'status');
    // 조정 읽기 한 번(reload +1)에 dispatchPending 은 두 번 — 이 화면의 내린 발령 읽기와 받은 요청(lib/requests)이 각자 부른다.
    await waitFor(() => {
        expect(api.dispatchPending).toHaveBeenCalledTimes(pendingReads + 2);
        expect(api.campaignRetinue).toHaveBeenCalledTimes(retinueReads + 1);
    });
    await act(async () => { await new Promise((r) => setTimeout(r, 20)); });
    expect(api.dispatchPending).toHaveBeenCalledTimes(pendingReads + 2);
});

test.each([
    ['거절', () => Promise.resolve({ status: 'BLOCKED', reason: '이미 대기 중인 상사가 있습니다.' }), '이미 대기 중인 상사가 있습니다.'],
    ['보내기 실패', () => Promise.reject(new TypeError('Failed to fetch')), '보내지 못했습니다'],
])('모바일 상사 %s — 시트는 남고 칸 안에 알림, 시트를 닫으면 화면 위에 보이며 조정 읽기는 다시 하지 않는다', async (_name, outcome, text) => {
    vi.mocked(api.courtReward).mockImplementation(outcome as never);
    const { sheet, reward } = await openMobileReward();
    const pendingReads = vi.mocked(api.dispatchPending).mock.calls.length;
    fireEvent.click(within(reward).getByRole('button', { name: '상사 — 접수' }));
    expect(await within(reward).findByText(new RegExp(text))).toBeInTheDocument();
    expect(screen.getAllByText(new RegExp(text))).toHaveLength(1);
    fireEvent.click(within(sheet).getByRole('button', { name: '닫기' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByText(new RegExp(text))).toHaveAttribute('role', 'status');
    expect(api.dispatchPending).toHaveBeenCalledTimes(pendingReads);
});

test('모바일 — 옛 소유자의 늦은 상사 접수 성공은 시트를 닫거나 조정 읽기를 다시 하지 않고 알림도 남기지 않는다', async () => {
    let finish!: (o: unknown) => void;
    vi.mocked(api.courtReward).mockImplementation(() => new Promise((resolve) => { finish = resolve; }) as never);
    const { reward, rerender } = await openMobileReward();
    fireEvent.click(within(reward).getByRole('button', { name: '상사 — 접수' }));
    await waitFor(() => expect(api.courtReward).toHaveBeenCalledTimes(1));
    sessionMock.serverId = 'beta';
    rerender(<CourtScreen hrefs={hrefs} />);
    const pendingReads = vi.mocked(api.dispatchPending).mock.calls.length;
    await act(async () => { finish({ status: 'AVAILABLE' }); await new Promise((r) => setTimeout(r, 20)); });
    expect(screen.getByRole('dialog', { name: '포상' })).toBeInTheDocument();
    expect(screen.queryByText(REWARD_QUEUED)).toBeNull();
    expect(api.dispatchPending).toHaveBeenCalledTimes(pendingReads);
});

test('창고 금을 확인할 수 없거나 모자라 보여도 접수는 막지 않는다(실행이 다시 확인)', async () => {
    rewardState = { cards: [card(31, 60, { recipientGeneralId: 55, name: '문관', funding: { status: 'UNAVAILABLE', scope: null, noneReason: null,
        unavailableReason: 'WAREHOUSE_MALFORMED', usableMoney: null, warehouseCount: null } })] };
    vi.mocked(api.courtReward).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    fireEvent.click(await within(reward).findByRole('option', { name: /문관/ }));
    expect(reward.querySelector('[data-reward-usable="unavailable"]')).toHaveTextContent('확인할 수 없음');
    expect(reward.querySelector('[data-reward-usable="unavailable"]')).not.toHaveTextContent('금 0');
    await typeAmount(reward, '150', '창고 금을 확인할 수 없습니다 — 접수는 할 수 있고, 실행 때 다시 확인합니다.');
    fireEvent.click(within(reward).getByRole('button', { name: '상사 — 접수' }));
    await waitFor(() => expect(api.courtReward).toHaveBeenCalledWith(7, { retainerId: 31, money: 150 }));
});

test('부 인물 읽기가 실패해도 상사 칸은 서버 카드로 그린다(초상만 빠진다)', async () => {
    vi.mocked(api.campaignRetinue).mockRejectedValue(new Error('HTTP_502: Bad Gateway'));
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    expect(await within(reward).findByRole('option', { name: /문관/ })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Bad Gateway');
});

// ── 읽기가 정상이 아닐 때 — 빈 목록 · 「없습니다」로 보이면 안 된다(리뷰 #1128: 옛 OrdersPanel 의 campaignReadNotice 회귀) ──

test('상사 선택지 읽기 실패 — 상사 칸은 한국어 오류 + 다시 시도, 빈 목록 · 「없습니다」 · 서버 원문 없음', async () => {
    routeFetch(() => json({ error: { code: 'BAD_GATEWAY', message: 'Bad Gateway' } }, 502));
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    expect(await within(reward).findByText('상사 선택지를 불러오지 못했습니다')).toBeInTheDocument();
    expect(within(reward).queryByRole('listbox')).toBeNull();
    expect(reward).not.toHaveTextContent('없습니다');
    expect(document.body).not.toHaveTextContent('Bad Gateway');
    rewardState = { cards: [] };
    routeFetch(serveReward);
    fireEvent.click(within(reward).getByRole('button', { name: /다시/ }));
    expect(await within(reward).findByText('상사할 직속 인물 카드가 없습니다.')).toBeInTheDocument();
});

test('옛 형식 월드(UNSUPPORTED_WORLD_FORMAT) — 상사 칸은 서버 상태 한 줄, 빈 목록이 아니다', async () => {
    routeFetch(() => json(closedBody(7, 'UNSUPPORTED_WORLD_FORMAT', 'UNSUPPORTED_WORLD_FORMAT'), 200));
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    expect(await within(reward).findByText('이 서버는 지금 게임 규칙과 맞지 않습니다.')).toBeInTheDocument();
    expect(within(reward).queryByRole('listbox')).toBeNull();
    expect(reward).not.toHaveTextContent('상사할 직속 인물 카드가 없습니다.');
});

test('내린 발령 · 발령 옵션 읽기 실패 — 「내린 발령이 없습니다」 · 「사람 장수가 없습니다」를 그리지 않고 새 발령은 사유로 막힌다', async () => {
    vi.mocked(api.dispatchPending).mockRejectedValue(new Error('HTTP_500: Internal Server Error'));
    vi.mocked(api.dispatchOptions).mockRejectedValue(new Error('HTTP_500: Internal Server Error'));
    render(<CourtScreen hrefs={hrefs} />);
    const col = await screen.findByRole('region', { name: '발령' });
    expect(await within(col).findByText('내린 발령을 불러오지 못했습니다')).toBeInTheDocument();
    expect(col).not.toHaveTextContent('내린 발령이 없습니다.');
    expect(col).not.toHaveTextContent('발령할 사람 장수가 없습니다');
    await waitFor(() => expect(within(col).getByRole('button', { name: /새 발령/ })).toHaveAttribute('aria-disabled', 'true'));
    expect(col).toHaveTextContent('가능 여부를 불러오지 못했습니다');
});

test('주공이 아니면(발령 옵션 result:false) — 「사람 장수가 없습니다」 안내를 붙이지 않는다', async () => {
    vi.mocked(api.dispatchOptions).mockResolvedValue({ result: false, code: 'NOT_LORD', reason: '발령은 주공만 할 수 있습니다.', targets: [], counties: [] } as never);
    render(<CourtScreen hrefs={hrefs} />);
    const col = await screen.findByRole('region', { name: '발령' });
    await waitFor(() => expect(col).toHaveTextContent('발령은 주공만 할 수 있습니다.'));
    expect(col).not.toHaveTextContent('발령할 사람 장수가 없습니다');
});

test('조정 명령 옵션 읽기 실패 — 결정 단추를 「가능」으로 두지 않고 사유로 막는다', async () => {
    vi.mocked(api.legacyCourtOptions).mockRejectedValue(new Error('HTTP_503: Service Unavailable'));
    render(<CourtScreen hrefs={hrefs} />);
    const capital = await screen.findByRole('region', { name: '천도' });
    await waitFor(() => expect(within(capital).getByRole('button', { name: /천도/ })).toHaveAttribute('aria-disabled', 'true'));
    expect(capital).toHaveTextContent('가능 여부를 불러오지 못했습니다');
});

test('상사 범위 — 서버 상한 · 판정 그대로(사용자 결정 D16), 100 단위 나머지는 미리 보기 경고, 100 미만은 막는다', async () => {
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    await waitFor(() => expect(reward).toHaveTextContent('금 100당 충성 +1 · 한 번에 최대 +10'));
    fireEvent.click(await within(reward).findByRole('option', { name: /문관/ }));
    await typeAmount(reward, '5000', '이번에 충성을 올릴 수 있는 금은 최대 1,000입니다.');
    expect(within(reward).getByRole('button', { name: /상사 — 접수/ })).toHaveAttribute('aria-disabled', 'true');
    await typeAmount(reward, '150', '충성 +1 — 충성 없이 나가는 금 50');
    expect(within(reward).getByRole('status', { name: '상사 미리 보기' })).toHaveTextContent('충성 +1 — 충성 없이 나가는 금 50');
    await typeAmount(reward, '50', '금 100 이상이어야 충성이 오릅니다.');
    expect(within(reward).getByRole('button', { name: /상사 — 접수/ })).toHaveAttribute('aria-disabled', 'true');
});

test('충성 100인 인물 — 금 100까지만 접수(기록 · 결속 사건이 남는다), 넘으면 사유로 막는다', async () => {
    rewardState = { cards: [rewardCard(100)] };
    vi.mocked(api.courtReward).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    fireEvent.click(await within(reward).findByRole('option', { name: /문관/ }));
    await typeAmount(reward, '200', '충성은 이미 100입니다 — 금 100으로 상을 내린 기록만 남길 수 있습니다.');
    expect(within(reward).getByRole('button', { name: /상사 — 접수/ })).toHaveAttribute('aria-disabled', 'true');
    await typeAmount(reward, '100', '충성은 이미 100입니다 — 상을 내린 기록 · 결속 사건만 남습니다');
    expect(within(reward).getByRole('status', { name: '상사 미리 보기' })).toHaveTextContent('충성은 이미 100입니다 — 상을 내린 기록 · 결속 사건만 남습니다');
    fireEvent.click(within(reward).getByRole('button', { name: '상사 — 접수' }));
    await waitFor(() => expect(api.courtReward).toHaveBeenCalledWith(7, { retainerId: 31, money: 100 }));
    expect(await screen.findByText('상사를 접수했습니다 — 다음 개인 턴에 처리합니다.')).toBeInTheDocument();
});

test.each([
    ['Failed to fetch', () => Promise.reject(new TypeError('Failed to fetch')), null],
    ['NetworkError when attempting to fetch resource.', () => Promise.reject(new TypeError('NetworkError when attempting to fetch resource.')), null],
    ['Service Unavailable', async () => new Response(JSON.stringify({ error: { code: 'X', message: 'Service Unavailable' } }), { status: 503 }), '503'],
    ['UNEXPECTED_SHAPE', async () => new Response(JSON.stringify({ status: 'UNEXPECTED_SHAPE' }), { status: 200 }), null],
])('읽기 오류 %s — HTTP 번호만 보이고 오류 원문은 숨긴다', async (message, respond, code) => {
    routeFetch(respond);
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    expect(await within(reward).findByText('상사 선택지를 불러오지 못했습니다')).toBeInTheDocument();
    expect(reward).not.toHaveTextContent(message);
    expect(reward).not.toHaveTextContent('상사할 직속 인물 카드가 없습니다.');
    if (code) expect(within(reward).getByRole('button', { name: `오류 번호 ${code} 복사` })).toBeInTheDocument();
    else expect(within(reward).queryByRole('button', { name: /오류 번호/ })).toBeNull();
    expect(within(reward).getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
});

test('모바일 발령 — 시트에 내린 발령 목록(데스크톱과 같은 부품), 「새 발령」은 그 안에서', async () => {
    viewport?.restore();
    viewport = installViewport(390);
    render(<CourtScreen hrefs={hrefs} />);
    const list = await screen.findByRole('list', { name: '조정 결정' });
    const item = within(list).getAllByRole('listitem').find((li) => li.textContent?.includes('내 부 사람 장수를'))!;
    await waitFor(() => expect(within(item).getByRole('button')).not.toHaveAttribute('aria-disabled', 'true'));
    fireEvent.click(within(item).getByRole('button'));
    const sheet = await screen.findByRole('dialog', { name: '발령' });
    expect(await within(sheet).findByRole('list', { name: '내린 발령' })).toHaveTextContent('순욱 → 허현');
    fireEvent.click(within(sheet).getByRole('button', { name: '새 발령' }));
    expect(await screen.findByRole('dialog', { name: '새 발령' })).toBeInTheDocument();
});

test.each([
    [0, '1000', '1100', '1,000'],
    [95, '500', '600', '500'],
])('상사 경계 — 충성 %i: %s 허용, %s 거절(서버 상한 %s)', async (loyalty, ok, over, max) => {
    rewardState = { cards: [rewardCard(loyalty)] };
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    fireEvent.click(await within(reward).findByRole('option', { name: /문관/ }));
    await typeAmount(reward, ok, '조회 시점 창고로 지급 가능');
    expect(within(reward).getByRole('button', { name: '상사 — 접수' })).not.toHaveAttribute('aria-disabled', 'true');
    await typeAmount(reward, over, `이번에 충성을 올릴 수 있는 금은 최대 ${max}입니다.`);
    expect(within(reward).getByRole('button', { name: /상사 — 접수/ })).toHaveAttribute('aria-disabled', 'true');
});
