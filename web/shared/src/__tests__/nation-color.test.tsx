// 국가색은 #rrggbb 만 화면 속성에 넣는다(K3 2026-10-04, 원장 D90).
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Flag } from '../Flag';
import { NO_NATION_COLOR, safeNationColor } from '../nationVisual';
import { Portrait } from '../Portrait';

describe('safeNationColor', () => {
  it('#rrggbb(대소문자 무관)는 그대로', () => {
    expect(safeNationColor('#800000')).toBe('#800000');
    expect(safeNationColor('#A1B2C3')).toBe('#A1B2C3');
  });
  it('그 밖의 값은 기본색(또는 넘긴 값)', () => {
    for (const bad of ['', 'red', '#fff', '#12345', '#1234567', ' #800000', '#800000;', 'url(x)', null, undefined, 3])
      expect(safeNationColor(bad), String(bad)).toBe(NO_NATION_COLOR);
    expect(safeNationColor('red', '#000000')).toBe('#000000');
  });
});

describe('국가색을 쓰는 공용 부품', () => {
  it('Flag 는 형식이 맞지 않는 색을 기본색으로 칠한다', () => {
    const { container, rerender } = render(<Flag color="#800000" />);
    expect(container.querySelector('path')).toHaveAttribute('fill', '#800000');
    rerender(<Flag color="url(x)" />);
    expect(container.querySelector('path')).toHaveAttribute('fill', NO_NATION_COLOR);
    expect(container.querySelector('svg')).toHaveAttribute('data-nation-color', NO_NATION_COLOR);
  });

  it('Portrait 테두리 색(--nation)도 같은 규칙', () => {
    const { container, rerender } = render(<Portrait picture={null} size="icon" alt="" ring={{ color: '#2e7d32', reason: 'context' }} />);
    const frame = () => container.querySelector('.os-portrait') as HTMLElement;
    expect(frame().style.getPropertyValue('--nation')).toBe('#2e7d32');
    rerender(<Portrait picture={null} size="icon" alt="" ring={{ color: 'url(x)', reason: 'context' }} />);
    expect(frame().style.getPropertyValue('--nation')).toBe(NO_NATION_COLOR);
  });
});
