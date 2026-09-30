import { describe, expect, it } from 'vitest';
import { formatGameDate, hasFinalConsonant, withParticle, worldEventSentence, type GameEvent } from '../gameEvents';

const names = {
  city: (id: number) => ({ 12: '허현', 13: '업' } as Record<number, string>)[id],
  nation: (id: number) => ({ 1: '조조', 2: '원소', 3: '손권', 4: '유표' } as Record<number, string>)[id],
};

function event(kind: string, refs: Record<string, number> = {}, at = { year: 200, month: 3, phase: 2, ordinal: 0 }): GameEvent {
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
    expect(worldEventSentence(event('roadFort.captured', { ROAD_FORT: 5, TO_NATION: 2 }), names))
      .toBe('어느 보루를 원소가 차지했습니다.');
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
