'use client';

// ── page 15 · 유산 (Inherit Points) ───────────────────────────────────────────
// Consumes api.inheritPoint() → InheritPointResponse (foundation phase). Mirrors
// legacy hwe/v_inheritPoint.php + ts/PageInheritPoint.vue. Title "유산 관리".
//
// Store/reset actions use the existing typed intake seam. CheckOwner is also submitted
// from this page so its PHP denial string can be shown without fabricating client errors.
//
// EMPTY-SAFE: missing item keys → 0; empty availableSpecialWar / availableUnique /
// availableTargetGeneral maps → empty selects; lastInheritPointLogs [] → empty log list.
// Never crashes on a zeroed/empty response (mirrors the 1010 fresh-seed shape).
//
// Verbatim Korean labels/info reproduced byte-for-byte from PageInheritPoint.vue:
//   inheritanceViewText (sum/previous/new/lived_month/…/betting),
//   inheritBuffHelpText (warAvoidRatio/…/domesticFailProb),
//   store action captions (다음 전투 특기 선택 / 유니크 경매 / 랜덤 턴 초기화 / 랜덤 유니크 획득 /
//   즉시 전투 특기 초기화), 장수 소유자 확인, 능력치 초기화, 유산 포인트 변경 내역.

import { useEffect, useRef, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import CommandModal from '../../../components/CommandModal';
import { api } from '../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../lib/commandSubmit';
import { useFrontInfo } from '../../../hooks/useFrontInfo';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type { InheritPointResponse } from '../../../types/game';

// ── inheritanceViewText (verbatim) — display order matches the Vue v-for ───────
// `sum`/`new` are derived client-side (sum = floor(Σ items), new = sum − previous),
// exactly as PageInheritPoint.vue computes them. A <hr> follows the `new` row.
interface InheritViewDef {
    key: string;
    title: string;
    info: string; // may contain <br> markup — rendered as HTML, verbatim from the Vue
}

const INHERIT_VIEW_TEXT: InheritViewDef[] = [
    { key: 'sum', title: '총 포인트', info: '다음 플레이에서 사용할 수 있는 총 포인트입니다.' },
    { key: 'previous', title: '기존 포인트', info: '이전에 물려받은 포인트입니다.' },
    { key: 'new', title: '신규 포인트', info: '이번 플레이에서 얻은 총 포인트입니다.' },
    { key: 'lived_month', title: '생존', info: '살아남은 기간입니다. (1개월 단위)' },
    { key: 'max_belong', title: '최대 임관년 수', info: '가장 오래 임관했던 국가의 연도입니다.' },
    { key: 'max_domestic_critical', title: '최대 연속 내정 성공', info: '성공한 내정 중 최대 연속값입니다.' },
    { key: 'active_action', title: '능동 행동 수', info: '장수 동향에 본인의 이름이 직접 나타난 수입니다.<br>일부 사령턴은 제외됩니다.' },
    { key: 'combat', title: '전투 횟수', info: '전투 횟수입니다.' },
    { key: 'sabotage', title: '계략 성공 횟수', info: '계략 성공 횟수입니다.' },
    { key: 'unifier', title: '천통 기여', info: '천통에 기여한 포인트입니다. <br>각 국의 군주, 천통 수뇌, 천통 군주가 받습니다.' },
    { key: 'dex', title: '숙련도', info: '총 숙련도합입니다. <br>최대 숙련 이후에는 상승량이 1/3로 감소합니다.' },
    { key: 'tournament', title: '토너먼트', info: '토너먼트 입상 포인트입니다.' },
    { key: 'betting', title: '베팅 당첨', info: '성공적인 베팅을 했습니다. <br>수익율과 베팅 성공 횟수를 따릅니다.' },
];

// ── inheritBuffHelpText (verbatim) — fold-slot #7 buff catalog (P6) ────────────
interface InheritBuffDef {
    key: string;
    title: string;
    info: string;
}

const INHERIT_BUFF_HELP: InheritBuffDef[] = [
    { key: 'warAvoidRatio', title: '회피 확률 증가', info: '전투 시 회피 확률이 1%p ~ 5%p 증가합니다.' },
    { key: 'warCriticalRatio', title: '필살 확률 증가', info: '전투 시 필살 확률이 1%p ~ 5%p 증가합니다.' },
    { key: 'warMagicTrialProb', title: '계략 시도 확률 증가', info: '전투 시 계략을 시도할 확률이 1%p ~ 5%p 증가합니다. 무장도 계략을 시도합니다.' },
    { key: 'warAvoidRatioOppose', title: '상대 회피 확률 감소', info: '전투 시 상대의 회피 확률이 1%p ~ 5%p 감소합니다.' },
    { key: 'warCriticalRatioOppose', title: '상대 필살 확률 감소', info: '전투 시 상대의 필살 확률이 1%p ~ 5%p 감소합니다.' },
    { key: 'warMagicTrialProbOppose', title: '상대 계략 시도 확률 감소', info: '전투 시 상대의 계략 시도 확률이 1%p ~ 5%p 감소합니다.' },
    { key: 'domesticSuccessProb', title: '내정 성공 확률 증가', info: '민심, 인구, 농업, 상업, 치안, 수비, 성벽, 기술 내정의 성공 확률이 1%p ~ 5%p 증가합니다.' },
    { key: 'domesticFailProb', title: '내정 실패 확률 감소', info: '민심, 인구, 농업, 상업, 치안, 수비, 성벽, 기술 내정의 실패 확률이 1%p ~ 5%p 감소합니다.' },
];

// Section header bar — same look as the npc-control / war-room section bars.
const sectionBarStyle: React.CSSProperties = {
    textAlign: 'center',
    border: '0.5px solid var(--border-medium)',
    background: 'var(--bg-elevated)',
    padding: 'var(--space-xs) var(--space-sm)',
    fontWeight: 600,
    fontSize: 'var(--text-sm)',
    marginBottom: 'var(--space-sm)',
    marginTop: 'var(--space-lg)',
};

const labelStyle: React.CSSProperties = {
    fontSize: 'var(--text-sm)',
    color: 'var(--text-secondary)',
    fontWeight: 500,
};

const valueStyle: React.CSSProperties = {
    fontVariantNumeric: 'tabular-nums',
    textAlign: 'right',
    fontWeight: 600,
};

const infoStyle: React.CSSProperties = {
    fontSize: 'var(--text-xs)',
    color: 'var(--text-muted)',
    textAlign: 'right',
    marginTop: 'var(--space-xs)',
};

const costStyle: React.CSSProperties = {
    fontSize: 'var(--text-xs)',
    color: 'var(--gold)',
};

const hr: React.CSSProperties = {
    border: 'none',
    borderTop: '1px solid var(--border-subtle)',
    opacity: 0.5,
    margin: 'var(--space-md) 0',
};

// Buy*/reset action descriptor — drives the CommandModal launch. P6-registered codes
// (BuyHiddenBuff / BuyRandomUnique) + F4 Wave C2 inheritance resets (inheritResetTurnTime /
// inheritResetSpecialWar / inheritSetNextSpecialWar). nextSpecial carries the picked specialWar key
// in extraArgs; the two resets are no-arg confirms.
interface BuyModalSpec {
    command:
        | 'BuyHiddenBuff'
        | 'BuyRandomUnique'
        | 'inheritResetTurnTime'
        | 'inheritResetSpecialWar'
        | 'inheritSetNextSpecialWar'
        | 'auctionOpenUnique';
    label: string;
    extraArgs: Record<string, unknown>;
}

export default function InheritPage() {
    const { frontInfo, refresh } = useFrontInfo();
    const generalId = frontInfo?.general.generalId ?? null;
    const nationId = frontInfo?.general.nationId;
    const [data, setData] = useState<InheritPointResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');
    const [ownerTarget, setOwnerTarget] = useState('');
    const [buyModal, setBuyModal] = useState<BuyModalSpec | null>(null);
    // F4 C2 — the 다음 전투 특기 select pick (drives the inheritSetNextSpecialWar extraArgs).
    const [nextSpecialPick, setNextSpecialPick] = useState<string>('');
    // P0-26 — 유니크 경매(auctionOpenUnique) 선택 아이템 + 입찰 포인트.
    // 바퀴 24 정정: 종전 'OpenUniqueAuction'은 미등록 코드 + {item} 인자라
    // CommandRegistry else→휴식 턴 예약으로 잠복 위조였다(재채점 audit-delta).
    // 정본 = intakeCodes 'auctionOpenUnique' + {itemId, amount}(OpenUniqueAuction.php:33-39).
    const [selectedUnique, setSelectedUnique] = useState<string>('');
    const [uniqueAmount, setUniqueAmount] = useState<number>(0);
    // P0-24 — ResetStat form state (기본3+추가3).
    const [baseLeadership, setBaseLeadership] = useState(55);
    const [baseStrength, setBaseStrength] = useState(55);
    const [baseIntel, setBaseIntel] = useState(55);
    const [bonusLeadership, setBonusLeadership] = useState(0);
    const [bonusStrength, setBonusStrength] = useState(0);
    const [bonusIntel, setBonusIntel] = useState(0);
    const initRef = useRef(false);

    function showToast(msg: string) {
        setToast(msg);
        setTimeout(() => setToast(''), 3000);
    }

    // 최초 조회·수동 조회·턴 갱신이 겹칠 수 있다. 세대 번호로 **마지막** 요청만 화면에 반영해
    // 늦게 온 옛 응답이 새 값을 덮어쓰지 않게 한다. background=true(턴 갱신)는 실패해도
    // 보고 있던 화면을 에러로 바꾸지 않는다.
    const loadSeq = useRef(0);

    function load(background = false) {
        const seq = ++loadSeq.current;
        const current = () => seq === loadSeq.current;
        (api.inheritPoint() as Promise<InheritPointResponse>)
            .then((d) => {
                if (current()) {
                    setData(d);
                    setError('');
                }
            })
            .catch(() => {
                if (current() && !background) setError('데이터를 불러올 수 없습니다.');
            })
            .finally(() => {
                if (current() && !background) setLoading(false);
            });
        return () => {
            // 언마운트/재요청 — 이 요청의 결과를 버린다.
            if (current()) loadSeq.current += 1;
        };
    }

    useEffect(() => load(), []);

    // 유산 포인트/로그만 재조회 — 상점 폼 선택값(nextSpecialPick 등)은 건드리지 않는다(OPENSAM-196).
    useTurnRefresh(() => {
        load(true);
    });

    async function handleResetStat() {
        if (generalId == null) {
            showToast('장수가 없어 초기화할 수 없습니다.');
            return;
        }
        const leadership = baseLeadership;
        const strength = baseStrength;
        const intel = baseIntel;
        const bl = bonusLeadership;
        const bs = bonusStrength;
        const bi = bonusIntel;

        // 능력치 총합은 하드코딩(165) 대신 현재 능력치(currentStat)에서 파생한다.
        const statTotal = currentStat
            ? currentStat.leadership + currentStat.strength + currentStat.intel
            : leadership + strength + intel;
        if (leadership + strength + intel !== statTotal) {
            showToast(`능력치 총합이 ${statTotal}이 아닙니다. 다시 입력해주세요!`);
            return;
        }
        const bonusSum = bl + bs + bi;
        if (bonusSum > 0 && (bonusSum < 3 || bonusSum > 5)) {
            showToast('추가 능력치 합이 잘못 지정되었습니다. 다시 입력해주세요!');
            return;
        }

        const args: {
            leadership: number;
            strength: number;
            intel: number;
            inheritBonusStat?: number[];
        } = { leadership, strength, intel };
        if (bonusSum > 0) {
            args.inheritBonusStat = [bl, bs, bi];
        }

        try {
            const out = await submitCommandAndAwaitResult(() => api.resetStat(args, generalId));
            if (out.status === 'applied') {
                showToast('능력치 초기화가 처리되었습니다.');
                refresh();
                load();
            } else if (out.status === 'rejected') {
                showToast(out.reason || '초기화가 거부되었습니다.');
            } else {
                showToast(out.reason);
            }
        } catch (err) {
            showToast(err instanceof Error ? err.message : '요청에 실패했습니다.');
        }
    }

    async function handleCheckOwner() {
        if (generalId == null || !ownerTarget) return;
        try {
            const out = await submitCommandAndAwaitResult(() =>
                api.instantAction('CheckOwner', generalId, { destGeneralID: Number(ownerTarget) }));
            if (out.status === 'applied') {
                showToast('장수 소유자 확인이 처리되었습니다.');
                load();
            } else if (out.status === 'rejected') {
                showToast(out.reason || '소유자 확인이 거부되었습니다.');
            } else {
                showToast(out.reason);
            }
        } catch (err) {
            showToast(err instanceof Error ? err.message : '요청에 실패했습니다.');
        }
    }

    function openBuy(spec: BuyModalSpec) {
        if (generalId == null) {
            showToast('장수가 없어 구매할 수 없습니다.');
            return;
        }
        setBuyModal(spec);
    }

    const items = data?.items ?? {};
    const cost = data?.inheritActionCost;
    const currentBuff = data?.currentInheritBuff ?? {};
    const availableSpecialWar = data?.availableSpecialWar ?? {};
    const availableUnique = data?.availableUnique ?? {};
    const availableTargetGeneral = data?.availableTargetGeneral ?? {};
    const currentStat = data?.currentStat;
    const logs = data?.lastInheritPointLogs ?? [];

    // P0-24 — initialize ResetStat inputs from current stat once on load.
    useEffect(() => {
        if (currentStat && !initRef.current) {
            setBaseLeadership(currentStat.leadership);
            setBaseStrength(currentStat.strength);
            setBaseIntel(currentStat.intel);
            initRef.current = true;
        }
    }, [currentStat]);

    // P0-26 — legacy는 입찰 포인트를 최소값으로 프리필한다(PageInheritPoint.vue:455).
    useEffect(() => {
        if (uniqueAmount === 0 && (cost?.minSpecificUnique ?? 0) > 0) {
            setUniqueAmount(cost!.minSpecificUnique);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [cost?.minSpecificUnique]);

    // Derived display points (PageInheritPoint.vue): sum = floor(Σ items), new = sum − previous.
    const totalPoint = Math.floor(Object.values(items).reduce((acc, v) => acc + v, 0));
    const previousPoint = Math.floor(items['previous'] ?? 0);
    const newPoint = totalPoint - previousPoint;

    function itemValue(key: string): number {
        if (key === 'sum') return totalPoint;
        if (key === 'new') return newPoint;
        return Math.floor(items[key] ?? 0);
    }

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>유산 관리</h1>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            {/* ── 유산 포인트 항목 (inheritanceViewText) ─────────────────────────── */}
            <GameCard>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 'var(--space-md)' }}>
                    {INHERIT_VIEW_TEXT.map((def) => (
                        <div key={def.key}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 'var(--space-sm)' }}>
                                <span style={labelStyle}>{def.title}</span>
                                <span style={valueStyle}>{itemValue(def.key).toLocaleString()}</span>
                            </div>
                            <div style={infoStyle} dangerouslySetInnerHTML={{ __html: def.info }} />
                        </div>
                    ))}
                </div>
            </GameCard>

            {/* ── 유산 포인트 상점 (read-only display; purchase deferred) ──────────── */}
            <div style={sectionBarStyle}>유산 포인트 상점</div>
            <GameCard>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 'var(--space-lg)' }}>
                    <div>
                        <div style={labelStyle}>다음 전투 특기 선택</div>
                        <select
                            className="form-select"
                            value={nextSpecialPick}
                            onChange={(e) => setNextSpecialPick(e.target.value)}
                            style={{ width: '100%', marginTop: 'var(--space-xs)' }}
                        >
                            <option value="">특기 선택</option>
                            {Object.entries(availableSpecialWar).map(([key, info]) => (
                                <option key={key} value={key}>{info.title}</option>
                            ))}
                        </select>
                        <div style={infoStyle}>
                            다음에 얻을 전투 특기를 정합니다.<br />
                            <span style={costStyle}>필요 포인트: {(cost?.nextSpecial ?? 0).toLocaleString()}</span>
                        </div>
                        <button
                            type="button"
                            disabled={!nextSpecialPick}
                            style={{ marginTop: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}
                            onClick={() => openBuy({ command: 'inheritSetNextSpecialWar', label: '다음 전투 특기 지정', extraArgs: { specialWar: nextSpecialPick } })}
                        >
                            구입
                        </button>
                    </div>
                    <div>
                        <div style={labelStyle}>유니크 경매</div>
                        <select
                            className="form-select"
                            value={selectedUnique}
                            onChange={(e) => setSelectedUnique(e.target.value)}
                            style={{ width: '100%', marginTop: 'var(--space-xs)' }}
                        >
                            <option value="">유니크 선택</option>
                            {Object.entries(availableUnique).map(([key, info]) => (
                                <option key={key} value={key}>{info.title}</option>
                            ))}
                        </select>
                        <div style={infoStyle}>
                            얻고자 하는 유니크 아이템으로 경매를 시작합니다. 24턴 동안 진행됩니다.<br />
                            <span style={costStyle}>입찰 포인트(최소): {(cost?.minSpecificUnique ?? 0).toLocaleString()}</span>
                        </div>
                        {/* 입찰 포인트 — legacy PageInheritPoint.vue:622-648 specificUniqueAmount 입력. */}
                        <input
                            type="number"
                            className="form-input"
                            min={cost?.minSpecificUnique ?? 0}
                            max={previousPoint}
                            step={1}
                            value={uniqueAmount || ''}
                            placeholder={`입찰 포인트 (최소 ${(cost?.minSpecificUnique ?? 0).toLocaleString()})`}
                            onChange={(e) => setUniqueAmount(Math.floor(Number(e.target.value) || 0))}
                            style={{ width: '100%', marginTop: 'var(--space-xs)' }}
                        />
                        <button
                            type="button"
                            disabled={!selectedUnique || uniqueAmount <= 0}
                            style={{ marginTop: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}
                            onClick={() => {
                                // legacy 가드(PageInheritPoint.vue:624-627): 보유 유산 포인트 부족.
                                if (previousPoint < uniqueAmount) {
                                    showToast('유산 포인트가 부족합니다.');
                                    return;
                                }
                                openBuy({
                                    command: 'auctionOpenUnique',
                                    label: '유니크 경매 시작',
                                    extraArgs: { itemId: selectedUnique, amount: uniqueAmount },
                                });
                            }}
                        >
                            경매 시작
                        </button>
                    </div>
                    <div>
                        <div style={labelStyle}>랜덤 턴 초기화</div>
                        <div style={infoStyle}>
                            다다음턴부터 시간이 랜덤하게 바뀝니다. (필요 포인트가 피보나치식으로 증가합니다)<br />
                            <span style={costStyle}>필요 포인트: {(cost?.resetTurnTime ?? 0).toLocaleString()}</span>
                        </div>
                        <button
                            type="button"
                            style={{ marginTop: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}
                            onClick={() => openBuy({ command: 'inheritResetTurnTime', label: '랜덤 턴 초기화', extraArgs: {} })}
                        >
                            구입
                        </button>
                    </div>
                    <div>
                        <div style={labelStyle}>랜덤 유니크 획득</div>
                        <div style={infoStyle}>
                            다음 턴에 랜덤 유니크를 얻습니다.<br />
                            <span style={costStyle}>필요 포인트: {(cost?.randomUnique ?? 0).toLocaleString()}</span>
                        </div>
                        <button
                            type="button"
                            style={{ marginTop: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}
                            onClick={() => openBuy({ command: 'BuyRandomUnique', label: '랜덤 유니크 획득', extraArgs: {} })}
                        >
                            구입
                        </button>
                    </div>
                    <div>
                        <div style={labelStyle}>즉시 전투 특기 초기화</div>
                        <div style={infoStyle}>
                            즉시 전투 특기를 초기화합니다. (필요 포인트가 피보나치식으로 증가합니다)<br />
                            <span style={costStyle}>필요 포인트: {(cost?.resetSpecialWar ?? 0).toLocaleString()}</span>
                        </div>
                        <button
                            type="button"
                            style={{ marginTop: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}
                            onClick={() => openBuy({ command: 'inheritResetSpecialWar', label: '즉시 전투 특기 초기화', extraArgs: {} })}
                        >
                            구입
                        </button>
                    </div>
                </div>
            </GameCard>

            {/* ── 유산 버프 (inheritBuffHelpText) — current levels, read-only ─────── */}
            <div style={{ ...hr }} />
            <GameCard>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 'var(--space-md)' }}>
                    {INHERIT_BUFF_HELP.map((def) => {
                        const level = currentBuff[def.key] ?? 0;
                        const step = cost?.buff ?? [];
                        const nextCost = step[level + 1] != null && step[level] != null ? step[level + 1] - step[level] : null;
                        return (
                            <div key={def.key}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 'var(--space-sm)' }}>
                                    <span style={labelStyle}>{def.title}</span>
                                    <span style={valueStyle}>{level} / {(data?.maxInheritBuff ?? 0).toLocaleString()}</span>
                                </div>
                                <div style={infoStyle}>
                                    {def.info}
                                    {nextCost != null && (
                                        <>
                                            <br />
                                            <span style={costStyle}>다음 등급 필요 포인트: {nextCost.toLocaleString()}</span>
                                        </>
                                    )}
                                </div>
                                {level < (data?.maxInheritBuff ?? 0) && (
                                    <button
                                        type="button"
                                        style={{ marginTop: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}
                                        onClick={() => openBuy({
                                            command: 'BuyHiddenBuff',
                                            label: `${def.title} 구매`,
                                            extraArgs: { buffKey: def.key, level: level + 1, prevLevel: level },
                                        })}
                                    >
                                        구입
                                    </button>
                                )}
                            </div>
                        );
                    })}
                </div>
            </GameCard>

            {/* ── 장수 소유자 확인 / 능력치 초기화 ──────────────────────────────── */}
            <div style={{ ...hr }} />
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 'var(--space-lg)' }}>
                <GameCard>
                    <div style={labelStyle}>장수 소유자 확인</div>
                    <select className="form-select" value={ownerTarget} onChange={(e) => setOwnerTarget(e.target.value)} style={{ width: '100%', marginTop: 'var(--space-xs)' }}>
                        <option value="">장수 선택</option>
                        {Object.entries(availableTargetGeneral).map(([key, name]) => (
                            <option key={key} value={key}>{name}</option>
                        ))}
                    </select>
                    <div style={infoStyle}>
                        장수의 소유자를 찾습니다.<br />
                        <span style={costStyle}>필요 포인트: {(cost?.checkOwner ?? 0).toLocaleString()}</span>
                    </div>
                    <button
                        type="button"
                        style={{ marginTop: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}
                        onClick={handleCheckOwner}
                        disabled={generalId == null || !ownerTarget}
                    >
                        확인
                    </button>
                </GameCard>
                <GameCard>
                    <div style={labelStyle}>능력치 초기화</div>
                    <div style={{ marginTop: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}>
                        <div style={{ color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>기본 능력치</div>
                        <div style={{ display: 'flex', gap: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                            <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)' }}>
                                통
                                <input
                                    type="number"
                                    className="form-input"
                                    value={baseLeadership}
                                    min={currentStat?.statMin ?? 15}
                                    max={currentStat?.statMax ?? 80}
                                    onChange={(e) => setBaseLeadership(Number(e.target.value))}
                                    style={{ width: '5ch' }}
                                />
                            </label>
                            <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)' }}>
                                무
                                <input
                                    type="number"
                                    className="form-input"
                                    value={baseStrength}
                                    min={currentStat?.statMin ?? 15}
                                    max={currentStat?.statMax ?? 80}
                                    onChange={(e) => setBaseStrength(Number(e.target.value))}
                                    style={{ width: '5ch' }}
                                />
                            </label>
                            <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)' }}>
                                지
                                <input
                                    type="number"
                                    className="form-input"
                                    value={baseIntel}
                                    min={currentStat?.statMin ?? 15}
                                    max={currentStat?.statMax ?? 80}
                                    onChange={(e) => setBaseIntel(Number(e.target.value))}
                                    style={{ width: '5ch' }}
                                />
                            </label>
                        </div>
                        <div style={{ color: 'var(--text-muted)', marginTop: 'var(--space-xs)', fontSize: 'var(--text-xs)' }}>
                            범위: {currentStat?.statMin ?? 0} ~ {currentStat?.statMax ?? 0} / 합 {currentStat ? currentStat.leadership + currentStat.strength + currentStat.intel : 0}
                        </div>
                        <div style={{ color: 'var(--text-secondary)', marginTop: 'var(--space-sm)', marginBottom: 'var(--space-xs)' }}>추가 능력치</div>
                        <div style={{ display: 'flex', gap: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                            <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)' }}>
                                통+
                                <input
                                    type="number"
                                    className="form-input"
                                    value={bonusLeadership}
                                    min={0}
                                    onChange={(e) => setBonusLeadership(Number(e.target.value))}
                                    style={{ width: '5ch' }}
                                />
                            </label>
                            <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)' }}>
                                무+
                                <input
                                    type="number"
                                    className="form-input"
                                    value={bonusStrength}
                                    min={0}
                                    onChange={(e) => setBonusStrength(Number(e.target.value))}
                                    style={{ width: '5ch' }}
                                />
                            </label>
                            <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)' }}>
                                지+
                                <input
                                    type="number"
                                    className="form-input"
                                    value={bonusIntel}
                                    min={0}
                                    onChange={(e) => setBonusIntel(Number(e.target.value))}
                                    style={{ width: '5ch' }}
                                />
                            </label>
                        </div>
                        <div style={{ color: 'var(--text-muted)', marginTop: 'var(--space-xs)', fontSize: 'var(--text-xs)' }}>
                            합 3~5 (0이면 랜덤 배분)
                        </div>
                    </div>
                    <div style={infoStyle}>
                        시즌 당 1회에 한 해 능력치를 초기화합니다.<br />
                        <span style={costStyle}>추가 능력치 필요 포인트: {(cost?.bornStatPoint ?? 0).toLocaleString()}</span>
                    </div>
                    <button
                        type="button"
                        style={{ marginTop: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}
                        onClick={handleResetStat}
                        disabled={generalId == null}
                    >
                        초기화
                    </button>
                </GameCard>
            </div>

            {/* ── 유산 포인트 변경 내역 (logs) ────────────────────────────────────── */}
            <div style={sectionBarStyle}>유산 포인트 변경 내역</div>
            <GameCard>
                {logs.length === 0 && !loading ? (
                    <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>변경 내역이 없습니다.</p>
                ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                        {logs.map((log) => (
                            <div key={log.id} style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'baseline' }}>
                                <span style={{ flexBasis: '14ch', flexShrink: 0, textAlign: 'right', color: 'var(--text-muted)', fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums' }}>
                                    [{log.date}]
                                </span>
                                <span style={{ flex: 1 }} dangerouslySetInnerHTML={{ __html: log.text }} />
                            </div>
                        ))}
                    </div>
                )}
            </GameCard>

            {/* 유산 포인트 구매 — CommandModal pinned to BuyHiddenBuff / BuyRandomUnique (P6-registered,
                no Select* arg). The buffKey/level/prevLevel (or empty for random-unique) are page-fixed
                args; a 예약 confirm posts via api.command. Other store actions remain read-only. */}
            {buyModal && generalId != null && (
                <CommandModal
                    onClose={() => setBuyModal(null)}
                    onToast={(msg) => showToast(msg)}
                    generalId={generalId}
                    nationId={nationId}
                    pinnedCommand={buyModal.command}
                    pinnedLabel={buyModal.label}
                    pinnedArgType={null}
                    extraArgs={buyModal.extraArgs}
                    onReserved={() => { refresh(); load(); }}
                />
            )}
        </Shell>
    );
}
