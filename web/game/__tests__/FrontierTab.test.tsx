// 주변 세계(P-K08) — 외교 「주변 세계」 탭 내용 골격. 읽기(K8-09)가 없어 서버 대기, 접촉 원장이 없으면 「자료 없음」(D29), 내륙이면 빈 상태.
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { expectServerWait, expectServerWaitGone } from '@opensamguk/ui';
import { FrontierTab } from '@/components/frontier/FrontierTab';

describe('FrontierTab', () => {
    it('지금(읽기 없음) — 영역 전체 서버 대기(K8-09), 지어낸 행위자 · 관계 칩 없음', () => {
        const { container } = render(<FrontierTab />);
        expectServerWait(container, ['K8-09']); // 공통 도우미(@opensamguk/ui serverWaitTesting) — 기다리는 행이 정확히 K8-09
        expect(container.querySelector('[data-server-wait="K8-09"] .os-status--waiting')).not.toBeNull();
        expect(screen.getByText('주변 세계 준비 중')).toBeInTheDocument();
        expect(container.textContent).not.toMatch(/남흉노|오환|선비|서강|백마저|부여|고구려|적대|교역 중/); // 원장 8행은 전부 CANDIDATE — 이름 · 관계를 짓지 않는다
        expect(container.querySelectorAll('button, a, [data-input-id]')).toHaveLength(0);
        expect(screen.getByText(/주변 세계는 지도 밖 세력입니다/)).toBeInTheDocument();
    });

    it('접촉 원장이 없으면 「자료 없음」(D29) — 빈 상태와 다르고, 다시 읽기를 부른다', () => {
        const onReload = vi.fn();
        const { container } = render(<FrontierTab load={{ state: 'unavailable', onReload }} />);
        expect(container.querySelector('.os-status--unavailable')).not.toBeNull();
        expect(screen.getByText('주변 세계를 읽을 수 없습니다')).toBeInTheDocument();
        expect(screen.queryByText('접경한 주변 세계가 없습니다')).toBeNull();
        expectServerWaitGone(container, ['K8-09'], { value: '주변 세계를 읽을 수 없습니다' }); // 서버가 답하면 K8-09 표지는 사라진다
        expectServerWait(container, []); // 남은 서버 대기 표지도 없다
        fireEvent.click(screen.getByRole('button', { name: /다시 읽기/ }));
        expect(onReload).toHaveBeenCalledTimes(1);
    });

    it('내륙 세력 — 「접경한 주변 세계가 없습니다」', () => {
        const { container } = render(<FrontierTab load={{ state: 'empty' }} />);
        expect(container.querySelector('.os-status--empty')).not.toBeNull();
        expect(screen.getByText('접경한 주변 세계가 없습니다')).toBeInTheDocument();
        expect(screen.queryByText('주변 세계를 읽을 수 없습니다')).toBeNull();
    });
});
