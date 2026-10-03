// 시즌 결산(P-H05) 골격 — 값 칸(시즌 결과 · 주요 인물 · 시즌)은 서버 대기(K8-14), 다음 시즌 안내만 지금 보인다.
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import SeasonResultScreen from '@/components/season-result/SeasonResultScreen';

describe('SeasonResultScreen', () => {
    it('시즌 결과 · 주요 인물 · 시즌 — 영역 전체 서버 대기(K8-14), 지어낸 결과 · 사람 없음', () => {
        const { container } = render(<SeasonResultScreen />);
        for (const name of ['시즌 결과', '주요 인물', '시즌']) {
            const region = screen.getByRole('region', { name });
            expect(region.querySelector('[data-server-wait="K8-14"] .os-status--waiting')).not.toBeNull();
        }
        expect(Array.from(container.querySelectorAll<HTMLElement>('[data-server-wait]')).map((el) => el.dataset.serverWait)).toEqual(['K8-14', 'K8-14', 'K8-14']);
        // 끝났는지 모른다 — 「통일했습니다」 · 「끝났습니다」 · 「기한이 끝나」를 그리지 않는다.
        expect(container.textContent).not.toMatch(/통일했습니다|시즌은 끝났습니다|기한이 끝나|새 순이 돌지 않습니다/);
        expect(container.textContent).not.toMatch(/명예의 전당/);
        expect(container.querySelectorAll('button, a, [data-input-id]')).toHaveLength(0);
    });

    it('다음 시즌 — 이월 없음 · 계정은 남는다(정책 그대로)', () => {
        render(<SeasonResultScreen />);
        const next = screen.getByRole('region', { name: '다음 시즌' });
        expect(next).toHaveTextContent('장수 · 부 · 자원은 다음 시즌으로 넘어가지 않습니다');
        expect(next).toHaveTextContent('계정은 남고');
        expect(next.querySelector('[data-server-wait]')).toBeNull();
    });
});
