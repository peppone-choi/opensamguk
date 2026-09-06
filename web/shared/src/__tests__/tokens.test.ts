import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// 토큰 파일 텍스트 계약(ADR-LITE-049 · S1). CSS 는 jsdom 이 계산하지 못하므로 선언 자체를 검사한다.
const css = readFileSync(join(__dirname, '..', 'tokens.css'), 'utf8');
const root = css.slice(css.indexOf(':root {'), css.indexOf('}', css.indexOf(':root {')));

describe('tokens.css (Concept A)', () => {
  it.each(['--bronze', '--bronze-dim', '--bronze-glow', '--moss', '--moss-2', '--rust', '--info', '--focus', '--panel', '--inset', '--raised', '--line', '--line-2', '--text', '--text-2', '--muted', '--font-serif'])('declares %s', (name) => {
    expect(root).toMatch(new RegExp(`${name}:`));
  });
  it('maps the legacy aliases onto the new palette and keeps corners square', () => {
    expect(root).toMatch(/--gold: var\(--bronze\)/);
    expect(root).toMatch(/--bg-card: var\(--panel\)/);
    expect(root).toMatch(/--border-subtle: var\(--line\)/);
    expect(root).toMatch(/--color-primary: var\(--bronze\)/);
    expect(root).toMatch(/--radius-sm: 0;/);
    expect(root).toMatch(/--radius-md: 0;/);
    expect(root).toMatch(/--radius-lg: 0;/);
    expect(root).toMatch(/--focus-ring: 3px solid var\(--focus\)/);
  });
  it('honours reduced motion and keeps the hero portrait uncropped', () => {
    expect(css).toMatch(/prefers-reduced-motion: reduce[^}]*--motion-turn: 0ms/s);
    expect(css).toMatch(/\.os-portrait--hero > img \{ object-fit: contain;/);
    expect(css).not.toMatch(/\.os-table td \{[^}]*white-space: nowrap/s);
  });
});
