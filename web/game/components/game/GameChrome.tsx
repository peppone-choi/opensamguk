'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useFrontInfo } from '@/hooks/useFrontInfo';
import { useToast } from '@/hooks/useToast';
import GameInfo from './GameInfo';
import GlobalMenu from './GlobalMenu';
import MainControlBar, { type ControlGating } from './MainControlBar';
import MainControlDropdown from './MainControlDropdown';
import MainStatusPanel from './MainStatusPanel';
import MapViewer from './MapViewer';
import PartialReservedCommand from './PartialReservedCommand';
import GeneralBasicCard from './GeneralBasicCard';
import NationBasicCard from './NationBasicCard';
import CityBasicCard from './CityBasicCard';
import CharacterClaim from './CharacterClaim';
import MessagePanel from './MessagePanel';
import Toast from '../Toast';
import { resolveServerGamePath } from '@/lib/serverGameUrl';
import type { MenuFlagSource } from '@/lib/menu-types';
import type { FrontInfoResponse } from '@/lib/types';

type GameChromeChildren = React.ReactNode | ((frontInfo: FrontInfoResponse) => React.ReactNode);
type GameChromeEntryMode = 'possession';

const gatewayPublicUrl = process.env.NEXT_PUBLIC_GATEWAY_URL ?? process.env.NEXT_PUBLIC_GATEWAY_ORIGIN;
const lobbyHref = gatewayPublicUrl ? `${gatewayPublicUrl.replace(/\/$/, '')}/lobby` : '/lobby';

export default function GameChrome({
    children,
    entryMode,
}: {
    children?: GameChromeChildren;
    entryMode?: GameChromeEntryMode;
}) {
    const router = useRouter();
    const { frontInfo, constData, menu, loading, error, refresh, refreshKey } = useFrontInfo();
    const { toasts, show, remove } = useToast();
    const [possessionClaimed, setPossessionClaimed] = useState(false);

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
    const hasGeneral = frontInfo?.general.hasGeneral ?? null;
    const joinHref = useMemo(() => {
        const serverId = frontInfo?.global.serverId;
        return serverId ? resolveServerGamePath(undefined, serverId, '/game', 'join') : '/game/join';
    }, [frontInfo?.global.serverId]);
    const gameHref = useMemo(() => {
        const serverId = frontInfo?.global.serverId;
        return serverId ? resolveServerGamePath(undefined, serverId, '/game') : '/game';
    }, [frontInfo?.global.serverId]);
    const onPossessionClaimed = useCallback(() => {
        setPossessionClaimed(true);
        refresh();
    }, [refresh]);

    useEffect(() => {
        if (!loading && possessionClaimed && hasGeneral === true) {
            router.replace(gameHref);
        }
    }, [gameHref, hasGeneral, loading, possessionClaimed, router]);

    useEffect(() => {
        if (!loading && hasGeneral === false && entryMode !== 'possession') {
            router.replace(joinHref);
        }
    }, [entryMode, hasGeneral, joinHref, loading, router]);

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
        if (entryMode === 'possession') {
            return <CharacterClaim global={frontInfo.global} onClaimed={onPossessionClaimed} />;
        }

        return (
            <div className="center-screen">
                <div className="spinner" />
                <p className="text-muted" style={{ marginTop: '1rem' }}>
                    장수 생성 화면으로 이동 중입니다.
                </p>
            </div>
        );
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
            <div className="main-refresh-row">
                <button type="button" className="main-refresh-btn" onClick={refresh}>
                    갱신
                </button>
                <a className="main-refresh-btn" href={lobbyHref}>
                    로비로
                </a>
            </div>
            <MainStatusPanel frontInfo={frontInfo} />

            <div className="ingame-board">
                <div className="ib-map">
                    <MapViewer
                        live
                        showMe={1}
                        refreshKey={refreshKey}
                        currentCityId={city?.id ?? null}
                        gameConst={constData?.gameConst}
                    />
                </div>

                <div className="ib-reserved">
                    {generalId != null && (
                        <PartialReservedCommand
                            generalId={generalId}
                            nationId={general.nationId}
                            maxTurn={constData?.maxTurn}
                            refreshKey={refreshKey}
                            onReserved={refresh}
                            onToast={show}
                        />
                    )}
                </div>

                <section className="ib-subject-panel" aria-label="현재 조작 대상">
                    <div className="subject-target-bar">
                        <div className="subject-target-title">조작 대상</div>
                        <div className="subject-target-current">
                            <span>본인</span>
                            <span>{general.name ?? '장수'}</span>
                            <span>{nation?.name ?? '재야'}</span>
                            <span>{city?.name ?? '소재 없음'}</span>
                        </div>
                    </div>
                    <CityBasicCard city={city} />
                    <div className="subject-secondary-grid">
                        <NationBasicCard nation={nation} />
                        <GeneralBasicCard general={general} nation={nation} />
                    </div>
                </section>

                {gating && (
                    <div className="ib-controlbar">
                        <MainControlBar gating={gating} />
                    </div>
                )}
            </div>

            <div className="main-page-content">{typeof children === 'function' ? children(frontInfo) : children}</div>

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
