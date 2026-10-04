// 역정보(P-K06) 골격 — 내가 건 역정보 · 고른 역정보는 서버 대기(K8-07), 상대는 장수, 피해자 쪽 표식 · 카드 이름 · 입력 단추는 없다.
import { render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, children, ...rest }: { slug: string; children: ReactNode }) => <a href={`/game/pep/${slug}`} {...rest}>{children}</a>,
}));

import CounterIntelScreen from '@/components/counter-intel/CounterIntelScreen';

describe('CounterIntelScreen', () => {
    it('내가 건 역정보 · 고른 역정보 — 영역 전체 서버 대기(K8-07), 상대는 장수', () => {
        const { container } = render(<CounterIntelScreen />);
        const list = screen.getByRole('region', { name: '내가 건 역정보' });
        const detail = screen.getByRole('region', { name: '고른 역정보' });
        expect(Array.from(container.querySelectorAll<HTMLElement>('[data-server-wait]')).map((el) => el.dataset.serverWait)).toEqual(['K8-07', 'K8-07']);
        for (const region of [list, detail]) expect(region.querySelector('[data-server-wait="K8-07"] .os-status--waiting')).not.toBeNull();
        expect(list).toHaveTextContent('나에게만 보입니다');
        expect(list).toHaveTextContent('상대 장수');
        expect(container.textContent).not.toMatch(/상대 세력|\[세력\]/); // 피해자는 세력이 아니라 장수다(victimGeneralId)
    });

    it('안내는 확정 규칙만 — 상대는 모른다 · 다시 첩보하면 사라진다 · 싸움 · 보급에 끼지 않는다, 카드 이름은 아직 쓰지 않는다', () => {
        const { container } = render(<CounterIntelScreen />);
        const guide = screen.getByRole('region', { name: '알아 둘 것' });
        expect(guide).toHaveTextContent('상대는 이것이 가짜인 줄 모릅니다');
        expect(guide).toHaveTextContent('다시 첩보하면 사라집니다');
        expect(guide).toHaveTextContent('싸움 · 보급 길에 끼지 않습니다');
        expect(container.textContent).not.toMatch(/반간|의병|허보|들킬/); // 카드 뜻(C5) · 발각 확률 공개는 결정 전
    });

    it('누를 것은 계책 덱 고리 하나 — 입력 단추는 그리지 않는다', () => {
        const { container } = render(<CounterIntelScreen />);
        expect(container.querySelector('[data-input-id]')).toBeNull();
        expect(container.querySelectorAll('button')).toHaveLength(0);
        const links = within(container).getAllByRole('link');
        expect(links.map((a) => [a.textContent, a.getAttribute('href')])).toEqual([['계책 덱으로', '/game/pep/stratagem']]);
    });
});
