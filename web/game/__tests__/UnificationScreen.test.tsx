// 천하 형세(P-H04) 골격 — 13주 격자(지도 州 층과 같은 이름표), 통일 조건 두 칸(새 규칙), 서버 대기 칸마다 계약판 행.
import { render, screen, within } from '@testing-library/react';
import { JU_NAMES } from '@opensamguk/ui';
import { describe, expect, it } from 'vitest';
import UnificationScreen from '@/components/unification/UnificationScreen';

describe('UnificationScreen', () => {
    it('13주 — 이름 13개(JU_NAMES)마다 「준비 중」(K8-13), 군국만 센다는 안내', () => {
        render(<UnificationScreen />);
        const grid = within(screen.getByRole('region', { name: '13주' })).getByRole('list', { name: '13주' });
        const tiles = within(grid).getAllByRole('listitem').filter((li) => li.querySelector('[data-server-wait]'));
        expect(tiles).toHaveLength(13);
        expect(tiles.map((t) => t.firstElementChild?.textContent)).toEqual([...JU_NAMES]);
        for (const t of tiles) expect(t.querySelector('[data-server-wait="K8-13"]')).toHaveTextContent('준비 중');
        expect(grid).toHaveTextContent('군국 밖 거점은 세지 않습니다');
    });

    it('통일 조건 — ① 13주 · 군국을 모두 쥔다(뜻 미정) ② 칭제(K8-15). 옛 규칙(호구 · 기간)은 없다', () => {
        const { container } = render(<UnificationScreen />);
        const cond = screen.getByRole('region', { name: '통일 조건' });
        expect(cond).toHaveTextContent('① 13주 · 군국을 모두 쥔다');
        expect(cond).toHaveTextContent('「쥔다」의 뜻은 아직 정해지지 않았습니다');
        expect(cond).toHaveTextContent('② 칭제');
        expect(cond.querySelector('[data-server-wait="K8-15"]')).not.toBeNull();
        expect(container.textContent).not.toMatch(/호구|점유율|유지 기간|기준선/);
    });

    it('세력 · 내 몫 — 서버 대기 A(K8-13), 가짜 순위를 그리지 않는다', () => {
        const { container } = render(<UnificationScreen />);
        for (const name of ['세력', '내 몫']) {
            const region = screen.getByRole('region', { name });
            expect(region.querySelector('[data-server-wait="K8-13"] .os-status--waiting')).not.toBeNull();
        }
        expect(container.querySelectorAll('a, button')).toHaveLength(0); // 누를 것이 없다(고를 자료가 없다)
    });
});
