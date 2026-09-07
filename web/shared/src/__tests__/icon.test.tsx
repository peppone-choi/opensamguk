import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Icon } from '../Icon';
import { ICON_NAMES, ICON_SPRITE_PATH } from '../icons';

const SPRITES = ['game', 'gateway'].map((app) => resolve(__dirname, `../../../${app}/public${ICON_SPRITE_PATH}`));

function spriteIds(path: string): string[] {
  return [...readFileSync(path, 'utf8').matchAll(/<symbol id="ico-([a-z0-9-]+)"/g)].map((m) => m[1]);
}

describe('Icon', () => {
  it('references the sprite symbol and is decorative unless labelled', () => {
    const { container, rerender } = render(<Icon name="search" />);
    const svg = container.querySelector('svg')!;
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.getAttribute('width')).toBe('16');
    expect(svg.querySelector('use')!.getAttribute('href')).toBe('/icons/icons.svg#ico-search');
    rerender(<Icon name="mail" size={20} label="서신" />);
    const labelled = container.querySelector('svg')!;
    expect(labelled.getAttribute('role')).toBe('img');
    expect(labelled.getAttribute('aria-label')).toBe('서신');
    expect(labelled.getAttribute('aria-hidden')).toBeNull();
  });

  it.each(SPRITES)('sprite %s carries exactly the names in ICON_NAMES', (path) => {
    const ids = spriteIds(path);
    expect(new Set(ids).size).toBe(ids.length);
    expect([...ids].sort()).toEqual([...ICON_NAMES].sort());
    const text = readFileSync(path, 'utf8');
    expect(text).not.toMatch(/(fill|stroke)="#/);
    expect(text).toContain('stroke="currentColor"');
  });
});
