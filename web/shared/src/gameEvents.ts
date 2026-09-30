// 게임 기록(ADR-LITE-069): 서버는 사건 종류(kind) · 역할별 id(refs) · 승인된 사실(facts)만 보낸다.
// 문장은 화면이 만든다 — 모두 알림체(「…했습니다」). 이름은 화면이 이미 가진 자료(지도 미리보기의 현 · 세력)에서 푼다.
// 풀지 못한 이름은 지어내지 않고 「어느 현」 · 「어느 세력」으로 적는다(설계서 P-H01 · K5-07 이름 사전 전까지).

export type GameEventSection = 'PERSONAL' | 'RETINUE_NATION' | 'COURT' | 'BATTLE' | 'WORLD';

export interface GameEventTime {
  readonly year: number;
  readonly month: number;
  /** 1 상순 · 2 중순 · 3 하순(서버 EventFeedPosition). */
  readonly phase: number;
  readonly ordinal: number;
}

export interface GameEvent {
  readonly id: number;
  readonly kind: string;
  readonly section: GameEventSection | string;
  readonly occurredAt: GameEventTime;
  readonly refs: Readonly<Record<string, number | string>>;
  readonly facts: Readonly<Record<string, number | string>>;
}

export interface GameEventPage {
  readonly events: readonly GameEvent[];
  readonly nextCursor: string | null;
}

export const PHASE_LABELS = ['상순', '중순', '하순'] as const;

/** 「200년 3월 중순」. 순이 범위를 벗어나면 순을 빼고 적는다(지어내지 않는다). */
export function formatGameDate(time: { readonly year: number; readonly month: number; readonly phase?: number | null }): string {
  const phase = time.phase != null ? PHASE_LABELS[time.phase - 1] : undefined;
  return `${time.year}년 ${time.month}월${phase ? ` ${phase}` : ''}`;
}

/** 이름 풀이 — 화면이 가진 자료로 id → 이름. 모르면 undefined. */
export interface EventNames {
  city(id: number): string | undefined;
  nation(id: number): string | undefined;
  /** 보루 id 는 안정 문자열이다(C0 인계). */
  roadFort?(id: string): string | undefined;
}

const HANGUL_BASE = 0xac00;
const HANGUL_LAST = 0xd7a3;

/** 마지막 글자에 받침이 있는가. 한글이 아니면(숫자 · 영문 · 괄호) 받침 없음으로 본다. */
export function hasFinalConsonant(word: string): boolean {
  const last = word.trim().slice(-1);
  const code = last.charCodeAt(0);
  if (Number.isNaN(code) || code < HANGUL_BASE || code > HANGUL_LAST) return false;
  return (code - HANGUL_BASE) % 28 !== 0;
}

/** 받침이 ㄹ인가(「로/으로」는 ㄹ 받침 뒤에 「로」를 쓴다). */
function endsWithRieul(word: string): boolean {
  const code = word.trim().slice(-1).charCodeAt(0);
  if (Number.isNaN(code) || code < HANGUL_BASE || code > HANGUL_LAST) return false;
  return (code - HANGUL_BASE) % 28 === 8;
}

type Particle = '이/가' | '을/를' | '은/는' | '와/과' | '로/으로' | '의';

/** 이름 + 조사. 「조조가」 「손권이」 「허현을」 「원소로」 「업으로」 「낙양으로」. */
export function withParticle(word: string, particle: Particle): string {
  if (particle === '의') return `${word}의`;
  const final = hasFinalConsonant(word);
  switch (particle) {
    case '이/가': return `${word}${final ? '이' : '가'}`;
    case '을/를': return `${word}${final ? '을' : '를'}`;
    case '은/는': return `${word}${final ? '은' : '는'}`;
    case '와/과': return `${word}${final ? '과' : '와'}`;
    case '로/으로': return `${word}${final && !endsWithRieul(word) ? '으로' : '로'}`;
    default: return word;
  }
}

/** 세력 id 0 = 주인 없음(재야 · 무주). */
const NO_NATION = 0;

function refId(event: GameEvent, role: string): number | undefined {
  const value = event.refs[role];
  if (typeof value === 'number') return value;
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) return Number(value);
  return undefined;
}

function nameOr(fallback: string, id: number | undefined, lookup: ((id: number) => string | undefined) | undefined): string {
  if (id == null || !lookup) return fallback;
  return lookup(id) ?? fallback;
}

/**
 * 천하 정세(WORLD) 사건 한 줄. 모르는 kind 는 null — 화면은 그 줄을 빼고, 빼면 안 되는 곳은 「사건」 한 줄로 둔다.
 * 서버 EventKind(WORLD): county.ownerChanged · roadFort.captured · yuedan.announced · server.catchUpFinished.
 */
export function worldEventSentence(event: GameEvent, names: EventNames): string | null {
  switch (event.kind) {
    case 'county.ownerChanged': {
      const city = nameOr('어느 현', refId(event, 'CITY'), (id) => names.city(id));
      const fromId = refId(event, 'FROM_NATION');
      const toId = refId(event, 'TO_NATION');
      const to = nameOr('어느 세력', toId, (id) => names.nation(id));
      // 세력 0 = 주인 없음(무주). 「무주에서」 · 「어느 세력에서」로 적지 않는다(pep 실측: FROM_NATION 0).
      if (fromId === NO_NATION && toId !== NO_NATION) return `주인 없던 ${withParticle(city, '을/를')} ${withParticle(to, '이/가')} 차지했습니다.`;
      if (toId === NO_NATION) return `${withParticle(city, '이/가')} 주인 없는 땅이 됐습니다.`;
      const from = nameOr('어느 세력', fromId, (id) => names.nation(id));
      return `${withParticle(city, '의')} 소유 세력이 ${from}에서 ${withParticle(to, '로/으로')} 바뀌었습니다.`;
    }
    case 'roadFort.captured': {
      const fortId = event.refs.ROAD_FORT;
      const fort = (typeof fortId === 'string' && names.roadFort?.(fortId)) || '어느 보루';
      const to = nameOr('어느 세력', refId(event, 'TO_NATION'), (id) => names.nation(id));
      return `${withParticle(fort, '을/를')} ${withParticle(to, '이/가')} 차지했습니다.`;
    }
    case 'yuedan.announced':
      return `${event.occurredAt.year}년 ${event.occurredAt.month}월 월단평 결과가 발표됐습니다.`;
    case 'server.catchUpFinished':
      return '서버가 멈췄던 동안 밀린 순을 모두 따라잡았습니다.';
    default:
      return null;
  }
}
