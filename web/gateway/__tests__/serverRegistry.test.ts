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
    it('exposes only lowercase alphanumeric runtime registry entries', async () => {
        process.env.SERVER_REGISTRY_JSON = JSON.stringify([
            { id: 'pep', gameApiUrl: 'http://pep-game-api' },
            { id: 'a1', gameApiUrl: 'http://a1-game-api' },
            { id: 'A1', gameApiUrl: 'http://uppercase-game-api' },
            { id: 'pep-id', gameApiUrl: 'http://hyphen-game-api' },
            { id: 'pep_id', gameApiUrl: 'http://underscore-game-api' },
            { id: ' pep ', gameApiUrl: 'http://spaced-game-api' },
        ]);

        const registry = await import('@/lib/serverRegistry');

        expect(registry.getServers().map((server) => server.id)).toEqual(['pep', 'a1']);
        expect(registry.resolveGameApiOrigin('pep')).toBe('http://pep-game-api');
        expect(registry.resolveGameApiOrigin('A1')).toBeUndefined();
    });

    it('filters noncanonical baked registry entries too', async () => {
        vi.doMock('@/config/servers.json', () => ({
            default: {
                servers: [
                    { id: 'pep' },
                    { id: 'a1' },
                    { id: 'A1' },
                    { id: 'pep-id' },
                    { id: 'pep_id' },
                ],
            },
        }));

        const registry = await import('@/lib/serverRegistry');

        expect(registry.getServers().map((server) => server.id)).toEqual(['pep', 'a1']);
    });
});
