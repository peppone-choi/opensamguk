import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const originalGameApiUrl = process.env.GAME_API_URL;
const originalGameApiOrigin = process.env.GAME_API_ORIGIN;
const originalRegistry = process.env.SERVER_REGISTRY_JSON;
const originalServerId = process.env.SERVER_ID;

beforeEach(() => {
  process.env.GAME_API_URL = 'http://default-game-api';
  delete process.env.GAME_API_ORIGIN;
  delete process.env.SERVER_REGISTRY_JSON;
  delete process.env.SERVER_ID;
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
  if (originalServerId === undefined) delete process.env.SERVER_ID;
  else process.env.SERVER_ID = originalServerId;
});

describe('game server registry canonical IDs', () => {
  it('resolves a fully validated lowercase alphanumeric runtime registry', async () => {
    const maxLengthId = 'a'.repeat(48);
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
      { id: maxLengthId, gameApiUrl: `http://s${maxLengthId}-game-api:8081` },
    ]);

    const registry = await import('@/lib/serverRegistry');

    expect(registry.resolveGameApiUrl('pep')).toBe('http://spep-game-api:8081');
    expect(registry.resolveGameApiUrl(maxLengthId)).toBe(`http://s${maxLengthId}-game-api:8081`);
  });

  it('fails closed when a mixed registry contains an invalid public ID', async () => {
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
      { id: 'main', gameApiUrl: 'http://smain-game-api:8081' },
    ]);

    const registry = await import('@/lib/serverRegistry');

    expect(registry.resolveGameApiUrl('pep')).toBeUndefined();
  });

  it('fails closed on canonical collisions and bad origins', async () => {
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
      { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
    ]);

    let registry = await import('@/lib/serverRegistry');
    expect(registry.resolveGameApiUrl('pep')).toBeUndefined();

    vi.resetModules();
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://wrong-game-api:8081' },
    ]);
    registry = await import('@/lib/serverRegistry');
    expect(registry.resolveGameApiUrl('pep')).toBeUndefined();
  });

  it('fails closed for object-form runtime registry duplicate keys', async () => {
    process.env.SERVER_REGISTRY_JSON =
      '{"pep":"http://wrong-game-api:8081","pep":"http://spep-game-api:8081"}';

    const registry = await import('@/lib/serverRegistry');

    expect(registry.resolveGameApiUrl('pep')).toBeUndefined();
  });

  it('uses the default only for an absent selector', async () => {
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
    ]);

    const registry = await import('@/lib/serverRegistry');

    expect(registry.resolveGameApiUrl(undefined)).toBe('http://default-game-api');
    expect(registry.resolveGameApiUrl(null)).toBe('http://default-game-api');
    expect(registry.resolveGameApiUrl('main')).toBeUndefined();
    expect(registry.resolveGameApiUrl('current')).toBeUndefined();
    expect(registry.resolveGameApiUrl('stale')).toBeUndefined();
  });

  it('uses GAME_API_URL for this canonical default-compose server without a registry', async () => {
    process.env.GAME_API_URL = 'http://game-api:8081';
    process.env.SERVER_ID = 's1';

    const registry = await import('@/lib/serverRegistry');

    expect(registry.resolveGameApiUrl('s1')).toBe('http://game-api:8081');
    expect(registry.resolveGameApiUrl('s2')).toBeUndefined();
  });

  it('permits current as the configured public server ID', async () => {
    process.env.SERVER_ID = 'current';

    const registry = await import('@/lib/serverRegistry');

    expect(registry.resolveGameApiUrl('current')).toBe('http://default-game-api');
  });
});
