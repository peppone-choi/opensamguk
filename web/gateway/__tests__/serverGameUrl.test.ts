import { describe, expect, it } from 'vitest';
import { fallbackGameUrlForServer, isPathServerId, resolveServerGamePath } from '@/lib/serverGameUrl';

const RESERVED_PUBLIC_SERVER_IDS = [
  'all',
  'main',
  'admin1',
  'admin2',
  'admin5',
  'admin7',
  'admin8',
  'auction',
  'battle-center',
  'betting',
  'board',
  'chief-center',
  'city',
  'coming-soon',
  'diplomacy',
  'generals',
  'global-diplomacy',
  'history',
  'inherit',
  'join',
  'mailbox',
  'map',
  'my',
  'my-boss',
  'my-cities',
  'my-generals',
  'my-nation',
  'nation',
  'nation-betting',
  'nation-finance',
  'npc-control',
  'rankings',
  'register',
  'select-pool',
  'simulator',
  'tournament',
  'tournament-admin',
  'troop',
  'vote',
  'world-log',
];

describe('serverGameUrl', () => {
  it('accepts canonical lowercase public server IDs in generated paths', () => {
    for (const serverId of ['pep', 'a1', 's1', 'current', 'backup', 'example', 'a'.repeat(48)]) {
      expect(isPathServerId(serverId)).toBe(true);
      expect(resolveServerGamePath(undefined, serverId, '/game', 'join')).toBe(`/game/${serverId}/join`);
    }
  });

  it('rejects invalid public IDs and keeps the query fallback', () => {
    for (const serverId of ['', 'A1', 'Pep', 'pep-id', 'pep_id', 'pep/id', '한글', ' pep ', 'a'.repeat(49), ...RESERVED_PUBLIC_SERVER_IDS]) {
      expect(isPathServerId(serverId)).toBe(false);
    }

    expect(fallbackGameUrlForServer('pep-id')).toBe('/game?server=pep-id');
    expect(fallbackGameUrlForServer('A1')).toBe('/game?server=A1');
    expect(fallbackGameUrlForServer('pep_id')).toBe('/game?server=pep_id');
    expect(fallbackGameUrlForServer('pep/id')).toBe('/game?server=pep%2Fid');
    expect(fallbackGameUrlForServer('join')).toBe('/game?server=join');
    expect(resolveServerGamePath(undefined, 'pep-id', '/game', 'join')).toBe('/game/join?server=pep-id');
    expect(resolveServerGamePath(undefined, 'A1', '/game', 'join')).toBe('/game/join?server=A1');
  });
});
