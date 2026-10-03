// 참모 제안(P-K05) 골격 — 이번 순 제안 · 고른 제안은 서버 대기(K8-06), 안내 세 줄(D59 다시 오지 않음), 채택 · 거부 단추 없음(원장 행 없음).
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ProposalsScreen from '@/components/proposals/ProposalsScreen';

describe('ProposalsScreen', () => {
    it('이번 순 제안 · 고른 제안 — 영역 전체 서버 대기(K8-06), 지어낸 제안 · 확신 숫자 없음', () => {
        const { container } = render(<ProposalsScreen />);
        expect(Array.from(container.querySelectorAll<HTMLElement>('[data-server-wait]')).map((el) => el.dataset.serverWait)).toEqual(['K8-06', 'K8-06']);
        for (const name of ['이번 순 제안', '고른 제안']) {
            expect(screen.getByRole('region', { name }).querySelector('[data-server-wait="K8-06"] .os-status--waiting')).not.toBeNull();
        }
        expect(container.textContent).not.toMatch(/순욱|이전|허저|확신 \d|회의/);
    });

    it('알아 둘 것 — 보드 안내 세 줄, 거부 · 만료된 제안은 다시 오지 않는다(D59)', () => {
        render(<ProposalsScreen />);
        const guide = screen.getByRole('region', { name: '알아 둘 것' });
        expect(within(guide).getAllByRole('listitem').map((li) => li.firstElementChild?.textContent)).toEqual([
            '채택하면 이 명령이 예약 순에 들어갑니다.',
            '고쳐서 채택은 명령 흐름에서 인자를 바꿉니다.',
            '거부 · 만료된 제안은 다시 오지 않습니다.',
        ]);
        expect(guide).toHaveTextContent('상황이 바뀌면 새 제안으로 옵니다');
    });

    it('채택 · 고쳐서 채택 · 거부 단추는 그리지 않는다 — 입력 원장 행이 없다', () => {
        const { container } = render(<ProposalsScreen />);
        expect(container.querySelectorAll('button, a, [data-input-id]')).toHaveLength(0);
    });
});
