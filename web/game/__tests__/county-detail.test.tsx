import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { Garrison } from '../components/county/CountyParts';
import { COUNTY_DETAIL_READY, countyDetailPath, garrisonRows } from '../lib/county-detail';

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
    const { unmount } = render(<Garrison rows={garrisonRows({ status: 'READY', cityId: 129, garrison: { troops: 1200, training: 60, morale: 75 } })} />);
    const block = screen.getByRole('region', { name: '수비군' });
    expect(block).toHaveTextContent('병력1,200');
    expect(block).toHaveTextContent('사기75');
    unmount();
    render(<Garrison rows={null} />);
    expect(screen.getByText('수비군 — 서버 대기')).toBeInTheDocument();
});
