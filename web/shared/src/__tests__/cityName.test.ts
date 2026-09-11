import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { cityDisplayName, isHanCounty } from '../iso/cityName';

// 값은 전부 infra/src/main/resources/map/han.json 실측이다(2026-09-10). 지어낸 城 은 없다.
const city = (id: number, name: string, level: number, nameCh?: string) =>
  ({ id, name, level, nameCh });

describe('isHanCounty', () => {
  it('nameCh 가 县 으로 끝나면 郡治라도 縣 이다', () => {
    // 홍농은 弘農郡의 治所이자 弘農縣 이다. 등급은 5(소군)라 등급만으로는 안 잡힌다.
    expect(isHanCounty(city(22, '홍농', 5, '弘农县'))).toBe(true);
    expect(isHanCounty(city(1, '장안', 9, '长安县'))).toBe(true);
  });

  it('侯國은 등급이 郡급이어도 縣 이다 — 屬國은 아니다', () => {
    // 실측(han-world-v3): 이 세 侯國이 등급 5·6 을 달고 있어 등급 규칙으로는 안 잡힌다.
    expect(isHanCounty(city(211, '낙평', 6, '乐平侯国'))).toBe(true);
    expect(isHanCounty(city(311, '곡양(下邳國)', 5, '曲阳侯国'))).toBe(true);
    expect(isHanCounty(city(430, '안중', 5, '安众侯国'))).toBe(true);
    // 屬國은 郡 한 급이다.
    expect(isHanCounty(city(638, '구자속국', 5, '龜茲屬國'))).toBe(false);
  });

  it('등급 10·11(영현·장현)은 nameCh 가 侯國·道 여도 縣 이다', () => {
    expect(isHanCounty(city(404, '원록', 10, '原鹿侯国'))).toBe(true);
    expect(isHanCounty(city(600, '문강', 10, '汶江道'))).toBe(true);
    // 두릉은 nameCh 에 단위가 안 붙어 있다. 등급 11 이 縣 임을 말한다.
    expect(isHanCounty(city(5, '두릉', 11, '杜陵'))).toBe(true);
  });

  it('縣 기록이 없는 郡·屬國은 縣 이 아니다 — 「감릉현」을 새로 만들지 않는다', () => {
    expect(isHanCounty(city(199, '감릉군', 6, '甘陵郡'))).toBe(false);
    expect(isHanCounty(city(629, '장액속국', 5, '張掖屬國'))).toBe(false);
    expect(isHanCounty(city(723, '요동속국', 5, '遼東屬國'))).toBe(false);
  });

  it('이민족 거점과 동이는 郡縣制 밖이다', () => {
    expect(isHanCounty(city(676, '흉노', 4, '南匈奴'))).toBe(false);
    expect(isHanCounty(city(754, '백제국', 5, '伯濟國'))).toBe(false);
    expect(isHanCounty(city(763, '안야국', 5, '安邪國'))).toBe(false);
  });

  it('음수 id(郡國 밖 세력)는 등급이 뭐든 縣 이 아니다', () => {
    expect(isHanCounty(city(-3, '졸본', 11))).toBe(false);
  });

  it('nameCh 가 아예 없으면 등급만으로 가른다', () => {
    expect(isHanCounty(city(2, '상락', 11))).toBe(true);
    expect(isHanCounty(city(9, '시평', 5))).toBe(false);
  });
});

describe('cityDisplayName', () => {
  it('縣 이면 「뭐뭐현」', () => {
    expect(cityDisplayName(city(1, '장안', 9, '长安县'))).toBe('장안현');
    expect(cityDisplayName(city(200, '청하국 영', 10, '灵县'))).toBe('청하국 영현');
  });

  it('縣 이 아니면 원 이름 그대로', () => {
    expect(cityDisplayName(city(199, '감릉군', 6, '甘陵郡'))).toBe('감릉군');
    expect(cityDisplayName(city(676, '흉노', 4, '南匈奴'))).toBe('흉노');
    expect(cityDisplayName(city(-1, '북옥저', 5))).toBe('북옥저');
  });

  it('이미 현으로 끝나면 두 번 붙이지 않는다', () => {
    expect(cityDisplayName(city(507, '문안현', 11, '文安县'))).toBe('문안현');
  });

  // 같은 독음이 겹치면 생성기가 소속 郡이나 번호를 뒤에 단다(han-world-v3 832 중 136 곳).
  // 그건 식별자를 유일하게 만드는 장치고, 화면에서는 뗀다(2026-09-10 「군을 빼」).
  it('한정자를 떼고 현을 붙인다', () => {
    expect(cityDisplayName(city(2, '의씨(河東郡)', 11, '猗氏县'))).toBe('의씨현');
    expect(cityDisplayName(city(1, '장안(京兆尹)', 9, '长安县'))).toBe('장안현');
    expect(cityDisplayName(city(222, '장(東平國)', 5, '章县'))).toBe('장현');
    expect(cityDisplayName(city(311, '영릉#123', 11, '零陵县'))).toBe('영릉현');
  });

  it('한정자를 떼고 나서 이미 현이면 두 번 붙이지 않는다', () => {
    expect(cityDisplayName(city(507, '문안현(涿郡)', 11, '文安县'))).toBe('문안현');
  });

  it('縣 이 아니어도 한정자는 뗀다', () => {
    expect(cityDisplayName(city(199, '감릉군(冀州)', 6, '甘陵郡'))).toBe('감릉군');
  });
});

// 화면 표기는 서버가 실어 보내는 meta.nameCh 에 행정 단위 꼬리가 붙어 있어야 산다.
// 2026-09-10 프로덕션 실측에서 han-world-v3 는 당시 781 곳 전부 꼬리 없는 줄기(「长安」)를
// 실어 보내고 있었고, 그래서 郡治 175 곳이 「뭐뭐현」을 못 받았다. 생성기를 고쳤으니
// 세계 파일 자체를 걸어 둔다 — 줄기로 되돌아가면 여기가 빨개진다.
describe('han-world-v3 의 meta.nameCh', () => {
  const ROOT = resolve(__dirname, '../../../..');
  const world = JSON.parse(
    readFileSync(resolve(ROOT, 'infra/src/main/resources/map/han-world-v3.json'), 'utf-8'),
  ) as { cities: { id: number; name: string; level: number; meta: { nameCh: string } }[] };

  it('행정 단위 꼬리를 달고 온다 — 縣 판정의 첫 규칙이 산다', () => {
    const counties = world.cities.filter((c) => c.meta.nameCh.endsWith('县'));
    expect(counties.length).toBe(756);
  });

  it('등급 10·11 밖의 城 도 縣 으로 잡힌다 — 郡治가 「뭐뭐현」을 받는다', () => {
    // 縣 등급(10·11) 밖 = 郡治 77 곳. 예전에는 175 였다 — 縣 93 곳이 郡 등급을
    // 물려받고 있었기 때문이다(build_han_world.build_v3 에서 고쳤다).
    const outside = world.cities.filter((c) => c.level !== 10 && c.level !== 11);
    expect(outside.length).toBe(77);
    const rest = outside
      .filter((c) => !isHanCounty({ id: c.id, name: c.name, level: c.level, nameCh: c.meta.nameCh }))
      .map((c) => c.name)
      .sort();
    // 縣 이 아닌 채로 남는 것은 縣 기록이 없는 邊境 郡과 屬國뿐이다. 「낙랑현」을 만들지 않는다.
    expect(rest).toEqual(
      ['교지군', '구진군', '낙랑군', '요동군', '요동속국', '일남군', '현도군'].sort(),
    );
  });

  it('屬國은 縣 등급을 달고 있어도 縣 이 아니다', () => {
    // 龜茲屬國은 上郡의 城 하나라 縣 등급(장현)을 받는다. 등급만 보면 「구자속국현」이
    // 되어 없는 縣 을 만든다 — nameCh 꼬리가 등급보다 앞선다.
    const gucha = world.cities.find((c) => c.meta.nameCh === '龜茲屬國')!;
    expect(gucha.level).toBe(11);
    expect(isHanCounty({
      id: gucha.id, name: gucha.name, level: gucha.level, nameCh: gucha.meta.nameCh,
    })).toBe(false);
  });

  it('縣 이 아닌 꼬리를 단 城 은 한 곳도 縣 으로 잡히지 않는다', () => {
    // 侯國은 縣 한 급이라 뺀다 — 續漢書 百官志 「列侯所食縣曰國」.
    const tails = ['属国', '屬國', '郡', '国', '國'];
    const sample = world.cities.filter(
      (c) => tails.some((t) => c.meta.nameCh.endsWith(t))
        && !c.meta.nameCh.endsWith('侯国') && !c.meta.nameCh.endsWith('侯國'),
    );
    const wrong = sample
      .filter((c) => isHanCounty({
        id: c.id, name: c.name, level: c.level, nameCh: c.meta.nameCh,
      }))
      .map((c) => c.meta.nameCh);
    expect(wrong).toEqual([]);
    // 0 건이 「조회가 죽었다」가 아님을 같이 못박는다.
    expect(sample.length).toBeGreaterThan(5);
  });
});
