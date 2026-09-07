import type { HTMLAttributes, ReactNode } from 'react';

export type SlotState = 'now' | 'planned' | 'rest';

export type SlotProps = Omit<HTMLAttributes<HTMLDivElement>, 'children'> & {
  /** 순 번호 등 좌측 mono 라벨. */
  readonly n: ReactNode;
  readonly cmd: ReactNode;
  readonly tgt?: ReactNode;
  readonly state?: SlotState;
  readonly trailing?: ReactNode;
};

/** 명령 목록 한 줄 = 한 순. 한 순 아래 여러 슬롯을 묶는 표현은 없다(사용자 정정 2026-09-06). */
export function Slot({ n, cmd, tgt, state = 'planned', trailing, className = '', ...props }: SlotProps) {
  const classes = ['os-slot', state === 'now' ? 'os-slot--now' : state === 'rest' ? 'os-slot--rest' : '', className].filter(Boolean).join(' ');
  return (
    <div className={classes} data-state={state} {...props}>
      <span className="os-slot__n">{n}</span>
      <span>
        <div className="os-slot__cmd">{cmd}</div>
        {tgt != null && <div className="os-slot__tgt">{tgt}</div>}
      </span>
      <span className="os-slot__trailing">{trailing}</span>
    </div>
  );
}
