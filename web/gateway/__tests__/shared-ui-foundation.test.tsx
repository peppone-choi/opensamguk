import { fireEvent, render, screen } from '@testing-library/react';
import { Brand, Button, Card } from '@opensamguk/ui';
import ConfirmModal from '@/components/ConfirmModal';
import BoardShell from '@/components/board/BoardShell';
import { describe, expect, it, vi } from 'vitest';

describe('shared UI foundation', () => {
  it('preserves native accessible semantics', () => {
    render(
      <Card>
        <Brand size="large" />
        <Button disabled>확인</Button>
      </Card>,
    );

    const brand = screen.getByRole('img', { name: '오픈삼국' });
    expect(new URL(brand.getAttribute('src') ?? '', window.location.origin).searchParams.get('url'))
      .toBe('/logo-wordmark.png');
    expect(brand).toHaveAttribute('width', '86');
    expect(brand).toHaveAttribute('height', '32');
    expect(screen.getByRole('button', { name: '확인' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '확인' })).toHaveAttribute('type', 'button');
  });

  it('preserves the board brand link', () => {
    render(<BoardShell><p>게시판</p></BoardShell>);

    expect(screen.getByRole('link', { name: '오픈삼국' })).toHaveAttribute('href', '/lobby');
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
});
