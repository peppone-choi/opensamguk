import { expect, test } from 'vitest';
import type { LastTurns } from '../lib/campaign-reads';
import { allItems, myTurns, nationItems, rangeText, recentCount } from '../lib/last-turns-view';

const hrefs = { court: '/game/pep/court?tab=orders', yuedan: '/game/pep/retinue/yuedan' };
const turn = (month: number, phase: number, entries: { kind: string; text: string }[]) =>
    ({ year: 200, month, phase, phaseLabel: ['상순', '중순', '하순'][phase - 1], entries });
const data: LastTurns = {
    status: 'READY',
    turns: [
        turn(3, 2, [{ kind: 'input.rejected', text: '대상이 같은 구역에 없어 무효가 되었습니다.' }, { kind: 'court.dispatchReceived', text: '관도 방면 군단장 발령이 왔습니다.' }]),
        turn(3, 1, []),
        turn(2, 3, [{ kind: 'march.corps', text: '군단이 움직였습니다.' }, { kind: 'brand.newKind', text: '새 종류' }]),
    ],
    nationSummary: [{ year: 200, month: 3, phase: 1, phaseLabel: '상순', kind: 'county.captured', text: '원소가 진류현을 차지했습니다.' }],
};

test('내 12순 — 서버 순서 그대로 순마다 묶고 빈 순도 남긴다, 분류 · 상태 · 바로가기', () => {
    const turns = myTurns(data, 'ALL', hrefs);
    expect(turns.map((t) => [t.label, t.items.length])).toEqual([['200년 3월 중순', 2], ['200년 3월 상순', 0], ['200년 2월 하순', 2]]);
    const [rejected, dispatch] = turns[0].items;
    expect(rejected).toMatchObject({ sectionLabel: '개인 행적', title: '입력 무효', status: { label: '무효', tone: 'rust' }, action: null });
    expect(dispatch).toMatchObject({ sectionLabel: '조정 공문', title: '발령 도착', action: { label: '조정에서 보기 →', href: hrefs.court } });
    const [march, unknown] = turns[2].items;
    expect(march).toMatchObject({ section: 'BATTLE', battleDetailPending: true });
    // 모르는 종류는 분류 칩 없이 「기록」 — 지어 넣지 않는다.
    expect(unknown).toMatchObject({ section: null, sectionLabel: null, title: '기록' });
});

test('분류 거르기 — 고른 분류만, 모르는 종류는 「전체」에서만', () => {
    expect(myTurns(data, 'COURT', hrefs).map((t) => t.items.map((i) => i.title))).toEqual([['발령 도착'], [], []]);
    expect(myTurns(data, 'PERSONAL', hrefs).flatMap((t) => t.items).some((i) => i.title === '기록')).toBe(false);
});

test('부 · 세력 · 전체 — 세력 요약에 날짜를 붙이고, 전체는 최근 순부터(같은 순이면 내 기록 먼저)', () => {
    expect(nationItems(data, 'ALL', hrefs).map((i) => `${i.title} · ${i.when}`)).toEqual(['현 점령 · 3월 상순']);
    expect(allItems(data, 'ALL', hrefs).map((i) => i.title)).toEqual(['입력 무효', '발령 도착', '현 점령', '군단 행군', '기록']);
    expect(allItems(data, 'WORLD', hrefs).map((i) => i.title)).toEqual(['현 점령']);
});

test('머리 범위 · 손잡이 수 — 받은 순의 처음과 끝, 지금 순 + 방금 끝난 순의 내 기록 수', () => {
    expect(rangeText(data)).toBe('200년 2월 하순 – 3월 중순');
    expect(rangeText({ ...data, turns: data.turns.slice(0, 2) })).toBe('200년 3월 상순 – 중순');
    expect(rangeText({ ...data, turns: [] })).toBeNull();
    // 지금 순(turns[0]) + 방금 끝난 순(turns[1]) — 그 앞(turns[2])은 세지 않는다.
    expect(recentCount(data)).toBe(2);
    // 순이 막 넘어가 지금 순이 비어도 방금 끝난 순의 결과를 센다(#1218 리뷰).
    expect(recentCount({ ...data, turns: data.turns.slice(1) })).toBe(2);
    expect(recentCount({ ...data, turns: [] })).toBe(0);
});
