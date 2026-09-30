import { Icon } from '../Icon';
import type { IconName } from '../icons';

/** v3.1 부품이 쓰는 아이콘 — 보드 v31system `icon()` 의 이름. */
export type PartIconName =
  | 'list' | 'alert' | 'lock' | 'clock' | 'unplug' | 'back' | 'tools'
  | 'target' | 'play' | 'pause' | 'prev' | 'next' | 'copy' | 'help' | 'close';

type Source =
  | { readonly sprite: IconName; readonly substitute: boolean }
  | { readonly glyph: 'target' | 'play' | 'pause' | 'prev' | 'next' | 'copy' | 'help' };

/**
 * 아이콘 이름 → 지금 그리는 것. **대체 자리는 이 표 하나뿐이다.**
 * 공용 스프라이트(opensamguk-images 정본 → 앱 export)에 보드 아이콘이 들어오면 그 줄을 `{ sprite: '<새 이름>', substitute: false }`
 * 로 바꾼다. 원천 모양은 v31system `icon()` 경로(승인본)이고 새로 그리지 않는다.
 *
 * | 이름 | 지금 | 상태 |
 * |---|---|---|
 * | tools · close | 스프라이트 그대로 | 정본 |
 * | list · alert · lock · clock · unplug · back | 비슷한 스프라이트(filter · cmd-no · cmd-sealed · cmd-need · refresh · arrow-left) | images PR 대기 |
 * | target · play · pause · prev · next · copy · help | CSS 도형 | images PR 대기 |
 */
export const PART_ICON_SOURCE: Record<PartIconName, Source> = {
  tools: { sprite: 'tools', substitute: false },
  close: { sprite: 'close', substitute: false },
  list: { sprite: 'filter', substitute: true },
  alert: { sprite: 'cmd-no', substitute: true },
  lock: { sprite: 'cmd-sealed', substitute: true },
  clock: { sprite: 'cmd-need', substitute: true },
  unplug: { sprite: 'refresh', substitute: true },
  back: { sprite: 'arrow-left', substitute: true },
  target: { glyph: 'target' },
  play: { glyph: 'play' },
  pause: { glyph: 'pause' },
  prev: { glyph: 'prev' },
  next: { glyph: 'next' },
  copy: { glyph: 'copy' },
  help: { glyph: 'help' },
};

/** 부품 아이콘. 늘 장식(aria-hidden)이다 — 뜻은 옆 글자나 단추의 aria-label 이 전한다. */
export function PartIcon({ name, size = 20 }: { readonly name: PartIconName; readonly size?: 16 | 20 }) {
  const source = PART_ICON_SOURCE[name];
  if ('sprite' in source) return <Icon name={source.sprite} size={size} data-part-icon={name} />;
  return <i className={`os-glyph os-glyph--${source.glyph} os-glyph--${size}`} data-part-icon={name} aria-hidden="true" />;
}
