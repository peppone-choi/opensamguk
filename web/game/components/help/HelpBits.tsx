'use client';

// 도움말 부품이 같이 쓰는 작은 조각 — 칩 · 상태 · 자원 줄 · 행.
import type { ReactNode } from 'react';
import type { CostSchema, ReviewState } from '@/lib/help';
import { RESOURCES, costValue } from '@/lib/help-labels';
import s from './Help.module.css';

export function Chip({ tone, children }: { tone?: 'info' | 'rust' | 'moss' | 'bronze'; children: ReactNode }) {
    return <span className={[s.chip, tone ? s[tone] : ''].filter(Boolean).join(' ')}>{children}</span>;
}

export function DraftChip({ state }: { state: ReviewState | undefined }) {
    return state === 'DRAFT' ? <Chip tone="info">초안</Chip> : null;
}

export function PlannedChip({ deliveryState }: { deliveryState: string | undefined }) {
    return deliveryState === 'PLANNED' ? <Chip tone="rust">준비 중</Chip> : null;
}

/** 로딩 — 높이를 고정한 막대(흔들림 없음). */
export function Skeleton({ rows = 3 }: { rows?: number }) {
    return (
        <div aria-busy="true" aria-label="불러오는 중">
            {Array.from({ length: rows }, (_, i) => <span key={i} className={s.skel} style={{ width: `${80 - (i % 3) * 18}%` }} />)}
        </div>
    );
}

export function StateBlock({ title, body, action }: { title: string; body?: string; action?: ReactNode }) {
    return (
        <div className={s.state} role="status">
            <strong>{title}</strong>
            {body ? <p>{body}</p> : null}
            {action}
        </div>
    );
}

const RES_COLOR: Record<string, string> = { money: '#e6c35c', grain: '#e2dcc3', iron: '#8fa0ad', timber: '#a5744a', horses: '#9c7bb0' };

/** 비용 다섯 칸 — 0 = 들지 않음, null = 상황에 따라(무료가 아니다). 색만으로 가르지 않고 글자와 함께. */
export function CostLine({ cost }: { cost: CostSchema }) {
    const values = RESOURCES.map((r) => ({ ...r, value: costValue(cost, r.key) }));
    if (values.every((v) => v.value === '상황에 따라')) {
        return (
            <span>
                {values.map((v) => <span key={v.key} className={s.res}><i style={{ background: RES_COLOR[v.key] }} />{v.label}</span>)}
                <span className={s.note}>— 상황에 따라</span>
            </span>
        );
    }
    return (
        <span>
            {values.map((v) => <span key={v.key} className={s.res}><i style={{ background: RES_COLOR[v.key] }} />{v.label} {v.value}</span>)}
        </span>
    );
}

export function Icon({ name }: { name: 'back' | 'close' | 'search' | 'next' | 'help' | 'clock' | 'check' }) {
    const d: Record<string, ReactNode> = {
        back: <path d="M15 5l-7 7 7 7" />,
        close: <path d="M6 6l12 12M18 6L6 18" />,
        search: <><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4.5 4.5" /></>,
        next: <path d="M10 6l6 6-6 6" />,
        help: <><circle cx="12" cy="12" r="9" /><path d="M9.5 9.2a2.6 2.6 0 1 1 3.6 2.4c-.7.3-1.1.9-1.1 1.6v.6M12 17v.6" /></>,
        clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
        check: <path d="M5 12.5l4.5 4.5L19 7.5" />,
    };
    return (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="square" aria-hidden="true">
            {d[name]}
        </svg>
    );
}
