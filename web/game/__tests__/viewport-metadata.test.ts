import { describe, expect, it } from 'vitest';
import * as rootLayout from '@/app/layout';

describe('mobile viewport metadata', () => {
  it('enables safe-area environment insets for the fixed bottom navigation', () => {
    const exported = rootLayout as unknown as {
      viewport?: { width?: string; initialScale?: number; viewportFit?: string };
    };

    expect(exported.viewport).toEqual({
      width: 'device-width',
      initialScale: 1,
      viewportFit: 'cover',
    });
  });
});
