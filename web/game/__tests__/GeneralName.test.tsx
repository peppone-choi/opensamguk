import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import GeneralName from '@/components/game/GeneralName';

describe('GeneralName', () => {
  it('황제 특별 NPC 이름 앞에 황제 배지를 표시한다', () => {
    render(<GeneralName name="유협" npcType={7} />);

    expect(screen.getByRole('img', { name: '황제' })).toHaveAttribute(
      'src',
      '/status/imperial-npc.png',
    );
    expect(screen.getByRole('img', { name: '황제' })).toHaveAttribute(
      'srcset',
      '/status/imperial-npc.png 1x, /status/2x/imperial-npc.png 2x',
    );
    expect(screen.getByText('유협')).toBeInTheDocument();
  });

  it('일반 NPC에는 황제 배지를 표시하지 않는다', () => {
    render(<GeneralName name="조조" npcType={2} />);

    expect(screen.queryByRole('img', { name: '황제' })).not.toBeInTheDocument();
    expect(screen.getByText('조조').parentElement).toHaveStyle({ color: 'rgb(0, 255, 255)' });
  });
});
