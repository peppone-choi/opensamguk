// 관직 · 봉신(P-K03 · P-K04) 골격 — 보드의 칸은 숨기지 않고 서버 대기(칸마다 계약판 행), 입력 단추는 원장 행이 없어 그리지 않는다.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

const session = vi.hoisted(() => ({ value: { frontInfo: { general: { nationId: 1 } } } as unknown }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => session.value }));

import OfficesScreen from '@/components/offices/OfficesScreen';

function waits(root: HTMLElement) {
    return Array.from(root.querySelectorAll<HTMLElement>('[data-server-wait]')).map((el) => el.dataset.serverWait);
}

describe('OfficesScreen', () => {
    it('지방 관직 — 관할과 앉은 사람(K8-03) · 고른 관할(K8-03) · 받은 임명 제안(K8-02), 상태 풀이 줄은 그대로', () => {
        session.value = { frontInfo: { general: { nationId: 1 } } };
        render(<OfficesScreen />);
        const panel = screen.getByRole('tabpanel', { name: '지방 관직' });
        expect(waits(panel)).toEqual(['K8-03', 'K8-03', 'K8-02']);
        for (const name of ['관할과 앉은 사람', '고른 관할', '받은 임명 제안']) expect(within(panel).getByRole('region', { name })).toBeInTheDocument();
        expect(within(panel).getByText('현령은 배치 · 발령으로 정합니다')).toBeInTheDocument();
        expect(panel.querySelectorAll('.os-status--waiting')).toHaveLength(3);
    });

    it('탭 — 추천 · 자칭 · 중앙 관직(K8-05) · 봉신(K8-04 · K8-02)', () => {
        session.value = { frontInfo: { general: { nationId: 1 } } };
        render(<OfficesScreen />);
        const expected: Array<[string, string[], string]> = [
            ['추천 · 자칭', ['K8-05'], '추천 · 자칭 기록이 아직 없습니다'],
            ['중앙 관직', ['K8-05'], '중앙 관직이 아직 없습니다'],
            ['봉신', ['K8-04', 'K8-02'], '봉신 계약'],
        ];
        for (const [tab, rows, text] of expected) {
            fireEvent.click(screen.getByRole('tab', { name: tab }));
            expect(screen.getByRole('tab', { name: tab })).toHaveAttribute('aria-selected', 'true');
            const panel = screen.getByRole('tabpanel', { name: tab });
            expect(waits(panel)).toEqual(rows);
            expect(within(panel).getAllByText(text).length).toBeGreaterThan(0);
        }
    });

    it('입력 단추는 그리지 않는다 — 입력 원장 행이 없다(임명 · 파면 · 봉신 세우기 · 변경 · 끝내기)', () => {
        session.value = { frontInfo: { general: { nationId: 1 } } };
        const { container } = render(<OfficesScreen />);
        for (const tab of ['지방 관직', '추천 · 자칭', '중앙 관직', '봉신']) {
            fireEvent.click(screen.getByRole('tab', { name: tab }));
            expect(container.querySelector('[data-input-id]')).toBeNull();
            expect(container.textContent).not.toMatch(/임명하기|파면|봉신으로 세우기|계약 변경|계약 끝내기/);
            // 탭 말고 누를 것은 없다
            expect(within(container).queryAllByRole('button').filter((b) => b.getAttribute('role') !== 'tab')).toHaveLength(0);
        }
    });

    it('재야 — 「세력에 속해야 관직이 있습니다」만, 탭 없음', () => {
        session.value = { frontInfo: { general: { nationId: 0 } } };
        render(<OfficesScreen />);
        expect(screen.getByText('세력에 속해야 관직이 있습니다')).toBeInTheDocument();
        expect(screen.queryByRole('tablist')).toBeNull();
    });
});
