// utilGame 포매터 Tier-0 시드 — legacy hwe/ts/utilGame 동작 패러티 잠금.
// 특히 web/game 자립 재구현한 binarySearch 버킷 경계(매치/미스 하위버킷/하한/상한)를 검증한다.
import { describe, it, expect } from 'vitest';
import {
  formatHonor,
  formatDefenceTrain,
  formatDexLevel,
  formatRefreshScore,
  formatInjury,
  formatOfficerLevelText,
  formatGeneralTypeCall,
  formatVoteColor,
  getNPCColor,
  isValidObjKey,
  nextExpLevelRemain,
  calcInjury,
  calcTournamentTerm,
  convTechLevel,
  getMaxRelativeTechLevel,
  formatLog,
  formatTournamentStep,
  formatTournamentType,
} from '@/lib/utilGame';

describe('binarySearch 버킷 포매터 (경계)', () => {
  it('formatHonor — 정확 임계/하위버킷/상한', () => {
    expect(formatHonor(0)).toBe('전무');
    expect(formatHonor(639)).toBe('전무'); // 640 미만 → 하위버킷
    expect(formatHonor(640)).toBe('무명'); // 정확 임계
    expect(formatHonor(2559)).toBe('무명');
    expect(formatHonor(2560)).toBe('신동');
    expect(formatHonor(100000)).toBe('구세주'); // 최상위(>=77440)
  });

  it('formatDefenceTrain — 기호 버킷', () => {
    expect(formatDefenceTrain(0)).toBe('△');
    expect(formatDefenceTrain(59)).toBe('△');
    expect(formatDefenceTrain(60)).toBe('○');
    expect(formatDefenceTrain(80)).toBe('◎');
    expect(formatDefenceTrain(90)).toBe('☆');
    expect(formatDefenceTrain(999)).toBe('×');
    expect(formatDefenceTrain(2000)).toBe('×');
  });

  it('formatDexLevel — level/name/color', () => {
    expect(formatDexLevel(0)).toEqual({ level: 0, name: 'F-', color: 'navy' });
    expect(formatDexLevel(349)).toEqual({ level: 0, name: 'F-', color: 'navy' });
    expect(formatDexLevel(350)).toEqual({ level: 1, name: 'F', color: 'navy' });
    expect(formatDexLevel(1275975)).toEqual({ level: 26, name: 'EX+', color: 'white' });
    expect(formatDexLevel(99999999)).toEqual({ level: 26, name: 'EX+', color: 'white' });
  });

  it('formatRefreshScore — null=0, 임계', () => {
    expect(formatRefreshScore(null)).toBe('안함');
    expect(formatRefreshScore(0)).toBe('안함');
    expect(formatRefreshScore(49)).toBe('안함');
    expect(formatRefreshScore(50)).toBe('무관심');
    expect(formatRefreshScore(99999)).toBe('헐...');
  });
});

describe('단순 버킷/맵 포매터', () => {
  it('formatInjury', () => {
    expect(formatInjury(0)).toEqual(['건강', 'white']);
    expect(formatInjury(20)).toEqual(['경상', 'yellow']);
    expect(formatInjury(40)).toEqual(['중상', 'orange']);
    expect(formatInjury(60)).toEqual(['심각', 'magenta']);
    expect(formatInjury(61)).toEqual(['위독', 'red']);
  });

  it('formatOfficerLevelText — default/국가레벨/officerLevel<5', () => {
    expect(formatOfficerLevelText(0)).toBe('재야');
    expect(formatOfficerLevelText(1)).toBe('일반');
    expect(formatOfficerLevelText(12)).toBe('군주');
    expect(formatOfficerLevelText(12, 7)).toBe('황제');
    expect(formatOfficerLevelText(12, 6)).toBe('왕');
    expect(formatOfficerLevelText(4, 7)).toBe('태수'); // <5 → 국가레벨 무시, default
    expect(formatOfficerLevelText(5, 5)).toBe('제3모사'); // nation7 map엔 5 없음 → default fallback
  });

  it('getNPCColor', () => {
    expect(getNPCColor(7)).toBe('gold');
    expect(getNPCColor(6)).toBe('mediumaquamarine');
    expect(getNPCColor(2)).toBe('cyan');
    expect(getNPCColor(1)).toBe('skyblue');
    expect(getNPCColor(0)).toBeUndefined();
  });

  it('formatVoteColor — 7색 순환', () => {
    expect(formatVoteColor(0)).toBe('#ff0000');
    expect(formatVoteColor(6)).toBe('#800080');
    expect(formatVoteColor(7)).toBe('#ff0000');
  });
});

describe('계산 포매터', () => {
  it('isValidObjKey', () => {
    expect(isValidObjKey('None')).toBe(false);
    expect(isValidObjKey(null)).toBe(false);
    expect(isValidObjKey(undefined)).toBe(false);
    expect(isValidObjKey(0)).toBe(true);
    expect(isValidObjKey('a')).toBe(true);
  });

  it('nextExpLevelRemain — exp<1000 / >=1000', () => {
    expect(nextExpLevelRemain(250, 2)).toEqual([50, 100]);
    expect(nextExpLevelRemain(1000, 10)).toEqual([0, 210]); // base=1000, next=1210
  });

  it('calcInjury — round(base*(100-injury)/100)', () => {
    expect(calcInjury('leadership', { leadership: 80, strength: 0, intel: 0, injury: 50 })).toBe(40);
    expect(calcInjury('strength', { leadership: 0, strength: 71, intel: 0, injury: 0 })).toBe(71);
  });

  it('calcTournamentTerm — clamp 5..120', () => {
    expect(calcTournamentTerm(3)).toBe(5);
    expect(calcTournamentTerm(60)).toBe(60);
    expect(calcTournamentTerm(200)).toBe(120);
  });

  it('techLevel — convTechLevel/getMaxRelativeTechLevel clamp', () => {
    expect(convTechLevel(2500, 9)).toBe(2);
    expect(convTechLevel(99999, 9)).toBe(9); // clamp 상한
    expect(getMaxRelativeTechLevel(180, 184, 9, 1, 2)).toBe(3); // floor(4/2)+1
    expect(getMaxRelativeTechLevel(180, 180, 9, 1, 2)).toBe(1); // clamp 하한
  });

  it('formatGeneralTypeCall — 대표 분기', () => {
    const gc = { chiefStatMin: 65, statGradeLevel: 11 };
    expect(formatGeneralTypeCall(30, 10, 10, gc)).toBe('아둔'); // lead<40, str+int<40
    expect(formatGeneralTypeCall(90, 90, 90, gc)).toBe('만능');
  });
});

describe('formatLog — 색 태그 → span', () => {
  it('단일/이중/닫기 태그', () => {
    expect(formatLog('<R>피해<\/>')).toBe('<span style="color: red;">피해</span>');
    expect(formatLog('<Y1>작게<\/>')).toBe('<span style="color: yellow;font-size: 0.9em;">작게</span>');
    expect(formatLog('')).toBe('');
    expect(formatLog('평문')).toBe('평문');
  });

  it('허용된 강조 태그만 보존하고 나머지 HTML은 escape', () => {
    expect(formatLog('<C><b>【지배】</b></>')).toBe('<span style="color: cyan;"><b>【지배】</b></span>');
    expect(formatLog("<span class='ev_failed'>실패</span>")).toBe('<span class="ev_failed">실패</span>');
    expect(formatLog("<span style='color:#FFFF00;'><b>국기</b></span>")).toBe('<span style="color:#FFFF00;"><b>국기</b></span>');
    expect(formatLog('<img src=x onerror=alert(1)>')).toBe('&lt;img src=x onerror=alert(1)&gt;');
    expect(formatLog('장수<script>alert(1)</script>')).toBe('장수&lt;script&gt;alert(1)&lt;/script&gt;');
  });
});

describe('formatTournament', () => {
  it('type/step', () => {
    expect(formatTournamentType(0)).toBe('전력전');
    expect(formatTournamentType(null)).toBe('?');
    expect(formatTournamentStep(1).state).toBe('참가 모집중');
    expect(formatTournamentStep(null).state).toBe('경기 없음');
  });
});
