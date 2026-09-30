import { Icon, type IconName } from '@opensamguk/ui';

/** 셸 아이콘 이름 — v31system `icon()` 의 이름(레일 · 하단 탭 · 머리줄). */
export type ShellIconName =
  | 'war' | 'retinue' | 'stratagem' | 'territory' | 'corps' | 'court' | 'records' | 'plaza'
  | 'help' | 'admin' | 'mail' | 'menu' | 'close';

/**
 * 아이콘 이름 → 지금 그리는 스프라이트. **대체 자리는 이 표 하나뿐이다.** 공용 스프라이트(opensamguk-images 정본)에
 * v3.1 레일 아이콘이 들어오면 그 줄을 새 이름으로 바꾼다. 원천 모양은 v3common `IC`(승인본)이다.
 */
export const SHELL_ICON_SOURCE: Record<ShellIconName, { readonly sprite: IconName; readonly substitute: boolean }> = {
  war: { sprite: 'dept-ops', substitute: true },
  retinue: { sprite: 'members', substitute: true },
  stratagem: { sprite: 'dice', substitute: true },
  territory: { sprite: 'dept-nation', substitute: true },
  corps: { sprite: 'dept-military', substitute: true },
  court: { sprite: 'diplomacy', substitute: true },
  records: { sprite: 'dept-records', substitute: true },
  plaza: { sprite: 'dept-plaza', substitute: true },
  help: { sprite: 'search', substitute: true },
  admin: { sprite: 'tools', substitute: true },
  mail: { sprite: 'mail', substitute: false },
  menu: { sprite: 'filter', substitute: true },
  close: { sprite: 'close', substitute: false },
};

export function ShellIcon({ name, size = 20 }: { readonly name: ShellIconName; readonly size?: 16 | 20 | 24 }) {
  return <Icon name={SHELL_ICON_SOURCE[name].sprite} size={size} data-shell-icon={name} />;
}
