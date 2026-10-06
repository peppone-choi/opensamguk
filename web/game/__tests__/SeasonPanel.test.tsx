// 계절 패널 내용(K8) — 지금 순은 서버 값만, 없으면 「확인 중」, 계절 소식은 「준비 중」.
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { expectServerWait, expectServerWaitGone } from '@opensamguk/ui';
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
        expectServerWait(container, ['K8-08']); // 기다리는 계약판 행 — 계절 · 통행 · 계절 사건
        expect(container.querySelector('table')).toBeNull();
        expect(screen.getByText(/2월 하순까지 8순 남았습니다/)).toBeInTheDocument();
    });

    it('통행 자료가 빠지면(UNAVAILABLE) 「통행 정보 없음」 + 다시 읽기 — 「닫힌 길 없음」으로 그리지 않는다', () => {
        const onReload = vi.fn();
        const { container } = render(
            <SeasonPanel month={3} phase={2} onClose={() => {}} passage={{ read: { passageStatus: 'UNAVAILABLE', closedEdges: [] }, onReload }} />,
        );
        expect(container.querySelector('.os-status--unavailable')).not.toBeNull();
        expect(screen.getByText('통행 정보 없음')).toBeInTheDocument();
        expect(screen.getByText(/길이 다 열렸다는 뜻은 아닙니다/)).toBeInTheDocument();
        expect(screen.queryByText('이번 계절에 닫힌 길이 없습니다.')).toBeNull();
        fireEvent.click(screen.getByRole('button', { name: '다시 읽기' }));
        expect(onReload).toHaveBeenCalledTimes(1);
        // 계절 사건은 여전히 서버 대기(K8-08) — 통행은 서버가 답했으니 그 칸은 대기가 아니다
        expect(screen.getByText('계절 사건은 아직 없습니다')).toBeInTheDocument();
        expect(container.querySelectorAll('.os-status--waiting')).toHaveLength(1);
        expect(container.querySelectorAll('[data-server-wait="K8-08"]')).toHaveLength(1);
        expect(container.querySelector('[data-server-wait="K8-08"]')).toHaveTextContent('계절 사건은 아직 없습니다');
    });

    it('READY 빈 목록일 때만 「닫힌 길이 없습니다」, 칸이 있으면 개수', () => {
        const { container, rerender } = render(
            <SeasonPanel month={3} phase={2} onClose={() => {}} passage={{ read: { passageStatus: 'READY', closedEdges: [] }, onReload: () => {} }} />,
        );
        expect(screen.getByText('이번 계절에 닫힌 길이 없습니다.')).toBeInTheDocument();
        expect(container.querySelector('.os-status--unavailable')).toBeNull();
        rerender(<SeasonPanel month={3} phase={2} onClose={() => {}} passage={{ read: { passageStatus: 'READY', closedEdges: [{}, {}, {}] }, onReload: () => {} }} />);
        expect(screen.getByText('이번 계절에 닫힌 길이 3곳 있습니다.')).toBeInTheDocument();
    });

    it('계절 사건 읽기가 오면 「내 영지 계절 사건」 줄 — 현 · 사건 · 방향(수치 없음), 모르는 현은 「어느 현」', () => {
        const names: Record<number, string> = { 11: '허현' };
        const { container } = render(
            <SeasonPanel
                month={7}
                phase={1}
                onClose={() => {}}
                events={{
                    state: { kind: 'ready', occurrences: [
                        { countyId: 11, kind: 'DROUGHT', effect: { trust: -2, agriculture: -8 } },
                        { countyId: 99, kind: 'RAINY_PASSAGE', effect: { passageClosed: true } },
                    ] },
                    countyName: (id) => names[id] ?? null,
                    onReload: () => {},
                }}
            />,
        );
        const list = screen.getByRole('region', { name: '내 영지 계절 사건' });
        const rows = list.querySelectorAll('li');
        expect(rows).toHaveLength(2);
        expect(rows[0]).toHaveTextContent('허현가뭄▼ 민심▼ 전답');
        expect(rows[1]).toHaveTextContent('어느 현우기 통행길 닫힘');
        expect(list.textContent).not.toMatch(/\d/); // 수치를 쓰지 않는다
        // 통행 읽기는 아직 없다 — 사건이 있어도 닫힌 길 칸을 짓지 않는다
        expect(screen.queryByText(/닫힌 길이/)).toBeNull();
        expect(container.querySelector('.os-status--waiting')).toBeNull();
        expectServerWaitGone(container, ['K8-08'], { value: '허현' }); // 사건이 오면 K8-08 표지는 사라지고 값은 대기 칸 밖에
        expectServerWait(container, []);
    });

    it('계절 사건: READY 빈 목록이면 「없습니다」, 셈하지 못하면(unavailable) 「정보 없음」 + 다시 읽기 — 둘을 섞지 않는다', () => {
        const onReload = vi.fn();
        const { container, rerender } = render(
            <SeasonPanel month={7} phase={1} onClose={() => {}} events={{ state: { kind: 'ready', occurrences: [] }, countyName: () => null, onReload }} />,
        );
        expect(screen.getByText('이번 계절 내 영지에 계절 사건이 없습니다.')).toBeInTheDocument();
        expect(container.querySelector('.os-status--unavailable')).toBeNull();
        expectServerWaitGone(container, ['K8-08'], { value: '이번 계절 내 영지에 계절 사건이 없습니다.' }); // 「없음」은 서버 답이다 — 대기가 아니다
        rerender(<SeasonPanel month={7} phase={1} onClose={() => {}} events={{ state: { kind: 'unavailable' }, countyName: () => null, onReload }} />);
        expect(screen.getByText('계절 사건 정보 없음')).toBeInTheDocument();
        expect(screen.getByText(/사건이 없다는 뜻은 아닙니다/)).toBeInTheDocument();
        expect(screen.queryByText('이번 계절 내 영지에 계절 사건이 없습니다.')).toBeNull();
        expectServerWaitGone(container, ['K8-08'], { value: '계절 사건 정보 없음' }); // 셈하지 못함도 서버 답(대기 표지 없음)
        fireEvent.click(screen.getByRole('button', { name: '다시 읽기' }));
        expect(onReload).toHaveBeenCalledTimes(1);
    });

    it('닫기 단추(44)를 누르면 onClose', () => {
        const onClose = vi.fn();
        render(<SeasonPanel month={3} phase={2} onClose={onClose} />);
        fireEvent.click(screen.getByRole('button', { name: '계절 닫기' }));
        expect(onClose).toHaveBeenCalledTimes(1);
    });
});
