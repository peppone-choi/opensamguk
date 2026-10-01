// 게임 기록(ADR-LITE-069): 서버는 사건 종류(kind) · 역할별 id(refs) · 승인된 사실(facts)만 보낸다.
// 문장은 화면이 만든다 — 모두 알림체(「…했습니다」). 이름은 화면이 이미 가진 자료(지도 미리보기의 현 · 세력)에서 푼다.
// 풀지 못한 이름은 지어내지 않고 「어느 현」 · 「어느 세력」으로 적는다(설계서 P-H01 · K5-07 이름 사전 전까지).

import type { RecordSection } from './recordSections';

/** 기록 5분류 — K4 recordSections 의 RecordSection 과 같은 타입(먼저 병합된 쪽을 따른다, K4 · K5 합의 2026-10-01). */
export type GameEventSection = RecordSection;

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
  /** 인물(ACTOR · PERSON · ISSUER · TARGET). K5-07 이름 사전 전에는 화면이 아는 인물만 푼다. */
  general?(id: number): string | undefined;
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

// ---------------------------------------------------------------- 기록 5분류(P-H01) — 36종 전부

/**
 * 서버가 이 종류를 실제로 사건으로 쓰는가(C7 전수 대조 2026-10-01, 정본 reports/opensamguk/tasks/2026-10-01-c7-k5-event-kind-coverage.md).
 * - WRITTEN 25종: 엔진이 `EventKind.X` 로 사건을 쓴다. 보루 차지(roadFort.captured)는 C3 RoadFortSiegeService 가 2026-10-01 에 쓰기 시작했다.
 * - ALIAS 2종: 옛 표지(county.captured · lost). 같은 전이가 county.ownerChanged 한 건으로 오므로 따로 오지 않는다.
 * - NOT_WRITTEN 9종: 선언만 있고 아직 쓰지 않는다. 화면은 「서버가 아직 사건으로 쓰지 않음」으로 둔다.
 * `__tests__/gameEvents.test.ts` 가 서버 EventKind.kt 와 엔진 소스를 직접 읽어 이 표와 대조한다.
 */
export type EventKindCoverage = 'WRITTEN' | 'ALIAS' | 'NOT_WRITTEN';

export const EVENT_KIND_COVERAGE: Readonly<Record<string, EventKindCoverage>> = {
  'march.assignment': 'WRITTEN',
  'march.corps': 'WRITTEN',
  'march.direct': 'WRITTEN',
  'encounter.personal': 'NOT_WRITTEN',
  'military.musterOrdered': 'WRITTEN',
  'deploy.started': 'WRITTEN',
  'encounter.pending': 'NOT_WRITTEN',
  'encounter.disbanded': 'NOT_WRITTEN',
  'court.dispatchIssued': 'WRITTEN',
  'court.dispatchReceived': 'WRITTEN',
  'court.dispatchAccepted': 'WRITTEN',
  'court.dispatchRefused': 'WRITTEN',
  'court.dispatchCancelled': 'WRITTEN',
  'court.rewardReceived': 'WRITTEN',
  'enlist.joined': 'WRITTEN',
  'enlist.retainerJoined': 'WRITTEN',
  'input.rejected': 'NOT_WRITTEN',
  'field.applied': 'NOT_WRITTEN',
  'personal.applied': 'NOT_WRITTEN',
  'offlineDelegation.started': 'WRITTEN',
  'offlineDelegation.ended': 'WRITTEN',
  'people.searched': 'WRITTEN',
  'people.joined': 'WRITTEN',
  'people.resisted': 'WRITTEN',
  'renown.event': 'WRITTEN',
  'yuedan.assessed': 'WRITTEN',
  'retinue.departureJudged': 'NOT_WRITTEN',
  'retinue.departed': 'NOT_WRITTEN',
  'roadFort.siege': 'NOT_WRITTEN',
  'income.monthly': 'WRITTEN',
  'county.captured': 'ALIAS',
  'county.lost': 'ALIAS',
  'roadFort.captured': 'WRITTEN',
  'yuedan.announced': 'WRITTEN',
  'server.catchUpFinished': 'WRITTEN',
  'county.ownerChanged': 'WRITTEN',
};

export const NOT_WRITTEN_NOTE = '서버가 아직 사건으로 쓰지 않음';

/** 종류 이름(쉬운 말). 거르기 목록과, refs 가 없어 문장을 못 만드는 줄에 쓴다. */
export const EVENT_KIND_LABEL: Readonly<Record<string, string>> = {
  'march.assignment': '부임 행군',
  'march.corps': '군단 행군',
  'march.direct': '직접 이동',
  'encounter.personal': '개인 조우',
  'military.musterOrdered': '집결 명령',
  'deploy.started': '출병',
  'encounter.pending': '조우 대기',
  'encounter.disbanded': '조우 해산',
  'court.dispatchIssued': '발령 내림',
  'court.dispatchReceived': '발령 도착',
  'court.dispatchAccepted': '발령 수락',
  'court.dispatchRefused': '발령 거절',
  'court.dispatchCancelled': '발령 취소',
  'court.rewardReceived': '포상 받음',
  'enlist.joined': '출사',
  'enlist.retainerJoined': '부에 새 장수',
  'input.rejected': '입력 무효',
  'field.applied': '현장 행동 반영',
  'personal.applied': '개인 행동 반영',
  'offlineDelegation.started': '부재 위임 시작',
  'offlineDelegation.ended': '부재 위임 끝',
  'people.searched': '인재 탐색',
  'people.joined': '등용 성공',
  'people.resisted': '등용 거절',
  'renown.event': '명망 사건',
  'yuedan.assessed': '내 월단평',
  'retinue.departureJudged': '이탈 판정',
  'retinue.departed': '부 이탈',
  'roadFort.siege': '보루 공성',
  'income.monthly': '월세입',
  'county.captured': '현 점령',
  'county.lost': '현 상실',
  'roadFort.captured': '보루 차지',
  'yuedan.announced': '월단평 발표',
  'server.catchUpFinished': '따라잡기 끝',
  'county.ownerChanged': '현 주인 바뀜',
};

/** 명망 사건 원인(서버 RenownEventSource 이름 → 그 label, 「縣」은 「현」). 모르는 코드는 적지 않는다. */
export const RENOWN_SOURCE_LABEL: Readonly<Record<string, string>> = {
  ENCOUNTER_VICTORY: '조우 승리',
  COUNTY_CAPTURE: '현 점령',
  ENCOUNTER_DEFEAT: '조우 패배',
  COUNTY_LOSS: '현 상실',
  COUNTY_INDICATOR_RISE: '관할 현 지표 상승',
  DIRECT_COUNTY_ACTION: '직접 내정 행동',
  DIRECT_MILITARY_ACTION: '직접 군사 행동',
  DIRECT_PERSONAL_ACTION: '직접 개인 행동',
  DIRECT_PEOPLE_ACTION: '직접 인물 행동',
  DIRECT_TRANSFER_ACTION: '직접 자원 이전',
  DEFECTION: '배반',
  SWORN_OATH: '결의',
  RECOMMENDATION: '천거',
  REWARD: '상사',
  OFFICE_APPOINTMENT: '관직 임명',
  MISRULE: '실정',
  DISPATCH_REFUSAL: '발령 거절',
};

/** 포상 까닭(서버 RewardReasonCode). */
export const REWARD_REASON_LABEL: Readonly<Record<string, string>> = {
  WAR_MERIT: '전공',
  DOMESTIC_MERIT: '내정 공로',
  LOYALTY_SUPPORT: '충성 격려',
  ROUTINE_SERVICE: '평소 근무',
};

/** 사실 이름(자세히 칸). 값은 서버가 준 것만 적는다. */
export const EVENT_FACT_LABEL: Readonly<Record<string, string>> = {
  COUNTIES: '세입이 든 현 창고',
  MONEY: '금',
  GRAIN: '쌀',
  IRON: '철',
  TIMBER: '목재',
  HORSES: '말',
  RENOWN_BEFORE: '명망(전)',
  RENOWN_AFTER: '명망(후)',
  RENOWN_CHANGE: '명망 변화',
  REASON: '까닭',
  SOURCE: '원인',
};

/**
 * 수 + 「을/를」 — 우리말로 읽은 끝소리를 따른다. 끝자리 2(이) · 4(사) · 5(오) · 9(구)는 받침이 없어 「를」,
 * 나머지는 「을」(0 으로 끝나면 십 · 백 · 천 · 만으로 읽혀 받침이 있다). `text` 는 화면에 적을 글자(쉼표 등).
 */
export function numberWithObjectParticle(value: number, text: string): string {
  const last = Math.abs(Math.trunc(value)) % 10;
  return `${text}${last === 2 || last === 4 || last === 5 || last === 9 ? '를' : '을'}`;
}

const own = (table: Readonly<Record<string, unknown>>, key: string) => Object.prototype.hasOwnProperty.call(table, key);

export function eventKindCoverage(kind: string): EventKindCoverage | null {
  return own(EVENT_KIND_COVERAGE, kind) ? EVENT_KIND_COVERAGE[kind] : null;
}

export function eventKindLabel(kind: string): string | null {
  return own(EVENT_KIND_LABEL, kind) ? EVENT_KIND_LABEL[kind] : null;
}

/** 사실 값 하나를 화면 글자로. 코드 값은 이름표로, 모르는 코드는 원문 그대로 두지 않고 null. 수는 천 단위 쉼표. */
export function eventFactText(role: string, value: number | string): string | null {
  if (role === 'SOURCE') return typeof value === 'string' && own(RENOWN_SOURCE_LABEL, value) ? RENOWN_SOURCE_LABEL[value] : null;
  if (role === 'REASON') return typeof value === 'string' && own(REWARD_REASON_LABEL, value) ? REWARD_REASON_LABEL[value] : null;
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  const text = Math.abs(value).toLocaleString('ko-KR');
  if (role === 'RENOWN_CHANGE') return value > 0 ? `+${text}` : value < 0 ? `−${text}` : '0';
  return value < 0 ? `−${text}` : text;
}

/** 보는 사람(조정 공문의 시점을 고를 때 — 내 장수 id 를 ISSUER · TARGET 과 견준다). */
export interface EventViewer {
  readonly generalId: number | null;
}

function factNumber(event: GameEvent, role: string): number | undefined {
  const value = event.facts[role];
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function person(event: GameEvent, role: string, names: EventNames): string {
  return nameOr('어느 인물', refId(event, role), names.general ? (id) => names.general?.(id) : undefined);
}

function courtSentence(event: GameEvent, names: EventNames, viewer: EventViewer): string | null {
  const issuerId = refId(event, 'ISSUER');
  const targetId = refId(event, 'TARGET');
  const me = viewer.generalId;
  const iAmIssuer = me != null && issuerId === me;
  const iAmTarget = me != null && targetId === me;
  const issuer = person(event, 'ISSUER', names);
  const target = person(event, 'TARGET', names);
  const answer = (verb: '수락' | '거절') => {
    if (iAmTarget) return `${issuer}의 발령을 ${verb}했습니다.`;
    if (iAmIssuer) return `${withParticle(target, '이/가')} 발령을 ${verb}했습니다.`;
    return `${withParticle(target, '이/가')} ${issuer}의 발령을 ${verb}했습니다.`;
  };
  switch (event.kind) {
    case 'court.dispatchIssued':
      return iAmIssuer || issuerId == null ? `${target}에게 발령을 내렸습니다.` : `${withParticle(issuer, '이/가')} ${target}에게 발령을 내렸습니다.`;
    case 'court.dispatchReceived':
      return `${issuer}의 발령이 도착했습니다.`;
    case 'court.dispatchAccepted':
      return answer('수락');
    case 'court.dispatchRefused':
      return answer('거절');
    case 'court.dispatchCancelled':
      return `${issuer}의 발령이 더는 유효하지 않아 불이익 없이 취소됐습니다.`;
    default:
      return null;
  }
}

/**
 * 기록 한 줄(5분류 전부). 모르는 kind 는 null — 화면은 그 줄만 「기록을 표시할 수 없습니다.」로 둔다.
 * - 전장 보고(BATTLE)는 지금 서버가 refs · facts 를 모두 지워 보낸다 → 종류 이름 문장만(K5-07 ① 전까지).
 * - enlist.retainerJoined 의 PERSON 은 뜻이 둘이라(주군 · 새 장수) 방향을 추정하지 않는다.
 * - 서버가 아직 쓰지 않는 종류(NOT_WRITTEN)가 오면 종류 이름만 적는다.
 */
export function eventSentence(event: GameEvent, names: EventNames, viewer: EventViewer = { generalId: null }): string | null {
  const world = worldEventSentence(event, names);
  if (world !== null) return world;
  if (event.kind.startsWith('court.dispatch')) return courtSentence(event, names, viewer);
  const city = (fallback: string) => nameOr(fallback, refId(event, 'CITY'), (id) => names.city(id));
  switch (event.kind) {
    // 개인 행적
    case 'march.assignment': {
      const where = city('');
      return where ? `발령지 ${withParticle(where, '로/으로')} 행군했습니다.` : '발령지로 행군했습니다.';
    }
    case 'court.rewardReceived': {
      const money = factNumber(event, 'MONEY');
      const from = person(event, 'ISSUER', names);
      return money != null ? `${from}에게서 포상으로 금 ${numberWithObjectParticle(money, eventFactText('MONEY', money) ?? String(money))} 받았습니다.` : `${from}에게서 포상을 받았습니다.`;
    }
    case 'enlist.joined':
      return `${nameOr('어느 세력', refId(event, 'NATION'), (id) => names.nation(id))}에 출사했습니다.`;
    case 'offlineDelegation.started':
      return '부재 위임이 시작됐습니다.';
    case 'offlineDelegation.ended':
      return '부재 위임이 끝났습니다.';
    case 'renown.event': {
      const source = event.facts.SOURCE != null ? eventFactText('SOURCE', event.facts.SOURCE) : null;
      return source ? `명망에 반영될 일이 있었습니다 — ${source}.` : '명망에 반영될 일이 있었습니다.';
    }
    case 'yuedan.assessed': {
      const before = factNumber(event, 'RENOWN_BEFORE');
      const after = factNumber(event, 'RENOWN_AFTER');
      if (before == null || after == null) return '월단평 평가가 나왔습니다.';
      if (after === before) return `월단평에서 명망이 그대로입니다(${eventFactText('RENOWN_AFTER', after)}).`;
      return `월단평에서 명망이 ${after > before ? '올랐습니다' : '내렸습니다'}(${eventFactText('RENOWN_BEFORE', before)} → ${eventFactText('RENOWN_AFTER', after)}).`;
    }
    // 부 · 세력
    case 'enlist.retainerJoined':
      return '새 장수가 부에 들었습니다.';
    case 'people.searched':
      return `${city('어느 현')}에서 인재를 탐색했습니다.`;
    case 'people.joined':
      return `${withParticle(person(event, 'PERSON', names), '이/가')} 제안에 동의해 부에 들어왔습니다.`;
    case 'people.resisted':
      return refId(event, 'PERSON') != null ? `${withParticle(person(event, 'PERSON', names), '이/가')} 등용 제안을 거절했습니다.` : '등용 제안이 거절됐습니다.';
    case 'income.monthly': {
      const counties = factNumber(event, 'COUNTIES');
      return counties != null ? `이번 달 세입이 현 창고 ${eventFactText('COUNTIES', counties)}곳에 들어왔습니다.` : '이번 달 세력 수입이 집계됐습니다.';
    }
    // 전장 보고 — refs 없이 종류만
    case 'march.corps':
      return '부대를 거느리고 행군했습니다.';
    case 'march.direct':
      return '목적지로 이동했습니다.';
    case 'military.musterOrdered':
      return '군단 집결을 명했습니다.';
    case 'deploy.started':
      return '출병했습니다.';
    default: {
      const label = eventKindCoverage(event.kind) === 'NOT_WRITTEN' ? eventKindLabel(event.kind) : null;
      return label ? `${label} 기록이 있습니다.` : null;
    }
  }
}
