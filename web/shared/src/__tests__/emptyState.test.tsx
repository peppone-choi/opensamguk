import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EMPTY_ILLUSTRATION_FILE, EMPTY_ILLUSTRATION_PATH, EmptyState } from '../EmptyState';

const APPS = ['game', 'gateway'];

describe('EmptyState', () => {
  it('renders a decorative illustration only when asked', () => {
    const { container, rerender } = render(<EmptyState title="기록이 없습니다." />);
    expect(container.querySelector('img')).toBeNull();
    rerender(<EmptyState illustration="records" title="기록이 없습니다." />);
    const img = container.querySelector('img')!;
    expect(img.getAttribute('src')).toBe('/illustrations/records-empty.svg');
    expect(img.getAttribute('alt')).toBe('');
    expect(img.getAttribute('aria-hidden')).toBe('true');
    expect(container.querySelector('.os-empty__title')!.textContent).toBe('기록이 없습니다.');
  });

  it.each(APPS)('every illustration file is exported into web/%s/public', (app) => {
    for (const file of Object.values(EMPTY_ILLUSTRATION_FILE)) {
      const path = resolve(__dirname, `../../../${app}/public${EMPTY_ILLUSTRATION_PATH}/${file}.svg`);
      expect(existsSync(path), path).toBe(true);
    }
  });
});
