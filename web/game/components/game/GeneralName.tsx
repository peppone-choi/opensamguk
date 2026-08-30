import type { CSSProperties } from 'react';
import { getNPCColor } from '../../lib/utilGame';

type GeneralNameProps = {
  name: string;
  npcType: number;
  className?: string;
  style?: CSSProperties;
};

/** NPC 색상과 황제 특별 표식을 한 곳에서 렌더링한다. */
export default function GeneralName({ name, npcType, className, style }: GeneralNameProps) {
  const color = style?.color ?? getNPCColor(npcType);

  return (
    <span
      className={className}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: npcType === 7 ? 3 : undefined,
        verticalAlign: 'middle',
        ...style,
        color: color ?? undefined,
      }}
    >
      {npcType === 7 && (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src="/status/imperial-npc.png"
          srcSet="/status/imperial-npc.png 1x, /status/2x/imperial-npc.png 2x"
          width={16}
          height={16}
          alt="황제"
          title="황제 특별 NPC"
          draggable={false}
          style={{ imageRendering: 'pixelated', flex: '0 0 auto' }}
        />
      )}
      <span>{name}</span>
    </span>
  );
}
