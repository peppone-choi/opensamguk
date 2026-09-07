import { describe, expect, it } from 'vitest';
import { parseTerrainEtagHash } from '../HanMapCanvas';

const SHA = 'a'.repeat(64);

describe('parseTerrainEtagHash', () => {
  it('강한 ETag 에서 지문을 뽑는다', () => {
    expect(parseTerrainEtagHash(`"sha256-${SHA}"`)).toBe(SHA);
  });

  // 적색 프로브: 강한 태그만 받던 시절 프로덕션(gzip 프록시)에서 지문이 늘 null 이었다.
  it('약한 ETag(W/) 도 같은 지문을 준다', () => {
    expect(parseTerrainEtagHash(`W/"sha256-${SHA}"`)).toBe(SHA);
  });

  it('ETag 가 없거나 형식이 다르면 null', () => {
    expect(parseTerrainEtagHash(null)).toBeNull();
    expect(parseTerrainEtagHash('"deadbeef"')).toBeNull();
    expect(parseTerrainEtagHash(`W/"sha256-${'z'.repeat(64)}"`)).toBeNull();
  });
});
