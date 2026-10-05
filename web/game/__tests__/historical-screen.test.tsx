// 역사 인물 고르기(P-E03) — 계약(#1137) 고정 자료로: 목록 · 거르기 · 더 보기 · 고르기 · 접수 → 결과(CREATED · REJECTED) · 생성 대기.
import { StrictMode } from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCEPTED, HISTORICAL_PAGE_1, HISTORICAL_PAGE_2, MAP_NATIONS, RESULT_CREATED, RESULT_PENDING, RESULT_REJECTED } from './fixtures/creation';

const mocks = vi.hoisted(() => ({ push: vi.fn(), refresh: vi.fn(), viewport: null as string | null }));
vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: mocks.push }),
    usePathname: () => '/game/pep/create/historical',
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ serverId: 'pep', refresh: mocks.refresh }) }));
vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, children, ...rest }: { slug: string; children: React.ReactNode }) => <a href={`/game/pep/${slug}`} {...rest}>{children}</a>,
}));
vi.mock('@opensamguk/ui', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@opensamguk/ui')>()),
    useViewportClass: () => mocks.viewport,
}));

import HistoricalScreen from '@/components/entry/HistoricalScreen';
import { newClientRequestId } from '@/hooks/useCreationRequest';

type Handler = (url: URL, init?: RequestInit) => Response | Promise<Response>;
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let routes: Record<string, Handler>;
let calls: { url: URL; init?: RequestInit }[];
let results: unknown[];

function installFetch() {
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
        const url = new URL(input, 'http://x');
        calls.push({ url, init });
        const key = `${init?.method ?? 'GET'} ${url.pathname}`;
        const handler = routes[key];
        return handler ? handler(url, init) : json(404, {});
    }));
}

const historicalCalls = () => calls.filter((c) => c.url.pathname === '/api/game/api/generals/creation/historical');

async function settle(ms = 0) {
    await act(async () => { await new Promise((r) => setTimeout(r, ms)); });
}

beforeEach(() => {
    calls = [];
    results = [RESULT_PENDING, RESULT_CREATED];
    mocks.push.mockReset();
    mocks.refresh.mockReset();
    mocks.viewport = null;
    routes = {
        'GET /api/game/api/generals/creation/historical': (url) => json(200, url.searchParams.get('cursor') === '103' ? HISTORICAL_PAGE_2 : HISTORICAL_PAGE_1),
        'GET /api/game/api/map/preview': () => json(200, { nations: MAP_NATIONS, cities: [], year: 200, month: 3 }),
        'POST /api/game/api/generals/creation': () => json(202, ACCEPTED),
        'GET /api/game/api/generals/creation/req-1': () => json(200, results.length > 1 ? results.shift() : results[0]),
    };
    installFetch();
});
afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
});

describe('목록 · 거르기', () => {
    it('카드 — 이름 · 소속(세력표 이름) · 다섯 능력, 고를 수 없는 카드는 잠김 + 사유, 계약에 없는 칸은 알림 한 줄', async () => {
        render(<HistoricalScreen />);
        await settle();
        const first = new URL(historicalCalls()[0].url.toString());
        expect(Object.fromEntries(first.searchParams)).toEqual({ sort: 'ID_ASC', limit: '50', status: 'AVAILABLE' });

        const list = screen.getByRole('listbox', { name: '역사 인물' });
        const options = within(list).getAllByRole('option');
        expect(options).toHaveLength(3);
        expect(options[0]).toHaveTextContent('하후돈');
        expect(options[0]).toHaveTextContent('조조 소속');
        expect(options[0]).toHaveTextContent('통 90 · 무 89 · 지 58 · 정 70 · 매 80');
        expect(options[2]).toHaveTextContent('재야');
        expect(options[1]).toHaveAttribute('aria-disabled', 'true');
        expect(options[1]).toHaveTextContent('지금 고를 수 없음');
        const reasonId = options[1].getAttribute('aria-describedby')!.split(' ').pop()!;
        expect(document.getElementById(reasonId)).toHaveTextContent('이 인물은 지금 선택할 수 없습니다. 목록을 다시 읽어 주세요.');
        expect(screen.getByText('역할 · 누구의 부 · 본관은 서버가 아직 주지 않아 카드에 없습니다.')).toBeInTheDocument();
        expect(screen.getByText(/역할\(주공 · 중간직 · 소속 장수 · 예비 주공 · 재야\)로 거르기는 서버가 아직 주지 않습니다/)).toBeInTheDocument();
    });

    it('더 보기 — 커서로 이어 붙이고, 끝이면 단추가 없다', async () => {
        render(<HistoricalScreen />);
        await settle();
        fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
        await settle();
        expect(historicalCalls().at(-1)!.url.searchParams.get('cursor')).toBe('103');
        const options = within(screen.getByRole('listbox', { name: '역사 인물' })).getAllByRole('option');
        expect(options.map((o) => o.querySelector('[class*="cardName"]')?.textContent)).toEqual(['하후돈', '순욱', '허저', '제갈량']);
        expect(options[3]).toHaveTextContent('아직 등장 안 함');
        expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull();
    });

    it('소속 「재야」 = nation=0, 상태 「전체」 = status 없음, 이름 찾기는 q', async () => {
        render(<HistoricalScreen />);
        await settle();
        fireEvent.click(screen.getByRole('radio', { name: '재야' }));
        await settle();
        expect(historicalCalls().at(-1)!.url.searchParams.get('nation')).toBe('0');
        fireEvent.click(screen.getByRole('radio', { name: '조조' }));
        await settle();
        expect(historicalCalls().at(-1)!.url.searchParams.get('nation')).toBe('1');
        fireEvent.click(within(screen.getByRole('radiogroup', { name: '상태' })).getByRole('radio', { name: '전체' }));
        await settle();
        expect(historicalCalls().at(-1)!.url.searchParams.has('status')).toBe(false);
        fireEvent.change(screen.getByRole('searchbox', { name: '이름으로 찾기' }), { target: { value: '허' } });
        await settle(350);
        expect(historicalCalls().at(-1)!.url.searchParams.get('q')).toBe('허');
    });

    it('서버가 503(정책 닫힘)이면 「생성 대기」 + 서버 문장', async () => {
        routes['GET /api/game/api/generals/creation/historical'] = () => json(503, { error: { code: 'CREATION_POLICY_UNAVAILABLE', message: '장수 만들기가 아직 열리지 않았습니다. 잠시 후 다시 확인해 주세요.' } });
        render(<HistoricalScreen />);
        await settle();
        expect(screen.getByText('장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중')).toBeInTheDocument();
        expect(screen.getByText('장수 만들기가 아직 열리지 않았습니다. 잠시 후 다시 확인해 주세요.')).toBeInTheDocument();
    });
});

describe('고르기 · 접수 · 결과', () => {
    async function pickAndStart() {
        render(<HistoricalScreen />);
        await settle();
        fireEvent.click(within(screen.getByRole('listbox', { name: '역사 인물' })).getAllByRole('option')[0]);
        const detail = screen.getByRole('region', { name: '고른 인물' });
        expect(within(detail).getByText('조조 소속')).toBeInTheDocument();
        expect(within(detail).getByText('무력').nextElementSibling).toHaveTextContent('89');
        expect(within(detail).getByText(/들어갈 자리 · 함께 시작할 인물 · 거병 조건 · 시작 위치 · 자리 한도는 서버가 아직 주지 않습니다/)).toBeInTheDocument();
        await act(async () => { fireEvent.click(within(detail).getByRole('button', { name: '이 인물로 시작' })); });
    }

    it('접수(202) → 만드는 중 → PENDING · CREATED 면 세션을 다시 읽고 입구(작전실)로', async () => {
        await pickAndStart();
        const post = calls.find((c) => c.init?.method === 'POST')!;
        const body = JSON.parse(String(post.init!.body));
        expect(body.expectedWorldId).toBe(1);
        expect(typeof body.clientRequestId).toBe('string');
        expect(body.choice).toEqual({ kind: 'HISTORICAL', historicalGeneralId: 101 });
        expect(await screen.findByText('장수를 만드는 중입니다')).toBeInTheDocument();
        await settle(1600);
        expect(mocks.push).not.toHaveBeenCalled();
        await settle(1600);
        expect(mocks.refresh).toHaveBeenCalled();
        expect(mocks.push).toHaveBeenCalledWith('/game/pep');
    }, 10_000);

    it('StrictMode(개발 모드 effect 두 번)에서도 접수 뒤 결과를 확인해 넘어간다', async () => {
        render(<StrictMode><HistoricalScreen /></StrictMode>);
        await settle();
        fireEvent.click(within(screen.getByRole('listbox', { name: '역사 인물' })).getAllByRole('option')[0]);
        await act(async () => { fireEvent.click(within(screen.getByRole('region', { name: '고른 인물' })).getByRole('button', { name: '이 인물로 시작' })); });
        expect(await screen.findByText('장수를 만드는 중입니다')).toBeInTheDocument();
        await settle(1600);
        await settle(1600);
        expect(mocks.push).toHaveBeenCalledWith('/game/pep');
    }, 10_000);

    it('결과 REJECTED 면 서버 문장 그대로, 「다른 인물 고르기」로 목록에 돌아간다', async () => {
        results = [RESULT_REJECTED];
        await pickAndStart();
        await settle(1600);
        expect(screen.getByRole('alert')).toHaveTextContent('이 인물은 지금 선택할 수 없습니다. 목록을 다시 읽어 주세요.');
        expect(screen.getByRole('link', { name: '직접 만들기' })).toHaveAttribute('href', '/game/pep/create');
        const before = historicalCalls().length;
        fireEvent.click(screen.getByRole('button', { name: '다른 인물 고르기' }));
        await settle();
        expect(historicalCalls().length).toBe(before + 1);
        expect(screen.getByRole('listbox', { name: '역사 인물' })).toBeInTheDocument();
        expect(mocks.push).not.toHaveBeenCalled();
    }, 10_000);

    it('접수 거절(409 GENERAL_ALREADY_OWNED)은 서버 문장 + 작전실로', async () => {
        routes['POST /api/game/api/generals/creation'] = () => json(409, { error: { code: 'GENERAL_ALREADY_OWNED', message: '이미 이 서버에 장수가 있습니다.' } });
        await pickAndStart();
        expect(screen.getByRole('alert')).toHaveTextContent('이미 이 서버에 장수가 있습니다.');
        expect(screen.getByRole('link', { name: '작전실로' })).toHaveAttribute('href', '/game/pep/');
        expect(screen.queryByRole('button', { name: '다른 인물 고르기' })).toBeNull();
    });
});

describe('모바일', () => {
    it('거르기는 하단 시트, 카드를 누르면 고른 인물 시트', async () => {
        mocks.viewport = 'mobile';
        render(<HistoricalScreen />);
        await settle();
        expect(screen.getByRole('heading', { level: 2, name: '역사 인물 고르기' })).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '거르기' }));
        expect(within(screen.getByRole('dialog', { name: '거르기' })).getByRole('radio', { name: '재야' })).toBeInTheDocument();
        fireEvent.click(within(screen.getByRole('dialog', { name: '거르기' })).getByRole('button', { name: '닫기' }));
        fireEvent.click(within(screen.getByRole('listbox', { name: '역사 인물' })).getAllByRole('option')[2]);
        const sheet = screen.getByRole('dialog', { name: '허저' });
        expect(within(sheet).getByRole('button', { name: '이 인물로 시작' })).toBeInTheDocument();
        expect(within(sheet).getByText('재야')).toBeInTheDocument();
    });
});

describe('접수 번호(clientRequestId)', () => {
    const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    it('randomUUID 가 없는 곳(보안 컨텍스트 밖)에서도 서버가 받는 v4 정규형', () => {
        const fallback = { getRandomValues: (a: Uint8Array) => { a.fill(0xff); return a; } } as unknown as Crypto;
        expect(newClientRequestId(fallback)).toMatch(UUID);
        expect(newClientRequestId()).toMatch(UUID);
    });
});

