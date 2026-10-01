import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  EVENT_KIND_COVERAGE, EVENT_KIND_LABEL, NOT_WRITTEN_NOTE, eventFactText, eventKindCoverage, eventSentence,
  formatGameDate, hasFinalConsonant, withParticle, worldEventSentence, type GameEvent,
} from '../gameEvents';
import { RECORD_KIND_SECTION } from '../recordSections';

const names = {
  city: (id: number) => ({ 12: '허현', 13: '업' } as Record<number, string>)[id],
  nation: (id: number) => ({ 1: '조조', 2: '원소', 3: '손권', 4: '유표' } as Record<number, string>)[id],
};

function event(kind: string, refs: Record<string, number | string> = {}, at = { year: 200, month: 3, phase: 2, ordinal: 0 }): GameEvent {
  return { id: 1, kind, section: 'WORLD', occurredAt: at, refs, facts: {} };
}

describe('formatGameDate', () => {
  it('순은 1 상순 · 2 중순 · 3 하순', () => {
    expect(formatGameDate({ year: 200, month: 3, phase: 1 })).toBe('200년 3월 상순');
    expect(formatGameDate({ year: 200, month: 3, phase: 2 })).toBe('200년 3월 중순');
    expect(formatGameDate({ year: 200, month: 12, phase: 3 })).toBe('200년 12월 하순');
  });
  it('범위 밖 순은 빼고 적는다', () => {
    expect(formatGameDate({ year: 200, month: 3, phase: 0 })).toBe('200년 3월');
    expect(formatGameDate({ year: 200, month: 3, phase: 4 })).toBe('200년 3월');
    expect(formatGameDate({ year: 200, month: 3 })).toBe('200년 3월');
  });
});

describe('조사', () => {
  it('받침', () => {
    expect(hasFinalConsonant('손권')).toBe(true);
    expect(hasFinalConsonant('조조')).toBe(false);
    expect(hasFinalConsonant('pep')).toBe(false);
  });
  it('로/으로 — ㄹ 받침 뒤는 로', () => {
    expect(withParticle('조조', '로/으로')).toBe('조조로');
    expect(withParticle('손권', '로/으로')).toBe('손권으로');
    expect(withParticle('서주', '로/으로')).toBe('서주로');
    expect(withParticle('낙양성', '로/으로')).toBe('낙양성으로');
    expect(withParticle('서울', '로/으로')).toBe('서울로');
  });
  it('이/가 · 을/를', () => {
    expect(withParticle('원소', '이/가')).toBe('원소가');
    expect(withParticle('손권', '이/가')).toBe('손권이');
    expect(withParticle('허현', '을/를')).toBe('허현을');
    expect(withParticle('보루', '을/를')).toBe('보루를');
  });
});

describe('worldEventSentence', () => {
  it('현 소유 바뀜 — 보드 문장과 같다', () => {
    expect(worldEventSentence(event('county.ownerChanged', { CITY: 12, FROM_NATION: 2, TO_NATION: 1 }), names))
      .toBe('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.');
    expect(worldEventSentence(event('county.ownerChanged', { CITY: 13, FROM_NATION: 1, TO_NATION: 3 }), names))
      .toBe('업의 소유 세력이 조조에서 손권으로 바뀌었습니다.');
  });
  it('세력 0 은 주인 없음 — 「어느 세력」 · 「무주에서」로 적지 않는다(pep 실측)', () => {
    expect(worldEventSentence(event('county.ownerChanged', { CITY: 12, FROM_NATION: 0, TO_NATION: 3 }), names))
      .toBe('주인 없던 허현을 손권이 차지했습니다.');
    expect(worldEventSentence(event('county.ownerChanged', { CITY: 13, FROM_NATION: 1, TO_NATION: 0 }), names))
      .toBe('업이 주인 없는 땅이 됐습니다.');
  });
  it('모르는 이름은 지어내지 않는다', () => {
    expect(worldEventSentence(event('county.ownerChanged', { CITY: 999, FROM_NATION: 77, TO_NATION: 1 }), names))
      .toBe('어느 현의 소유 세력이 어느 세력에서 조조로 바뀌었습니다.');
    expect(worldEventSentence(event('roadFort.captured', { ROAD_FORT: 'fort-hulao', TO_NATION: 2 }), names))
      .toBe('어느 보루를 원소가 차지했습니다.');
    expect(worldEventSentence(event('roadFort.captured', { ROAD_FORT: 'fort-hulao', TO_NATION: 2 }), { ...names, roadFort: (id) => (id === 'fort-hulao' ? '호뢰관' : undefined) }))
      .toBe('호뢰관을 원소가 차지했습니다.');
  });
  it('월단평 · 따라잡기 끝', () => {
    expect(worldEventSentence(event('yuedan.announced', {}, { year: 200, month: 7, phase: 1, ordinal: 0 }), names))
      .toBe('200년 7월 월단평 결과가 발표됐습니다.');
    expect(worldEventSentence(event('server.catchUpFinished'), names)).toBe('서버가 멈췄던 동안 밀린 순을 모두 따라잡았습니다.');
  });
  it('모르는 종류는 null', () => {
    expect(worldEventSentence(event('county.captured', { CITY: 12 }), names)).toBeNull();
  });
});

// ---------------------------------------------------------------- 기록 5분류 36종

const people = { ...names, general: (id: number) => ({ 7: '하후돈', 8: '조조', 9: '허저' } as Record<number, string>)[id] };
const full = (kind: string, refs: Record<string, number | string> = {}, facts: Record<string, number | string> = {}): GameEvent =>
  ({ id: 5, kind, section: RECORD_KIND_SECTION[kind] ?? 'WORLD', occurredAt: { year: 200, month: 3, phase: 2, ordinal: 0 }, refs, facts });

// 두 번째 축: 서버 EventKind.kt(종류 · 관객)와 엔진 소스(`EventKind.X` 로 쓰는 곳)를 직접 읽는다 — C7 전수 대조와 같은 방법.
const ROOT = resolve(__dirname, '../../../..');
function kotlinFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? kotlinFiles(path) : path.endsWith('.kt') ? [path] : [];
  });
}
function serverCoverage(): Record<string, string> {
  const kinds = readFileSync(join(ROOT, 'logic/src/main/kotlin/opensamguk/logic/record/EventKind.kt'), 'utf-8');
  const engine = kotlinFiles(join(ROOT, 'app/game-engine/src/main/kotlin')).map((file) => readFileSync(file, 'utf-8')).join('\n');
  const out: Record<string, string> = {};
  for (const m of kinds.matchAll(/^\s+([A-Z_]+)\("([a-zA-Z.]+)",\s*[A-Z_]+,\s*(emptySet\(\)|setOf)/gm)) {
    const written = new RegExp(`EventKind\\.${m[1]}\\b`).test(engine);
    out[m[2]] = written ? 'WRITTEN' : m[3] === 'emptySet()' ? 'ALIAS' : 'NOT_WRITTEN';
  }
  return out;
}

describe('종류 표', () => {
  it('서버가 쓰는지 표가 서버 EventKind.kt · 엔진 소스와 한 줄씩 같다(24 · 2 · 10)', () => {
    const server = serverCoverage();
    expect(Object.keys(server)).toHaveLength(36); // 읽기가 살아 있는지(0건 통과 방지)
    expect(EVENT_KIND_COVERAGE).toEqual(server);
    const count = (c: string) => Object.values(EVENT_KIND_COVERAGE).filter((v) => v === c).length;
    expect([count('WRITTEN'), count('ALIAS'), count('NOT_WRITTEN')]).toEqual([24, 2, 10]);
  });
  it('분류 표 · 이름 표와 종류가 같다', () => {
    expect(Object.keys(EVENT_KIND_COVERAGE).sort()).toEqual(Object.keys(RECORD_KIND_SECTION).sort());
    expect(Object.keys(EVENT_KIND_LABEL).sort()).toEqual(Object.keys(RECORD_KIND_SECTION).sort());
    expect(NOT_WRITTEN_NOTE).toBe('서버가 아직 사건으로 쓰지 않음');
  });
  it('모르는 종류 · 프로토타입 이름은 표에 없다', () => {
    expect(eventKindCoverage('something.new')).toBeNull();
    expect(eventKindCoverage('toString')).toBeNull();
  });
});

describe('eventSentence', () => {
  it('서버가 쓰는 24종은 refs · facts 가 비어도 문장이 있다(지어낸 이름 없이)', () => {
    for (const [kind, coverage] of Object.entries(EVENT_KIND_COVERAGE)) {
      if (coverage !== 'WRITTEN') continue;
      const text = eventSentence(full(kind), people);
      expect(text, kind).toMatch(/[.다)]$/);
      expect(text, kind).not.toMatch(/undefined|NaN|null/);
    }
  });
  it('아직 쓰지 않는 종류는 종류 이름만, 보루 차지는 천하 정세 문장', () => {
    expect(eventSentence(full('encounter.personal', { ACTOR: 7, CITY: 12 }), people)).toBe('개인 조우 기록이 있습니다.');
    expect(eventSentence(full('input.rejected'), people)).toBe('입력 무효 기록이 있습니다.');
    expect(eventSentence(full('roadFort.captured', { ROAD_FORT: 'e@1,2', TO_NATION: 2 }), people)).toBe('어느 보루를 원소가 차지했습니다.');
  });
  it('옛 별칭 · 모르는 종류는 null(그 줄만 「기록을 표시할 수 없습니다.」)', () => {
    expect(eventSentence(full('county.captured', { CITY: 12 }), people)).toBeNull();
    expect(eventSentence(full('something.new'), people)).toBeNull();
    expect(eventSentence(full('constructor'), people)).toBeNull();
  });
  it('개인 행적', () => {
    expect(eventSentence(full('march.assignment', { ACTOR: 7, CITY: 12 }), people)).toBe('발령지 허현으로 행군했습니다.');
    expect(eventSentence(full('march.assignment', { ACTOR: 7, CITY: 999 }), people)).toBe('발령지로 행군했습니다.');
    expect(eventSentence(full('court.rewardReceived', { ISSUER: 8, TARGET: 7 }, { MONEY: 1200, REASON: 'ROUTINE_SERVICE' }), people))
      .toBe('조조에게서 포상으로 금 1,200을 받았습니다.');
    expect(eventSentence(full('enlist.joined', { ACTOR: 7, NATION: 1 }), people)).toBe('조조에 출사했습니다.');
    expect(eventSentence(full('renown.event', { ACTOR: 7 }, { SOURCE: 'COUNTY_CAPTURE' }), people)).toBe('명망에 반영될 일이 있었습니다 — 현 점령.');
    expect(eventSentence(full('renown.event', { ACTOR: 7 }, { SOURCE: 'NEW_CODE' }), people)).toBe('명망에 반영될 일이 있었습니다.');
    expect(eventSentence(full('yuedan.assessed', { ACTOR: 7 }, { RENOWN_BEFORE: 40, RENOWN_AFTER: 52, RENOWN_CHANGE: 12 }), people))
      .toBe('월단평에서 명망이 올랐습니다(40 → 52).');
    expect(eventSentence(full('yuedan.assessed', { ACTOR: 7 }, { RENOWN_BEFORE: 52, RENOWN_AFTER: 44, RENOWN_CHANGE: -8 }), people))
      .toBe('월단평에서 명망이 내렸습니다(52 → 44).');
    expect(eventSentence(full('yuedan.assessed', { ACTOR: 7 }, { RENOWN_BEFORE: 44, RENOWN_AFTER: 44, RENOWN_CHANGE: 0 }), people))
      .toBe('월단평에서 명망이 그대로입니다(44).');
  });
  it('조정 공문은 보는 사람(ISSUER · TARGET)에 따라 시점을 고른다', () => {
    const refs = { REQUEST: 'req-1', ISSUER: 8, TARGET: 7 };
    expect(eventSentence(full('court.dispatchReceived', refs), people, { generalId: 7 })).toBe('조조의 발령이 도착했습니다.');
    expect(eventSentence(full('court.dispatchIssued', refs), people, { generalId: 8 })).toBe('하후돈에게 발령을 내렸습니다.');
    expect(eventSentence(full('court.dispatchAccepted', refs), people, { generalId: 7 })).toBe('조조의 발령을 수락했습니다.');
    expect(eventSentence(full('court.dispatchAccepted', refs), people, { generalId: 8 })).toBe('하후돈이 발령을 수락했습니다.');
    expect(eventSentence(full('court.dispatchRefused', refs), people, { generalId: null })).toBe('하후돈이 조조의 발령을 거절했습니다.');
    expect(eventSentence(full('court.dispatchCancelled', refs), people, { generalId: 7 })).toBe('조조의 발령이 더는 유효하지 않아 불이익 없이 취소됐습니다.');
    expect(eventSentence(full('court.dispatchReceived', { REQUEST: 'req-1', ISSUER: 99 }), people, { generalId: 7 })).toBe('어느 인물의 발령이 도착했습니다.');
  });
  it('부 · 세력 — 부에 새 장수는 방향을 추정하지 않는다', () => {
    expect(eventSentence(full('enlist.retainerJoined', { PERSON: 9 }), people, { generalId: 7 })).toBe('새 장수가 부에 들었습니다.');
    expect(eventSentence(full('enlist.retainerJoined', { PERSON: 8 }), people, { generalId: 9 })).toBe('새 장수가 부에 들었습니다.');
    expect(eventSentence(full('people.joined', { ACTOR: 7, PERSON: 9 }), people)).toBe('허저가 제안에 동의해 부에 들어왔습니다.');
    expect(eventSentence(full('people.resisted', { ACTOR: 7 }), people)).toBe('등용 제안이 거절됐습니다.');
    expect(eventSentence(full('people.searched', { ACTOR: 7, CITY: 13 }), people)).toBe('업에서 인재를 탐색했습니다.');
    expect(eventSentence(full('income.monthly', { NATION: 1 }, { COUNTIES: 9, MONEY: 3400 }), people)).toBe('이번 달 세입이 현 창고 9곳에 들어왔습니다.');
  });
  it('전장 보고는 refs 가 와도 종류 문장만(K5-07 전)', () => {
    expect(eventSentence(full('deploy.started', { ACTOR: 7, CITY: 12 }), people)).toBe('출병했습니다.');
    expect(eventSentence(full('march.corps'), people)).toBe('부대를 거느리고 행군했습니다.');
  });
});

describe('eventFactText', () => {
  it('코드는 이름표로, 모르는 코드는 null, 수는 쉼표 · 부호', () => {
    expect(eventFactText('SOURCE', 'ENCOUNTER_VICTORY')).toBe('조우 승리');
    expect(eventFactText('SOURCE', 'toString')).toBeNull();
    expect(eventFactText('REASON', 'LOYALTY_SUPPORT')).toBe('충성 격려');
    expect(eventFactText('MONEY', 123456)).toBe('123,456');
    expect(eventFactText('RENOWN_CHANGE', 12)).toBe('+12');
    expect(eventFactText('RENOWN_CHANGE', -8)).toBe('−8');
    expect(eventFactText('MONEY', 'x')).toBeNull();
  });
});
