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
import { useToast } from '@/hooks/useToast';
import GameInfo from './GameInfo';
import GlobalMenu from './GlobalMenu';
import MainControlBar, { type ControlGating } from './MainControlBar';
import MainControlDropdown from './MainControlDropdown';
import CharacterClaim from './CharacterClaim';
import MapViewer from './MapViewer';
import PartialReservedCommand from './PartialReservedCommand';
import GeneralBasicCard from './GeneralBasicCard';
import NationBasicCard from './NationBasicCard';
import CityBasicCard from './CityBasicCard';
import MessagePanel from './MessagePanel';
import Toast from '../Toast';
import type { MenuFlagSource } from '@/lib/menu-types';

export default function GameChrome({ children }: { children?: React.ReactNode }) {
    const { frontInfo, constData, menu, loading, error, refresh, refreshKey } = useFrontInfo();
    const { toasts, show, remove } = useToast();

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

    if (!frontInfo.general.hasGeneral) {
        return <CharacterClaim global={frontInfo.global} onClaimed={refresh} />;
    }

    const flagSource = frontInfo.global as unknown as MenuFlagSource;
    const general = frontInfo.general;
    const nation = frontInfo.nation;
    const city = frontInfo.city;
    // The player's OWN general id (front-info) — threaded into the command modal + reserved panel + messages.
    const generalId = general.generalId;

    return (
        <div className="game-chrome">
            {/* commonToolbar → GlobalMenu (top) */}
            <div className="common-toolbar">
                <GlobalMenu menu={menu} global={flagSource} />
            </div>

            {/* GameInfo status header */}
            <GameInfo global={frontInfo.global} constData={constData} />

            {/* #ingameBoard — 레거시 1000px 보드 그리드.
                영역 순서: mapView → reservedCommandZone → city/nation/general → MainControlBar → content.
                맵은 로비와 동일한 정적 마커 맵 — 도시 클릭 시 도시 정보 페이지로 이동.
                NOTE(de-dup): general/nation/city 카드는 여기서만 렌더한다. 메인 라우트(/game page.tsx)는
                children 을 비워(중복 '내 정보' 카드 제거) 이 카드들이 단 한 번만 보이게 한다. */}
            <div className="ingame-board">
                <div className="ib-map">
                    <MapViewer />
                </div>

                <div className="ib-reserved">
                    {generalId != null && (
                        <PartialReservedCommand
                            generalId={generalId}
                            nationId={general.nationId}
                            maxTurn={constData?.maxTurn}
                            onReserved={refresh}
                            onToast={show}
                        />
                    )}
                </div>

                {/* info 카드 묶음 — wrapper는 display: contents, 카드들이 #ingameBoard grid에 직접 배치된다. */}
                <div className="ib-cards">
                    <CityBasicCard city={city} />
                    <NationBasicCard nation={nation} />
                    <GeneralBasicCard general={general} nation={nation} />
                </div>

                {gating && (
                    <div className="ib-controlbar">
                        <MainControlBar gating={gating} />
                    </div>
                )}

                {/* generalInfo content slot (서브페이지 content 가 여기 렌더; 메인 라우트는 비어 있음 → :empty 숨김). */}
                <div className="ib-content">{children}</div>
            </div>

            {/* MessagePanel (#msgPanel) */}
            {generalId != null && (
                <MessagePanel
                    generalId={generalId}
                    nationId={general.nationId}
                    refreshKey={refreshKey}
                    onToast={show}
                />
            )}

            {/* commonToolbar → GlobalMenu (repeated bottom) */}
            <div className="common-toolbar">
                <GlobalMenu menu={menu} global={flagSource} />
            </div>

            {/* Mobile 국가 메뉴 dropdown (secret items per permission/officerLevel) */}
            {gating && (general.permission >= 1 || general.officerLevel >= 2) && (
                <details className="mobile-control-dropdown">
                    <summary>국가 메뉴</summary>
                    <MainControlDropdown gating={gating} />
                </details>
            )}

            <Toast toasts={toasts} onRemove={remove} />
        </div>
    );
}
