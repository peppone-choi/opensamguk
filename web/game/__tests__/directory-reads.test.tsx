import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { campaignPartialNotice, campaignReadNotice } from '../components/campaign/GameStates';
import { api } from '../lib/api';
import { countiesPath, peoplePath, usePeopleList, type DirectoryPerson } from '../lib/directory-reads';

vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7 }) }));
vi.mock('../lib/api', () => ({ api: { people: vi.fn() } }));

const person = (id: number): DirectoryPerson => ({
    generalId: id, name: `인물${id}`, portrait: { picture: null, imageServer: 0 }, affiliation: null, role: null,
    lordGeneralId: null, stats: null, aptitudes: null, locationCityId: null, bonds: null,
});

beforeEach(() => vi.clearAllMocks());

test('인물 일람 주소는 서버가 받는 인자만 싣는다(정렬 ID, 빈 커서는 빼고)', () => {
    expect(peoplePath({ scope: 'NATION', q: '  순 ', limit: 50 }, null)).toBe('/api/people?scope=NATION&q=%EC%88%9C&sort=ID&limit=50');
    expect(peoplePath({ scope: 'ALL', q: '', limit: 50 }, 'c1')).toBe('/api/people?scope=ALL&q=&sort=ID&limit=50&cursor=c1');
});

test('현 목록 주소 — 군 범위일 때만 군 id 를 싣는다', () => {
    expect(countiesPath(7, 'NATION', 'yingchuan')).toBe('/api/counties?generalId=7&scope=NATION');
    expect(countiesPath(7, 'COMMANDERY', 'yingchuan')).toBe('/api/counties?generalId=7&scope=COMMANDERY&commanderyId=yingchuan');
});

test('더 보기는 서버 커서로 이어 붙이고, 다음 쪽 실패는 받은 목록을 지우지 않는다', async () => {
    vi.mocked(api.people)
        .mockResolvedValueOnce({ status: 'READY', people: [person(1), person(2)], nextCursor: 'c2' })
        .mockRejectedValueOnce(new Error('500: boom'))
        .mockResolvedValueOnce({ status: 'READY', people: [person(3)], nextCursor: null });
    const { result } = renderHook(() => usePeopleList({ scope: 'ALL', q: '', limit: 2 }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.people.map((p) => p.generalId)).toEqual([1, 2]);
    expect(result.current.hasMore).toBe(true);

    act(() => result.current.loadMore());
    await waitFor(() => expect(result.current.moreError).toBe('500: boom'));
    expect(result.current.people).toHaveLength(2);
    expect(result.current.error).toBeNull();

    act(() => result.current.loadMore());
    await waitFor(() => expect(result.current.people.map((p) => p.generalId)).toEqual([1, 2, 3]));
    expect(result.current.hasMore).toBe(false);
    expect(vi.mocked(api.people).mock.calls.map((c) => c[1])).toEqual([null, 'c2', 'c2']);
});

test('첫 쪽 실패는 빈 목록과 다르게 알린다', async () => {
    vi.mocked(api.people).mockRejectedValueOnce(new Error('403: Forbidden'));
    const { result } = renderHook(() => usePeopleList({ scope: 'RETINUE', q: '', limit: 50 }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe('403: Forbidden');
    expect(result.current.status).toBeNull();
});

test('옛 형식 월드는 빈 목록이 아니라 알림으로 보인다', () => {
    expect(campaignReadNotice({ loading: false, error: null }, 'UNSUPPORTED_WORLD_FORMAT')).toBe('이 서버는 지금 게임 규칙과 맞지 않습니다.');
    expect(campaignReadNotice({ loading: false, error: null }, 'READY')).toBeNull();
    // 서버 원문(영어 · 상태 코드)은 화면 문장에 붙이지 않는다(K10 측정 「불러오지 못했습니다 — 503: Service Unavailable」).
    expect(campaignReadNotice({ loading: false, error: '503: Service Unavailable' })).toBe('불러오지 못했습니다 — 서버가 잠시 응답하지 않습니다. 잠시 뒤 다시 해 보세요.');
    expect(campaignReadNotice({ loading: false, error: null }, 'WRONG_RULE_PROFILE')).toBe('이 서버는 지금 게임 규칙과 맞지 않습니다.');
});

test('재야 · 장수 없음은 빈 칸이 아니라 알림으로 보인다(세 조회의 서버 상태값)', () => {
    const idle = { loading: false, error: null };
    expect(campaignReadNotice(idle, 'NO_NATION')).toBe('소속이 없어 세력 정보가 없습니다. 출사하거나 거병하면 보입니다.');
    expect(campaignReadNotice(idle, 'NO_GENERAL')).toBe('이 서버에 장수가 없습니다.');
    // PARTIAL 은 받은 값을 가리지 않는다 — 곁에 한 줄만.
    expect(campaignReadNotice(idle, 'PARTIAL')).toBeNull();
    expect(campaignPartialNotice('PARTIAL')).toBe('일부 값을 읽지 못했습니다 — 읽은 것만 보입니다.');
    expect(campaignPartialNotice('READY')).toBeNull();
});

test('범위가 바뀌면 다시 받는 동안 이전 범위 목록을 비운다', async () => {
    let resolveNation: (v: unknown) => void = () => {};
    vi.mocked(api.people)
        .mockResolvedValueOnce({ status: 'READY', people: [person(1), person(2)], nextCursor: null })
        .mockReturnValueOnce(new Promise((r) => { resolveNation = r; }) as never);
    const { result, rerender } = renderHook(({ scope }) => usePeopleList({ scope, q: '', limit: 50 }),
        { initialProps: { scope: 'ALL' as 'ALL' | 'NATION' } });
    await waitFor(() => expect(result.current.people).toHaveLength(2));
    rerender({ scope: 'NATION' });
    await waitFor(() => expect(result.current.loading).toBe(true));
    expect(result.current.people).toHaveLength(0);
    act(() => resolveNation({ status: 'READY', people: [person(3)], nextCursor: null }));
    await waitFor(() => expect(result.current.people.map((p) => p.generalId)).toEqual([3]));
});
