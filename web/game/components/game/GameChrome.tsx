'use client';

// GameChrome — the F2 chrome spine. Composes GameInfo (header) + GlobalMenu (server-driven menu) +
// MainControlBar (20-button bar), and gates CharacterClaim when hasGeneral === false. NOT the full
// main-screen assembly (MapViewer + reserved-command + info cards are W4/W5) — it exposes a placeholder
// slot (`children`) where those + sub-pages render. Loads everything through useFrontInfo (front-info +
// const + global-menu), soft-refreshes on SSE turnCompleted, and refetches after a successful claim.
//
// Gating derivation (spec §2 buckets ← front-info general): myLevel = officerLevel, permission =
// permission, showSecret = showSecret, nationLevel = nation.level (0 when factionless). Highlight flags
// come off `global` (isTournamentApplicationOpen / isBettingActive), defaulting to falsy when absent.

import { useMemo } from 'react';
import { useFrontInfo } from '@/hooks/useFrontInfo';
import GameInfo from './GameInfo';
import GlobalMenu from './GlobalMenu';
import MainControlBar, { type ControlGating } from './MainControlBar';
import MainControlDropdown from './MainControlDropdown';
import CharacterClaim from './CharacterClaim';
import type { MenuFlagSource } from '@/lib/menu-types';

export default function GameChrome({ children }: { children?: React.ReactNode }) {
    const { frontInfo, constData, menu, loading, error, refresh } = useFrontInfo();

    const gating: ControlGating | null = useMemo(() => {
        if (!frontInfo) return null;
        const g = frontInfo.general;
        const global = frontInfo.global;
        return {
            showSecret: g.showSecret,
            permission: g.permission,
            myLevel: g.officerLevel,
            nationLevel: frontInfo.nation?.level ?? 0,
            isTournamentApplicationOpen: Boolean(global.isTournamentApplicationOpen),
            isBettingActive: Boolean(global.isBettingActive),
        };
    }, [frontInfo]);

    // asyncReady gate (spec §1.1): suppress everything until the first front-info + const resolve.
    if (loading) {
        return (
            <div className="center-screen">
                <div className="spinner" />
                <p className="text-muted" style={{ marginTop: '1rem' }}>
                    서버 갱신 중입니다.
                </p>
            </div>
        );
    }

    if (error || !frontInfo) {
        return (
            <div className="error-state">
                <p>{error ?? '서버 정보를 불러올 수 없습니다.'}</p>
                <button onClick={refresh}>다시 시도</button>
            </div>
        );
    }

    // hasGeneral === false → 장수 선택/빙의 화면 (spec §6). On claim, refetch front-info → enter game.
    if (!frontInfo.general.hasGeneral) {
        return <CharacterClaim onClaimed={refresh} />;
    }

    const flagSource = frontInfo.global as unknown as MenuFlagSource;

    return (
        <div className="game-chrome">
            {/* commonToolbar → GlobalMenu (top) */}
            <div className="common-toolbar">
                <GlobalMenu menu={menu} global={flagSource} />
            </div>

            {/* GameInfo status header */}
            <GameInfo global={frontInfo.global} constData={constData} />

            {/* ingameBoard placeholder slot — MapViewer + reserved-command + info cards land in W4/W5 */}
            <div className="ingame-board-slot">{children}</div>

            {/* MainControlBar (국가 메뉴) */}
            {gating && <MainControlBar gating={gating} />}

            {/* commonToolbar → GlobalMenu (repeated bottom) */}
            <div className="common-toolbar">
                <GlobalMenu menu={menu} global={flagSource} />
            </div>

            {/* Mobile 국가 메뉴 dropdown (secret items per permission/officerLevel) */}
            {gating && (frontInfo.general.permission >= 1 || frontInfo.general.officerLevel >= 2) && (
                <details className="mobile-control-dropdown">
                    <summary>국가 메뉴</summary>
                    <MainControlDropdown gating={gating} />
                </details>
            )}
        </div>
    );
}
