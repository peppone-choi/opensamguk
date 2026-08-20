import { render, screen } from '@testing-library/react';
import { Brand, Button, Card } from '@opensamguk/ui';
import GameCard from '@/components/GameCard';
import { describe, expect, it } from 'vitest';

describe('shared UI foundation', () => {
  it('keeps consumer classes while adding shared primitives', () => {
    const { container } = render(
      <Card className="consumer-card">
        <Brand />
        <Button className="consumer-button" variant="primary">실행</Button>
      </Card>,
    );

    const brand = screen.getByRole('img', { name: '오픈삼국' });
    expect(new URL(brand.getAttribute('src') ?? '', window.location.origin).searchParams.get('url'))
      .toBe('/logo-wordmark.png');
    expect(brand).toHaveAttribute('width', '64');
    expect(brand).toHaveAttribute('height', '24');
    expect(screen.getByRole('button', { name: '실행' })).toHaveClass('consumer-button');
    expect(container.firstElementChild).toHaveClass('consumer-card');
  });

  it('preserves the existing game card class contract', () => {
    const { container } = render(<GameCard className="consumer-card">컨텐츠</GameCard>);

    expect(container.firstElementChild).toHaveClass('os-card', 'game-card', 'consumer-card');
  });
});
