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
  it('v3 의 등급 4 는 郡國 밖 이민족 거점 7 곳뿐이다 — 漢 縣은 한 곳도 없다', () => {
    // 예전에는 낡은 이민족 자리를 번호째 물려받은 漢 縣 7 곳(곡창·남안·단양·무렴·
    // 목미·완온·함광)이 '이' 등급을 달고 있었다. build_han_world.build_v3 가 등급을
    // seatRole·郡國志 戶口에서 다시 세우면서 사라졌다 —
    // tools/scenario/tests/test_build_han_world_v3.py 가 그쪽을 건다.
    // 2026-09-17: 郡國 밖 취락 37 곳이 城으로 서면서(w5) 東夷傳 권역 밖 이민족 거점 7 곳이 '이' 를 받았다
    // (build_han_world SETTLEMENT_NODE). v2 의 7 곳과 같은 세력이고, 이름만 사료 관할 이름이다.
    expect(world(V3).filter((c) => c.level === 4).map((c) => c.name).sort()).toEqual(
      ['남흉노', '백마저', '산월', '서강', '선비', '애뢰(哀牢)', '오환'].sort(),
    );
    // 그래도 이 함수는 남는다. v2(han.json)의 등급 4 는 진짜 이민족이고,
    // 아래 검사가 그쪽을 지킨다.
    expect(cityIconLevel({ id: 1, name: '곡창현', level: 4, nameCh: '谷昌县' })).toBe(11);
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
