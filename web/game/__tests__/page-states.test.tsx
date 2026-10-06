// 페이지 수준 상태(K3 2026-10-05, 보드 P-X01) — 없는 화면은 셸 안에서 「작전실로 · 기록으로」, 깨진 화면은 「다시 시도 + 오류 번호」.
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { STATUS_TEXT } from '@opensamguk/ui';
import { CrashScreen, NotFoundScreen } from '@/components/states/PageStates';
import { useGameSession } from '@/lib/campaign-session';

vi.mock('@/lib/campaign-session', () => ({ useGameSession: vi.fn() }));

describe('NotFoundScreen', () => {
    it('「찾는 화면이 없습니다」 + 작전실로 · 기록으로 — 주소는 지금 서버의 캠페인 경로', () => {
        vi.mocked(useGameSession).mockReturnValue({ serverId: 'pep' } as ReturnType<typeof useGameSession>);
        render(<NotFoundScreen />);
        expect(screen.getByRole('status')).toHaveTextContent(STATUS_TEXT.notFoundTitle);
        expect(screen.getByRole('link', { name: '작전실로' }).getAttribute('href')).toMatch(/^\/game\/pep\/?$/);
        expect(screen.getByRole('link', { name: '기록으로' })).toHaveAttribute('href', '/game/pep/records');
    });
});

describe('CrashScreen', () => {
    it('다시 시도는 경계를 되살리고(onRetry), 서버 digest 가 있으면 오류 번호를 보인다', () => {
        const onRetry = vi.fn();
        const { rerender } = render(<CrashScreen digest="1234567890" onRetry={onRetry} />);
        expect(screen.getByRole('alert')).toHaveTextContent('화면을 불러오지 못했습니다');
        expect(screen.getByRole('button', { name: '오류 번호 1234567890 복사' })).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: STATUS_TEXT.retry }));
        expect(onRetry).toHaveBeenCalledTimes(1);
        rerender(<CrashScreen onRetry={onRetry} />);
        expect(screen.queryByRole('button', { name: /오류 번호/ })).toBeNull();
    });
});
