import { fireEvent, render, screen } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { PersonActions, PersonHero, PersonStateGrid, PersonWaitingPanels } from '../components/people/PersonParts';
import { employQuery, personRelation, relationActions, stateCells, type Viewer } from '../lib/person-view';

const viewer: Viewer = { generalId: 7, nationId: 1, retinueGeneralIds: new Set([101]) };
const aff = (nationId: number) => ({ nationId, name: nationId === 1 ? '조조' : '원소', color: '#123456' });

test('관계 — 나 · 내 부 · 같은 세력 · 다른 세력 · 재야, 조작 목록', () => {
    expect(personRelation({ generalId: 7, affiliation: aff(1) }, viewer)).toBe('SELF');
    expect(personRelation({ generalId: 101, affiliation: aff(1) }, viewer)).toBe('RETINUE');
    expect(personRelation({ generalId: 21, affiliation: aff(1) }, viewer)).toBe('NATION');
    expect(personRelation({ generalId: 31, affiliation: aff(2) }, viewer)).toBe('OTHER');
    expect(personRelation({ generalId: 41, affiliation: null }, viewer)).toBe('FREE');
    // 재야 장수(내 세력 없음)가 보면 소속 있는 사람은 다른 세력.
    expect(personRelation({ generalId: 21, affiliation: aff(1) }, { ...viewer, nationId: 0 })).toBe('OTHER');
    expect(relationActions('FREE')).toEqual(['employ', 'letter']);
    expect(relationActions('RETINUE')).toEqual(['placement', 'letter']);
    expect(employQuery(41)).toBe('?do=action.employ&target=general%3A41');
});

test('상태 8칸 — 충성 · 녹봉은 내 부만(밖은 「? — 내 부 인물만」), 없는 값은 짓지 않는다', () => {
    const other = stateCells({ relation: 'OTHER', location: '허현', post: null, loyalty: 90 });
    expect(other.find((c) => c.key === '충성')).toEqual({ key: '충성', value: '? — 내 부 인물만', hidden: true });
    expect(other.find((c) => c.key === '자리')?.value).toBe('?');
    const mine = stateCells({ relation: 'RETINUE', location: null, post: null, loyalty: 90 });
    expect(mine.find((c) => c.key === '충성')?.value).toBe('90');
    expect(mine.find((c) => c.key === '자리')?.value).toBe('미배치');
    expect(mine.find((c) => c.key === '위치')?.value).toBe('?');
    render(<PersonStateGrid cells={other} />);
    const hidden = screen.getAllByText('? — 내 부 인물만');
    expect(hidden).toHaveLength(2);
    for (const h of hidden) expect(h.closest('div')).toHaveAttribute('data-hidden', 'true');
});

test('히어로 · 단추 — 재야는 등용(서버 사유) + 서신, 나는 내 부로 · 기록', () => {
    const onEmploy = vi.fn();
    const { rerender } = render(
        <PersonHero name="석도" picture={null} imageServer={0} relation="FREE" affiliation={null}
            actions={<PersonActions relation="FREE" hrefs={{ letter: '/game/pep/letters/new?to=41' }}
                employ={{ inputId: 'action.employ', status: 'BLOCKED', reason: '같은 칸에서 탐색한 인재만 등용할 수 있습니다.' }} onEmploy={onEmploy} />} />,
    );
    expect(screen.getByRole('region', { name: '석도 인물 카드' })).toHaveTextContent('재야');
    expect(screen.getByRole('button', { name: /이 사람을 등용/ })).toHaveAttribute('aria-disabled', 'true');
    expect(document.body).toHaveTextContent('같은 칸에서 탐색한 인재만 등용할 수 있습니다.');
    expect(screen.getByRole('link', { name: '서신 쓰기' })).toHaveAttribute('href', '/game/pep/letters/new?to=41');

    rerender(<PersonHero name="하후돈" picture={null} imageServer={0} relation="SELF" affiliation="조조"
        actions={<PersonActions relation="SELF" hrefs={{ myRetinue: '/game/pep/retinue', records: '/game/pep/records?generalId=7' }} />} />);
    expect(screen.getByRole('link', { name: '내 부로' })).toHaveAttribute('href', '/game/pep/retinue');
    expect(screen.getByRole('link', { name: '이 인물의 기록' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /등용/ })).toBeNull();

    rerender(<PersonActions relation="FREE" hrefs={{}} employ={{ inputId: 'action.employ', status: 'AVAILABLE' }} onEmploy={onEmploy} />);
    fireEvent.click(screen.getByRole('button', { name: '이 사람을 등용 — 명령 목록에 넣기' }));
    expect(onEmploy).toHaveBeenCalledTimes(1);
});

test('서버 대기 칸 둘 — 계책 기여 · 관직 카드', () => {
    render(<PersonWaitingPanels />);
    expect(screen.getByText('계책 기여 — 준비 중')).toBeInTheDocument();
    expect(screen.getByText('관직 카드 — 준비 중')).toBeInTheDocument();
});
