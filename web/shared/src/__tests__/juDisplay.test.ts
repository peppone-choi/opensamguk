import { describe, expect, it } from 'vitest';
import { JU_NAMES } from '../map/juDisplay';
import { juDisplayName, juHanja } from '../map/juDisplay';

// 원장 D25(사용자 결정 2026-10-01): 涼州 = 「서량」, 揚州 = 「양주」, 司隸 = 「사례」. 데이터 키는 그대로다.
describe('13주 화면 이름', () => {
  it('涼州 · 司隸 · 揚州는 결정된 화면 이름으로 보인다', () => {
    expect(juDisplayName('량주')).toBe('서량');
    expect(juDisplayName('사예')).toBe('사례');
    expect(juDisplayName('양주')).toBe('양주');
  });

  it('나머지 10주는 데이터 키 그대로다', () => {
    const rest = JU_NAMES.filter((key) => !['량주', '사예', '양주'].includes(key));
    expect(rest).toHaveLength(10);
    for (const key of rest) expect(juDisplayName(key)).toBe(key);
  });

  it('모르는 키는 키 그대로 돌려준다', () => {
    expect(juDisplayName('동이')).toBe('동이');
    expect(juDisplayName('')).toBe('');
    expect(juHanja('동이')).toBeNull();
  });

  it('데이터 키는 서버 ju 색인과 맞춘 값 그대로다(화면 이름으로 바꾸지 않는다)', () => {
    expect(JU_NAMES).toContain('량주');
    expect(JU_NAMES).toContain('사예');
    expect(JU_NAMES).not.toContain('서량');
  });

  it('한자 병기 자리는 화면 이름에 정사 한자를 붙인다: 「서량(涼州)」', () => {
    expect(`${juDisplayName('량주')}(${juHanja('량주')})`).toBe('서량(涼州)');
    expect(juHanja('양주')).toBe('揚州');
    expect(juHanja('사예')).toBe('司隸');
    for (const key of JU_NAMES) expect(juHanja(key), key).toMatch(/^[㐀-鿿]{2}$/);
  });
});
