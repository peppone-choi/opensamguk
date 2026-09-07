import type { SVGProps } from 'react';

// 세력 깃발 — 국가색은 깃발 mask 에만 쓴다(도시·지형 아이콘 본체 재염색 금지, 로드맵 규칙).
// Phase 5(이미지 제작)에서 문양·테두리·축·바람 방향을 가진 sprite 로 교체한다. 그 전까지 시안과 같은 인라인 SVG.
export type FlagProps = Omit<SVGProps<SVGSVGElement>, 'color'> & {
  readonly color: string;
  readonly size?: number;
  readonly label?: string;
};

export function Flag({ color, size = 14, label, className = '', ...rest }: FlagProps) {
  return (
    <svg
      {...rest}
      className={`os-flag ${className}`.trim()}
      width={size}
      height={size}
      viewBox="0 0 14 14"
      xmlns="http://www.w3.org/2000/svg"
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      data-nation-color={color}
    >
      <rect x="2" y="1" width="1.5" height="12" fill="#8a8477" />
      <path d="M3.5 1.5 L12 3.2 L9.5 5.2 L12 7.4 L3.5 8.6 Z" fill={color} />
    </svg>
  );
}
