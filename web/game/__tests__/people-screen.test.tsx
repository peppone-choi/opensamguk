import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { PeopleScreen } from '../components/people/PeopleScreen';
import { api } from '../lib/api';
import type { DirectoryPerson } from '../lib/directory-reads';

vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7 }) }));
vi.mock('../lib/api', () => ({ api: { people: vi.fn() } }));

const person = (id: number, name: string, over: Partial<DirectoryPerson> = {}): DirectoryPerson => ({
    generalId: id, name, portrait: { picture: null, imageServer: 0 }, affiliation: null, role: null, lordGeneralId: null,
    stats: null, aptitudes: null, locationCityId: null, bonds: null, ...over,
});
const hrefs = { person: (id: number) => `/game/pep/retinue/people/${id}`, letter: (id: number) => `/game/pep/letters/new?to=${id}`, search: '/game/pep?do=action.search' };

let viewport: ReturnType<typeof installViewport> | null = null;
const setViewport = (kind: 'mobile' | 'desktop') => { viewport?.restore(); viewport = installViewport(kind === 'mobile' ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });

beforeEach(() => {
    vi.clearAllMocks();
    setViewport('desktop');
});

test('표 + 미리보기, 「인물 상세 열기」는 나 · 내 부만, 범위를 바꾸면 그 범위로 다시 읽는다', async () => {
    vi.mocked(api.people).mockResolvedValue({ status: 'READY', people: [person(7, '하후돈'), person(9, '석도', { lordGeneralId: 7 }), person(11, '원소')], nextCursor: null } as never);
    render(<PeopleScreen initialScope="ALL" hrefs={hrefs} cityName={() => null} />);
    const preview = await screen.findByRole('complementary', { name: '미리보기' });
    expect(within(preview).getByRole('link', { name: '인물 상세 열기' })).toHaveAttribute('href', '/game/pep/retinue/people/7');
    fireEvent.click(within(screen.getByRole('table')).getByRole('button', { name: /석도/ }));
    expect(within(screen.getByRole('complementary', { name: '미리보기' })).getByRole('link', { name: '인물 상세 열기' }))
        .toHaveAttribute('href', '/game/pep/retinue/people/9');
    // 나 · 내 부가 아니면 인물 상세가 채울 것이 없다(K4-13 전) — 고리를 두지 않는다
    fireEvent.click(within(screen.getByRole('table')).getByRole('button', { name: /원소/ }));
    expect(within(screen.getByRole('complementary', { name: '미리보기' })).queryByRole('link', { name: '인물 상세 열기' })).toBeNull();
    fireEvent.click(within(screen.getByRole('radiogroup', { name: '범위' })).getByRole('radio', { name: '내 부' }));
    await waitFor(() => expect(vi.mocked(api.people).mock.calls.at(-1)?.[0]).toMatchObject({ scope: 'RETINUE' }));
});

test('K4-05 보강 — 쪽이 범위 전체 수(total)를 주면 「1,000명 중 2」, 다음 쪽도 같은 total 로 센다', async () => {
    vi.mocked(api.people).mockResolvedValue({ status: 'READY', people: [person(7, '하후돈'), person(9, '석도')], nextCursor: null, total: 1000 } as never);
    render(<PeopleScreen hrefs={hrefs} />);
    expect(await screen.findByText(/^1,000명 중 2/)).toBeInTheDocument();
});

test('첫 쪽 실패는 다시 시도, 누르면 다시 읽는다', async () => {
    vi.mocked(api.people).mockRejectedValueOnce(new Error('500: boom'))
        .mockResolvedValue({ status: 'READY', people: [person(9, '석도')], nextCursor: null } as never);
    render(<PeopleScreen initialScope="ALL" hrefs={hrefs} cityName={() => null} />);
    expect(await screen.findByText('인물 목록을 불러오지 못했습니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('table')).toHaveTextContent('석도');
});

test('빈 셋 — 찾기 결과 0(지우기) · 내 부 0(인재탐색) · 세계 0', async () => {
    vi.mocked(api.people).mockResolvedValue({ status: 'READY', people: [], nextCursor: null } as never);
    const { unmount } = render(<PeopleScreen initialScope="RETINUE" hrefs={hrefs} cityName={() => null} />);
    expect(await screen.findByText('아직 거느린 인물이 없습니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '인재탐색 — 명령 목록에 넣기' })).toHaveAttribute('href', hrefs.search);
    fireEvent.change(screen.getByRole('searchbox', { name: '이름 찾기' }), { target: { value: '없는이름' } });
    expect(await screen.findByText('조건에 맞는 인물이 없습니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '찾기 지우기' }));
    expect(await screen.findByText('아직 거느린 인물이 없습니다')).toBeInTheDocument();
    unmount();
    render(<PeopleScreen initialScope="ALL" hrefs={hrefs} cityName={() => null} />);
    expect(await screen.findByText('이 세계에는 아직 인물이 없습니다')).toBeInTheDocument();
});

test('정렬 — 키 · 방향을 바꾸면 서버에 그 정렬로 다시 읽는다(받은 쪽을 다시 정렬하지 않는다)', async () => {
    vi.mocked(api.people).mockResolvedValue({ status: 'READY', people: [person(7, '하후돈'), person(9, '석도')], nextCursor: null } as never);
    render(<PeopleScreen initialScope="ALL" hrefs={hrefs} cityName={() => null} />);
    await screen.findByRole('complementary', { name: '미리보기' });
    fireEvent.change(screen.getByRole('combobox', { name: '정렬' }), { target: { value: 'INTEL' } });
    await waitFor(() => expect(vi.mocked(api.people)).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'INTEL', direction: 'DESC' }), null, expect.anything()));
    fireEvent.click(screen.getByRole('button', { name: /정렬 방향 — 지금 높은 순/ }));
    await waitFor(() => expect(vi.mocked(api.people)).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'INTEL', direction: 'ASC' }), null, expect.anything()));
});

test('첫 쪽 실패의 오류 번호는 공용 읽기 번호만 — 서버 원문은 화면에 없다', async () => {
    vi.mocked(api.people).mockRejectedValue(new Error('503: Service Unavailable'));
    render(<PeopleScreen initialScope="ALL" hrefs={hrefs} cityName={() => null} />);
    expect(await screen.findByText('인물 목록을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '오류 번호 503 복사' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Service Unavailable');
});

test('모바일 — 카드를 누르면 늘 미리보기 시트(5능력 · 소속, 닫기 44), 나 · 내 부면 시트 안에 「인물 상세 열기」(#1265 리뷰)', async () => {
    setViewport('mobile');
    const stats = { leadership: 90, strength: 80, intel: 70, politics: 60, charm: 50 };
    vi.mocked(api.people).mockResolvedValue({ status: 'READY', people: [person(7, '하후돈'), person(9, '석도', { stats }), person(11, '원소', { stats })], nextCursor: null } as never);
    render(<PeopleScreen initialScope="ALL" hrefs={hrefs} cityName={() => null} />);
    // 나 · 내 부가 아닌 인물 — 인물 상세로 보내지 않고 시트에 일람 값(5능력)을 보인다
    fireEvent.click(await screen.findByRole('button', { name: /원소/ }));
    const sheet = await screen.findByRole('dialog', { name: '원소 미리보기' });
    expect(sheet).toHaveTextContent('90');
    expect(within(sheet).queryByRole('link', { name: '인물 상세 열기' })).toBeNull();
    fireEvent.click(within(sheet).getByRole('button', { name: '닫기' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // 나 — 시트 안에 인물 상세 고리
    fireEvent.click(screen.getByRole('button', { name: /하후돈/ }));
    const mine = await screen.findByRole('dialog', { name: '하후돈 미리보기' });
    expect(within(mine).getByRole('link', { name: '인물 상세 열기' })).toHaveAttribute('href', '/game/pep/retinue/people/7');
});
