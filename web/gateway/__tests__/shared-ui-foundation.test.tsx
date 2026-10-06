import { fireEvent, render, screen } from '@testing-library/react';
import { Brand, Button, Card, Table } from '@opensamguk/ui';
import ConfirmModal from '@/components/ConfirmModal';
import CommunityShell from '@/components/community/CommunityShell';
import { AuthProvider } from '@/lib/auth-context';
import { describe, expect, it, vi } from 'vitest';

describe('shared UI foundation', () => {
  it('preserves native accessible semantics', () => {
    render(
      <Card>
        <Brand size="large" />
        <Button disabled reason="테스트">확인</Button>
      </Card>,
    );

    const brand = screen.getByRole('img', { name: '오픈삼국' });
    expect(brand).toHaveAttribute('src', '/logo-wordmark-sm.png');
    expect(brand).toHaveAttribute('width', '86');
    expect(brand).toHaveAttribute('height', '32');
    // 비활성도 누를 수 있게 aria-disabled 로 두고, 누르면 사유가 열린다(ADR-LITE-049 (7)).
    expect(screen.getByRole('button', { name: '확인' })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('button', { name: '확인' })).toHaveAccessibleDescription('테스트');
    expect(screen.getByRole('button', { name: '확인' })).toHaveAttribute('type', 'button');
  });

  it('preserves the board brand link', () => {
    const user = { id: 1, username: 'tester', email: null, nickname: '테스터', role: 'USER', picture: null, imageServer: 0 };
    render(<AuthProvider initialUser={user}><CommunityShell><p>게시판</p></CommunityShell></AuthProvider>);

    // 커뮤니티 셸(P-G06~G08) — 회원 머리줄의 로고는 로비로 간다.
    expect(screen.getByRole('link', { name: '오픈삼국 — 로비로' })).toHaveAttribute('href', '/lobby');
  });

  it('exports a semantic shared table surface', () => {
    render(<Table caption="서버 현황" headers={['서버', '상태']} rows={[["청룡", '운영 중']]} />);

    expect(screen.getByRole('table', { name: '서버 현황' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '서버' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '운영 중' })).toBeInTheDocument();
  });

  it('preserves dialog focus and keyboard cancellation', () => {
    const onCancel = vi.fn();

    render(
      <ConfirmModal
        open
        title="삭제 확인"
        message="정말 삭제하시겠습니까?"
        confirmLabel="삭제"
        onConfirm={vi.fn()}
        onCancel={onCancel}
      />,
    );

    expect(screen.getByRole('dialog', { name: '삭제 확인' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '삭제' })).toHaveFocus();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it('keeps busy confirmation focus inside the dialog and blocks cancellation', () => {
    const onCancel = vi.fn();

    render(
      <ConfirmModal
        open
        busy
        title="삭제 확인"
        message="처리 중입니다."
        confirmLabel="삭제"
        onConfirm={vi.fn()}
        onCancel={onCancel}
      />,
    );

    const dialog = screen.getByRole('dialog', { name: '삭제 확인' });
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(onCancel).not.toHaveBeenCalled();
  });
});
