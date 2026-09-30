import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { BREAKPOINTS, MEDIA, viewportClass } from '../breakpoints';

// 화면 폭 3단은 한 곳(breakpoints.ts)에서 정하고, tokens.css 의 --bp-* 는 같은 값을 적은 참고값이다.
const css = readFileSync(join(__dirname, '..', 'tokens.css'), 'utf8');
const root = css.slice(css.indexOf(':root {'), css.indexOf('}', css.indexOf(':root {')));

describe('breakpoints', () => {
  it('has exactly the three v3.1 bands', () => {
    expect(BREAKPOINTS).toEqual({ tablet: 768, desktop: 1200 });
    expect([0, 390, 767].map(viewportClass)).toEqual(['mobile', 'mobile', 'mobile']);
    expect([768, 1024, 1199].map(viewportClass)).toEqual(['tablet', 'tablet', 'tablet']);
    expect([1200, 1440].map(viewportClass)).toEqual(['desktop', 'desktop']);
  });

  it('writes media conditions that neither overlap nor leave a gap', () => {
    expect(MEDIA.mobile).toBe('(max-width: 767.98px)');
    expect(MEDIA.tablet).toBe('(min-width: 768px) and (max-width: 1199.98px)');
    expect(MEDIA.desktop).toBe('(min-width: 1200px)');
    expect(MEDIA.tabletUp).toBe('(min-width: 768px)');
  });

  it('keeps the tokens.css reference values equal to BREAKPOINTS', () => {
    expect(root).toMatch(new RegExp(`--bp-tablet: ${BREAKPOINTS.tablet}px;`));
    expect(root).toMatch(new RegExp(`--bp-desktop: ${BREAKPOINTS.desktop}px;`));
  });

  it.each(['--header-h', '--rail-w', '--tabbar-h', '--turns-w', '--flow-w', '--drawer-w', '--sheet-peek', '--touch-target',
    '--z-map', '--z-map-mark', '--z-map-ctrl', '--z-float', '--z-drawer', '--z-sheet', '--z-dialog', '--z-toast',
    '--shadow-float', '--shadow-pop', '--shadow-sheet', '--shadow-dialog'])('declares the v3.1 shell token %s', (name) => {
    expect(root).toMatch(new RegExp(`${name}:`));
  });

  it('stacks layers in the order the design system fixes (map lowest, toast highest)', () => {
    const z = ['--z-map', '--z-map-mark', '--z-map-ctrl', '--z-float', '--z-drawer', '--z-sheet', '--z-dialog', '--z-toast']
      .map((name) => Number(new RegExp(`${name}: (\\d+);`).exec(root)?.[1]));
    expect(z.every(Number.isFinite)).toBe(true);
    expect([...z].sort((a, b) => a - b)).toEqual(z);
    expect(new Set(z).size).toBe(z.length);
  });
});
