import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { setViewport } from './helpers/viewport';
import { PeopleScreen } from '../components/people/PeopleScreen';
import { api } from '../lib/api';
import type { DirectoryPerson } from '../lib/directory-reads';

vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7 }) }));
vi.mock('../lib/api', () => ({ api: { people: vi.fn() } }));

const person = (id: number, name: string): DirectoryPerson => ({
    generalId: id, name, portrait: { picture: null, imageServer: 0 }, affiliation: null, role: null, lordGeneralId: null,
    stats: null, aptitudes: null, locationCityId: null, bonds: null,
});
const hrefs = { person: (id: number) => `/game/pep/retinue/people/${id}`, letter: (id: number) => `/game/pep/letters/new?to=${id}`, search: '/game/pep?do=action.search' };

beforeEach(() => {
    vi.clearAllMocks();
    setViewport('desktop');
});

test('표 + 미리보기, 범위를 바꾸면 그 범위로 다시 읽는다', async () => {
    vi.mocked(api.people).mockResolvedValue({ status: 'READY', people: [person(7, '하후돈'), person(9, '석도')], nextCursor: null } as never);
    render(<PeopleScreen initialScope="ALL" hrefs={hrefs} cityName={() => null} />);
    const preview = await screen.findByRole('complementary', { name: '미리보기' });
    expect(within(preview).getByRole('link', { name: '인물 상세 열기' })).toHaveAttribute('href', '/game/pep/retinue/people/7');
    fireEvent.click(within(screen.getByRole('table')).getByRole('button', { name: /석도/ }));
    expect(within(screen.getByRole('complementary', { name: '미리보기' })).getByRole('link', { name: '인물 상세 열기' }))
        .toHaveAttribute('href', '/game/pep/retinue/people/9');
    fireEvent.click(within(screen.getByRole('radiogroup', { name: '범위' })).getByRole('radio', { name: '내 부' }));
    await waitFor(() => expect(vi.mocked(api.people).mock.calls.at(-1)?.[0]).toMatchObject({ scope: 'RETINUE' }));
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
