import { render, screen } from '@testing-library/react';
import { Brand, Button, Card, Modal } from '@opensamguk/ui';
import GameCard from '@/components/GameCard';
import GameTable from '@/components/GameTable';
import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

describe('shared UI foundation', () => {
  it('keeps consumer classes while adding shared primitives', () => {
    const { container } = render(
      <Card className="consumer-card">
        <Brand />
        <Button className="consumer-button" variant="primary">실행</Button>
      </Card>,
    );

    const brand = screen.getByRole('img', { name: '오픈삼국' });
    expect(brand).toHaveAttribute('src', '/logo-wordmark.png');
    expect(brand).toHaveAttribute('width', '64');
    expect(brand).toHaveAttribute('height', '24');
    expect(screen.getByRole('button', { name: '실행' })).toHaveClass('consumer-button');
    expect(container.firstElementChild).toHaveClass('consumer-card');
  });

  it('preserves the existing game card class contract', () => {
    const { container } = render(<GameCard className="consumer-card">컨텐츠</GameCard>);

    expect(container.firstElementChild).toHaveClass('os-card', 'game-card', 'consumer-card');
  });

  it('uses the shared table while preserving the game class contract', () => {
    const { container } = render(<GameTable headers={['장수', '점수']} rows={[["관우", 100]]} />);

    expect(screen.getByRole('columnheader', { name: '장수' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '100' })).toBeInTheDocument();
    expect(container.querySelector('.os-table')).toHaveClass('game-table');
  });

  it('provides accessible Escape and backdrop cancellation for content modals', () => {
    const onClose = vi.fn();
    const { container } = render(
      <Modal ariaLabel="명령" onClose={onClose}>
        <button type="button">닫기</button>
      </Modal>,
    );

    expect(screen.getByRole('dialog', { name: '명령' })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.click(container.querySelector('.os-modal-overlay') as HTMLElement);
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('contains modal focus and restores the previously focused control', () => {
    const trigger = document.createElement('button');
    document.body.appendChild(trigger);
    trigger.focus();

    const { unmount } = render(
      <Modal ariaLabel="명령" onClose={() => undefined}>
        <button type="button">첫 번째</button>
        <button type="button">마지막</button>
      </Modal>,
    );

    const first = screen.getByRole('button', { name: '첫 번째' });
    const last = screen.getByRole('button', { name: '마지막' });
    expect(first).toHaveFocus();
    expect(document.body.style.overflow).toBe('hidden');
    expect(trigger).toHaveProperty('inert', true);
    expect(trigger).toHaveAttribute('aria-hidden', 'true');

    last.focus();
    fireEvent.keyDown(window, { key: 'Tab' });
    expect(first).toHaveFocus();

    first.focus();
    fireEvent.keyDown(window, { key: 'Tab', shiftKey: true });
    expect(last).toHaveFocus();

    unmount();
    expect(trigger).toHaveFocus();
    expect(document.body.style.overflow).toBe('');
    expect(trigger.inert).not.toBe(true);
    expect(trigger).not.toHaveAttribute('aria-hidden');
    trigger.remove();
  });

  it('focuses the dialog when it has no enabled controls', () => {
    render(<Modal ariaLabel="안내" onClose={() => undefined}>내용</Modal>);

    expect(screen.getByRole('dialog', { name: '안내' })).toHaveFocus();
  });
});
