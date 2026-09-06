import type { SVGAttributes } from 'react';
import { ICON_SPRITE_PATH, type IconName } from './icons';

export type IconSize = 12 | 14 | 16 | 20 | 24 | 32;

export type IconProps = Omit<SVGAttributes<SVGSVGElement>, 'children' | 'name'> & {
  readonly name: IconName;
  /** 기본 16. 격자는 20×20 이라 16·20·32 에서 가장 또렷하다. */
  readonly size?: IconSize;
  /** 있으면 스크린리더에 읽히는 이미지(role=img)가 되고, 없으면 장식(aria-hidden)이다. */
  readonly label?: string;
};

/** sprite `<use>` 아이콘. 색은 `currentColor` — 부모의 color 를 그대로 따른다. 이모지 대체용. */
export function Icon({ name, size = 16, label, className = '', ...rest }: IconProps) {
  return (
    <svg
      className={`os-icon ${className}`.trim()}
      width={size}
      height={size}
      data-icon={name}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      focusable="false"
      {...rest}
    >
      <use href={`${ICON_SPRITE_PATH}#ico-${name}`} />
    </svg>
  );
}
