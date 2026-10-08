// 명령 흐름(P-W02) — 설계서 §2.1 상태 유지 규칙이 화면에서 지켜지는지.
import { act, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import CommandFlow from '../components/command-flow/CommandFlow';
import { api } from '../lib/api';
import { submitCommandAndAwaitResult } from '../lib/commandSubmit';
import { __resetHelpCache } from '../lib/help';
import HelpLinkScope from '../components/shell/HelpLinkScope';

// jsdom에서 부품 · 목록을 그리고 가짜 서버 응답을 기다린다 — CI · 로컬 병렬 부하에서 기본 1초 대기 창 · 5초 한도가 모자란다
// (부하 평균 557에서 「찾을 수 없음」으로 재현, 응답을 1.2초 늦추면 같은 실패가 나고 창을 5초로 늘리면 통과 — 2026-10-01).
configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
// api 만 흉내 낸다 — 도움말 읽기(lib/help)는 진짜 fetchGame 으로 아래 fetch 흉내에 닿는다.
vi.mock('../lib/api', async (importOriginal) => ({
    ...(await importOriginal<typeof import('../lib/api')>()),
    api: {
        reservedCommands: vi.fn(), command: vi.fn(), travelOptions: vi.fn(), deployOptions: vi.fn(), campaignSieges: vi.fn(),
        fieldOptions: vi.fn(), personalOptions: vi.fn(), peopleOptions: vi.fn(), mapPreview: vi.fn(), gameConst: vi.fn(),
    },
}));
vi.mock('../lib/commandSubmit', () => ({ submitCommandAndAwaitResult: vi.fn() }));
// 사유 시트의 도움말(K7 useReasonHelp)은 지금 주소 · 쿼리를 두고 서랍을 연다 — 라우터 흉내.
const nav = vi.hoisted(() => ({ pathname: '/game/pep', search: 'do=action.farm' }));
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }));
vi.mock('next/navigation', () => ({
    usePathname: () => nav.pathname,
    useSearchParams: () => new URLSearchParams(nav.search),
    useRouter: () => router,
}));
/** 도움말 실패 사유 응답(`/api/help/failures/<code>?inputId=…`). 없으면 원장에 없는 사유(404). */
const failures = new Map<string, unknown>();

const ring = (filled: number[]) => ({
    result: true, generalId: 1,
    slots: filled.map((turnIdx) => ({ turnIdx, action: 'action.farm', brief: '', arg: {} })),
});

beforeEach(() => {
    vi.clearAllMocks();
    __resetHelpCache();
    failures.clear();
    const respond = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
        const m = /\/api\/game\/api\/help\/failures\/([^?]+)\?inputId=(.+)$/.exec(url);
        const hit = m ? failures.get(`${decodeURIComponent(m[1])}@${decodeURIComponent(m[2])}`) : undefined;
        return hit ? respond(200, hit) : respond(404, { error: { code: 'FAILURE_REASON_NOT_FOUND', message: '' } });
    }));
    vi.mocked(api.reservedCommands).mockResolvedValue(ring([0, 1]) as never);
    vi.mocked(api.mapPreview).mockResolvedValue({ cities: [{ id: 9, name: '진류', displayName: '진류현' }] } as never);
    vi.mocked(api.gameConst).mockResolvedValue({ gameUnitConst: [{ id: 1100, name: '창병' }] } as never);
    vi.mocked(api.travelOptions).mockImplementation(async (inputId) => ({
        inputId, available: true,
        destinations: [
            { provinceId: 'P-1', name: '영천', available: true },
            { provinceId: 'P-2', name: '양적', available: inputId === 'action.move', reason: '강행으로는 갈 수 없습니다' },
        ],
    }));
    vi.mocked(api.fieldOptions).mockResolvedValue({ inputId: 'action.farm', available: true, countyName: '허현' });
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => { await submit(); return { status: 'reserved' } as never; });
});

// 역할 · 이름 조회는 jsdom에서 비싸다 — 명령 행은 data-input-id, 후보는 칸(data-arg-key) 안에서만 찾는다.
const flow = () => screen.getByTestId('command-flow');
const pressedSlot = () => flow().querySelector('[data-turn-idx][aria-pressed="true"]');
const cmd = (inputId: string) => {
    const el = flow().querySelector(`ul button[data-input-id="${inputId}"]`);
    if (!el) throw new Error(`명령 행 없음: ${inputId}`);
    return el as HTMLElement;
};
/** 제출 단추(InputAction) — 상태 속성으로 찾고, 글자는 따로 확인한다. */
const submitButton = () => waitFor(() => {
    const el = flow().querySelector('[data-input-status]');
    if (!el) throw new Error('제출 단추 없음');
    return el as HTMLElement;
});
const slotButton = (label: string) => flow().querySelector(`[aria-label="${label}"]`);
const place = async (name: RegExp, selected?: boolean) => {
    const field = await waitFor(() => {
        const el = flow().querySelector('[data-arg-key="destinationProvinceId"]');
        if (!el) throw new Error('목적지 칸 없음');
        return el as HTMLElement;
    });
    return within(field).findByRole('option', selected === undefined ? { name } : { name, selected });
};

test('열면 다음 빈 순을 고르고, 채운 순은 명령 이름으로 보인다', async () => {
    render(<CommandFlow generalId={1} onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    expect(slotButton('01순 — 농지개간')).not.toBeNull();
    expect(slotButton('03순 — 빈 순')).toHaveAttribute('aria-pressed', 'true');
});

test('12순을 읽기 전에는 주소에 임시 순을 적지 않는다', async () => {
    let resolve: (value: ReturnType<typeof ring>) => void = () => {};
    vi.mocked(api.reservedCommands).mockReturnValue(new Promise((r) => { resolve = r; }) as never);
    const onLocationChange = vi.fn();
    render(<CommandFlow generalId={1} onClose={vi.fn()} onLocationChange={onLocationChange} />);
    expect(onLocationChange).not.toHaveBeenCalled();
    await act(async () => resolve(ring([0, 1])));
    await waitFor(() => expect(onLocationChange).toHaveBeenCalledWith({ inputId: null, slot: 2 }));
    expect(onLocationChange.mock.calls.every(([location]) => location.slot === 2)).toBe(true);
});

test.each(['500: Internal Server Error', 'TypeError: Failed to fetch'])('옵션 읽기 실패 원문 대신 한국어와 오류 번호를 보인다: %s', async (message) => {
    vi.mocked(api.fieldOptions).mockRejectedValue(new Error(message));
    render(<CommandFlow generalId={1} initialInputId="action.farm" onClose={vi.fn()} />);
    const text = message.startsWith('500') ? '서버에서 문제가 생겼습니다. 잠시 뒤 다시 해 보세요.' : '서버에 닿지 않습니다. 인터넷 연결을 확인하고 다시 해 보세요.';
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(screen.queryByText(message)).not.toBeInTheDocument();
    if (message.startsWith('500')) expect(screen.getByRole('button', { name: '오류 번호 500 복사' })).toBeInTheDocument();
});

test.each([
    ['503: Service Unavailable', '서버가 잠시 응답하지 않습니다. 잠시 뒤 다시 해 보세요.'],
    ['TypeError: Failed to fetch', '서버에 닿지 않습니다. 인터넷 연결을 확인하고 다시 해 보세요.'],
    ['예약을 받지 못했습니다', '예약을 받지 못했습니다.'],
])('예약 실패 원문을 공용 오류 문구로 바꾼다: %s', async (message, text) => {
    vi.mocked(submitCommandAndAwaitResult).mockRejectedValue(new Error(message));
    render(<CommandFlow generalId={1} initialInputId="action.farm" onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    fireEvent.click(await submitButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(text);
});

test('명령을 바꿔도 초안이 남고, 같은 종류 목적지는 이어받는다', async () => {
    render(<CommandFlow generalId={1} onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()).not.toBeNull());
    fireEvent.click(cmd('action.move'));
    fireEvent.click(await place(/영천/));
    fireEvent.click(cmd('action.farm'));
    expect(await screen.findByText('일어나는 곳: 허현')).toBeInTheDocument();
    fireEvent.click(cmd('action.forcedMarch'));
    expect(await screen.findByText(/이어받았습니다/)).toBeInTheDocument();
    expect(await place(/영천/, true)).toBeInTheDocument();
});

test('이어받은 곳이 새 명령에서 안 되면 비우고 한 줄 알린다', async () => {
    render(<CommandFlow generalId={1} onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()).not.toBeNull());
    fireEvent.click(cmd('action.move'));
    fireEvent.click(await place(/양적/));
    fireEvent.click(cmd('action.forcedMarch'));
    expect(await screen.findByText(/고를 수 없는 곳이라 비웠습니다/)).toBeInTheDocument();
});

test('빈 칸이면 보내지 않고 알린다 · 채우면 지금 순에 예약하고 다음 빈 순으로 간다', async () => {
    render(<CommandFlow generalId={1} onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    fireEvent.click(cmd('action.move'));
    const submit = await submitButton();
    expect(submit).toHaveTextContent('03순에 예약');
    expect(submit).toHaveAttribute('data-input-id', 'action.move');
    fireEvent.click(submit);
    expect(await screen.findByText('「어디로」을 고르세요.')).toBeInTheDocument();
    expect(api.command).not.toHaveBeenCalled();

    fireEvent.click(await place(/영천/));
    vi.mocked(api.reservedCommands).mockResolvedValue({ ...ring([0, 1]), slots: [...ring([0, 1]).slots,
        { turnIdx: 2, action: 'saved.move', brief: '이동', arg: { destCityID: 9 } },
    ] } as never);
    fireEvent.click(await submitButton());
    await waitFor(() => expect(api.command).toHaveBeenCalledWith('action.move', { destinationProvinceId: 'P-1' }, 1, 2));
    // 현재 선택은 영천이어도 성공과 띠는 서버에 저장된 진류현을 표시한다.
    expect(await screen.findByText('「진류현으로 이동」 — 03순에 예약했습니다.')).toBeInTheDocument();
    await waitFor(() => expect(slotButton('03순 — 진류현으로 이동')).not.toBeNull());
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('3'));
    // 닫히지 않고 초안이 남는다.
    expect(await place(/영천/, true)).toBeInTheDocument();
});

test('채운 순에 예약하면 바꾸기를 한 번 묻는다', async () => {
    render(<CommandFlow generalId={1} initialSlot={0} initialInputId="action.farm" onClose={vi.fn()} />);
    expect(await screen.findByTestId('slot-reserved')).toHaveTextContent('01순 지금 예약: 농지개간');
    const submit = await submitButton();
    expect(submit).toHaveTextContent('01순에 예약');
    fireEvent.click(submit);
    expect(await screen.findByText('01순을 바꿉니다')).toBeInTheDocument();
    expect(api.command).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '바꾸기' }));
    await waitFor(() => expect(api.command).toHaveBeenCalledWith('action.farm', {}, 1, 0));
});

test('준비 중 명령은 목록에 남고, 서버를 부르지 않으며 예약 단추가 「준비 중」이다', async () => {
    render(<CommandFlow generalId={1} onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()).not.toBeNull());
    const row = cmd('action.retire');
    expect(within(row).getByText('준비 중')).toBeInTheDocument();
    fireEvent.click(row);
    expect(await screen.findByText('아직 열리지 않은 명령입니다')).toBeInTheDocument();
    expect(api.personalOptions).not.toHaveBeenCalled();
    const submit = await submitButton();
    expect(submit).toHaveTextContent('03순에 예약');
    expect(submit).toHaveAttribute('aria-disabled', 'true');
    expect(submit).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
});

test('옵션이 막으면 서버 사유를 그대로 보인다', async () => {
    vi.mocked(api.fieldOptions).mockResolvedValue({ inputId: 'action.farm', available: false, code: 'OUTSIDE_CITY', reason: '성 밖에 있습니다' });
    render(<CommandFlow generalId={1} initialInputId="action.farm" onClose={vi.fn()} />);
    await waitFor(async () => expect(await submitButton()).toHaveAttribute('data-input-status', 'BLOCKED'));
    expect(flow().querySelector('[data-reason-code="OUTSIDE_CITY"]')).not.toBeNull();
    expect(screen.getAllByText('성 밖에 있습니다').length).toBeGreaterThan(0);
});

test('막힌 예약 단추의 사유 시트 — 「이렇게 하면 됩니다」와 도움말(지금 주소 · 쿼리를 두고 서랍을 연다)', async () => {
    vi.mocked(api.fieldOptions).mockResolvedValue({ inputId: 'action.farm', available: false, code: 'OUTSIDE_CITY', reason: '성 밖에 있습니다' });
    failures.set('OUTSIDE_CITY@action.farm', {
        schemaVersion: 1, reason: 'OUTSIDE_CITY', reviewState: 'DRAFT', explanation: '성 밖에 있습니다',
        recoveryAdvice: '성 안으로 들어간 뒤 다시 예약하세요.', relatedTopicIds: [],
    });
    // 서랍을 여는 법은 /game 레이아웃(HelpLinkScope)이 준다 — useReasonHelp 는 라우터를 직접 부르지 않는다(K7 10-03).
    render(<HelpLinkScope><CommandFlow generalId={1} initialInputId="action.farm" onClose={vi.fn()} /></HelpLinkScope>);
    await waitFor(async () => expect(await submitButton()).toHaveAttribute('data-input-status', 'BLOCKED'));
    fireEvent.click(await submitButton());
    expect(await screen.findByText('성 안으로 들어간 뒤 다시 예약하세요.')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /^도움말 — / });
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    fireEvent(link, click);
    expect(click.defaultPrevented).toBe(true); // 기본 링크(`?help=…`, 다른 쿼리를 지움) 대신
    expect(router.push).toHaveBeenCalledWith('/game/pep?do=action.farm&help=input%3Aaction.farm%21OUTSIDE_CITY', { scroll: false });
});

test('서버가 제출을 거절하면 그 code · reason으로 막고 닫지 않는다 — 칸이나 순을 바꾸면 풀린다', async () => {
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (submit) => {
        await submit();
        return { status: 'rejected', reason: '전투 중이라 새 명령을 받지 않습니다', code: 'BATTLE_LOCKED' } as never;
    });
    const onClose = vi.fn();
    render(<CommandFlow generalId={1} initialInputId="action.farm" onClose={onClose} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    fireEvent.click(await submitButton());
    await waitFor(async () => expect(await submitButton()).toHaveAttribute('data-input-status', 'BLOCKED'));
    const sheet = flow().querySelector('[data-reason-code="BATTLE_LOCKED"]') as HTMLElement;
    expect(sheet).not.toBeNull();
    // 거절되면 사유 시트가 열린 채로 뜬다(누르지 않아도).
    expect(within(sheet).getByRole('dialog')).toHaveTextContent('전투 중이라 새 명령을 받지 않습니다');
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(slotButton('04순 — 빈 순')!);
    await waitFor(async () => expect(await submitButton()).toHaveAttribute('data-input-status', 'AVAILABLE'));
});

test('옵션을 읽는 중에도 단추는 누를 수 있다 — 인자 없는 명령은 보내고, 인자가 필요하면 기다리라고 한다', async () => {
    vi.mocked(api.fieldOptions).mockReturnValue(new Promise(() => {}));
    vi.mocked(api.travelOptions).mockReturnValue(new Promise(() => {}));
    render(<CommandFlow generalId={1} initialInputId="action.farm" onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    expect(await submitButton()).toHaveAttribute('data-input-status', 'AVAILABLE');
    fireEvent.click(await submitButton());
    await waitFor(() => expect(api.command).toHaveBeenCalledWith('action.farm', {}, 1, 2));

    vi.mocked(api.command).mockClear();
    fireEvent.click(cmd('action.move'));
    expect(await submitButton()).toHaveAttribute('data-input-status', 'AVAILABLE');
    fireEvent.click(await submitButton());
    expect(await screen.findByText('선택지를 불러오는 중입니다 — 잠시 뒤 다시 눌러 주세요.')).toBeInTheDocument();
    expect(api.command).not.toHaveBeenCalled();
});

test('Esc로 닫는다 — 보내는 중에는 닫지 않는다', async () => {
    const onClose = vi.fn();
    let release: () => void = () => {};
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(() => new Promise((r) => { release = () => r({ status: 'reserved' } as never); }));
    render(<CommandFlow generalId={1} initialInputId="action.farm" onClose={onClose} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    vi.mocked(api.reservedCommands).mockResolvedValue(ring([0, 1, 2]) as never);
    fireEvent.click(await submitButton());
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
    const otherSlot = slotButton('04순 — 빈 순')!;
    expect(otherSlot).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(otherSlot);
    expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2');
    await act(async () => release());
    await screen.findByText(/예약했습니다/);
    expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('3');
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
});

test('예약 readback 실패/다른 명령은 현재 선택을 저장된 목적지인 것처럼 성공 안내에 넣지 않는다', async () => {
    render(<CommandFlow generalId={1} initialInputId="action.move" onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    fireEvent.click(await place(/영천/));
    vi.mocked(api.reservedCommands).mockRejectedValue(new Error('503'));
    fireEvent.click(await submitButton());
    expect(await screen.findByText('03순 예약은 접수했습니다 — 저장된 명령 문장을 아직 확인하지 못했습니다.')).toBeInTheDocument();
    expect(screen.queryByText(/「영천.*예약했습니다/)).toBeNull();
});

test('한글 조합 중 Esc는 무시하고, 찾기칸의 Esc는 먼저 검색어만 비운다', async () => {
    const onClose = vi.fn();
    render(<CommandFlow generalId={1} onClose={onClose} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    const search = screen.getByRole('searchbox', { name: '명령 찾기' });
    fireEvent.change(search, { target: { value: '이동' } });
    fireEvent.keyDown(search, { key: 'Escape', isComposing: true });
    fireEvent.keyDown(search, { key: 'Escape', keyCode: 229 });
    expect(search).toHaveValue('이동');
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(search, { key: 'Escape' });
    expect(search).toHaveValue('');
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(search, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
});

test('사유 시트 밖에 포커스가 있어도 Esc는 시트만 닫고 명령 초안은 남긴다', async () => {
    vi.mocked(submitCommandAndAwaitResult).mockResolvedValue({ status: 'rejected', code: 'BATTLE_LOCKED', reason: '전투 중입니다' } as never);
    const onClose = vi.fn();
    render(<CommandFlow generalId={1} initialInputId="action.move" onClose={onClose} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    fireEvent.click(await place(/영천/));
    fireEvent.click(await submitButton());
    expect(await screen.findByRole('dialog')).toHaveTextContent('전투 중입니다');
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(await place(/영천/, true)).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
});

test('「여기로 명령」 — 받은 장소를 받는 명령이 위로 오고, 처음 고른 명령이 그 장소를 이어받는다', async () => {
    render(<CommandFlow generalId={1} initialTarget={{ kind: 'province', id: 'P-1' }} onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()).not.toBeNull());
    const rows = within(screen.getByRole('list', { name: '명령' })).getAllByRole('button');
    expect(rows.slice(0, 3).map((r) => r.getAttribute('data-input-id'))).toEqual(['action.deploy', 'action.move', 'action.forcedMarch']);
    fireEvent.click(rows[1]);
    expect(await place(/영천/, true)).toBeInTheDocument();
});

test('공성에서 고른 현은 숫자 targetCountyId로 접수하고 저장된 현의 이름으로 읽는다', async () => {
    vi.mocked(api.campaignSieges).mockResolvedValue({ status: 'READY', sieges: [{
        countyId: 9, countyName: '진류현', status: 'ACTIVE', besieger: { generalId: 1 }, canAct: true,
        canAssault: true, assaultCode: null, assaultReason: null,
    }] } as never);
    let resolveInitialSlots!: (value: ReturnType<typeof ring>) => void;
    vi.mocked(api.reservedCommands).mockReturnValueOnce(new Promise((resolve) => { resolveInitialSlots = resolve; }) as never);
    render(<CommandFlow generalId={1} initialInputId="action.assault" initialTarget={{ kind: 'county', id: '9' }} onClose={vi.fn()} />);
    const county = await waitFor(() => {
        const field = flow().querySelector('[data-arg-key="targetCountyId"]');
        if (!field) throw new Error('공격할 현 칸 없음');
        return field as HTMLElement;
    });
    expect(within(county).getByRole('option', { name: /진류현/ })).toHaveAttribute('aria-selected', 'true');
    expect(pressedSlot()).toBeNull();
    resolveInitialSlots(ring([0, 1]));
    await waitFor(() => expect(pressedSlot()).toHaveAttribute('data-turn-idx', '2'));
    vi.mocked(api.reservedCommands).mockResolvedValue({ ...ring([0, 1]), slots: [...ring([0, 1]).slots,
        { turnIdx: 2, action: 'action.assault', brief: '강공', arg: { targetCountyId: 9 } },
    ] } as never);
    fireEvent.click(await submitButton());
    await waitFor(() => expect(api.command).toHaveBeenCalledWith('action.assault', { targetCountyId: 9 }, 1, 2));
    expect(api.command).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('「진류현 공격」 — 03순에 예약했습니다.')).toBeInTheDocument();
});

// 흐름이 열린 채로 같은 작전실에서 주소만 바뀌는 경우(첫걸음 · 도움말 「이 명령 하러 가기」 · 지도 「여기로 명령」, K7 10-02 발견).
// 작전실의 syncFlow 는 쿼리가 바뀔 때마다 새 함수가 된다 — 시험도 새 함수를 넘겨 그 경우를 흉내 낸다.
const submitInput = async () => (await submitButton()).getAttribute('data-input-id');

test('흐름이 열린 채로 주소의 명령(?do=)이 바깥에서 바뀌면 그 명령으로 가고, 주소를 옛 명령으로 되돌리지 않는다', async () => {
    const first = vi.fn();
    const { rerender } = render(<CommandFlow generalId={1} initialInputId="action.farm" onClose={vi.fn()} onLocationChange={first} />);
    await waitFor(() => expect(first).toHaveBeenCalledWith({ inputId: 'action.farm', slot: 2 }));
    const second = vi.fn();
    rerender(<CommandFlow generalId={1} initialInputId="action.move" onClose={vi.fn()} onLocationChange={second} />);
    await waitFor(async () => expect(await submitInput()).toBe('action.move'));
    expect(second.mock.calls.map(([l]) => l.inputId)).not.toContain('action.farm');
});

test('흐름 안에서 바꾼 명령은 다른 쿼리 변화(syncFlow 새 함수)로 되돌아가지 않는다', async () => {
    const first = vi.fn();
    const { rerender } = render(<CommandFlow generalId={1} initialInputId="action.farm" onClose={vi.fn()} onLocationChange={first} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    fireEvent.click(cmd('action.move'));
    await waitFor(async () => expect(await submitInput()).toBe('action.move'));
    const second = vi.fn();
    rerender(<CommandFlow generalId={1} initialInputId="action.farm" onClose={vi.fn()} onLocationChange={second} />);
    await new Promise((r) => setTimeout(r, 0));
    expect(await submitInput()).toBe('action.move');
    expect(second.mock.calls.map(([l]) => l.inputId)).not.toContain('action.farm');
});

// #1202 리뷰: 바깥 `?do=` 전환도 흐름 안 전환(목록에서 고르기)과 같이 앞 명령의 안내 · 표시를 비운다.
test('바깥 ?do= 로 명령이 바뀌면 앞 명령의 「비웠습니다」 안내가 남지 않는다', async () => {
    const { rerender } = render(<CommandFlow generalId={1} initialInputId="action.move" onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    fireEvent.click(await place(/양적/));
    fireEvent.click(cmd('action.forcedMarch'));
    expect(await screen.findByText(/고를 수 없는 곳이라 비웠습니다/)).toBeInTheDocument();
    rerender(<CommandFlow generalId={1} initialInputId="action.farm" onClose={vi.fn()} />);
    await waitFor(async () => expect(await submitInput()).toBe('action.farm'));
    expect(screen.queryByText(/고를 수 없는 곳이라 비웠습니다/)).not.toBeInTheDocument();
});

test('바깥 ?do= 로 명령이 바뀌면 앞 명령의 「빠짐」 표시가 같은 이름 칸에 남지 않는다', async () => {
    const { rerender } = render(<CommandFlow generalId={1} initialInputId="action.move" onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    await place(/영천/);
    fireEvent.click(await submitButton());
    expect(await screen.findByText('「어디로」을 고르세요.')).toBeInTheDocument();
    rerender(<CommandFlow generalId={1} initialInputId="action.forcedMarch" onClose={vi.fn()} />);
    await waitFor(async () => expect(await submitInput()).toBe('action.forcedMarch'));
    await place(/영천/);
    expect(screen.queryByText('「어디로」을 고르세요.')).not.toBeInTheDocument();
});

test('흐름 안에서 두 번 바꾼 뒤 앞서 적은 주소가 늦게 그려져도 되돌아가지 않고, 그 뒤 진짜 바깥 전환은 받는다', async () => {
    const first = vi.fn();
    const { rerender } = render(<CommandFlow generalId={1} initialInputId="action.farm" onClose={vi.fn()} onLocationChange={first} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    fireEvent.click(cmd('action.move'));
    await waitFor(() => expect(first).toHaveBeenCalledWith({ inputId: 'action.move', slot: 2 }));
    fireEvent.click(cmd('action.forcedMarch'));
    await waitFor(() => expect(first).toHaveBeenCalledWith({ inputId: 'action.forcedMarch', slot: 2 }));
    // 라우터가 앞서 적은 `do=action.move` 를 늦게 그린다.
    const late = vi.fn();
    rerender(<CommandFlow generalId={1} initialInputId="action.move" onClose={vi.fn()} onLocationChange={late} />);
    await new Promise((r) => setTimeout(r, 0));
    expect(await submitInput()).toBe('action.forcedMarch');
    expect(late.mock.calls.map(([l]) => l.inputId)).not.toContain('action.move');
    // 주소가 지금 명령을 따라잡는다.
    rerender(<CommandFlow generalId={1} initialInputId="action.forcedMarch" onClose={vi.fn()} onLocationChange={late} />);
    await new Promise((r) => setTimeout(r, 0));
    expect(await submitInput()).toBe('action.forcedMarch');
    // 그 뒤의 진짜 바깥 전환(도움말 「이 명령 하러 가기」 등)은 받는다.
    rerender(<CommandFlow generalId={1} initialInputId="action.move" onClose={vi.fn()} onLocationChange={late} />);
    await waitFor(async () => expect(await submitInput()).toBe('action.move'));
});

test('흐름이 열린 채로 바깥에서 대상(target)이 오면 지금 명령의 그 칸에 넣는다', async () => {
    const { rerender } = render(<CommandFlow generalId={1} initialInputId="action.move" onClose={vi.fn()} />);
    expect(await place(/영천/, false)).toBeInTheDocument();
    rerender(<CommandFlow generalId={1} initialInputId="action.move" initialTarget={{ kind: 'province', id: 'P-1' }} onClose={vi.fn()} />);
    expect(await place(/영천/, true)).toBeInTheDocument();
});

// 옛 CommandModal 의 「개인 행동 → 출병」 자리 — 작전실 명령 흐름이 옛 출병 폼과 같은 읽기(`/api/deploy/options`) · 같은 인자로 보낸다.
// 옛 출병 폼(DeployForm)은 부르는 화면이 없어 지웠다(K6, 10-03).
test('출병 — 부곡과 목적지를 고르면 옛 출병 폼과 같은 인자(bugokIds · destinationProvinceId)로 지금 순에 보낸다', async () => {
    vi.mocked(api.deployOptions).mockResolvedValue({
        available: true, maxReservedTurns: 12,
        bugoks: [{ id: 7, name: '일곱', troops: 20, available: true }, { id: 9, name: '아홉', troops: 10, available: false, reason: '이미 나가 있습니다' }],
        destinations: [{ provinceId: 'p1', name: '영천', available: true }],
    });
    render(<CommandFlow generalId={1} onClose={vi.fn()} />);
    await waitFor(() => expect(pressedSlot()?.getAttribute('data-turn-idx')).toBe('2'));
    fireEvent.click(cmd('action.deploy'));
    const units = await waitFor(() => {
        const el = flow().querySelector('[data-arg-key="bugokIds"]');
        if (!el) throw new Error('부곡 칸 없음');
        return el as HTMLElement;
    });
    fireEvent.click(await within(units).findByRole('option', { name: /일곱/ }));
    expect(within(units).getByRole('option', { name: /아홉/ })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(await place(/영천/));
    fireEvent.click(await submitButton());
    await waitFor(() => expect(api.command).toHaveBeenCalledWith('action.deploy', { bugokIds: [7], destinationProvinceId: 'p1' }, 1, 2));
});

afterEach(() => { vi.unstubAllGlobals(); });


test('귀환은 이번 이웃과 근무성을 구분해 그리고 빈 인자로 예약한다', async () => {
    vi.mocked(api.travelOptions).mockResolvedValue({ inputId: 'action.return', available: true,
        workplace: { provinceId: 'HOME', name: '진류 근무성', countyId: 9 },
        destinations: [{ provinceId: 'NEXT', name: '영천 이웃', available: true, estimatedTurns: 1,
            arrivesThisTurn: true, reachability: 'THIS_TURN', distanceMm: 200_000_000, costMm: 500_000_000 }] });
    render(<CommandFlow generalId={1} initialInputId="action.return" onClose={vi.fn()} />);
    expect(await screen.findByText(/^이번 도착지: 영천 이웃/)).toHaveTextContent('예상 1순');
    expect(screen.getByText('근무성: 진류 근무성')).toBeInTheDocument();
    expect(flow().querySelector('[data-arg-key="destinationProvinceId"]')).toBeNull();
    expect(flow().textContent).not.toMatch(/370|500|일어나는 곳: 진류/);
    fireEvent.click(await submitButton());
    await waitFor(() => expect(api.command).toHaveBeenCalledWith('action.return', {}, 1, 2));
});
