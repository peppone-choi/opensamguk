'use client';

// GameInfo — status header (spec §3), port of GameInfo.vue.
// Title row + a 13-cell info grid rendered in the spec's parity ORDER with verbatim templates.
// Values come from front-info `global` (+ const for the title). Fields game-api does not yet emit
// fall back gracefully (no fabricated numbers); the render ORDER and label templates are the contract.

import type { FrontGlobalInfo, GameConstResponse } from '@/lib/types';
import { calcTournamentTerm } from '@/lib/utilGame';

const NPC_MODE_TEXT = ['불가능', '가능', '선택 생성'];

function num(n: number | undefined): string {
    return (n ?? 0).toLocaleString('ko-KR');
}

function autorunInfoText(autorunUser: FrontGlobalInfo['autorunUser']): string {
    return autorunUser && autorunUser.limit_minutes > 0 ? '자율행동' : '';
}

export default function GameInfo({
    global,
    constData,
}: {
    global: FrontGlobalInfo;
    constData: GameConstResponse | null;
}) {
    const title = global.title ?? constData?.mapName ?? '삼국지';
    const serverName = global.serverName ?? '';
    const serverCnt = global.serverCnt ?? 1;
    const locked = global.serverLocked === true;

    return (
        <header className="game-info">
            <h3 className="scenario-name">
                {title} {serverName}
                {serverCnt}기 <span className="avoid-wrap text-cyan">{global.scenarioText}</span>
            </h3>

            <div className="game-info-grid">
                {/* 1 subScenarioName */}
                <div className="gi-cell gi-wide text-cyan">{global.scenarioText}</div>

                {/* 2 subNPCType */}
                <div className="gi-cell text-cyan">
                    NPC {num(global.npcCount)}명, 상성: {global.extendedGeneral ? '확장' : '표준'}{' '}
                    {global.isFiction ? '가상' : '사실'}
                </div>

                {/* 3 subNPCMode */}
                <div className="gi-cell text-cyan">NPC선택: {NPC_MODE_TEXT[global.npcMode ?? 0]}</div>

                {/* 4 subTournamentMode */}
                <div className="gi-cell text-cyan">토너먼트: 경기당 {calcTournamentTerm(global.turnterm)}분</div>

                {/* 5 subOtherSetting */}
                <div className="gi-cell text-cyan">기타 설정: {autorunInfoText(global.autorunUser)}</div>

                {/* 6 subYearMonth */}
                <div className="gi-cell gi-wide">
                    현재: {global.year}年 {global.month}月 ({global.turnterm}분 턴 서버)
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
                        <a href="/game/tournament" target="_blank" rel="noopener noreferrer">
                            ↑
                            <span className="text-cyan">
                                {global.tournamentType ?? ''} <span className="text-orange">{global.tournamentState ?? ''}</span>
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
                        <a href="/game/auction" target="_blank" rel="noopener noreferrer" className="text-cyan">
                            {num(global.auctionCount)}건 거래 진행중
                        </a>
                    ) : (
                        <span className="text-magenta">진행중인 거래 없음</span>
                    )}
                </div>

                {/* 13 subVoteState */}
                <div className="gi-cell gi-wide">
                    {global.lastVote ? (
                        <a href="/game/vote" target="_blank" rel="noopener noreferrer">
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
