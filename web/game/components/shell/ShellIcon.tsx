import { Icon, type IconName } from '@opensamguk/ui';

/** 셸 아이콘 이름 — v31system `icon()` 의 이름(레일 · 하단 탭 · 머리줄). */
export type ShellIconName =
  | 'war' | 'retinue' | 'stratagem' | 'territory' | 'corps' | 'court' | 'records' | 'plaza'
  | 'help' | 'admin' | 'mail' | 'menu' | 'close';

/**
 * 셸 아이콘 이름 → 공용 스프라이트 이름. 모두 정본이다 — v3.1 `icon()`(승인본)을 opensamguk-images 가 20 격자로 옮긴
 * 것(#24 · #25)을 앱 export 로 받았다. 대체는 없다.
 */
export const SHELL_ICON_SOURCE: Record<ShellIconName, IconName> = {
  war: 'war-room',
  retinue: 'retinue',
  stratagem: 'stratagem',
  territory: 'territory',
  corps: 'corps',
  court: 'court',
  records: 'records',
  plaza: 'plaza',
  help: 'help',
  admin: 'admin',
  mail: 'mail',
  menu: 'menu',
  close: 'close',
};

export function ShellIcon({ name, size = 20 }: { readonly name: ShellIconName; readonly size?: 16 | 20 | 24 }) {
  return <Icon name={SHELL_ICON_SOURCE[name]} size={size} data-shell-icon={name} />;
}
