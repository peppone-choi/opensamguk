import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const originalGameApiUrl = process.env.GAME_API_URL;
const originalGameApiOrigin = process.env.GAME_API_ORIGIN;
const originalRegistry = process.env.SERVER_REGISTRY_JSON;

beforeEach(() => {
  process.env.GAME_API_URL = 'http://default-game-api';
  delete process.env.GAME_API_ORIGIN;
  delete process.env.SERVER_REGISTRY_JSON;
});

afterEach(() => {
  vi.resetModules();
});

afterAll(() => {
  if (originalGameApiUrl === undefined) delete process.env.GAME_API_URL;
  else process.env.GAME_API_URL = originalGameApiUrl;
  if (originalGameApiOrigin === undefined) delete process.env.GAME_API_ORIGIN;
  else process.env.GAME_API_ORIGIN = originalGameApiOrigin;
  if (originalRegistry === undefined) delete process.env.SERVER_REGISTRY_JSON;
  else process.env.SERVER_REGISTRY_JSON = originalRegistry;
});

describe('game server registry canonical IDs', () => {
  it('exposes only lowercase alphanumeric runtime entries up to 48 characters', async () => {
    const maxLengthId = 'a'.repeat(48);
    const tooLongId = 'a'.repeat(49);
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://pep-game-api' },
      { id: maxLengthId, gameApiUrl: 'http://max-game-api' },
      { id: 'A1', gameApiUrl: 'http://uppercase-game-api' },
      { id: tooLongId, gameApiUrl: 'http://too-long-game-api' },
      { id: 'pep-id', gameApiUrl: 'http://hyphen-game-api' },
      { id: 'pep_id', gameApiUrl: 'http://underscore-game-api' },
      { id: ' pep ', gameApiUrl: 'http://spaced-game-api' },
    ]);

    const registry = await import('@/lib/serverRegistry');

    expect(registry.resolveGameApiUrl('pep')).toBe('http://pep-game-api');
    expect(registry.resolveGameApiUrl(maxLengthId)).toBe('http://max-game-api');
    for (const invalidId of ['A1', tooLongId, 'pep-id', 'pep_id', ' pep ', 'stale']) {
      expect(registry.resolveGameApiUrl(invalidId)).toBeUndefined();
    }
  });

  it('filters noncanonical IDs from object-form runtime registries too', async () => {
    process.env.SERVER_REGISTRY_JSON = JSON.stringify({
      pep: 'http://pep-game-api',
      a1: { gameApiUrl: 'http://a1-game-api' },
      A1: 'http://uppercase-game-api',
      'pep-id': 'http://hyphen-game-api',
      pep_id: 'http://underscore-game-api',
    });

    const registry = await import('@/lib/serverRegistry');

    expect(registry.resolveGameApiUrl('pep')).toBe('http://pep-game-api');
    expect(registry.resolveGameApiUrl('a1')).toBe('http://a1-game-api');
    expect(registry.resolveGameApiUrl('A1')).toBeUndefined();
    expect(registry.resolveGameApiUrl('pep-id')).toBeUndefined();
    expect(registry.resolveGameApiUrl('pep_id')).toBeUndefined();
  });

  it('uses the default only for an absent selector', async () => {
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://pep-game-api' },
    ]);

    const registry = await import('@/lib/serverRegistry');

    expect(registry.resolveGameApiUrl(undefined)).toBe('http://default-game-api');
    expect(registry.resolveGameApiUrl(null)).toBe('http://default-game-api');
    expect(registry.resolveGameApiUrl('main')).toBeUndefined();
    expect(registry.resolveGameApiUrl('current')).toBeUndefined();
    expect(registry.resolveGameApiUrl('stale')).toBeUndefined();
  });
});
