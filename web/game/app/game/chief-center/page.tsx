'use client';

// 사령부 (chief center) — page 7 of the F4 action-page wave.
// READ + MUTATION (W4 FE): api.chiefReserved() → ChiefReservedResponse. 8개 직책 칸(officer level
// 12/11/10/9/8/7/6/5)을 카드 그리드로 렌더하고, 각 칸은 점유 장수 + 직책별 예약 국가 명령(slot별 brief)을
// 보여준다. 내 직책 칸(officerLevel === myOfficerLevel)에서는 슬롯을 골라 commandList(사령부 명령 팔레트)
// 에서 명령을 선택해 예약할 수 있다 → CommandModal을 그 명령 value + 그 turnIdx에 pin해 띄운다.
// POST /api/command/nation/bulk?generalId= (nation_turn 링 — P0-09). 팔레트는 백엔드 commandList가
// 내려보내는 명령(현재 F4StateText.CHIEF_COMMAND_TABLE의 6개 카테고리: 휴식/인사/외교/특수/전략/기타)만
// 렌더하며, 렌더된 명령은 모두 이 경로를 탄다. event_*연구 9종은 레지스트리 등록·ring 배선돼 있으나
// 이 테이블에는 아직 미포함 — 백엔드가 테이블에 포함시키면 함께 노출된다.
//
// Parity notes (legacy hwe/ts/PageChiefCenter.vue + ChiefCenter/TopItem.vue + ChiefReservedCommand.vue):
//  - Display order of the 8 posts is the legacy [12, 10, 8, 6, 11, 9, 7, 5] (two columns of 4).
//  - officerLevelText / name / turnTime come from the server; 예약 brief의 che_발령 후처리
//    (postFilterNationCommand)는 legacy와 동일하게 CLIENT에서 적용한다(서버는 generic brief 저장).
//  - Vacant occupant name renders as '-' (legacy `officer?.name ?? "-"`).
//  - Occupant name color follows the NPC tier (legacy getNPCColor).
//  - turnTime is shown as its last 5 chars (HH:mm), matching BottomItem's `.slice(-5)`.
//  - 예약 슬롯 brief는 색/태그 마크업 verbatim, 빈 슬롯은 '휴식'.
//  - myOfficerLevel >= 5 gate: 수뇌부가 아니면 INFO notice, 칸은 read-only(예약 편집 불가).
// EMPTY-SAFE: missing posts / empty reservedTurns render empty cells, never crash.

import { useEffect, useState, useCallback } from 'react';
import { LogText, Panel, SectionHeader, Slot } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import GeneralName from '../../../components/game/GeneralName';
import CommandModal from '../../../components/CommandModal';
import ChiefCommandReserve, { type ChiefReserveLaunch } from '../../../components/game/ChiefCommandReserve';
import { api } from '../../../lib/api';
import { useFrontInfo } from '../../../hooks/useFrontInfo';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type { ChiefReservedResponse, ChiefPost, ChiefCommandCategory } from '../../../types/game';
import { postFilterNationCommandGen, type TurnObj } from '../../../lib/utilGame/postFilterNationCommandGen';
import type { GameCityConstItem } from '../../../lib/types';

// Legacy [12, 10, 8, 6, 11, 9, 7, 5] — preserved verbatim for parity of the post grid order.
const CHIEF_LEVEL_ORDER = [12, 10, 8, 6, 11, 9, 7, 5];

// BottomItem.vue: `(officer?.turnTime ?? "  -  ").slice(-5)` — last 5 chars (HH:mm).
function shortTurnTime(turnTime: string | null): string {
    return (turnTime ?? '  -  ').slice(-5);
}

function ChiefPostCard({
    post,
    maxChiefTurn,
    isMe,
    commandList,
    onLaunch,
    generalId,
    postFilter,
}: {
    post: ChiefPost | undefined;
    maxChiefTurn: number;
    isMe: boolean;
    commandList: ChiefCommandCategory[];
    onLaunch: (spec: ChiefReserveLaunch) => void;
    generalId?: number | null;
    postFilter: (turnObj: TurnObj) => TurnObj;
}) {
    const name = post ? (post.name ?? '-') : '-';
    // legacy PageChiefCenter.vue: officer.turn.map(postFilterNationCommand) — che_발령 예약 brief를
    // 《부대명》【도시명】로 발령으로 후처리(dest가 부대장일 때만). 양 브랜치(편집/read-only) 공유.
    const turns = (post?.reservedTurns ?? []).map((t) => {
        const out = postFilter({ action: t.actionCode, brief: t.brief, arg: (t.arg ?? {}) as TurnObj['arg'] });
        return { ...t, brief: out.brief };
    });

    return (
        <Panel className="record-panel chief-post" frame={isMe ? 'bronze' : 'none'} aria-label={`${post?.officerLevelText ?? '직책'} ${name}`}>
            <SectionHeader
                title={<GeneralName name={name} npcType={post?.npcType ?? 0} style={{ textDecoration: isMe ? 'underline' : undefined }} />}
                sub={post?.officerLevelText ?? ''}
                actions={post?.turnTime != null ? <span className="os-num chief-post__time">{shortTurnTime(post.turnTime)}</span> : undefined}
            />
            {/* 내 직책 칸이면 슬롯-편집(ChiefCommandReserve), 아니면 read-only 순 목록(한 순 = 한 슬롯). */}
            {isMe ? (
                <ChiefCommandReserve
                    maxChiefTurn={maxChiefTurn}
                    reservedTurns={turns}
                    commandList={commandList}
                    onLaunch={onLaunch}
                    generalId={generalId}
                />
            ) : (
                <div className="chief-post__slots">
                    {Array.from({ length: maxChiefTurn }, (_, idx) => {
                        const turn = turns.find((t) => t.turnIdx === idx);
                        // 빈 슬롯은 legacy ChiefReservedCommand 와 같이 '휴식'(색/태그 토큰은 LogText 로).
                        return (
                            <Slot
                                key={idx}
                                n={idx + 1}
                                state={turn?.brief ? 'planned' : 'rest'}
                                cmd={turn?.brief ? <LogText text={turn.brief} /> : '휴식'}
                            />
                        );
                    })}
                </div>
            )}
        </Panel>
    );
}

export default function ChiefCenterPage() {
    const { frontInfo, refresh } = useFrontInfo();
    const generalId = frontInfo?.general.generalId ?? null;
    const nationId = frontInfo?.general.nationId;
    const [data, setData] = useState<ChiefReservedResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');
    // 슬롯+명령이 선택돼 열린 CommandModal spec(null = 닫힘).
    const [launch, setLaunch] = useState<ChiefReserveLaunch | null>(null);
    // che_발령 brief 후처리에 필요한 도시상수(불변 → 1회 로드). legacy gameConstStore.cityConst 등가.
    const [cityConst, setCityConst] = useState<GameCityConstItem[]>([]);

    function showToast(msg: string) {
        setToast(msg);
        setTimeout(() => setToast(''), 3000);
    }

    // background=true: 전체 로딩 문구 없이 직책 칸만 갱신(OPENSAM-196). 열려 있는 CommandModal(launch)은
    // 별도 상태라 이 갱신으로 닫히거나 초기화되지 않는다.
    const fetchData = useCallback(async (background = false) => {
        if (!background) setLoading(true);
        try {
            const res = await api.chiefReserved();
            setData(res);
            setError('');
        } catch {
            if (!background) setError('사령부 정보를 불러올 수 없습니다.');
        } finally {
            if (!background) setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    // 도시상수 1회 로드(불변). 미로드/실패시 postFilter는 brief 원본을 그대로 둔다(graceful).
    useEffect(() => {
        let on = true;
        api.gameConst()
            .then((c) => { if (on) setCityConst(c.cityConst ?? []); })
            .catch(() => { /* graceful: cityConst 미로드 → che_발령 brief 원본 유지 */ });
        return () => { on = false; };
    }, []);

    // 턴 완료 시 직책/예약 갱신. Shell의 단일 SSE 구독으로 대체(OPENSAM-196).
    useTurnRefresh(() => void fetchData(true));

    const maxChiefTurn = data?.maxChiefTurn ?? 0;
    const posts = data?.posts ?? [];
    const commandList = data?.commandList ?? [];
    // officerLevel로 색인(직책 칸 조회). posts는 배열이므로 레벨→post 맵으로 변환.
    const postByLevel = new Map(posts.map((p) => [p.officerLevel, p]));
    // myOfficerLevel >= 5 gate: only 수뇌부 (chief posts lv 5+) may view/edit the 사령부.
    const myOfficerLevel = data?.myOfficerLevel ?? 0;
    const isAllowed = myOfficerLevel >= 5;
    // troopList(troopLeaderId→부대명)를 number 키 맵으로 변환 → legacy postFilterNationCommandGen 생성.
    // arg.destGeneralID가 부대장이면 brief를 《부대명》【도시명】로 발령으로 후처리(아니면 원본 유지).
    const troopMap: Record<number, string> = {};
    for (const [k, v] of Object.entries(data?.troopList ?? {})) troopMap[Number(k)] = v;
    const postFilter = postFilterNationCommandGen<TurnObj>(troopMap, cityConst);

    return (
        <Shell>
            <PageHead
                title="사령부"
                chip={data ? `${data.year}年 ${data.month}月` : undefined}
                actions={<button type="button" className="os-button os-button--sm os-button--ghost" onClick={() => void fetchData()}>새로고침</button>}
            />

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            {data && !isAllowed && (
                <Panel className="record-panel">
                    <p className="record-empty">권한이 부족합니다. 수뇌부가 아닙니다.</p>
                </Panel>
            )}

            {data && isAllowed && (
                <div className="chief-grid">
                    {CHIEF_LEVEL_ORDER.map((level) => (
                        <ChiefPostCard
                            key={level}
                            post={postByLevel.get(level)}
                            maxChiefTurn={maxChiefTurn}
                            isMe={level === myOfficerLevel}
                            commandList={commandList}
                            generalId={generalId}
                            postFilter={postFilter}
                            onLaunch={(spec) => {
                                if (generalId == null) {
                                    showToast('장수가 없어 명령을 예약할 수 없습니다.');
                                    return;
                                }
                                setLaunch(spec);
                            }}
                        />
                    ))}
                </div>
            )}

            {/* 슬롯+명령 선택 → CommandModal을 그 명령 value + turnIdx에 pin해 띄운다.
                argType은 game-api가 argsSchema에서 파생한 값(city/nation/general/amount|null) →
                인자 폼이 필요하면 모달이 해당 picker를 연다. 인자 없는 명령은 즉시 예약. */}
            {launch && generalId != null && (
                <CommandModal
                    onClose={() => setLaunch(null)}
                    onToast={(msg) => showToast(msg)}
                    generalId={generalId}
                    nationId={nationId}
                    turnIdx={launch.turnIdx}
                    pinnedCommand={launch.command.value}
                    pinnedLabel={`${launch.command.simpleName} (${launch.turnIdx + 1}턴)`}
                    pinnedArgType={launch.command.argType}
                    onReserved={() => {
                        refresh();
                        fetchData();
                    }}
                    isNationCommand
                />
            )}
        </Shell>
    );
}
