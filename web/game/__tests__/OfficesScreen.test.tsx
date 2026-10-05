// 관직 · 봉신(P-K03 · P-K04) 골격 — 보드의 칸은 숨기지 않고 서버 대기(칸마다 계약판 행), 입력 단추는 원장 행이 없어 그리지 않는다.
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

const session = vi.hoisted(() => ({ value: { generalId: 7, frontInfo: { general: { nationId: 1 } } } as unknown }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => session.value }));
// 지방 관직 읽기(K8-03)는 서버에 아직 경로가 없다(404 = 서버 대기, D124 미리 짓기). 다른 읽기(봉신 등)는 끝나지 않게 둬
// 그 탭들은 지금처럼 불러오는 중 · 서버 대기 칸만 본다.
vi.mock('@/lib/api', () => ({
    fetchGame: (path: string) =>
        path.startsWith('/api/court/local-offices')
            ? Promise.resolve(new Response(JSON.stringify({ error: { code: 'NOT_FOUND', message: 'x' } }), { status: 404 }))
            : new Promise<Response>(() => {}),
    api: { mapPreview: () => new Promise(() => {}) },
}));

import OfficesScreen from '@/components/offices/OfficesScreen';

function waits(root: HTMLElement) {
    return Array.from(root.querySelectorAll<HTMLElement>('[data-server-wait]')).map((el) => el.dataset.serverWait);
}

describe('OfficesScreen', () => {
    it('지방 관직 — 관할과 앉은 사람(K8-03) · 고른 관할(K8-03) · 받은 임명 제안(K8-02), 상태 풀이 줄은 그대로', async () => {
        session.value = { generalId: 7, frontInfo: { general: { nationId: 1 } } };
        render(<OfficesScreen />);
        await act(async () => { await Promise.resolve(); });
        await act(async () => { await Promise.resolve(); });
        const panel = screen.getByRole('tabpanel', { name: '지방 관직' });
        expect(waits(panel)).toEqual(['K8-03', 'K8-03', 'K8-02']);
        for (const name of ['관할과 앉은 사람', '고른 관할', '받은 임명 제안']) expect(within(panel).getByRole('region', { name })).toBeInTheDocument();
        expect(within(panel).getByText('현령은 배치 · 발령으로 정합니다')).toBeInTheDocument();
        expect(panel.querySelectorAll('.os-status--waiting')).toHaveLength(3);
    });

    it('탭 — 내 속관(K8-05 둘) · 추천 · 자칭 · 중앙 관직(K8-05) · 봉신(K8-04 · K8-02 · K8-17)', () => {
        session.value = { generalId: 7, frontInfo: { general: { nationId: 1 } } };
        render(<OfficesScreen />);
        const expected: Array<[string, string[], string]> = [
            ['내 속관', ['K8-05', 'K8-05'], '속관 자리가 아직 없습니다'],
            ['추천 · 자칭', ['K8-05'], '추천 · 자칭 기록이 아직 없습니다'],
            ['중앙 관직', ['K8-05'], '중앙 관직이 아직 없습니다'],
            ['봉신', ['K8-04', 'K8-02', 'K8-17'], '봉신 계약'],
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
        session.value = { generalId: 7, frontInfo: { general: { nationId: 1 } } };
        const { container } = render(<OfficesScreen />);
        for (const tab of ['지방 관직', '내 속관', '추천 · 자칭', '중앙 관직', '봉신']) {
            fireEvent.click(screen.getByRole('tab', { name: tab }));
            expect(container.querySelector('[data-input-id]')).toBeNull();
            expect(container.textContent).not.toMatch(/임명하기|파면|봉신으로 세우기|계약 변경|계약 끝내기|속관으로 임명|해임|천거하기/);
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

    it('내 속관 — 보드 규칙 세 줄(D43) · 속관을 둘 수 있는 관직 안내, 입력 원장 행이 없어 임명 · 해임 단추 없음', () => {
        session.value = { generalId: 7, frontInfo: { general: { nationId: 1 } } };
        render(<OfficesScreen />);
        fireEvent.click(screen.getByRole('tab', { name: '내 속관' }));
        const panel = screen.getByRole('tabpanel', { name: '내 속관' });
        for (const name of ['내 속관', '고른 자리', '속관의 규칙']) expect(within(panel).getByRole('region', { name })).toBeInTheDocument();
        const rules = within(panel).getByRole('region', { name: '속관의 규칙' });
        expect(within(rules).getAllByRole('listitem').map((li) => li.firstElementChild?.textContent)).toEqual([
            '속관은 내 부 소속에게만 줍니다.',
            '내가 이 관직을 잃으면 속관도 모두 함께 물러납니다.',
            '속관을 지낸 사람과의 인연은 결속으로 남습니다.',
        ]);
        expect(panel).toHaveTextContent('현령 · 현장은 속관을 두지 않습니다');
        expect(panel.querySelector('[data-input-id]')).toBeNull();
    });

    it('추천 · 자칭에 부하 천거 · 중앙 관직 묶음(D44 · D45) 안내', () => {
        session.value = { generalId: 7, frontInfo: { general: { nationId: 1 } } };
        render(<OfficesScreen />);
        fireEvent.click(screen.getByRole('tab', { name: '추천 · 자칭' }));
        expect(screen.getByRole('tabpanel', { name: '추천 · 자칭' })).toHaveTextContent('천거는 정원 없이 조정이 심의합니다');
        fireEvent.click(screen.getByRole('tab', { name: '중앙 관직' }));
        expect(screen.getByRole('tabpanel', { name: '중앙 관직' })).toHaveTextContent('상공 · 삼공 · 구경 · 상서 · 장군 · 소부에 딸린 자리');
    });
});
