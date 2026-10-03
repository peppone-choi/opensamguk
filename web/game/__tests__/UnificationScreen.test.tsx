// 천하 형세(P-H04) 골격 — 13주 격자(지도 州 층과 같은 이름표), 통일 조건 두 칸(새 규칙), 서버 대기 칸마다 계약판 행.
import { fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { JU_NAMES, juDisplayName } from '@opensamguk/ui';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, query = '', children, ...rest }: { slug: string; query?: string; children: ReactNode }) => <a href={`/game/pep/${slug}${query}`} {...rest}>{children}</a>,
}));

import UnificationScreen from '@/components/unification/UnificationScreen';

afterEach(() => vi.unstubAllEnvs());

describe('UnificationScreen', () => {
    it('13주 — 데이터 키 13개(JU_NAMES)를 화면 이름(D25)으로, 칸마다 「준비 중」(K8-13), 군국만 센다는 안내', () => {
        render(<UnificationScreen />);
        const grid = within(screen.getByRole('region', { name: '13주' })).getByRole('list', { name: '13주' });
        const tiles = within(grid).getAllByRole('listitem').filter((li) => li.querySelector('[data-server-wait]'));
        expect(tiles).toHaveLength(13);
        expect(tiles.map((t) => t.dataset.ju)).toEqual([...JU_NAMES]);
        const shown = tiles.map((t) => t.firstElementChild?.textContent);
        expect(shown).toEqual(JU_NAMES.map(juDisplayName));
        expect(shown).toEqual(expect.arrayContaining(['사례', '양주', '서량']));
        expect(shown).not.toEqual(expect.arrayContaining(['사예'])); // 데이터 키를 그대로 찍지 않는다
        expect(shown).not.toEqual(expect.arrayContaining(['량주']));
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
        // 누를 것은 지도 단추 하나뿐이다(고를 자료가 없어 세력 · 내 몫에는 없다).
        expect(Array.from(container.querySelectorAll('a, button')).map((el) => el.textContent)).toEqual(['지도에서 보기 — 주 경계']);
    });

    it('지도에서 보기 — 주 경계: 새 지도 스위치가 꺼진 빌드는 사유가 있는 비활성(네이티브 disabled 아님), 누르면 「새 지도에서 열립니다」', () => {
        vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '');
        render(<UnificationScreen />);
        const zhou = screen.getByRole('region', { name: '13주' });
        const button = within(zhou).getByRole('button', { name: /지도에서 보기 — 주 경계/ });
        expect(button).toHaveAttribute('aria-disabled', 'true');
        expect(button).not.toBeDisabled();
        expect(button).not.toHaveAttribute('href');
        fireEvent.click(button);
        const sheet = screen.getByRole('dialog');
        expect(sheet).toHaveTextContent('새 지도에서 열립니다');
        expect(sheet).toHaveTextContent('주 경계 보기는 새 지도에서만 바로 열 수 있습니다');
    });

    it('지도에서 보기 — 주 경계: 새 지도 스위치가 켜진 빌드는 작전실을 주 보기(?view=ju)로 여는 고리(K2 #1213)', () => {
        vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '1');
        render(<UnificationScreen />);
        const zhou = screen.getByRole('region', { name: '13주' });
        const link = within(zhou).getByRole('link', { name: /지도에서 보기 — 주 경계/ });
        expect(link).toHaveAttribute('href', '/game/pep/?view=ju');
        expect(link).not.toHaveAttribute('aria-disabled');
        expect(within(zhou).queryByRole('button', { name: /지도에서 보기/ })).toBeNull();
    });
});
