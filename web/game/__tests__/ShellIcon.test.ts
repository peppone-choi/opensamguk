import { describe, expect, it } from 'vitest';
import { ICON_NAMES } from '@opensamguk/ui';
import { SHELL_ICON_SOURCE } from '../components/shell/ShellIcon';

describe('ShellIcon 스프라이트 표', () => {
  it('모든 셸 아이콘이 공용 스프라이트의 정본 이름을 가리킨다', () => {
    for (const [name, sprite] of Object.entries(SHELL_ICON_SOURCE)) {
      expect(ICON_NAMES, `${name} → ${sprite}`).toContain(sprite);
    }
  });

  it('이름이 다른 매핑은 고정한다 — 레일 war 는 war-room(v3.1 키 war)', () => {
    expect(SHELL_ICON_SOURCE.war).toBe('war-room');
  });
});
