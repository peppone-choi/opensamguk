import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { cityIconLevel } from '../iso/cityIconLevel';

// 실측만 쓴다. v3 는 저장소의 세계 파일을 그대로 읽고, v2 는 han.json 을 그대로 읽는다.
const ROOT = resolve(__dirname, '../../../..');
const read = (p: string) => JSON.parse(readFileSync(resolve(ROOT, p), 'utf-8'));

interface WorldCity {
  id: number;
  name: string;
  level: number;
  meta?: { nameCh?: string; isSeat?: boolean; jun?: string };
}
const world = (p: string): WorldCity[] => read(p).cities;
const asInput = (c: WorldCity) => ({
  id: c.id, name: c.name, level: c.level, nameCh: c.meta?.nameCh,
});

const V3 = 'infra/src/main/resources/map/han-world-v3.json';
const V2 = 'infra/src/main/resources/map/han.json';

describe('cityIconLevel', () => {
  it('v3 의 등급 4 는 전부 漢 縣이라 야영이 서지 않는다', () => {
    const four = world(V3).filter((c) => c.level === 4);
    // 낡은 이민족 자리를 물려받은 7 곳. 늘거나 줄면 세계가 바뀐 것이므로 다시 봐야 한다.
    expect(four.map((c) => c.name).sort()).toEqual(
      ['곡창', '남안', '단양', '무렴', '목미', '완온', '함광'].sort(),
    );
    for (const c of four) {
      expect(c.meta?.nameCh?.endsWith('县')).toBe(true);
      expect(cityIconLevel(asInput(c))).not.toBe(4);
      expect(cityIconLevel(asInput(c))).toBe(11);
    }
  });

  it('v2 의 등급 4 는 진짜 이민족이라 야영 그대로다', () => {
    const four = world(V2).filter((c) => c.level === 4);
    expect(four).toHaveLength(7);
    expect(four.map((c) => c.name).sort()).toEqual(
      ['강', '남만', '선비', '산월', '오환', '저', '흉노'].sort(),
    );
    for (const c of four) expect(cityIconLevel(asInput(c))).toBe(4);
  });

  it('등급 4 가 아닌 城 은 한 곳도 옮기지 않는다', () => {
    for (const p of [V3, V2]) {
      for (const c of world(p)) {
        if (c.level === 4) continue;
        expect(cityIconLevel(asInput(c))).toBe(c.level);
      }
    }
  });

  it('郡國 밖 세력(음수 id)은 등급 4 여도 야영 그대로다', () => {
    expect(cityIconLevel({ id: -1, name: '흉노', level: 4, nameCh: '南匈奴' })).toBe(4);
  });
});
