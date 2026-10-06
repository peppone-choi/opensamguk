// 연감(P-H02) — 계약판 K5-08 고정 자료로: 해 고르기 · 연말 판도 · 그해 큰 사건(세력 거르기 · 더 보기) · 서버 대기 · 첫 해 전.
// 보강(소비 안 K5-WAIT-04): 세력별 현 목록 · 연말 판도 지도 · 결손(absent) · 미발행 · 원천 실패 · 더 보기 도중 고침.
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { expectServerWait, expectServerWaitGone } from '@opensamguk/ui';
import { BAKE_PIN, MAP_PREVIEW, YEARBOOK_200, YEARBOOK_200_ABSENT, YEARBOOK_200_FULL, YEARBOOK_200_MORE, YEARS } from './fixtures/yearbook';
import { countiesPart, eventTouchesNation, neighbours, ownershipPart, publishedYears, territoryRows, yearEndPreview, yearbookNames } from '@/lib/yearbook-view';

const mocks = vi.hoisted(() => ({ serverId: 'pep', viewport: null as string | null, mapProps: null as Record<string, unknown> | null }));
vi.mock('@/lib/campaign-session', () => ({
    useGameSession: () => ({ serverId: mocks.serverId, generalId: 7, frontInfo: { global: { year: 201 }, general: { name: '하후돈' } } }),
}));
vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, children, ...rest }: { slug: string; children: React.ReactNode }) => <a href={`/game/pep/${slug}`} {...rest}>{children}</a>,
}));
vi.mock('@opensamguk/ui', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@opensamguk/ui')>()),
    useViewportClass: () => mocks.viewport,
}));
// 연말 판도 지도 부품은 따로 시험한다(yearbook-map.test) — 여기서는 무엇을 넘기는지만 본다
vi.mock('@/components/records/YearbookMap', () => ({
    default: (props: Record<string, unknown>) => { mocks.mapProps = props; return <div data-testid="yearbook-map" />; },
}));

import YearbookScreen from '@/components/records/YearbookScreen';
import { readYearbook, readYearbookYears, YearbookResponseError } from '@/lib/yearbook-api';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let routes: Record<string, (url: URL) => Response | Promise<Response>>;
let seen: URL[];

beforeEach(() => {
    seen = [];
    mocks.serverId = 'pep';
    mocks.viewport = null;
    mocks.mapProps = null;
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

    it('보강 칸 — 없음은 서버 대기, absent 는 결손, 일부 행만 현 목록이 오면 기다린다', () => {
        expect(ownershipPart(YEARBOOK_200)).toEqual({ kind: 'waiting' });
        expect(ownershipPart(YEARBOOK_200_ABSENT)).toEqual({ kind: 'absent' });
        expect(ownershipPart(YEARBOOK_200_FULL)).toEqual({ kind: 'ready', value: YEARBOOK_200_FULL.ownership });
        expect(countiesPart(YEARBOOK_200)).toEqual({ kind: 'waiting' });
        expect(countiesPart(YEARBOOK_200_ABSENT)).toEqual({ kind: 'absent' });
        const partial = { ...YEARBOOK_200_FULL, territory: [YEARBOOK_200_FULL.territory[0], YEARBOOK_200.territory[1]] };
        expect(countiesPart(partial)).toEqual({ kind: 'waiting' });
        const full = countiesPart(YEARBOOK_200_FULL);
        expect(full.kind === 'ready' && full.value.get(2)).toEqual(['업현', '장사현']);
    });

    it('연말 판도 → 지도 입력: 그해 세력 이름 · 색, 무주는 칠하지 않는다', () => {
        const preview = yearEndPreview(YEARBOOK_200_FULL.ownership!, YEARBOOK_200_FULL.territory);
        expect(preview.nations.map((n) => n.id)).toEqual([2, 1, 3]);
        expect(preview.provinceOccupancy!.map((o) => [o.provinceIndex, o.nationId])).toEqual([[0, 2], [1, 1], [2, 0], [3, 3]]);
    });
});

describe('연감', () => {
    it('가장 최근 해(200년) — 판도 표 · 수도 이름 · 큰 사건 문장, 지도와 현 목록은 서버 대기', async () => {
        const { container } = render(<YearbookScreen />);
        await settle();
        // 연감 본문(K5-08)은 왔다 — 표지는 사라지고 값은 대기 칸 밖에. 연말 소유 지도(보강 표의 K5-08)는 아직 기다린다
        expectServerWaitGone(container, ['K5-08'], { value: '허현의 소유 세력이 원소에서 조조로 바뀌었습니다.' });
        expectServerWait(container, ['K5-08 보강']);
        expect(yearbookCalls()[0].searchParams.get('year')).toBe('200');
        const terr = screen.getByRole('list', { name: '연말 판도' });
        const rows = within(terr).getAllByRole('listitem');
        expect(rows.map((r) => r.textContent)).toEqual(['원소현 14수도 이름 기록 없음', '조조현 9수도 이름 기록 없음', '유비현 1', '무주현 3']);
        expect(screen.getByText('200년 말 판도 지도는 준비 중입니다')).toBeInTheDocument();
        expect(screen.getByText('세력별 현 목록은 서버가 아직 주지 않습니다.')).toBeInTheDocument();
        const events = within(screen.getByRole('list', { name: '그해 큰 사건 목록' })).getAllByRole('listitem');
        expect(events[0]).toHaveTextContent('200년 12월 하순');
        expect(events[0]).toHaveTextContent('어느 현의 소유 세력이 원소에서 조조로 바뀌었습니다.');
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
        expect(events[2]).toHaveTextContent('주인 없던 어느 현을 조조가 차지했습니다.');
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
        const { container } = render(<YearbookScreen />);
        await settle();
        expect(screen.getByText('연감을 준비하고 있습니다')).toBeInTheDocument();
        expectServerWait(container, ['K5-08']);
        expect(screen.getByRole('link', { name: '기록으로' })).toHaveAttribute('href', '/game/pep/records');
        expect(yearbookCalls()).toHaveLength(0);
    });

    it('발행된 해가 없으면 첫 해 전 빈 상태(지금 해로)', async () => {
        routes['/api/game/api/yearbook/years'] = () => json(200, [{ year: 201, published: false }]);
        const { container } = render(<YearbookScreen />);
        await settle();
        expect(screen.getByText('첫 연감은 201년이 끝나면 나옵니다')).toBeInTheDocument();
        // 서버는 답했다(아직 발행된 해가 없을 뿐) — 서버 대기 표지가 아니다
        expectServerWait(container, []);
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

    it('보강 칸이 오면 — 지도 · 현 목록의 서버 대기 표지가 사라지고 그해 값으로 그린다', async () => {
        routes['/api/game/api/yearbook'] = () => json(200, YEARBOOK_200_FULL);
        routes['/api/game/api/map/preview'] = () => json(200, { ...MAP_PREVIEW, topdownBakeId: BAKE_PIN });
        const { container } = render(<YearbookScreen />);
        await settle();
        expectServerWaitGone(container, ['K5-08', 'K5-08 보강'], { value: '업현 · 장사현' });
        expect(screen.queryByText('세력별 현 목록은 서버가 아직 주지 않습니다.')).toBeNull();
        expect(screen.getByTestId('yearbook-map')).toBeInTheDocument();
        expect(mocks.mapProps).toMatchObject({ year: 200, ownership: { mapPin: BAKE_PIN }, currentPin: BAKE_PIN });
        expect((mocks.mapProps!.territory as unknown[]).length).toBe(4);
        const lists = within(screen.getByRole('list', { name: '세력별 현 목록' })).getAllByRole('group');
        expect(lists.map((d) => d.querySelector('summary')!.textContent)).toEqual(['원소 현 2곳', '조조 현 1곳', '유비 현 1곳', '무주 현 0곳']);
        expect(lists[3]).toHaveTextContent('그해 말 소속 현이 없습니다.');
    });

    it('발행됐지만 원천이 없으면(absent) 「기록이 없습니다」 — 서버 대기도 지금 소유도 아니다', async () => {
        routes['/api/game/api/yearbook'] = () => json(200, YEARBOOK_200_ABSENT);
        const { container } = render(<YearbookScreen />);
        await settle();
        expectServerWait(container, []);
        expect(screen.getByText('200년 말 판도 지도 기록이 없습니다')).toBeInTheDocument();
        expect(screen.getByText('그해 현 목록 기록이 없습니다. 세력마다 현 수만 보입니다.')).toBeInTheDocument();
        expect(screen.queryByTestId('yearbook-map')).toBeNull();
        expect(seen.some((u) => u.pathname === '/api/game/api/map/topdown')).toBe(false);
    });

    it('그해 연감이 아직 안 나왔으면(404 YEARBOOK_NOT_PUBLISHED) 「그해가 끝나면 나옵니다」 — 서버 대기가 아니다', async () => {
        routes['/api/game/api/yearbook'] = () => json(404, { error: { code: 'YEARBOOK_NOT_PUBLISHED', message: '아직' } });
        const { container } = render(<YearbookScreen />);
        await settle();
        expect(screen.getByText('200년 연감은 그해가 끝나면 나옵니다')).toBeInTheDocument();
        expectServerWait(container, []);
        expect(screen.getByRole('link', { name: '기록으로' })).toHaveAttribute('href', '/game/pep/records');
    });

    it('원천을 읽지 못하면(503 YEARBOOK_SOURCE_UNAVAILABLE) 오류 + 다시 시도 — 빈 연감 · 서버 대기로 보이지 않는다', async () => {
        let fail = true;
        routes['/api/game/api/yearbook'] = () => (fail ? json(503, { error: { code: 'YEARBOOK_SOURCE_UNAVAILABLE', message: '원천' } }) : json(200, YEARBOOK_200));
        const { container } = render(<YearbookScreen />);
        await settle();
        expect(screen.getByText('연감 기록을 읽지 못했습니다. 잠시 뒤 다시 해 보세요.')).toBeInTheDocument();
        expectServerWait(container, []);
        fail = false;
        fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
        await settle();
        expect(screen.getByRole('list', { name: '연말 판도' })).toBeInTheDocument();
    });

    it('더 보기 사이에 연감이 고쳐지면(snapshot revision) 이어 붙이지 않고 처음부터 다시 읽는다', async () => {
        let revision = 'r1';
        routes['/api/game/api/yearbook'] = (url) => (url.searchParams.get('cursor') === '902'
            ? json(200, { ...YEARBOOK_200_MORE, snapshot: { ...YEARBOOK_200_FULL.snapshot!, revision } })
            : json(200, { ...YEARBOOK_200_FULL, snapshot: { ...YEARBOOK_200_FULL.snapshot!, revision }, ownership: { ...YEARBOOK_200_FULL.ownership!, revision } }));
        render(<YearbookScreen />);
        await settle();
        revision = 'r2';
        fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
        await settle();
        await settle();
        expect(yearbookCalls().map((u) => u.searchParams.get('cursor'))).toEqual([null, '902', null]);
        expect(within(screen.getByRole('list', { name: '그해 큰 사건 목록' })).getAllByRole('listitem')).toHaveLength(2);
        expect(screen.getByRole('status')).toHaveTextContent('연감이 그사이 고쳐져 이 해를 처음부터 다시 불러왔습니다.');
    });
});


describe('C10 contract guard', () => {
    it.each([
        ['null page', null],
        ['wrong requested year', { ...YEARBOOK_200, year: 199 }],
        ['missing events', { ...YEARBOOK_200, events: null }],
        ['invalid territory', { ...YEARBOOK_200, territory: [{}] }],
        ['invalid event refs', { ...YEARBOOK_200, events: [{ ...YEARBOOK_200.events[0], refs: null }] }],
        ['wrong snapshot year', { ...YEARBOOK_200_FULL, snapshot: { ...YEARBOOK_200_FULL.snapshot!, year: 199 } }],
        ['invalid snapshot world', { ...YEARBOOK_200_FULL, snapshot: { ...YEARBOOK_200_FULL.snapshot!, worldId: '1' } }],
        ['invalid snapshot revision', { ...YEARBOOK_200_FULL, snapshot: { ...YEARBOOK_200_FULL.snapshot!, revision: '' } }],
        ['invalid ownership indices', { ...YEARBOOK_200_FULL, ownership: { ...YEARBOOK_200_FULL.ownership!, provinces: [{ index: 0.5, nationId: 1 }] } }],
        ['malformed counties', { ...YEARBOOK_200, territory: [{ ...YEARBOOK_200.territory[0], counties: {} }] }],
        ['malformed absence list', { ...YEARBOOK_200, absent: {} }],
    ])('rejects %s without returning an empty yearbook', async (_name, body) => {
        routes['/api/game/api/yearbook'] = () => json(200, body);
        await expect(readYearbook(200, null)).rejects.toBeInstanceOf(YearbookResponseError);
    });

    it.each([YEARBOOK_200, YEARBOOK_200_FULL, YEARBOOK_200_ABSENT])('preserves existing optional field states %#', async (body) => {
        routes['/api/game/api/yearbook'] = () => json(200, body);
        await expect(readYearbook(200, null)).resolves.toEqual({ kind: 'ready', data: body });
        const call = vi.mocked(fetch).mock.calls.at(-1)!;
        expect(call[1]).toMatchObject({ cache: 'no-store' });
    });

    it.each([null, {}, [{ year: 200, published: 'true' }]])('rejects malformed years %#', async (body) => {
        routes['/api/game/api/yearbook/years'] = () => json(200, body);
        await expect(readYearbookYears()).rejects.toBeInstanceOf(YearbookResponseError);
    });

    it('does not append events from a different snapshot world with the same revision', async () => {
        let worldId = 1;
        routes['/api/game/api/yearbook'] = (url) => json(200, {
            ...(url.searchParams.get('cursor') === '902' ? YEARBOOK_200_MORE : YEARBOOK_200_FULL),
            snapshot: { ...YEARBOOK_200_FULL.snapshot!, worldId },
        });
        render(<YearbookScreen />);
        await settle();
        worldId = 2;
        fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
        await settle();
        await settle();
        expect(yearbookCalls().map((u) => u.searchParams.get('cursor'))).toEqual([null, '902']);
        expect(within(screen.getByRole('list', { name: '그해 큰 사건 목록' })).getAllByRole('listitem')).toHaveLength(2);
        expect(screen.getByRole('alert')).toHaveTextContent('연감 응답이 요청한 해의 기록과 맞지 않습니다.');
    });

    it('reports a wrong requested year as an error rather than another year’s territory', async () => {
        routes['/api/game/api/yearbook'] = () => json(200, { ...YEARBOOK_200, year: 199 });
        render(<YearbookScreen />);
        await settle();
        expect(screen.getByText('연감 응답이 요청한 해의 기록과 맞지 않습니다. 다시 시도해 주세요.')).toBeInTheDocument();
        expect(screen.queryByRole('list', { name: '연말 판도' })).toBeNull();
        expect(screen.queryByTestId('yearbook-map')).toBeNull();
    });
});


describe('C10 ACK guard', () => {
    it.each([
        ['ownership null without absence', { ...YEARBOOK_200_FULL, ownership: null }],
        ['ownership and absence', { ...YEARBOOK_200_FULL, absent: ['ownership'] }],
        ['absence without null', { ...YEARBOOK_200, snapshot: YEARBOOK_200_FULL.snapshot, absent: ['ownership'] }],
        ['enhancements without snapshot', { ...YEARBOOK_200_FULL, snapshot: undefined }],
        ['null snapshot', { ...YEARBOOK_200_FULL, snapshot: null }],
        ['ownership revision mismatch', { ...YEARBOOK_200_FULL, ownership: { ...YEARBOOK_200_FULL.ownership!, revision: 'r2' } }],
        ['county count mismatch', { ...YEARBOOK_200_FULL, territory: [{ ...YEARBOOK_200_FULL.territory[0], countyCount: 99 }] }],
        ['counties null without absence', { ...YEARBOOK_200_FULL, territory: YEARBOOK_200_FULL.territory.map((row) => ({ ...row, counties: null })) }],
        ['counties array and absence', { ...YEARBOOK_200_FULL, absent: ['counties'] }],
        ['absence with omitted counties', { ...YEARBOOK_200, snapshot: YEARBOOK_200_FULL.snapshot, absent: ['counties'] }],
        ['duplicate absence', { ...YEARBOOK_200_ABSENT, absent: ['ownership', 'counties', 'counties'] }],
        ['unknown absence', { ...YEARBOOK_200, absent: ['capital'] }],
        ['invalid UTC timestamp', { ...YEARBOOK_200_FULL, snapshot: { ...YEARBOOK_200_FULL.snapshot!, publishedAt: '2026-02-30T12:00:00Z' } }],
    ])('rejects %s as a contract error', async (_name, body) => {
        routes['/api/game/api/yearbook'] = () => json(200, body);
        await expect(readYearbook(200, null)).rejects.toBeInstanceOf(YearbookResponseError);
    });

    it('keeps partial counties waiting without supplementing omitted rows', async () => {
        const body = { ...YEARBOOK_200_FULL, territory: YEARBOOK_200_FULL.territory.map((row, index) => index === 0 ? { ...row, counties: undefined } : row) };
        routes['/api/game/api/yearbook'] = () => json(200, body);
        render(<YearbookScreen />);
        await settle();
        expect(screen.getByText('세력별 현 목록은 서버가 아직 주지 않습니다.')).toBeInTheDocument();
        expect(screen.queryByRole('list', { name: '세력별 현 목록' })).toBeNull();
    });

    it('does not classify a list not-published code as server waiting', async () => {
        routes['/api/game/api/yearbook/years'] = () => json(404, { error: { code: 'YEARBOOK_NOT_PUBLISHED' } });
        render(<YearbookScreen />);
        await settle();
        expect(screen.getByText('연감 목록을 불러오지 못했습니다')).toBeInTheDocument();
        expect(screen.queryByText('연감을 준비하고 있습니다')).toBeNull();
    });

    it.each([404, 503])('keeps code-less %s waiting and coded failures as errors', async (status) => {
        routes['/api/game/api/yearbook'] = () => json(status, {});
        await expect(readYearbook(200, null)).resolves.toEqual({ kind: 'waiting' });
        routes['/api/game/api/yearbook'] = () => json(status, { error: { code: 'OTHER_ERROR' } });
        await expect(readYearbook(200, null)).rejects.toMatchObject({ status, code: 'OTHER_ERROR' });
    });

    it('re-reads when a legacy page first receives a snapshot', async () => {
        let supplied = false;
        routes['/api/game/api/yearbook'] = (url) => {
            if (url.searchParams.has('cursor')) { supplied = true; return json(200, { ...YEARBOOK_200_MORE, snapshot: YEARBOOK_200_FULL.snapshot }); }
            return json(200, supplied ? YEARBOOK_200_FULL : YEARBOOK_200);
        };
        render(<YearbookScreen />);
        await settle();
        fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
        await settle();
        await settle();
        expect(yearbookCalls().map((url) => url.searchParams.get('cursor'))).toEqual([null, '902', null]);
        expect(within(screen.getByRole('list', { name: '그해 큰 사건 목록' })).getAllByRole('listitem')).toHaveLength(2);
    });

    it('does not append a legacy page after a snapshot', async () => {
        routes['/api/game/api/yearbook'] = (url) => json(200, url.searchParams.has('cursor') ? YEARBOOK_200_MORE : YEARBOOK_200_FULL);
        render(<YearbookScreen />);
        await settle();
        fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
        await settle();
        expect(screen.getByRole('alert')).toHaveTextContent('연감 응답이 요청한 해의 기록');
        expect(within(screen.getByRole('list', { name: '그해 큰 사건 목록' })).getAllByRole('listitem')).toHaveLength(2);
    });

    it('does not append a cursor response from the previous selected world', async () => {
        let finish!: (value: Response) => void;
        routes['/api/game/api/yearbook'] = (url) => url.searchParams.has('cursor')
            ? new Promise((resolve) => { finish = resolve; }) : json(200, YEARBOOK_200_FULL);
        const { rerender } = render(<YearbookScreen />);
        await settle();
        fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
        await settle();
        mocks.serverId = 'other';
        rerender(<YearbookScreen />);
        await settle();
        await act(async () => { finish(json(200, { ...YEARBOOK_200_MORE, snapshot: YEARBOOK_200_FULL.snapshot })); });
        expect(within(screen.getByRole('list', { name: '그해 큰 사건 목록' })).getAllByRole('listitem')).toHaveLength(2);
    });

    it('uses only historical county and nation names, never preview or current general names', async () => {
        const body = {
            ...YEARBOOK_200_FULL,
            territory: YEARBOOK_200_FULL.territory.map((row) => ({ ...row, name: `당시${row.name}`, counties: row.counties?.map((county) => ({ ...county, name: `당시${county.name}` })) })),
        };
        routes['/api/game/api/yearbook'] = () => json(200, body);
        render(<YearbookScreen />);
        await settle();
        const rows = within(screen.getByRole('list', { name: '연말 판도' })).getAllByRole('listitem');
        expect(rows[0]).toHaveTextContent('수도 당시업현');
        expect(screen.getByRole('list', { name: '그해 큰 사건 목록' })).toHaveTextContent('당시허현의 소유 세력이 당시원소에서 당시조조로 바뀌었습니다.');
        expect(screen.queryByText('수도 업현')).toBeNull();
    });
});


describe('C10 ACK guard', () => {
    it('does not use the session general as a historical event name', () => {
        expect(yearbookNames(YEARBOOK_200_FULL.territory).general?.(7)).toBeUndefined();
        expect(yearbookNames(YEARBOOK_200.territory).city(11)).toBeUndefined();
    });
    it('keeps an empty territory county list waiting rather than proving supplied counties', async () => {
        const body = { ...YEARBOOK_200, territory: [], nextCursor: null };
        routes['/api/game/api/yearbook'] = () => json(200, body);
        await expect(readYearbook(200, null)).resolves.toEqual({ kind: 'ready', data: body });
        expect(countiesPart(body)).toEqual({ kind: 'waiting' });
    });
});
