import { fireEvent, render, screen } from '@testing-library/react';
import { Brand, Button, Card, Table } from '@opensamguk/ui';
import ConfirmModal from '@/components/ConfirmModal';
import BoardShell from '@/components/board/BoardShell';
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
    expect(brand).toHaveAttribute('src', '/logo-wordmark.png');
    expect(brand).toHaveAttribute('width', '86');
    expect(brand).toHaveAttribute('height', '32');
    expect(screen.getByRole('button', { name: '확인' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '확인' })).toHaveAttribute('type', 'button');
  });

  it('preserves the board brand link', () => {
    render(<BoardShell><p>게시판</p></BoardShell>);

    expect(screen.getByRole('link', { name: '오픈삼국' })).toHaveAttribute('href', '/lobby');
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

    expect(screen.getByRole('dialog', { name: '삭제 확인' })).toHaveFocus();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onCancel).not.toHaveBeenCalled();
  });
});
