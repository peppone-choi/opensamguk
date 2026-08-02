import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const originalRegistry = process.env.SERVER_REGISTRY_JSON;

beforeEach(() => {
  delete process.env.SERVER_REGISTRY_JSON;
});

afterEach(() => {
  vi.doUnmock('@/config/servers.json');
  vi.resetModules();
});

afterAll(() => {
  if (originalRegistry === undefined) delete process.env.SERVER_REGISTRY_JSON;
  else process.env.SERVER_REGISTRY_JSON = originalRegistry;
});

describe('serverRegistry canonical IDs', () => {
  it('resolves a fully validated lowercase alphanumeric runtime registry', async () => {
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
      { id: 'a1', gameApiUrl: 'http://sa1-game-api:8081' },
    ]);

    const registry = await import('@/lib/serverRegistry');

    expect(registry.getServers().map((server) => server.id)).toEqual(['pep', 'a1']);
    expect(registry.resolveGameApiOrigin('pep')).toBe('http://spep-game-api:8081');
    expect(registry.resolveGameApiOrigin('A1')).toBeUndefined();
  });

  it('fails closed when any runtime entry is invalid instead of falling back to a valid prefix', async () => {
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
      { id: 'main', gameApiUrl: 'http://smain-game-api:8081' },
    ]);

    const registry = await import('@/lib/serverRegistry');

    expect(registry.getServers()).toEqual([]);
    expect(registry.resolveGameApiOrigin('pep')).toBeUndefined();
    expect(registry.isValidEmptyServerRegistry()).toBe(false);
  });

  it.each([
    ['malformed JSON', '{'],
    ['a non-array JSON value', '{}'],
    [
      'a mixed-validity collection',
      JSON.stringify([
        { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
        { id: 'main', gameApiUrl: 'http://smain-game-api:8081' },
      ]),
    ],
  ])('does not classify %s as an empty valid runtime registry', async (_description, raw) => {
    process.env.SERVER_REGISTRY_JSON = raw;

    const registry = await import('@/lib/serverRegistry');

    expect(registry.getServers()).toEqual([]);
    expect(registry.isValidEmptyServerRegistry()).toBe(false);
  });

  it('classifies an explicit empty runtime registry as valid and empty', async () => {
    process.env.SERVER_REGISTRY_JSON = '[]';

    const registry = await import('@/lib/serverRegistry');

    expect(registry.getServers()).toEqual([]);
    expect(registry.isValidEmptyServerRegistry()).toBe(true);
  });

  it('fails closed on canonical collisions and bad origins', async () => {
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
      { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
    ]);

    let registry = await import('@/lib/serverRegistry');
    expect(registry.getServers()).toEqual([]);

    vi.resetModules();
    process.env.SERVER_REGISTRY_JSON = JSON.stringify([
      { id: 'pep', gameApiUrl: 'http://wrong-game-api:8081' },
    ]);
    registry = await import('@/lib/serverRegistry');
    expect(registry.getServers()).toEqual([]);
  });

  it('fails closed for invalid baked data too', async () => {
    vi.doMock('@/config/servers.json', () => ({
      default: {
        servers: [
          { id: 'pep', gameApiUrl: 'http://spep-game-api:8081' },
          { id: 'main', gameApiUrl: 'http://smain-game-api:8081' },
        ],
      },
    }));

    const registry = await import('@/lib/serverRegistry');

    expect(registry.getServers()).toEqual([]);
    expect(registry.isValidEmptyServerRegistry()).toBe(false);
  });

  it('classifies a valid empty baked registry as valid and empty', async () => {
    vi.doMock('@/config/servers.json', () => ({
      default: { servers: [] },
    }));

    const registry = await import('@/lib/serverRegistry');

    expect(registry.getServers()).toEqual([]);
    expect(registry.isValidEmptyServerRegistry()).toBe(true);
  });

  it('fails closed for object-form registry duplicate keys', async () => {
    process.env.SERVER_REGISTRY_JSON =
      '{"pep":"http://wrong-game-api:8081","pep":"http://spep-game-api:8081"}';

    const registry = await import('@/lib/serverRegistry');

    expect(registry.getServers()).toEqual([]);
    expect(registry.resolveGameApiOrigin('pep')).toBeUndefined();
  });
});
