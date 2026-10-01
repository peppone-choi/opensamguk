import { Icon } from '../Icon';
import type { IconName } from '../icons';

/** v3.1 부품이 쓰는 아이콘 — 보드 v31system `icon()` 의 이름. */
export type PartIconName =
  | 'list' | 'alert' | 'lock' | 'clock' | 'unplug' | 'back' | 'tools'
  | 'target' | 'play' | 'pause' | 'prev' | 'next' | 'copy' | 'help' | 'close';

/**
 * 부품 아이콘 이름 → 공용 스프라이트 이름. 모두 정본이다 — v3.1 `icon()`(승인본)을 opensamguk-images 가 20 격자로 옮긴
 * 것(#24)을 앱 export 로 받았다. 대체(비슷한 스프라이트 · CSS 도형)는 없다.
 */
export const PART_ICON_SOURCE: Record<PartIconName, IconName> = {
  list: 'list',
  alert: 'alert',
  lock: 'lock',
  clock: 'clock',
  unplug: 'unplug',
  back: 'chevron-left',
  tools: 'tools',
  target: 'target',
  play: 'play',
  pause: 'pause',
  prev: 'skip-back',
  next: 'skip-forward',
  copy: 'copy',
  help: 'help',
  close: 'close',
};

/** 부품 아이콘. 늘 장식(aria-hidden)이다 — 뜻은 옆 글자나 단추의 aria-label 이 전한다. */
export function PartIcon({ name, size = 20 }: { readonly name: PartIconName; readonly size?: 16 | 20 }) {
  return <Icon name={PART_ICON_SOURCE[name]} size={size} data-part-icon={name} />;
}
