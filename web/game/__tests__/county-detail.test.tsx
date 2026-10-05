import { render, screen, within } from '@testing-library/react';
import { expect, test } from 'vitest';
import { expectServerWait, expectServerWaitGone } from '@opensamguk/ui';
import { Garrison, HeadChips, Indicators, PeopleHere } from '../components/county/CountyParts';
import {
    COUNTY_DETAIL_READY, countyDetailPath, detailIndicatorCells, garrisonRows, gradeLabel, hiddenText, peopleHereRows, type CountyDetailRead,
} from '../lib/county-detail';

test('현 상세 읽기(K4-04) 미리 연결 — 경로는 꺼 둔다, 수비군 세 줄은 READY · garrison 이 있을 때만', () => {
    // C10 경로가 main 에 들어오기 전에는 화면이 부르지 않는다 — 켜는 PR 에서 이 단언을 뒤집는다.
    expect(COUNTY_DETAIL_READY).toBe(false);
    expect(countyDetailPath(7, 129)).toBe('/api/counties/129?generalId=7');
    expect(garrisonRows({ status: 'READY', cityId: 129, garrison: { troops: 1200, training: 60, morale: 75 } })).toEqual([
        { label: '병력', value: '1,200' }, { label: '훈련', value: '60' }, { label: '사기', value: '75' },
    ]);
    // 시야 밖(null) · 칸 없음 · 읽기 없음 · READY 아님은 모두 줄 없음 → 서버 대기(0 으로 그리지 않는다).
    expect(garrisonRows({ status: 'READY', cityId: 129, garrison: null })).toBeNull();
    expect(garrisonRows({ status: 'READY', cityId: 129 })).toBeNull();
    expect(garrisonRows(null)).toBeNull();
    expect(garrisonRows({ status: 'NO_GENERAL', cityId: 129, garrison: { troops: 1, training: 1, morale: 1 } })).toBeNull();
});

test('수비군 칸 — 줄이 있으면 병력 · 훈련 · 사기, 없으면 「수비군 — 서버 대기」', () => {
    const filled = render(<Garrison rows={garrisonRows({ status: 'READY', cityId: 129, garrison: { troops: 1200, training: 60, morale: 75 } })} />);
    const block = screen.getByRole('region', { name: '수비군' });
    expect(block).toHaveTextContent('병력1,200');
    expect(block).toHaveTextContent('사기75');
    // 값이 오면 K4-04 서버 대기 표지는 사라진다(K10 #1335 틀).
    expectServerWaitGone(filled.container, ['K4-04'], { value: '1,200' });
    filled.unmount();
    const waiting = render(<Garrison rows={null} />);
    expect(screen.getByText('수비군 — 서버 대기')).toBeInTheDocument();
    expectServerWait(waiting.container, ['K4-04']);
});

// ── 계약 확정 모양(C9 「K4 생산자 후속 타입 · ACL 합의」, C10 accepted-fields) ──
const it = (value: number, max: number) => ({ value, max, trend: null });
const detail = (over: Partial<CountyDetailRead> = {}): CountyDetailRead => ({
    status: 'READY', cityId: 129,
    indicators: {
        population: it(12000, 20000), agriculture: it(300, 1000), commerce: it(250, 1000), security: it(40, 100),
        trust: { value: 47.6, max: 100.0, trend: null }, defence: it(500, 1000), wall: it(800, 1000),
    },
    grade: { code: 5, label: '중현' }, garrison: null, peopleHere: null, unavailableReasons: { '/peopleHere': 'NO_SOURCE' }, ...over,
});

test('7지표 — 엔진 키 일곱을 보드 순서 · 이름으로, 민심은 소수를 반올림해 보이고 50 아래 경고색, 방비는 defence 에서', () => {
    const cells = detailIndicatorCells(detail())!;
    expect(cells.map((c) => c.label)).toEqual(['호구', '전답', '시장', '치안', '민심', '방비', '성벽']);
    expect(cells[4]).toMatchObject({ value: 47.6, max: 100, display: '48 / 100', tone: 'rust' });
    expect(cells[5]).toMatchObject({ value: 500, max: 1000, tone: 'bronze' });
});

test('7지표 — 한 칸이 null 이면 그 칸만 「?」, 전체가 없거나 READY 가 아니면 null(front-info · 서버 대기로 물러난다)', () => {
    const one = detailIndicatorCells(detail({ indicators: { ...detail().indicators!, security: null } }))!;
    expect(one[3]).toEqual({ label: '치안', value: null, max: null });
    expect(one[0].value).toBe(12000);
    expect(detailIndicatorCells(detail({ indicators: null }))).toBeNull();
    expect(detailIndicatorCells({ status: 'UNAVAILABLE', cityId: 129, indicators: detail().indicators })).toBeNull();
    expect(detailIndicatorCells(null)).toBeNull();
    const { container } = render(<Indicators rows={one} />);
    expect(screen.getByRole('group', { name: '치안 모름' })).toHaveTextContent('치안?');
    expect(screen.getByRole('meter', { name: '호구' })).toHaveTextContent('12000 / 20000');
    expectServerWaitGone(container, ['K4-04'], { value: '12000 / 20000' });
});

test('등급 — label 만 칩으로, 없으면 칩 없음 · 권한 밖 칸은 「볼 수 없음」(서버 대기 표지 없이)', () => {
    expect(gradeLabel(detail())).toBe('중현');
    expect(gradeLabel(detail({ grade: null }))).toBeNull();
    expect(gradeLabel(detail({ grade: { code: 5, label: ' ' } }))).toBeNull();
    const head = { name: '양성현', commanderyName: '영천군', ownerName: '조조', ownerColor: null, mine: false, isCapital: false, isSeat: false, isolated: false, here: false };
    render(<HeadChips head={head} vision={{ tier: 'FULL', ageTurns: null, commanderyId: null } as never} grade={gradeLabel(detail())} />);
    expect(screen.getByText('중현')).toBeInTheDocument();

    const hidden = detail({ indicators: null, unavailableReasons: { '/indicators': 'NOT_AUTHORIZED', '/garrison': 'NO_SOURCE' } });
    expect(hiddenText(hidden, '/indicators')).toBe('볼 수 없는 정보입니다.');
    expect(hiddenText(hidden, '/garrison')).toBeNull();
    const shown = render(<Indicators rows={null} hidden={hiddenText(hidden, '/indicators')} />);
    expect(screen.getByText('볼 수 없는 정보입니다.')).toBeInTheDocument();
    expectServerWaitGone(shown.container, ['K4-04'], { value: '볼 수 없는 정보입니다.' });
});

test('이 현에 있는 사람 — null 은 서버 대기, [] 는 「없습니다」, 줄은 관계 칩(다른 세력을 「적」으로 바꾸지 않는다) · 군단 줄은 늘 서버 대기', () => {
    expect(peopleHereRows(detail())).toBeNull();
    const waiting = render(<PeopleHere rows={peopleHereRows(detail())} />);
    expectServerWait(waiting.container, ['K4-04']);
    waiting.unmount();

    const empty = render(<PeopleHere rows={peopleHereRows(detail({ peopleHere: [] }))} />);
    expect(screen.getByText('이 현에 있는 사람이 없습니다.')).toBeInTheDocument();
    empty.unmount();

    const rows = peopleHereRows(detail({ peopleHere: [
        { generalId: 7, name: '하후돈', portrait: { picture: null, imageServer: 0 }, affiliation: { nationId: 1, name: '조조', color: '#4f7fbf' }, relation: 'SELF' },
        { generalId: 9, name: '안량', portrait: { picture: null, imageServer: 0 }, affiliation: { nationId: 2, name: '원소', color: '#9c4a3f' }, relation: 'OTHER' },
    ] }))!;
    expect(rows.map((r) => r.relation)).toEqual(['나', '다른 세력']);
    render(<PeopleHere rows={rows} />);
    const list = screen.getByRole('region', { name: '이 현에 있는 사람' });
    expect(within(list).getByText('안량')).toBeInTheDocument();
    expect(within(list).queryByText('적')).toBeNull();
    expect(list.querySelector('[data-server-wait="K4-04"]')).toHaveTextContent('군단 줄');
});

test('수비군 — 권한 밖이면 「볼 수 없음」, 그 밖에 없으면 서버 대기', () => {
    const hidden = render(<Garrison rows={null} hidden="볼 수 없는 정보입니다." />);
    expect(screen.getByText('볼 수 없는 정보입니다.')).toBeInTheDocument();
    expectServerWaitGone(hidden.container, ['K4-04'], { value: '볼 수 없는 정보입니다.' });
});
