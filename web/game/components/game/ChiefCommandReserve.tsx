'use client';

// ChiefCommandReserve — 사령부(chief-center) 예약 명령 편집 affordance.
// 내 직책 칸에서 (1) 예약 슬롯(turnIdx 0..maxChiefTurn-1)을 고르고 (2) 카테고리별 사령부 명령
// 팔레트(commandList)에서 명령을 선택하면, 부모가 CommandModal을 그 명령 + 그 슬롯에 pin해 띄운다
// (locked F2 결정: 페이지 이동 대신 모달). 팔레트는 백엔드 commandList가 내려보내는 명령만 렌더한다
// (현재 F4StateText.CHIEF_COMMAND_TABLE = 휴식/인사/외교/특수/전략/기타 6개 카테고리의 사령부 명령).
// event_*연구 9종은 레지스트리에 등록·ring 배선돼 있으나 이 테이블에는 아직 포함돼 있지 않다 — 백엔드가
// 테이블에 포함시키면 그때 함께 나타난다. 렌더된 명령은 모두 같은 nation_turn 링 +
// 같은 POST /api/command/{value}?turnIdx= 경로를 탄다.
//
// 패러티(legacy hwe/ts/components/ChiefReservedCommand.vue + CommandSelectForm.vue):
//  - 슬롯 행: 1부터의 인덱스 + 현재 예약 brief(색/태그 마크업 verbatim, 빈 슬롯은 '휴식').
//  - 명령 팔레트: 카테고리 탭(휴식/인사/외교/특수/전략/기타) → 명령 그리드. compensation ▲/▼,
//    possible=false → strikethrough-red(.cmd-impossible). possible은 백엔드 실제 precheck 결과
//    (ChiefCenterController가 AvailableCommandsController와 동일하게 배선) — 더 이상 추정/고정값 아님.
//    deny 사유(cmd.reason)는 임파서블 명령의 title 툴팁으로 노출. title-as-subtext는 CommandModal과 동일 규칙.
//  - 드래그-셀렉트/고급(반복·범위·보관함) 모드는 이 MVP 범위 밖(슬롯 단건 예약). 후속 wave에서 확장.

import { useState } from 'react';
import { api, isIntakeQueued, isIntakeDenied } from '../../lib/api';
import type { ChiefCommandCategory, ChiefCommand, ChiefReservedTurn } from '../../types/game';

// 부모가 CommandModal을 띄울 때 필요한 1개 명령 spec(선택된 명령 + 대상 슬롯).
export interface ChiefReserveLaunch {
    command: ChiefCommand;
    turnIdx: number;
}

function CompensationTag({ value }: { value: number }) {
    if (value > 0) return <span className="cmd-comp-pos">▲</span>;
    if (value < 0) return <span className="cmd-comp-neg">▼</span>;
    return null;
}

// 명령 팔레트 팝오버 — 카테고리 탭 + 명령 그리드(CommandModal의 catalog 그리드와 동일 클래스).
function CommandPalette({
    commandList,
    onPick,
    onClose,
}: {
    commandList: ChiefCommandCategory[];
    onPick: (cmd: ChiefCommand) => void;
    onClose: () => void;
}) {
    const [cat, setCat] = useState<string>(commandList[0]?.category ?? '');
    const filtered = commandList.find((c) => c.category === cat)?.values ?? [];

    return (
        <div
            style={{
                marginTop: 'var(--space-xs)',
                padding: 'var(--space-sm)',
                border: '1px solid var(--border-subtle)',
                borderRadius: 'var(--radius-sm)',
                background: 'var(--bg-hover)',
            }}
        >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-xs)' }}>
                <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>명령 선택</span>
                <button onClick={onClose} aria-label="닫기" style={{ fontSize: 'var(--text-xs)', padding: '0 var(--space-xs)' }}>
                    ×
                </button>
            </div>
            <div className="cmd-cats">
                {commandList.map((c) => (
                    <button key={c.category} className={cat === c.category ? 'active' : ''} onClick={() => setCat(c.category)}>
                        {c.category}
                    </button>
                ))}
            </div>
            <div className="cmd-grid">
                {filtered.map((cmd) => (
                    <button
                        key={cmd.value}
                        className="cmd-item"
                        // 임파서블 명령은 precheck deny 사유(reason)를 툴팁으로 노출, 아니면 기존 title.
                        title={!cmd.possible && cmd.reason ? cmd.reason : cmd.title}
                        onClick={() => onPick(cmd)}
                    >
                        <span className={cmd.possible ? '' : 'cmd-impossible'}>
                            {cmd.simpleName} <CompensationTag value={cmd.compensation} />
                        </span>
                        {cmd.title && cmd.title !== cmd.simpleName && (
                            <small className="cmd-item-sub">
                                {cmd.title.startsWith(cmd.simpleName) ? cmd.title.substring(cmd.simpleName.length) : cmd.title}
                            </small>
                        )}
                    </button>
                ))}
            </div>
        </div>
    );
}

export default function ChiefCommandReserve({
    maxChiefTurn,
    reservedTurns,
    commandList,
    onLaunch,
    generalId,
}: {
    maxChiefTurn: number;
    reservedTurns: ChiefReservedTurn[];
    commandList: ChiefCommandCategory[];
    onLaunch: (spec: ChiefReserveLaunch) => void;
    generalId?: number | null;
}) {
    // 명령 팔레트가 열려 있는 대상 슬롯(turnIdx). null = 닫힘.
    const [openSlot, setOpenSlot] = useState<number | null>(null);
    // P0-10 — 당기기/미루기/반복 입력값.
    const [pushAmount, setPushAmount] = useState(1);
    const [repeatAmount, setRepeatAmount] = useState(1);

    // turnIdx → 예약 brief(빠른 조회). 빈 슬롯은 '휴식' fallback.
    const briefByIdx = new Map(reservedTurns.map((t) => [t.turnIdx, t.brief]));

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1px', marginTop: 'var(--space-sm)' }}>
            {Array.from({ length: maxChiefTurn }, (_, idx) => {
                const brief = briefByIdx.get(idx);
                const isOpen = openSlot === idx;
                return (
                    <div key={idx}>
                        <button
                            onClick={() => setOpenSlot(isOpen ? null : idx)}
                            style={{
                                display: 'grid',
                                gridTemplateColumns: '1.75rem 1fr',
                                alignItems: 'center',
                                width: '100%',
                                textAlign: 'left',
                                fontSize: 'var(--text-sm)',
                                padding: '2px var(--space-xs)',
                                background: isOpen ? 'var(--gold-dim)' : idx % 2 === 0 ? 'var(--bg-hover)' : 'transparent',
                                borderRadius: 'var(--radius-sm)',
                                border: '1px solid transparent',
                                cursor: 'pointer',
                            }}
                        >
                            <span style={{ color: 'var(--text-muted)', fontSize: 'var(--text-xs)' }}>{idx + 1}</span>
                            {/* brief는 색/태그 마크업을 담을 수 있어 verbatim 렌더(troop/chief read 경로와 동일). */}
                            <span
                                style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                                dangerouslySetInnerHTML={{ __html: brief ?? '휴식' }}
                            />
                        </button>
                        {isOpen && (
                            <CommandPalette
                                commandList={commandList}
                                onClose={() => setOpenSlot(null)}
                                onPick={(cmd) => {
                                    setOpenSlot(null);
                                    onLaunch({ command: cmd, turnIdx: idx });
                                }}
                            />
                        )}
                    </div>
                );
            })}
            {/* P0-10 — 당기기/미루기/반복 (legacy PushCommand/RepeatCommand). */}
            {generalId != null && (
                <div style={{ display: 'flex', gap: 'var(--space-xs)', marginTop: 'var(--space-sm)', flexWrap: 'wrap', alignItems: 'center' }}>
                    <label style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>당기기/미루기</label>
                    <input
                        type="number"
                        value={pushAmount}
                        onChange={(e) => setPushAmount(Number(e.target.value))}
                        style={{ width: '5ch', fontSize: 'var(--text-sm)' }}
                    />
                    <button
                        type="button"
                        style={{ fontSize: 'var(--text-xs)' }}
                        onClick={async () => {
                            try {
                                const out = await api.commandQueue.nationPush(generalId, pushAmount);
                                if (isIntakeQueued(out)) {
                                    window.alert('적용되었습니다.');
                                } else if (isIntakeDenied(out)) {
                                    window.alert(out.reason || '적용할 수 없습니다.');
                                }
                            } catch (e: any) {
                                window.alert('요청에 실패했습니다: ' + (e?.message || ''));
                            }
                        }}
                    >
                        적용
                    </button>
                    <label style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', marginLeft: 'var(--space-sm)' }}>반복</label>
                    <input
                        type="number"
                        value={repeatAmount}
                        onChange={(e) => setRepeatAmount(Number(e.target.value))}
                        style={{ width: '5ch', fontSize: 'var(--text-sm)' }}
                    />
                    <button
                        type="button"
                        style={{ fontSize: 'var(--text-xs)' }}
                        onClick={async () => {
                            try {
                                const out = await api.commandQueue.nationRepeat(generalId, repeatAmount);
                                if (isIntakeQueued(out)) {
                                    window.alert('적용되었습니다.');
                                } else if (isIntakeDenied(out)) {
                                    window.alert(out.reason || '적용할 수 없습니다.');
                                }
                            } catch (e: any) {
                                window.alert('요청에 실패했습니다: ' + (e?.message || ''));
                            }
                        }}
                    >
                        적용
                    </button>
                </div>
            )}
        </div>
    );
}
