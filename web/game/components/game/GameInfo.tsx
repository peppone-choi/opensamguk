'use client';

// GameInfo — status header (spec §3), port of GameInfo.vue.
// Title row + a 13-cell info grid rendered in the spec's parity ORDER with verbatim templates.
// Values come from front-info `global` (+ const for the title). Fields game-api does not yet emit
// fall back gracefully (no fabricated numbers); the render ORDER and label templates are the contract.

import { resolveServerGamePath } from '@/lib/serverGameUrl';
import type { FrontGlobalInfo, GameConstResponse } from '@/lib/types';

function num(n: number | undefined): string {
    return (n ?? 0).toLocaleString('ko-KR');
}

function gameHref(global: FrontGlobalInfo, childPath: string): string {
    const serverId = global.serverId;
    if (!serverId) return `/game/${childPath}`;
    return resolveServerGamePath(undefined, serverId, '/game', childPath);
}

export default function GameInfo({
    global,
    constData,
}: {
    global: FrontGlobalInfo;
    constData: GameConstResponse | null;
}) {
    const serverName = global.serverName ?? '';
    const title = global.title ?? (serverName ? '' : constData?.mapName ?? '삼국지');
    const generation = global.generation ?? global.serverCnt;
    const generationText = generation == null ? '' : `${generation}기`;
    const titleText = [title, serverName, generationText].filter(Boolean).join(' ');
    const locked = global.serverLocked === true;
    const currentText = `${global.year}年 ${global.month}月${global.turnPhaseText ? ` ${global.turnPhaseText}` : ''}`;

    return (
        <header className="game-info" aria-label="서버 정보">
            {/* 서버명·기수·시나리오는 쉘 상태바가 보여 준다 — 여기서는 보조 기술(스크린리더)로만 남긴다. */}
            <h3 className="scenario-name sr-only">
                {titleText} <span className="avoid-wrap text-cyan">{global.scenarioText}</span>
            </h3>

            <div className="game-info-grid game-info-strip">
                {/* 1 subScenarioName */}
                <div className="gi-cell gi-wide text-cyan">{global.scenarioText}</div>

                {/* 2 subNPCType */}
                <div className="gi-cell text-cyan">{global.npcSummaryText ?? `NPC ${num(global.npcCount)}명`}</div>

                {/* 3 subNPCMode */}
                <div className="gi-cell text-cyan">NPC선택: {global.npcModeText ?? '-'}</div>

                {/* 4 subTournamentMode */}
                <div className="gi-cell text-cyan">토너먼트: 경기당 {num(global.tournamentTermMinutes)}분</div>

                {/* 5 subOtherSetting */}
                <div className="gi-cell text-cyan">기타 설정: {global.otherSettingText ?? ''}</div>

                {/* 6 subYearMonth */}
                <div className="gi-cell gi-wide">
                    현재: {currentText} ({global.turnterm}분 턴 서버)
                </div>

                {/* 7 subOnlineUserCnt */}
                <div className="gi-cell">전체 접속자 수: {num(global.onlineUserCnt)}명</div>

                {/* 8 subAPILimit */}
                <div className="gi-cell">턴당 갱신횟수: {num(global.apiLimit)}회</div>

                {/* 9 subGeneralCnt */}
                <div className="gi-cell gi-wide">
                    등록 장수: 유저 {num(global.createdUserCnt ?? global.generalCount)} /{' '}
                    {num(global.generalCntLimit)} + <span className="text-cyan">NPC {num(global.createdNPCCnt ?? global.npcCount)} 명</span>
                </div>

                {/* 10 subTournamentState */}
                <div className="gi-cell">
                    {global.isTournamentActive ? (
                        <a href={gameHref(global, 'tournament')} target="_blank" rel="noopener noreferrer">
                            ↑
                            <span className="text-cyan">
                                {global.tournamentType ?? ''}{' '}
                                <span className="text-orange">{global.tournamentState ?? ''}</span>
                            </span>
                            ↑
                        </a>
                    ) : (
                        <span className="text-magenta">현재 토너먼트 경기 없음</span>
                    )}
                </div>

                {/* 11 subLastExecuted */}
                <div className={`gi-cell ${locked ? 'text-magenta' : 'text-cyan'}`}>
                    동작 시각: {global.lastExecuted ?? '-'}
                </div>

                {/* 12 subAuctionState */}
                <div className="gi-cell">
                    {global.auctionCount ? (
                        <a
                            href={gameHref(global, 'auction')}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-cyan"
                        >
                            {num(global.auctionCount)}건 거래 진행중
                        </a>
                    ) : (
                        <span className="text-magenta">진행중인 거래 없음</span>
                    )}
                </div>

                {/* 13 subVoteState */}
                <div className="gi-cell gi-wide">
                    {global.lastVote ? (
                        <a href={gameHref(global, 'vote')} target="_blank" rel="noopener noreferrer">
                            <span className="text-cyan">설문 진행 중: </span>
                            <span>{global.lastVote.title}</span>
                        </a>
                    ) : (
                        <span className="text-magenta">진행중인 설문 없음</span>
                    )}
                </div>
            </div>
        </header>
    );
}
