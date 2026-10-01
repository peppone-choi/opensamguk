import { describe, expect, it } from 'vitest';
import { kstClock, turnLoopView } from '../lib/turnLoop';

const SERVER = '2026-10-01T12:52:00Z'; // 21:52 KST

describe('turnLoopView — TURN_LOOP_UI', () => {
  it('RUNNING 은 띠 없이 서버 시각을 보인다', () => {
    expect(turnLoopView({ game: { turnLoop: { state: 'RUNNING' }, serverTime: SERVER } })).toEqual({ state: 'RUNNING', clock: 'time', band: null });
  });

  it('turnLoop 이 없는 지금 서버도 catchUp.active 면 따라잡기 띠(서버 값) — 배속 · 다 따라잡는 때', () => {
    const v = turnLoopView({ game: { catchUp: { active: true, multiplier: 2, etaAt: '2026-10-01T12:40:00Z' } } });
    expect(v.state).toBe('CATCHING_UP');
    expect(v.band?.body).toBe('서버가 멈췄던 동안 밀린 순을 2배 빠르기로 돌립니다. 다 따라잡는 때 21:40');
    expect(turnLoopView({ game: { catchUp: { active: true, multiplier: 2, etaAt: null } } }).band?.body).toContain('다 따라잡는 때 확인 중');
  });

  it('turnLoop 도 따라잡기도 없거나 읽기가 실패하면 UNKNOWN — 시각은 「확인 중」, 경보(alert)가 아니다', () => {
    for (const response of [null, {}, { game: null }, { game: { status: 'OPEN' } }]) {
      const v = turnLoopView(response);
      expect(v.state).toBe('UNKNOWN');
      expect(v.clock).toBe('확인 중');
      expect(v.band).toMatchObject({ kind: 'unknown', role: 'status', head: '운영 상태 확인 중', action: 'recheck' });
      expect(v.band?.body).not.toContain('마지막 확인');
    }
  });

  it('모르는 새 상태 값도 UNKNOWN 으로 받는다 · 마지막 확인 시각은 serverTime', () => {
    const v = turnLoopView({ game: { turnLoop: { state: 'DRAINING' }, serverTime: SERVER } });
    expect(v.state).toBe('UNKNOWN');
    expect(v.band?.body).toContain('(마지막 확인 21:52)');
  });

  it('STALLED 는 턴 멈춤 띠(alert) — 멈춘 지 n분은 staleSeconds, 확인 시각은 serverTime', () => {
    const v = turnLoopView({ game: { turnLoop: { state: 'STALLED', staleSeconds: 42 * 60 + 5 }, serverTime: SERVER, month: 3, turnPhaseText: '중순' } });
    expect(v.clock).toBe('미정');
    expect(v.band).toMatchObject({ kind: 'stop', role: 'alert', head: '턴이 멈췄습니다' });
    expect(v.band?.body).toBe('마지막 순 3월 중순, 멈춘 지 42분 (21:52 확인). 걸어 둔 예약은 그대로 남습니다');
  });

  it('PAUSED 는 까닭을 지어내지 않는다 — ADMIN 일 때만 운영진', () => {
    expect(turnLoopView({ game: { turnLoop: { state: 'PAUSED', pausedReason: null } } }).band).toMatchObject({ kind: 'paused', head: '턴이 멈춰 있습니다' });
    expect(turnLoopView({ game: { turnLoop: { state: 'PAUSED', pausedReason: 'ADMIN' } } }).band).toMatchObject({ kind: 'paused_admin' });
    expect(turnLoopView({ game: { turnLoop: { state: 'PAUSED', pausedReason: 'MAINTENANCE' } } }).band?.kind).toBe('paused');
  });

  it('WAITING 은 첫 순 시각(nextTurnAt = 예정 시각)', () => {
    expect(turnLoopView({ game: { turnLoop: { state: 'WAITING' }, nextTurnAt: '2026-10-03T11:00:00Z' } }).band?.body).toBe('첫 순은 10월 3일 20:00에 시작합니다');
  });

  it('kstClock 은 읽을 수 없는 값에 null', () => {
    expect(kstClock('nope')).toBeNull();
    expect(kstClock(null)).toBeNull();
  });
});
