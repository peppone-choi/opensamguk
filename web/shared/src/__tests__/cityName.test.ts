import { describe, expect, it } from 'vitest';
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
});
