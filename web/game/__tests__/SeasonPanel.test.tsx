// 계절 패널 내용(K8) — 지금 순은 서버 값만, 없으면 「확인 중」, 계절 소식은 「준비 중」.
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import SeasonPanel from '@/components/season/SeasonPanel';

function cells(container: HTMLElement) {
    return Array.from(container.querySelectorAll<HTMLElement>('[data-cell]')).map((el) => el.dataset.cell);
}

describe('SeasonPanel', () => {
    it('서버가 준 3월 중순 — 봄, 남은 순, 다음 계절, 달력 8번째 칸이 지금', () => {
        const { container } = render(<SeasonPanel month={3} phase={2} onClose={() => {}} />);
        expect(screen.getByRole('heading', { name: '계절 — 봄' })).toBeInTheDocument();
        expect(screen.getByText(/5월 하순까지 7순 남았습니다/)).toBeInTheDocument();
        expect(screen.getByText(/다음은 여름\(6–8월\)/)).toBeInTheDocument();
        expect(screen.getByRole('img', { name: '1년 36순 달력 — 지금 3월 중순' })).toBeInTheDocument();
        const c = cells(container);
        expect(c).toHaveLength(36);
        expect(c.indexOf('now')).toBe(7);
    });

    it('순을 모르면(turnPhase 없음) 「확인 중」이고 칸을 칠하지 않는다 — 짐작 금지', () => {
        const { container } = render(<SeasonPanel month={3} phase={null} onClose={() => {}} />);
        expect(screen.getByRole('heading', { name: '계절' })).toBeInTheDocument();
        expect(screen.getByText('지금이 몇 월 몇 순인지 확인 중입니다.')).toBeInTheDocument();
        expect(screen.getByRole('img', { name: '1년 36순 달력 — 지금 날짜 확인 중' })).toBeInTheDocument();
        expect(cells(container).every((c) => c === 'unknown')).toBe(true);
    });

    it('닫힌 길 · 내 영지 사건은 서버 대기(StatusView waiting) — 빈 표를 그리지 않는다', () => {
        const { container } = render(<SeasonPanel month={12} phase={1} onClose={() => {}} />);
        expect(screen.getByText('계절 소식은 아직 없습니다')).toBeInTheDocument();
        expect(container.querySelector('.os-status--waiting')).not.toBeNull();
        expect(container.querySelector('table')).toBeNull();
        expect(screen.getByText(/2월 하순까지 8순 남았습니다/)).toBeInTheDocument();
    });

    it('닫기 단추(44)를 누르면 onClose', () => {
        const onClose = vi.fn();
        render(<SeasonPanel month={3} phase={2} onClose={onClose} />);
        fireEvent.click(screen.getByRole('button', { name: '계절 닫기' }));
        expect(onClose).toHaveBeenCalledTimes(1);
    });
});
