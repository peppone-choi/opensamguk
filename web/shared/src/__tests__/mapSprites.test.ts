import { afterEach, expect, it, vi } from 'vitest';
import { acquireMapSprite } from '../iso/mapSprites';
afterEach(() => vi.unstubAllGlobals());
it('removes the image source and rejects when its last reader cancels', async () => {
  const image = document.createElement('img');
  vi.stubGlobal('Image', class { constructor() { return image; } });
  const lease = acquireMapSprite('/cancelled-sprite.png');
  const rejected = expect(lease.promise).rejects.toMatchObject({ name: 'AbortError' });
  await Promise.resolve();
  expect(image.getAttribute('src')).toBe('/cancelled-sprite.png');
  lease.release();
  await rejected;
  expect(image.hasAttribute('src')).toBe(false);
});
