'use client';

import { Chip, type CommanderyVisibility, type CommanderyCell } from '@opensamguk/ui';

const VISIBILITY_LABEL: Record<CommanderyVisibility, string> = {
    FULL: '완전 시야', INTEL: '첩보 시야', FOG: '안개',
};
const VISIBILITY_TONE: Record<CommanderyVisibility, 'moss' | 'info' | 'rust'> = {
    FULL: 'moss', INTEL: 'info', FOG: 'rust',
};

export interface CommanderyNavigatorProps {
    focus: CommanderyCell;
    home?: CommanderyCell;
    visibility: ReadonlyMap<number, CommanderyVisibility> | null;
    intelAge?: ReadonlyMap<number, number>;
    scoutable?: ReadonlySet<number>;
    onScout?: (no: number) => void;
    scoutPending?: boolean;
    overlayInfo?: boolean;
}

/** 초점 郡의 시야 · 첩보 줄(郡 이동은 지도의 자유 끌기 · 「내 위치로」가 맡는다). */
export function CommanderyNavigator({ focus, home, visibility,
    intelAge, scoutable, onScout, scoutPending, overlayInfo = false }: CommanderyNavigatorProps) {
    const tier = visibility ? visibility.get(focus.no) ?? 'FOG' : null;
    return <>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingTop: overlayInfo ? 4 : 10,
            flexWrap: 'wrap', ...(overlayInfo ? { position: 'absolute' as const, zIndex: 2,
                // 지도판 확대 · 축소 칸(왼쪽 아래 44px + 틈 8)을 비켜 선다 — 겹치면 축소 단추가 이 줄 밑에 깔린다.
                // 화면 틀이 다른 겹층(작전실 엿보기 시트 · 서랍 · 작은 지도)을 비키게 할 때는 --commandery-info-* 로 자리를 준다(K4 P-W01).
                bottom: 'var(--commandery-info-bottom, 8px)',
                left: 'var(--commandery-info-left, calc(var(--battlefield-left-clearance, 8px) + 52px))',
                right: 'var(--commandery-info-right, var(--battlefield-control-right, 8px))',
                // 겹층 띠는 글만 보인다 — 누르기 · 끌기 · 핀치는 지나가 지도 캔버스에 닿고(지도 표지 DOM 과 같은 규칙), 「첩보 보내기」 단추만 받는다.
                // 띠가 받으면 모바일 핀치 손가락이 그 위에 떨어져 지도가 확대되지 않았다(#1232 CI).
                pointerEvents: 'none' as const,
                padding: 6, background: 'rgba(12,15,14,0.82)' } : {}) }}>
            <span data-testid="commandery-focus" style={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{focus.name}</span>
            {home && focus.no === home.no ? <Chip tone="info">지금 여기</Chip> : null}
            {tier ? <Chip tone={VISIBILITY_TONE[tier]}>{VISIBILITY_LABEL[tier]}</Chip> : null}
            {tier === 'INTEL' && intelAge?.get(focus.no) != null ?
                <span style={{ fontSize: 12, color: 'var(--muted)' }}>{`${intelAge.get(focus.no)}순 전 정보`}</span> : null}
            {tier && tier !== 'FULL' && onScout && (!scoutable || scoutable.has(focus.no)) ?
                <button type="button" className="os-button os-button--primary os-button--sm" style={overlayInfo ? { pointerEvents: 'auto' } : undefined}
                    onClick={() => onScout(focus.no)} disabled={scoutPending}>첩보 보내기</button> : null}
        </div>
    </>;
}
