// 연감(P-H02) — 계약판 K5-08 고정 자료로: 해 고르기 · 연말 판도 · 그해 큰 사건(세력 거르기 · 더 보기) · 서버 대기 · 첫 해 전.
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MAP_PREVIEW, YEARBOOK_200, YEARBOOK_200_MORE, YEARS } from './fixtures/yearbook';
import { eventTouchesNation, neighbours, publishedYears, territoryRows } from '@/lib/yearbook-view';

const mocks = vi.hoisted(() => ({ viewport: null as string | null }));
vi.mock('@/lib/campaign-session', () => ({
    useGameSession: () => ({ serverId: 'pep', generalId: 7, frontInfo: { global: { year: 201 }, general: { name: '하후돈' } } }),
}));
vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, children, ...rest }: { slug: string; children: React.ReactNode }) => <a href={`/game/pep/${slug}`} {...rest}>{children}</a>,
}));
vi.mock('@opensamguk/ui', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@opensamguk/ui')>()),
    useViewportClass: () => mocks.viewport,
}));

import YearbookScreen from '@/components/records/YearbookScreen';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let routes: Record<string, (url: URL) => Response>;
let seen: URL[];

beforeEach(() => {
    seen = [];
    mocks.viewport = null;
    routes = {
        '/api/game/api/yearbook/years': () => json(200, YEARS),
        '/api/game/api/yearbook': (url) => json(200, url.searchParams.get('cursor') === '902' ? YEARBOOK_200_MORE : { ...YEARBOOK_200, year: Number(url.searchParams.get('year')) }),
        '/api/game/api/map/preview': () => json(200, MAP_PREVIEW),
    };
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
        const url = new URL(input, 'http://x');
        seen.push(url);
        const handler = routes[url.pathname];
        return handler ? handler(url) : json(404, {});
    }));
});
afterEach(() => vi.unstubAllGlobals());

async function settle() {
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
}
const yearbookCalls = () => seen.filter((u) => u.pathname === '/api/game/api/yearbook');

describe('보기 모델', () => {
    it('발행된 해 · 앞뒤 해 · 판도 순서(현 수 많은 쪽, 무주는 맨 뒤) · 사건의 세력', () => {
        expect(publishedYears(YEARS)).toEqual([199, 200]);
        expect(neighbours([199, 200], 200)).toEqual({ prev: 199, next: null });
        expect(neighbours([199, 200], 199)).toEqual({ prev: null, next: 200 });
        expect(territoryRows(YEARBOOK_200.territory).map((r) => r.name)).toEqual(['원소', '조조', '유비', '무주']);
        expect(eventTouchesNation(YEARBOOK_200.events[0], 1)).toBe(true);
        expect(eventTouchesNation(YEARBOOK_200.events[0], 3)).toBe(false);
    });
});

describe('연감', () => {
    it('가장 최근 해(200년) — 판도 표 · 수도 이름 · 큰 사건 문장, 지도와 현 목록은 서버 대기', async () => {
        render(<YearbookScreen />);
        await settle();
        expect(yearbookCalls()[0].searchParams.get('year')).toBe('200');
        const terr = screen.getByRole('list', { name: '연말 판도' });
        const rows = within(terr).getAllByRole('listitem');
        expect(rows.map((r) => r.textContent)).toEqual(['원소현 14수도 업현', '조조현 9수도 허현', '유비현 1', '무주현 3']);
        expect(screen.getByText('200년 말 판도 지도는 준비 중입니다')).toBeInTheDocument();
        expect(screen.getByText('세력별 현 목록은 서버가 아직 주지 않습니다.')).toBeInTheDocument();
        const events = within(screen.getByRole('list', { name: '그해 큰 사건 목록' })).getAllByRole('listitem');
        expect(events[0]).toHaveTextContent('200년 12월 하순');
        expect(events[0]).toHaveTextContent('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.');
    });

    it('세력으로 거르기 · 더 보기(커서)', async () => {
        render(<YearbookScreen />);
        await settle();
        fireEvent.click(within(screen.getByRole('radiogroup', { name: '세력으로 거르기' })).getByRole('radio', { name: '유비' }));
        expect(within(screen.getByRole('list', { name: '그해 큰 사건 목록' })).getAllByRole('listitem')).toHaveLength(1);
        fireEvent.click(within(screen.getByRole('radiogroup', { name: '세력으로 거르기' })).getByRole('radio', { name: '전체' }));
        fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
        await settle();
        expect(yearbookCalls().at(-1)!.searchParams.get('cursor')).toBe('902');
        const events = within(screen.getByRole('list', { name: '그해 큰 사건 목록' })).getAllByRole('listitem');
        expect(events).toHaveLength(3);
        expect(events[2]).toHaveTextContent('주인 없던 영음현을 조조가 차지했습니다.');
        expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull();
    });

    it('해 고르기 — 앞 해로, 아직 안 끝난 해는 사유 단추', async () => {
        render(<YearbookScreen />);
        await settle();
        const next = screen.getByRole('button', { name: '201년 ▶' });
        expect(next).toHaveAttribute('aria-disabled', 'true');
        fireEvent.click(screen.getByRole('button', { name: '◀ 199년' }));
        await settle();
        expect(yearbookCalls().at(-1)!.searchParams.get('year')).toBe('199');
        expect(screen.getByRole('button', { name: '◀ 앞 해' })).toHaveAttribute('aria-disabled', 'true');
    });

    it('서버 경로가 없으면(404) 「연감을 준비하고 있습니다」 + 기록으로', async () => {
        routes['/api/game/api/yearbook/years'] = () => json(404, {});
        render(<YearbookScreen />);
        await settle();
        expect(screen.getByText('연감을 준비하고 있습니다')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: '기록으로' })).toHaveAttribute('href', '/game/pep/records');
        expect(yearbookCalls()).toHaveLength(0);
    });

    it('발행된 해가 없으면 첫 해 전 빈 상태(지금 해로)', async () => {
        routes['/api/game/api/yearbook/years'] = () => json(200, [{ year: 201, published: false }]);
        render(<YearbookScreen />);
        await settle();
        expect(screen.getByText('첫 연감은 201년이 끝나면 나옵니다')).toBeInTheDocument();
        expect(yearbookCalls()).toHaveLength(0);
    });

    it('모바일 — 해 줄 · 지도 자리 · 판도 · 사건이 한 줄로', async () => {
        mocks.viewport = 'mobile';
        render(<YearbookScreen />);
        await settle();
        expect(screen.getByText('200년')).toBeInTheDocument();
        expect(screen.getByRole('list', { name: '연말 판도' })).toBeInTheDocument();
        expect(screen.getByRole('list', { name: '그해 큰 사건 목록' })).toBeInTheDocument();
    });
});
