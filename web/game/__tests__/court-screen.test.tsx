import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { installViewport } from '@opensamguk/ui';
import { CourtScreen } from '../components/court/CourtScreen';
import { api } from '../lib/api';

vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, frontInfo: { global: { year: 200, month: 3, turnPhase: 2 }, nation: { id: 1, name: '조조', color: '#123', capitalCityId: 3 } } }) }));
vi.mock('../lib/api', () => ({
    api: {
        dispatchPending: vi.fn(), dispatchOptions: vi.fn(), politicalConsentOptions: vi.fn(), campaignRetinue: vi.fn(), legacyCourtOptions: vi.fn(),
        courtDispatch: vi.fn(), courtReward: vi.fn(), courtLegacy: vi.fn(), courtDispatchReply: vi.fn(), courtPoliticalConsent: vi.fn(), mapPreview: vi.fn(),
    },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const phase = { year: 200, month: 3, phase: 2 };
const hrefs = { territory: '/game/pep/territory', office: '/game/pep/court/offices', diplomacy: '/game/pep/court/diplomacy' };

let viewport: ReturnType<typeof installViewport> | null = null;
afterEach(() => { viewport?.restore(); viewport = null; });

beforeEach(() => {
    vi.clearAllMocks();
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
});

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

test('상사 — 장수 카드만 대상, 인물 · 금액을 골라 retainerId 로 접수(창고 잔액 상한은 화면이 짓지 않음)', async () => {
    vi.mocked(api.courtReward).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    await waitFor(() => expect(within(reward).getByRole('option', { name: /문관/ })).toBeInTheDocument());
    expect(within(reward).queryByRole('option', { name: /무명 공조/ })).toBeNull();
    expect(reward.querySelector('[data-waiting="reward-usable"]')).toHaveTextContent('준비 중');
    fireEvent.click(within(reward).getByRole('option', { name: /문관/ }));
    fireEvent.change(within(reward).getByRole('textbox', { name: '상사 금액' }), { target: { value: '100' } });
    fireEvent.click(within(reward).getByRole('button', { name: '상사 — 접수' }));
    await waitFor(() => expect(vi.mocked(api.courtReward)).toHaveBeenCalledWith(7, { retainerId: 31, money: 100 }));
});

// ── 읽기가 정상이 아닐 때 — 빈 목록 · 「없습니다」로 보이면 안 된다(리뷰 #1128: 옛 OrdersPanel 의 campaignReadNotice 회귀) ──

test('부 인물 읽기 실패 — 상사 칸은 한국어 오류 + 다시 시도, 빈 목록 · 「없습니다」 · 서버 원문 없음', async () => {
    vi.mocked(api.campaignRetinue).mockRejectedValue(new Error('HTTP_502: Bad Gateway'));
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    expect(await within(reward).findByText('부 인물을 불러오지 못했습니다')).toBeInTheDocument();
    expect(within(reward).queryByRole('listbox')).toBeNull();
    expect(reward).not.toHaveTextContent('없습니다');
    expect(document.body).not.toHaveTextContent('Bad Gateway');
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 1, costSum: 0, overCapacity: false, units: [], people: [] } as never);
    fireEvent.click(within(reward).getByRole('button', { name: /다시/ }));
    expect(await within(reward).findByText('상사할 직속 인물 카드가 없습니다.')).toBeInTheDocument();
});

test('옛 형식 월드(UNSUPPORTED_WORLD_FORMAT) — 상사 칸은 서버 상태 한 줄, 빈 목록이 아니다', async () => {
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'UNSUPPORTED_WORLD_FORMAT', renown: 0, costSum: 0, overCapacity: false, units: [], people: [] } as never);
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

test('상사 범위 — 충성을 올릴 수 있는 만큼까지만(사용자 결정 D16), 100 단위 나머지는 미리 보기 경고, 100 미만은 막는다', async () => {
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    expect(reward).toHaveTextContent('금 100당 충성 +1 · 한 번에 최대 +10');
    fireEvent.click(await within(reward).findByRole('option', { name: /문관/ }));
    const amount = within(reward).getByRole('textbox', { name: '상사 금액' });
    fireEvent.change(amount, { target: { value: '5000' } });
    expect(within(reward).getByRole('button', { name: /상사 — 접수/ })).toHaveAttribute('aria-disabled', 'true');
    expect(reward).toHaveTextContent('이번에 충성을 올릴 수 있는 금은 최대 1,000입니다.');
    fireEvent.change(amount, { target: { value: '150' } });
    expect(within(reward).getByRole('status', { name: '상사 미리 보기' })).toHaveTextContent('충성 +1 — 충성 없이 나가는 금 50');
    fireEvent.change(amount, { target: { value: '50' } });
    expect(within(reward).getByRole('button', { name: /상사 — 접수/ })).toHaveAttribute('aria-disabled', 'true');
    expect(reward).toHaveTextContent('금 100 이상이어야 충성이 오릅니다.');
});

test('충성 100인 인물 — 금 100까지만 접수(기록 · 결속 사건이 남는다), 넘으면 사유로 막는다', async () => {
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 1, costSum: 0, overCapacity: false, units: [], people: [
        { retainerId: 31, generalId: 55, name: '문관', picture: null, imageServer: 0, loyalty: 100 },
    ] } as never);
    vi.mocked(api.courtReward).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    fireEvent.click(await within(reward).findByRole('option', { name: /문관/ }));
    const amount = within(reward).getByRole('textbox', { name: '상사 금액' });
    fireEvent.change(amount, { target: { value: '200' } });
    expect(within(reward).getByRole('button', { name: /상사 — 접수/ })).toHaveAttribute('aria-disabled', 'true');
    expect(reward).toHaveTextContent('충성은 이미 100입니다 — 금 100으로 상을 내린 기록만 남길 수 있습니다.');
    fireEvent.change(amount, { target: { value: '100' } });
    expect(within(reward).getByRole('status', { name: '상사 미리 보기' })).toHaveTextContent('충성은 이미 100입니다 — 상을 내린 기록 · 결속 사건만 남습니다');
    fireEvent.click(within(reward).getByRole('button', { name: '상사 — 접수' }));
    await waitFor(() => expect(api.courtReward).toHaveBeenCalledWith(7, { retainerId: 31, money: 100 }));
    expect(await screen.findByText('상사를 접수했습니다 — 다음 개인 턴에 처리합니다.')).toBeInTheDocument();
});

test.each([
    ['Failed to fetch', null],
    ['NetworkError when attempting to fetch resource.', null],
    ['503: Service Unavailable', '503'],
    ['5030: invalid response', null],
])('읽기 오류 %s — HTTP 번호만 보이고 오류 원문은 숨긴다', async (message, code) => {
    vi.mocked(api.campaignRetinue).mockRejectedValue(new TypeError(message));
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    expect(await within(reward).findByText('부 인물을 불러오지 못했습니다')).toBeInTheDocument();
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
])('상사 경계 — 충성 %i: %s 허용, %s 거절(최대 %s)', async (loyalty, ok, over, max) => {
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 1, costSum: 0, overCapacity: false, units: [], people: [
        { retainerId: 31, generalId: 55, name: '문관', picture: null, imageServer: 0, loyalty },
    ] } as never);
    render(<CourtScreen hrefs={hrefs} />);
    const reward = await screen.findByRole('region', { name: '상사' });
    fireEvent.click(await within(reward).findByRole('option', { name: /문관/ }));
    const amount = within(reward).getByRole('textbox', { name: '상사 금액' });
    fireEvent.change(amount, { target: { value: ok } });
    expect(within(reward).getByRole('button', { name: '상사 — 접수' })).not.toHaveAttribute('aria-disabled', 'true');
    fireEvent.change(amount, { target: { value: over } });
    expect(within(reward).getByRole('button', { name: /상사 — 접수/ })).toHaveAttribute('aria-disabled', 'true');
    expect(reward).toHaveTextContent(`이번에 충성을 올릴 수 있는 금은 최대 ${max}입니다.`);
});
