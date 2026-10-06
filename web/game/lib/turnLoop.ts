// 턴 루프 공개 상태 → 셸이 보일 것(v31system TURN_LOOP_UI · BANDS, 계약판 K10-01 · 01f).
//
// 읽는 곳: 게이트웨이 `/api/server-basic-info/<서버>` 의 `game`. `turnLoop` · `serverTime` · `staleSeconds` 는 C8(#1073)이
// 더한다. 그 전에는 `turnLoop` 이 없다 — `catchUp.active` 면 CATCHING_UP(서버 값이지 짐작이 아니다), 아니면 UNKNOWN.
// 시각 · 배속 · 멈춘 시간은 서버가 준 값만 쓰고, 기기 시계로 다시 계산하지 않는다.

export type TurnLoopState = 'RUNNING' | 'CATCHING_UP' | 'WAITING' | 'PAUSED' | 'STALLED' | 'UNKNOWN';

/** 머리줄 「다음 개인 턴」 · 순 띠 시각 칸 — 'time' 일 때만 서버 시각을 보인다. */
export type TurnClock = 'time' | '미정' | '확인 중';

export type BandKind = 'catch' | 'stop' | 'paused' | 'paused_admin' | 'unknown' | 'waiting';

export interface TurnBand {
  readonly kind: BandKind;
  /** 턴 멈춤만 alert(바로 읽음), 나머지는 status(K10). */
  readonly role: 'alert' | 'status';
  /** 굵게 보일 머리말. */
  readonly head: string;
  readonly body: string;
  /** 「다시 확인」처럼 띠가 여는 조작. 갈 곳이 없는 조작(공지 보기 등)은 두지 않는다. */
  readonly action: 'recheck' | null;
}

export interface TurnLoopView {
  readonly state: TurnLoopState;
  readonly clock: TurnClock;
  readonly band: TurnBand | null;
}

interface RawGame {
  readonly status?: string;
  /** 2 · 3 = 끝난 서버(시즌 종료 — 통일 · 미통일). 게이트웨이 로비 lobbyVerdict 와 같은 칸. */
  readonly isUnited?: number;
  readonly serverTime?: string | null;
  readonly nextTurnAt?: string | null;
  readonly month?: number;
  readonly turnPhaseText?: string;
  readonly turnLoop?: { readonly state?: string; readonly staleSeconds?: number | null; readonly pausedReason?: string | null } | null;
  readonly catchUp?: { readonly active?: boolean; readonly multiplier?: number; readonly etaAt?: string | null } | null;
}

/**
 * 게임 전체 「점검 중」(보드 V31SystemMMaint 전체 화면 · BAND_ORDER 맨 앞)인가 — 판정은 여기 한 곳.
 * 임시: 서버 상태 CLOSED(게이트웨이 로비 lobbyVerdict 와 같은 신호, CEO 결정 2026-10-05). 보드의 정식 조건
 * (turnLoop PAUSED + pausedReason MAINTENANCE + maintenance ACTIVE, 계약판 K10-01c)이 서버에 생기면 이 함수만 바꾼다.
 * 끝난 서버(시즌 종료 isUnited 2 · 3)는 점검이 아니다 — 닫혀 있어도 점검으로 보이지 않는다.
 */
export function isMaintenance(response: { readonly game?: RawGame | null } | null): boolean {
  const game = response?.game ?? null;
  if (!game) return false;
  if (game.isUnited === 2 || game.isUnited === 3) return false;
  return game.status === 'CLOSED';
}

const KNOWN: ReadonlySet<string> = new Set(['RUNNING', 'CATCHING_UP', 'WAITING', 'PAUSED', 'STALLED']);

/** 서버 시각을 한국 시각 「21:40」로. 값이 없거나 읽을 수 없으면 null. */
export function kstClock(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hour12: false }).format(at);
}

/** 서버 시각을 「10월 3일 20:00」로. */
export function kstDateClock(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;
  const parts = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' }).formatToParts(at);
  const month = parts.find((p) => p.type === 'month')?.value;
  const day = parts.find((p) => p.type === 'day')?.value;
  return `${month}월 ${day}일 ${kstClock(iso)}`;
}

function stateOf(game: RawGame | null | undefined): TurnLoopState {
  const raw = game?.turnLoop?.state;
  if (typeof raw === 'string') return KNOWN.has(raw) ? (raw as TurnLoopState) : 'UNKNOWN';
  if (game?.catchUp?.active === true) return 'CATCHING_UP';
  return 'UNKNOWN';
}

const KEEP = '걸어 둔 예약은 그대로 남습니다';

/** `/api/server-basic-info/<서버>` 응답(또는 읽기 실패 = null)을 셸 표시로. */
export function turnLoopView(response: { readonly game?: RawGame | null } | null): TurnLoopView {
  const game = response?.game ?? null;
  const state = stateOf(game);
  const checked = kstClock(game?.serverTime);
  const checkedNote = checked ? ` (${checked} 확인)` : '';
  switch (state) {
    case 'RUNNING':
      return { state, clock: 'time', band: null };
    case 'CATCHING_UP': {
      const multiplier = game?.catchUp?.multiplier;
      const eta = kstClock(game?.catchUp?.etaAt);
      const speed = typeof multiplier === 'number' && multiplier > 0 ? `${multiplier}배 빠르기로` : '빠르게';
      return {
        state, clock: 'time', band: {
          kind: 'catch', role: 'status', head: '따라잡는 중',
          body: `서버가 멈췄던 동안 밀린 순을 ${speed} 돌립니다. 다 따라잡는 때 ${eta ?? '확인 중'}`, action: null,
        },
      };
    }
    case 'WAITING': {
      const first = kstDateClock(game?.nextTurnAt);
      return {
        state, clock: 'time', band: {
          kind: 'waiting', role: 'status', head: '첫 순 전입니다',
          body: first ? `첫 순은 ${first}에 시작합니다` : '첫 순 시각은 확인 중입니다', action: null,
        },
      };
    }
    case 'PAUSED': {
      // 점검(MAINTENANCE + maintenance ACTIVE)의 전체 화면은 점검 계약(K10-01c)이 온 뒤다. PAUSED 만으로 점검이라 짐작하지 않는다.
      const admin = game?.turnLoop?.pausedReason === 'ADMIN';
      return {
        state, clock: '미정', band: admin
          ? { kind: 'paused_admin', role: 'status', head: '운영진이 턴을 잠시 멈췄습니다', body: KEEP, action: null }
          : { kind: 'paused', role: 'status', head: '턴이 멈춰 있습니다', body: `멈춘 까닭은 아직 알 수 없습니다. ${KEEP}`, action: null },
      };
    }
    case 'STALLED': {
      const seconds = game?.turnLoop?.staleSeconds;
      const since = typeof seconds === 'number' && seconds >= 0 ? `멈춘 지 ${Math.floor(seconds / 60)}분` : '멈춘 시간은 확인 중';
      const last = game?.month ? `마지막 순 ${game.month}월${game.turnPhaseText ? ` ${game.turnPhaseText}` : ''}, ` : '';
      return {
        state, clock: '미정', band: {
          kind: 'stop', role: 'alert', head: '턴이 멈췄습니다', body: `${last}${since}${checkedNote}. ${KEEP}`, action: null,
        },
      };
    }
    default:
      return {
        state: 'UNKNOWN', clock: '확인 중', band: {
          kind: 'unknown', role: 'status', head: '운영 상태 확인 중',
          body: `지금은 턴이 도는지 확인할 수 없습니다. 다음 턴 시각은 확인될 때까지 보이지 않습니다${checked ? ` (마지막 확인 ${checked})` : ''}`,
          action: 'recheck',
        },
      };
  }
}
