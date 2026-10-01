// 황제 소재지 읽기(K8 데이터 층) — 서버 응답 예시(app/game-api/src/test/resources/imperial/presence-*.json)와
// 같은 파일로 계약을 잠근다. 모르는 모양은 성공으로 치지 않는다(fail closed).
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import {
    emperorWhere,
    imperialPresenceView,
    parseImperialPresence,
    readImperialPresence,
    useImperialPresence,
    type ImperialBadge,
} from '@/lib/imperial';

const FIX = resolve(__dirname, '../../../app/game-api/src/test/resources/imperial');
const fixture = (name: string): unknown => JSON.parse(readFileSync(resolve(FIX, name), 'utf8'));

function mockFetch(status: number, body: unknown, bad = false) {
    const res = {
        ok: status >= 200 && status < 300,
        status,
        statusText: String(status),
        json: () => (bad ? Promise.reject(new SyntaxError('bad json')) : Promise.resolve(body)),
    } as Response;
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(res);
}

const BADGE: ImperialBadge = {
    lineCode: 'han',
    lineName: '한',
    emperorGeneralId: 7,
    emperorNodeKind: 'LAND_PROVINCE',
    emperorNodeId: '83011',
    emperorCityId: 130,
    courtCityId: 130,
};

beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
});
afterEach(() => {
    vi.unstubAllGlobals();
});

describe('parseImperialPresence — 서버 응답 예시 3종', () => {
    it('READY 예시를 그대로 읽는다(성 id 와 조정 소재지는 따로)', () => {
        const p = parseImperialPresence(fixture('presence-ready.json'));
        expect(p).not.toBeNull();
        expect(p!.status).toBe('READY');
        expect(p!.badges).toHaveLength(1);
        expect(p!.badges[0]).toMatchObject({ emperorNodeKind: 'LAND_PROVINCE', emperorNodeId: '70930', emperorCityId: 12, courtCityId: 11 });
        expect(imperialPresenceView(p!).kind).toBe('PRESENT');
    });

    it('NOT_SEEDED 는 「황실 없음」이지 공위가 아니다', () => {
        const p = parseImperialPresence(fixture('presence-not-seeded.json'));
        expect(p?.status).toBe('NOT_SEEDED');
        expect(imperialPresenceView(p!).kind).toBe('NO_IMPERIAL_HOUSE');
    });

    it('STATE_UNAVAILABLE 본문도 읽힌다(화면은 배지를 숨긴다)', () => {
        const p = parseImperialPresence(fixture('presence-unavailable.json'));
        expect(p?.status).toBe('STATE_UNAVAILABLE');
        expect(imperialPresenceView(p!).kind).toBe('UNAVAILABLE');
    });
});

describe('parseImperialPresence — 계약과 다르면 받지 않는다', () => {
    const ok = { status: 'READY', badges: [BADGE] };
    it.each([
        ['모르는 status', { status: 'SEEDED', badges: [] }],
        ['badges 없음', { status: 'READY' }],
        ['NOT_SEEDED 인데 배지가 있음', { status: 'NOT_SEEDED', badges: [BADGE] }],
        ['emperorCityId 키가 빠짐(명시적 null 이어야 한다)', { status: 'READY', badges: [{ ...BADGE, emperorCityId: undefined }] }],
        ['courtCityId 가 문자열', { status: 'READY', badges: [{ ...BADGE, courtCityId: '130' }] }],
        ['모르는 노드 종류', { status: 'READY', badges: [{ ...BADGE, emperorNodeKind: 'CITY' }] }],
        ['수역에 있는데 성 id 가 있음', { status: 'READY', badges: [{ ...BADGE, emperorNodeKind: 'WATER_ZONE' }] }],
        ['lineCode 오름차순이 아님', { status: 'READY', badges: [{ ...BADGE, lineCode: 'wei' }, { ...BADGE, lineCode: 'han' }] }],
        ['lineCode 가 겹침', { status: 'READY', badges: [BADGE, BADGE] }],
    ])('%s → null', (_, body) => {
        const b = JSON.parse(JSON.stringify(body)); // undefined 키는 JSON 에서 빠진다
        expect(parseImperialPresence(b)).toBeNull();
    });
    it('맞는 본문은 통과한다(대조군)', () => {
        expect(parseImperialPresence(ok)).not.toBeNull();
    });
});

describe('imperialPresenceView · emperorWhere', () => {
    it('READY 인데 배지가 없으면 공위', () => {
        expect(imperialPresenceView({ status: 'READY', badges: [] }).kind).toBe('VACANT');
    });
    it('성 id 가 없으면 성 밖, 수역이면 물 위', () => {
        expect(emperorWhere(BADGE)).toBe('IN_CITY');
        expect(emperorWhere({ ...BADGE, emperorCityId: null })).toBe('OUTSIDE_CITY');
        expect(emperorWhere({ ...BADGE, emperorNodeKind: 'WATER_ZONE', emperorCityId: null })).toBe('ON_WATER');
    });
});

describe('readImperialPresence — HTTP', () => {
    it('게임 프록시 경로를 캐시 없이 부른다', async () => {
        mockFetch(200, fixture('presence-not-seeded.json'));
        await readImperialPresence();
        const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
        expect(url).toBe('/api/game/api/imperial/presence');
        expect(init.cache).toBe('no-store');
    });

    it('409 + STATE_UNAVAILABLE 본문은 실패가 아니라 그 상태로 읽는다', async () => {
        mockFetch(409, fixture('presence-unavailable.json'));
        const r = await readImperialPresence();
        expect(r).toEqual({ ok: true, presence: { status: 'STATE_UNAVAILABLE', badges: [] } });
    });

    it('409 인데 READY 본문, 200 인데 STATE_UNAVAILABLE 본문은 받지 않는다', async () => {
        mockFetch(409, fixture('presence-ready.json'));
        expect((await readImperialPresence()).ok).toBe(false);
        mockFetch(200, fixture('presence-unavailable.json'));
        expect((await readImperialPresence()).ok).toBe(false);
    });

    it('500 · 깨진 JSON · 네트워크 실패는 실패(성공으로 위장하지 않는다)', async () => {
        mockFetch(500, {});
        expect(await readImperialPresence()).toEqual({ ok: false, httpStatus: 500 });
        mockFetch(200, null, true);
        expect(await readImperialPresence()).toEqual({ ok: false, httpStatus: 200 });
        (globalThis.fetch as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new TypeError('network'));
        expect(await readImperialPresence()).toEqual({ ok: false, httpStatus: null });
    });
});

describe('useImperialPresence', () => {
    it('읽기 → ready + view, 실패 뒤 retry 로 다시 읽는다', async () => {
        mockFetch(500, {});
        const { result } = renderHook(() => useImperialPresence());
        expect(result.current.state).toBe('loading');
        await waitFor(() => expect(result.current.state).toBe('error'));

        mockFetch(200, fixture('presence-ready.json'));
        act(() => result.current.retry());
        await waitFor(() => expect(result.current.state).toBe('ready'));
        const cur = result.current;
        if (cur.state !== 'ready') throw new Error('not ready');
        expect(cur.view.kind).toBe('PRESENT');
        expect(globalThis.fetch).toHaveBeenCalledTimes(2);
    });
});
