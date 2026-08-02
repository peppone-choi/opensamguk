import { describe, expect, it } from 'vitest';
import { fallbackGameUrlForServer, isPathServerId, resolveServerGamePath } from '@/lib/serverGameUrl';

describe('serverGameUrl', () => {
  it('accepts canonical lowercase public server IDs in generated paths', () => {
    for (const serverId of ['pep', 'a1', 's1']) {
      expect(isPathServerId(serverId)).toBe(true);
      expect(resolveServerGamePath(undefined, serverId, '/game', 'join')).toBe(`/game/${serverId}/join`);
    }
  });

  it('rejects invalid public IDs and keeps the query fallback', () => {
    for (const serverId of ['', 'A1', 'Pep', 'pep-id', 'pep_id', 'pep/id', '한글', ' pep ']) {
      expect(isPathServerId(serverId)).toBe(false);
    }

    expect(fallbackGameUrlForServer('pep-id')).toBe('/game?server=pep-id');
    expect(fallbackGameUrlForServer('A1')).toBe('/game?server=A1');
    expect(fallbackGameUrlForServer('pep_id')).toBe('/game?server=pep_id');
    expect(fallbackGameUrlForServer('pep/id')).toBe('/game?server=pep%2Fid');
    expect(resolveServerGamePath(undefined, 'pep-id', '/game', 'join')).toBe('/game/join?server=pep-id');
    expect(resolveServerGamePath(undefined, 'A1', '/game', 'join')).toBe('/game/join?server=A1');
  });
});
