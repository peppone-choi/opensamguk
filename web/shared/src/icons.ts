/**
 * UI 아이콘 sprite(`/icons/icons.svg`, `<symbol id="ico-<name>">`)의 이름 목록.
 * 정본은 opensamguk-images `assets/ui-icons/source/*.svg` 이고 여기엔 export 만 복제된다(ADR-LITE-049 Phase 5 I-1).
 * 이 목록과 두 앱의 sprite 가 어긋나면 `__tests__/icon.test.tsx` 가 빨개진다.
 */
export const ICON_NAMES = [
  // 부서 6
  'dept-ops', 'dept-nation', 'dept-military', 'dept-info', 'dept-plaza', 'dept-records',
  // 기록 허브 7
  'hub-best-generals', 'hub-emperor', 'hub-generals', 'hub-kingdoms', 'hub-npcs', 'hub-hall-of-fame', 'hub-traffic',
  // 명령 상태 4
  'cmd-ok', 'cmd-need', 'cmd-no', 'cmd-sealed',
  // 자원 4
  'res-gold', 'res-rice', 'res-troops', 'res-provisions',
  // 공통 9
  'search', 'refresh', 'close', 'arrow-left', 'arrow-right', 'arrow-up', 'arrow-down', 'external', 'filter',
  // 로비 타일 6
  'auction', 'dice', 'diplomacy', 'mail', 'tools', 'members',
] as const;

export type IconName = (typeof ICON_NAMES)[number];

export const ICON_SPRITE_PATH = '/icons/icons.svg';
