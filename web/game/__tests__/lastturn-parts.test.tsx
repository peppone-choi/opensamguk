import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { LastTurnFilters, LastTurnHandle, LastTurnList } from '../components/lastturn/LastTurnParts';
import type { LastTurns } from '../lib/campaign-reads';
import { entryCount, entrySection, lastTurnGroups, rangeText } from '../lib/last-turn-view';

const now = { year: 200, month: 3, phase: 2 };
const data: LastTurns = {
    status: 'READY',
    turns: [
        { year: 200, month: 3, phase: 1, phaseLabel: '상순', entries: [
            { kind: 'court.dispatchReceived', text: '조조가 양성현 현령으로 발령했습니다.', refs: { dispatchId: 'd1', countyId: 129 } },
            { kind: 'input.rejected', text: '출병이 처리되지 않았습니다.' },
        ] },
        { year: 200, month: 2, phase: 2, phaseLabel: '중순', entries: [{ kind: 'yuedan.assessed', text: '월단평 — 명망 +3' }] },
        // 12순 창 밖(너무 옛날)은 빠진다.
        { year: 199, month: 1, phase: 1, phaseLabel: '상순', entries: [{ kind: 'march.corps', text: '옛 행군' }] },
    ],
    nationSummary: [{ year: 200, month: 3, phase: 1, phaseLabel: '상순', kind: 'county.captured', text: '진류현을 점령했습니다.' }],
};

test('보기 모델 — 서버 section 표 + 옛 종류(county.captured → 천하 정세), 12순 창, 빈 순도 묶음, 탭 · 분류 거르기', () => {
    expect(entrySection('march.corps')).toBe('BATTLE');
    expect(entrySection('county.captured')).toBe('WORLD');
    expect(entrySection('unknown.kind')).toBeNull();
    const all = lastTurnGroups(data, now, 'all', null, new Set(['d1']));
    expect(all).toHaveLength(12);
    expect(all[0].when).toBe('200년 3월 상순');
    expect(all[0].items.map((i) => [i.section, i.status, i.shortcuts])).toEqual([
        ['COURT', 'awaitingReply', ['reply', 'county']],
        ['PERSONAL', 'invalid', ['why']],
        ['WORLD', null, []],
    ]);
    expect(all.flatMap((g) => g.items).map((i) => i.text)).not.toContain('옛 행군');
    expect(entryCount(all)).toBe(4);
    expect(entryCount(lastTurnGroups(data, now, 'mine', null))).toBe(3);
    expect(entryCount(lastTurnGroups(data, now, 'nation', null))).toBe(1);
    expect(entryCount(lastTurnGroups(data, now, 'all', 'COURT'))).toBe(1);
    // 이미 답한 발령은 응답 대기가 아니다.
    expect(lastTurnGroups(data, now, 'mine', null, new Set())[0].items[0].status).toBeNull();
    expect(rangeText(now)).toBe('199년 11월 중순 – 200년 3월 상순');
});

test('목록 — 빈 순은 접은 한 줄, 응답하기 · 월단평 · 현 · 왜? 바로가기, 기록 전체 보기', () => {
    const onReply = vi.fn();
    render(<LastTurnList groups={lastTurnGroups(data, now, 'all', null, new Set(['d1']))} onReply={onReply}
        yuedanHref="/game/pep/retinue/yuedan" countyHref={(id) => `/game/pep/territory/county/${id}`} recordsHref="/game/pep/records" />);
    const groups = within(screen.getByRole('list', { name: '지난 12순' })).getAllByRole('listitem').filter((li) => li.parentElement?.getAttribute('aria-label') === '지난 12순');
    expect(groups[1]).toHaveTextContent('200년 2월 하순 — 기록 없음');
    fireEvent.click(screen.getByRole('button', { name: '응답하기' }));
    expect(onReply).toHaveBeenCalledWith('d1');
    expect(screen.getByRole('link', { name: '월단평 열기' })).toHaveAttribute('href', '/game/pep/retinue/yuedan');
    expect(screen.getByRole('link', { name: '현 보기' })).toHaveAttribute('href', '/game/pep/territory/county/129');
    expect(screen.getByRole('button', { name: '왜?' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '기록 전체 보기 →' })).toHaveAttribute('href', '/game/pep/records');
});

test('빈 목록 · 손잡이 · 거르기', () => {
    render(<LastTurnList groups={lastTurnGroups({ status: 'READY', turns: [], nationSummary: [] }, now, 'all', null)} onReply={() => {}} />);
    expect(screen.getByRole('status')).toHaveTextContent('최근 12순에 남은 기록이 없습니다');
    const onToggle = vi.fn();
    render(<LastTurnHandle count={null} open={false} onToggle={onToggle} />);
    const handle = screen.getByRole('button', { name: /지난 순/ });
    expect(handle).toHaveTextContent('—');
    expect(handle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(handle);
    expect(onToggle).toHaveBeenCalled();
    const onSection = vi.fn();
    render(<LastTurnFilters tab="all" onTab={() => {}} section={null} onSection={onSection} />);
    const cats = within(screen.getByRole('radiogroup', { name: '분류' })).getAllByRole('radio');
    expect(cats.map((c) => c.textContent)).toEqual(['전체', '개인 행적', '부 · 세력', '조정 공문', '전장 보고', '천하 정세']);
    fireEvent.click(cats[4]);
    expect(onSection).toHaveBeenCalledWith('BATTLE');
    fireEvent.click(cats[0]);
    expect(onSection).toHaveBeenCalledWith(null);
});
