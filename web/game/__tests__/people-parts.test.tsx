import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { PeopleCards } from '../components/people/PeopleCards';
import { PeopleFilterBar } from '../components/people/PeopleFilterBar';
import { PeopleMore } from '../components/people/PeopleMore';
import { PeopleTable } from '../components/people/PeopleTable';
import { PersonPreview } from '../components/people/PersonPreview';
import type { DirectoryPerson } from '../lib/directory-reads';
import { bondLabels, loadedText, peopleRows, scopeFromQuery, statTotal, topAptitude } from '../lib/people-view';

const stats = { leadership: 90, strength: 70, intel: 60, politics: 50, charm: 40 };
const person = (id: number, over: Partial<DirectoryPerson> = {}): DirectoryPerson => ({
    generalId: id, name: `인물${id}`, portrait: { picture: null, imageServer: 0 },
    affiliation: { nationId: 1, name: '조조', color: '#4f7fbf' }, role: null, lordGeneralId: null,
    stats: null, aptitudes: null, locationCityId: null, bonds: null, ...over,
});
const people = [
    person(7, { name: '하후돈', stats, aptitudes: { command: 82, administration: 53, strategy: 58, envoy: 44 }, locationCityId: 2,
        bonds: [{ kind: 'HYANGDANG', targetId: '1' }, { kind: 'HYANGDANG', targetId: '2' }, { kind: 'NEW_KIND', targetId: '3' }] }),
    person(9, { name: '석도', affiliation: null }),
];
const rows = peopleRows(people, 7);
const cityName = (id: number) => (id === 2 ? '양적현' : null);

test('보기 모델 — 능력 합 · 가장 높은 적성 · 결속 이름(모르는 종류는 코드 대신 「결속」) · 권한 밖은 null', () => {
    expect(statTotal(stats)).toBe(310);
    expect(statTotal(null)).toBeNull();
    expect(topAptitude({ command: 5, administration: 9, strategy: 9, envoy: 1 })).toEqual({ key: 'administration', label: '리', value: 9 });
    expect(bondLabels(people[0].bonds)).toEqual(['향당', '결속']);
    expect(bondLabels(null)).toBeNull();
    expect(rows.map((r) => [r.rank, r.isMe, r.total])).toEqual([[1, true, 310], [2, false, null]]);
    expect(scopeFromQuery('NATION')).toBe('NATION');
    expect(scopeFromQuery('generals')).toBe('ALL');
    expect(loadedText(50, true)).toBe('50명 · 더 있음');
});

test('표 — 권한 밖 값은 「?」, 재야는 글자, 내 행 표시, 인물 칸 단추로 고른다', () => {
    const onSelect = vi.fn();
    render(<PeopleTable rows={rows} selectedId={7} onSelect={onSelect} cityName={cityName} />);
    const [, me, other] = screen.getAllByRole('row');
    expect(me).toHaveAttribute('data-me', 'true');
    expect(me).toHaveTextContent('양적현');
    expect(me).toHaveTextContent('장 82');
    expect(within(me).getByRole('button', { pressed: true })).toHaveTextContent('하후돈나');
    expect(other).toHaveTextContent('재야');
    expect(within(other).getAllByText('?').length).toBeGreaterThanOrEqual(8);
    fireEvent.click(within(other).getByRole('button', { name: /석도/ }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ generalId: 9 }));
});

test('거르기 줄 — 범위 셋, 이름 · 초성 찾기, 정렬 키 14개 · 방향(키를 바꾸면 그 키의 첫 방향)', () => {
    const onScopeChange = vi.fn();
    const onSortChange = vi.fn();
    render(<PeopleFilterBar scope="ALL" onScopeChange={onScopeChange} query="" onQueryChange={() => {}}
        sort="ID" direction="ASC" onSortChange={onSortChange} loaded={50} hasMore />);
    const radios = within(screen.getByRole('radiogroup', { name: '범위' })).getAllByRole('radio');
    expect(radios.map((r) => r.textContent)).toEqual(['내 부', '소속 세력', '전체']);
    fireEvent.click(radios[0]);
    expect(onScopeChange).toHaveBeenCalledWith('RETINUE');
    expect(screen.getByRole('searchbox', { name: '이름 찾기' })).toHaveAttribute('placeholder', '이름 · 초성');
    const select = screen.getByRole('combobox', { name: '정렬' });
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual([
        '등록순', '이름', '소속', '통솔', '무력', '지력', '정치', '매력', '능력 합', '장 · 군단', '리 · 내정', '사 · 계책', '사자 · 외교', '나이',
    ]);
    fireEvent.change(select, { target: { value: 'LEADERSHIP' } });
    expect(onSortChange).toHaveBeenLastCalledWith('LEADERSHIP', 'DESC');
    fireEvent.change(select, { target: { value: 'NAME' } });
    expect(onSortChange).toHaveBeenLastCalledWith('NAME', 'ASC');
    fireEvent.click(screen.getByRole('button', { name: /정렬 방향 — 지금 앞에서부터/ }));
    expect(onSortChange).toHaveBeenLastCalledWith('ID', 'DESC');
    expect(screen.getByRole('status')).toHaveTextContent('50명 · 더 있음');
});

test('더 보기 — 실패하면 받은 목록은 두고 한 줄 알림, 서버 원문은 보이지 않는다', () => {
    const onMore = vi.fn();
    render(<PeopleMore hasMore loading={false} moreError="403: Forbidden" onMore={onMore} />);
    expect(screen.getByRole('alert')).toHaveTextContent('다음 인물을 불러오지 못했습니다');
    expect(screen.getByRole('alert')).not.toHaveTextContent('Forbidden');
    fireEvent.click(screen.getByRole('button', { name: '다시 받기' }));
    expect(onMore).toHaveBeenCalledTimes(1);
});

test('모바일 카드 · 미리보기 — 카드는 상세로 가는 고리, 미리보기는 「?」 칸과 상세 · 서신 고리', () => {
    render(<PeopleCards rows={rows} detailHref={(id) => `/game/pep/retinue/people/${id}`} cityName={cityName} />);
    const links = screen.getAllByRole('link');
    expect(links[0]).toHaveAttribute('href', '/game/pep/retinue/people/7');
    expect(links[1]).toHaveTextContent('통 ? · 무 ? · 지 ? · 정 ? · 매 ?');

    render(<PersonPreview row={rows[1]} detailHref="/game/pep/retinue/people/9" letterHref="/game/pep/letters/new?to=9" cityName={cityName} />);
    const preview = screen.getByRole('article', { name: '석도 미리보기' });
    expect(within(preview).getByRole('group', { name: '능력' })).toHaveTextContent('통솔?');
    expect(within(preview).getByRole('link', { name: '서신 쓰기' })).toHaveAttribute('href', '/game/pep/letters/new?to=9');
    expect(within(preview).getByText('결속 ?')).toBeInTheDocument();
});
