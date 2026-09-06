'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { SectionHeader } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import GameCard from '../../../components/GameCard';
import GameTable from '../../../components/GameTable';
import StatusBadge from '../../../components/StatusBadge';
import CommandModal from '../../../components/CommandModal';
import { RichTextEditor, countHtmlCodePoints } from '../../../components/RichTextEditor';
import { SafeHtml } from '../../../components/SafeHtml';
import { api } from '../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../lib/commandSubmit';
import { formatNumber } from '../../../lib/format';
import type { FrontInfoResponse } from '../../../lib/types';
import type { NationFinanceResponse } from '../../../types/game';
import type { CommandArgType } from '../../../types/game';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';

interface FinanceModalSpec {
    command: string;
    label: string;
    argType: CommandArgType | null;
    amountMin?: number;
    amountMax?: number;
    extraArgs?: Record<string, unknown>;
}

// PHP raw `msg` length gates before sanitization:
// legacy/devsam-core/hwe/sammo/API/Nation/SetNotice.php:18-29 (`mb_strlen`-equivalent 16,384 limit).
const NOTICE_MAX_LENGTH = 16384;
// legacy/devsam-core/hwe/sammo/API/Nation/SetScoutMsg.php:17-28 (`mb_strlen`-equivalent 1,000 limit).
const SCOUT_MAX_LENGTH = 1000;

// Mirrors legacy hwe/ts/PageNationStratFinan.vue:
//  - 예산&정책 budget tables (자금 예산 / 군량 예산) with verbatim labels + computed rows
//  - 정책(세율/지급률/기밀 권한/전쟁 금지 설정) read-only display
// Identity (nationId) resolved from api.frontInfo().general.nationId, then api.nationFinance(id).
// EMPTY-SAFE: a no-nation viewer (재야, nationId 0) renders an INFO empty state, never crashes.

// PHP truncate-toward-zero parity (Math.floor on non-negative budget figures, matching
// the Vue's Math.floor(...) over the same positive accumulations).
function floor(n: number): number {
    return Math.floor(n);
}

// 외교 상태 → {표시명, 색}. legacy defs/index.ts `diplomacyStateInfo` 충실 포팅(0/1/2/7만 정의,
// 그 외는 통상 폴백). 색 미지정(통상)은 기본 텍스트색.
const diplomacyStateInfo: Record<number, { name: string; color?: string }> = {
    0: { name: '교전', color: 'red' },
    1: { name: '선포중', color: 'magenta' },
    2: { name: '통상' },
    7: { name: '불가침', color: 'green' },
};
function diplomacyStateText(state: number): { name: string; color?: string } {
    return diplomacyStateInfo[state] ?? diplomacyStateInfo[2];
}

// legacy util/joinYearMonth·parseYearMonth 충실 포팅(year*12+month-1 ↔ [trunc/12, %12+1]).
function joinYearMonth(year: number, month: number): number {
    return year * 12 + month - 1;
}
function parseYearMonth(yearMonth: number): [number, number] {
    return [Math.trunc(yearMonth / 12), (yearMonth % 12) + 1];
}

export default function NationFinancePage() {
    const [data, setData] = useState<NationFinanceResponse | null>(null);
    const [noNation, setNoNation] = useState(false);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    // identity for the CommandModal (own general/nation), + the open finance modal spec.
    const [generalId, setGeneralId] = useState<number | null>(null);
    const [nationId, setNationId] = useState<number | null>(null);
    // 기밀권한 — checkSecretPermission($me)(func.php:390-435). <1 이면 내무부 진입 거부(v_nationStratFinan.php:28-34).
    const [permission, setPermission] = useState(0);
    const [financeModal, setFinanceModal] = useState<FinanceModalSpec | null>(null);
    const [toast, setToast] = useState<string | null>(null);
    // draft text for the notice / scout-message edits (passed via extraArgs.msg).
    const [noticeDraft, setNoticeDraft] = useState('');
    const [scoutDraft, setScoutDraft] = useState('');
    const [editingNotice, setEditingNotice] = useState(false);
    const [editingScout, setEditingScout] = useState(false);
    const [savingMessages, setSavingMessages] = useState({ notice: false, scout: false });
    const latestFetchId = useRef(0);
    const foregroundFetchPending = useRef(false);
    const backgroundFetchPending = useRef(false);
    const backgroundFetchQueued = useRef(false);
    const committedNationId = useRef<number | undefined>(undefined);

    const fetchData = useCallback(async (background = false) => {
        if (background && foregroundFetchPending.current) return;
        if (background && backgroundFetchPending.current) {
            backgroundFetchQueued.current = true;
            return;
        }
        if (background) {
            backgroundFetchPending.current = true;
        } else {
            foregroundFetchPending.current = true;
            backgroundFetchQueued.current = false;
            setLoading(true);
            setError('');
            setNoNation(false);
        }

        do {
            backgroundFetchQueued.current = false;
            const fetchId = latestFetchId.current + 1;
            latestFetchId.current = fetchId;
            try {
                const fi: FrontInfoResponse = await api.frontInfo();
                if (fetchId !== latestFetchId.current) continue;
                const nid = fi.general.nationId;
                if (committedNationId.current !== undefined && committedNationId.current !== nid) {
                    setEditingNotice(false);
                    setEditingScout(false);
                    setNoticeDraft('');
                    setScoutDraft('');
                    setGeneralId(fi.general.generalId);
                    setNationId(nid);
                    setPermission(0);
                    setNoNation(!nid);
                    setData(null);
                }
                committedNationId.current = nid;
                // 재야(무소속): nationId 0 → 내무부 없음.
                if (!nid) {
                    setGeneralId(fi.general.generalId);
                    setNationId(nid);
                    setPermission(fi.general.permission ?? 0);
                    setError('');
                    setNoNation(true);
                    setData(null);
                    continue;
                }
                const res = await api.nationFinance(nid);
                if (fetchId !== latestFetchId.current) continue;
                setGeneralId(fi.general.generalId);
                setNationId(nid);
                setPermission(fi.general.permission ?? 0);
                setError('');
                setNoNation(false);
                setData(res);
            } catch {
                if (!background && fetchId === latestFetchId.current) {
                    setError('내무부 정보를 불러올 수 없습니다.');
                }
            } finally {
                if (fetchId === latestFetchId.current) setLoading(false);
            }
        } while (background && backgroundFetchQueued.current && !foregroundFetchPending.current);

        if (background) {
            backgroundFetchPending.current = false;
        } else {
            foregroundFetchPending.current = false;
        }
    }, []);

    useEffect(() => {
        void fetchData();
    }, [fetchData]);

    useTurnRefresh(() => void fetchData(true));

    useEffect(() => {
        if (data?.editable === false) {
            setEditingNotice(false);
            setEditingScout(false);
        }
    }, [data?.editable]);

    async function saveMessage(kind: 'notice' | 'scout') {
        if (generalId == null) {
            setToast('장수 정보를 불러올 수 없습니다.');
            return;
        }

        const command = kind === 'notice' ? 'setNotice' : 'setScoutMsg';
        const msg = kind === 'notice' ? noticeDraft : scoutDraft;
        const maxLength = kind === 'notice' ? NOTICE_MAX_LENGTH : SCOUT_MAX_LENGTH;
        if (countHtmlCodePoints(msg) > maxLength) {
            setToast(`${maxLength.toLocaleString()}자 이하로 입력해주세요.`);
            return;
        }
        setSavingMessages(current => ({ ...current, [kind]: true }));
        try {
            const outcome = await submitCommandAndAwaitResult(() => api.command(command, { msg }, generalId));
            if (outcome.status !== 'applied') {
                setToast(outcome.reason ?? '명령을 실행할 수 없습니다.');
                return;
            }

            setData(current => current === null ? current : {
                ...current,
                ...(kind === 'notice' ? { nationMsg: msg } : { scoutMsg: msg }),
            });
            if (kind === 'notice') setEditingNotice(false);
            else setEditingScout(false);
            setToast(kind === 'notice' ? '국가 방침을 저장했습니다.' : '임관 권유문을 저장했습니다.');
            void fetchData(true);
        } catch (cause) {
            setToast(cause instanceof Error ? cause.message : '명령을 실행할 수 없습니다.');
        } finally {
            setSavingMessages(current => ({ ...current, [kind]: false }));
        }
    }

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <PageHead title="내무부" />
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <PageHead title="내무부" />
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={() => void fetchData()}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    if (noNation) {
        return (
            <Shell>
                <div className="page-content">
                    <PageHead title="내무부" />
                    <GameCard>
                        <p className="text-muted">국가에 소속되어있지 않습니다.</p>
                    </GameCard>
                </div>
            </Shell>
        );
    }

    // 진입 권한 게이트 — 레거시 v_nationStratFinan.php:28-34 와 동형.
    // 무소속(!nid)은 위 noNation 분기가 "국가에 소속되어있지 않습니다."로 처리(permission<0 등가).
    // 소속이나 기밀권한 미달(평장수·사관년도 부족 → permission 0)은 페이지 자체를 거부한다(국고/세율/정책 기밀).
    // frontInfo 미로드 기본값(permission 0)도 동일 차단. 백엔드 read 게이트(NF-P0-D)는 별건 백로그.
    if (permission < 1) {
        return (
            <Shell>
                <div className="page-content">
                    <PageHead title="내무부" />
                    <GameCard>
                        <p className="text-muted">권한이 부족합니다. 수뇌부가 아니거나 사관년도가 부족합니다.</p>
                    </GameCard>
                </div>
            </Shell>
        );
    }

    if (!data) return null;

    const { income, outcome, policy, warSettingCnt } = data;
    // 편집 권한 게이트 — 레거시 PageNationStratFinan.vue 의 `v-if="editable"` 와 동일.
    // editable = (officerLevel>=5 || permission==4) (game-api DTO에서 서버측 산정).
    // 권한이 없으면 설정 버튼 자체를 렌더하지 않는다(레거시와 동일하게 read-only 표시).
    const editable = data.editable;
    const noticeTooLong = countHtmlCodePoints(noticeDraft) > NOTICE_MAX_LENGTH;
    const scoutTooLong = countHtmlCodePoints(scoutDraft) > SCOUT_MAX_LENGTH;

    // Computed budget figures — byte-for-byte the legacy Vue computed() chain
    // (incomeGoldCity = income.gold.city * rate / 100, etc).
    //
    // income/outcome 는 game-api 가 rate=100 LIVE 산정으로 내려준다(NationFinanceController, IncomeTick
    // 패러티 재사용). policy.rate/bill 은 nation.meta 에서 읽으며 시드가 rate=15/bill=100 을 채운다
    // (ScenarioImporter — PHP Scenario/Nation.php 패러티). 셋 중 하나라도 부재면(구 game-api 이미지 등)
    // 날조 대신 수입 행을 '-' 로 표시하는 방어 가드는 유지한다.
    const hasIncome = income != null && outcome != null && policy.rate != null && policy.bill != null;

    const incomeGoldCity = hasIncome ? (income!.gold.city * policy.rate!) / 100 : 0;
    const incomeGold = hasIncome ? incomeGoldCity + income!.gold.war : 0;
    const incomeRiceCity = hasIncome ? (income!.rice.city * policy.rate!) / 100 : 0;
    const incomeRiceWall = hasIncome ? (income!.rice.wall * policy.rate!) / 100 : 0;
    const incomeRice = hasIncome ? incomeRiceCity + incomeRiceWall : 0;
    const outcomeByBill = hasIncome ? (outcome! * policy.bill!) / 100 : 0;

    const goldBudget = data.gold + incomeGold - outcomeByBill;
    const goldDelta = incomeGold - outcomeByBill;
    const riceBudget = data.rice + incomeRice - outcomeByBill;
    const riceDelta = incomeRice - outcomeByBill;

    // 자금 예산 — labels verbatim from legacy template (현 재 / 단기수입 / 세 금 / 수입/지출 / 국고 예산).
    // income/rate 부재(구 game-api 이미지) 시에만 수입 행 '-' 표시(날조 금지).
    const goldRows: (string | number | React.ReactNode)[][] = [
        ['현 재', formatNumber(data.gold)],
        ['단기수입', hasIncome ? formatNumber(income!.gold.war) : '-'],
        ['세 금', hasIncome ? formatNumber(floor(incomeGoldCity)) : '-'],
        ['수입/지출', hasIncome ? `+${formatNumber(floor(incomeGold))} / ${formatNumber(floor(-outcomeByBill))}` : '-'],
        [
            '국고 예산',
            hasIncome
                ? `${formatNumber(floor(goldBudget))} (${incomeGold >= outcomeByBill ? '+' : ''}${formatNumber(floor(goldDelta))})`
                : formatNumber(data.gold),
        ],
    ];

    // 군량 예산 — labels verbatim (현 재 / 둔전수입 / 세 금 / 수입/지출 / 국고 예산).
    // income/rate 부재(구 game-api 이미지) 시에만 수입 행 '-' 표시(날조 금지).
    const riceRows: (string | number | React.ReactNode)[][] = [
        ['현 재', formatNumber(data.rice)],
        ['둔전수입', hasIncome ? formatNumber(floor(incomeRiceWall)) : '-'],
        ['세 금', hasIncome ? formatNumber(floor(incomeRiceCity)) : '-'],
        ['수입/지출', hasIncome ? `+${formatNumber(floor(incomeRice))} / ${formatNumber(floor(-outcomeByBill))}` : '-'],
        [
            '국고 예산',
            hasIncome
                ? `${formatNumber(floor(riceBudget))} (${incomeRice >= outcomeByBill ? '+' : ''}${formatNumber(floor(riceDelta))})`
                : formatNumber(data.rice),
        ],
    ];

    return (
        <Shell>
            <div className="page-content">
                <PageHead title="내무부" />

                {/* 외교관계 — legacy PageNationStratFinan.vue:4-46. nationsList(getAllNationStaticInfo +
                    cityCnt + diplomacy)를 표로. game-api 가 미배출(구 이미지)이면 미렌더(날조 금지). */}
                {data.nationsList && data.nationsList.length > 0 && (
                    <>
                        <SectionHeader as="h2" title="외교관계" />
                        <GameCard>
                            <div style={{ overflowX: 'auto' }}>
                                <table className="game-table" style={{ width: '100%' }}>
                                    <thead>
                                        <tr>
                                            <th>국가명</th>
                                            <th>국력</th>
                                            <th>장수</th>
                                            <th>속령</th>
                                            <th>상태</th>
                                            <th>기간</th>
                                            <th>종료 시점</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {data.nationsList.map((n) => {
                                            const isSelf = n.nation === data.nationId;
                                            const term = n.diplomacy.term ?? 0;
                                            const [endYear, endMonth] = parseYearMonth(
                                                joinYearMonth(data.year, data.month) + term,
                                            );
                                            const st = diplomacyStateText(n.diplomacy.state);
                                            return (
                                                <tr key={n.nation}>
                                                    <td style={{ color: n.color || undefined }}>{n.name}</td>
                                                    <td>{formatNumber(n.power)}</td>
                                                    <td>{formatNumber(n.gennum)}</td>
                                                    <td>{formatNumber(n.cityCnt)}</td>
                                                    {isSelf ? (
                                                        // 자국 행은 외교 상태/기간/종료시점을 '-'(legacy v-if 자국 분기).
                                                        <td colSpan={3} style={{ textAlign: 'center' }}>-</td>
                                                    ) : (
                                                        <>
                                                            <td style={{ color: st.color }}>{st.name}</td>
                                                            <td>{term === 0 ? '-' : `${term}개월`}</td>
                                                            <td>{term === 0 ? '-' : `${endYear}년 ${endMonth}월`}</td>
                                                        </>
                                                    )}
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            </div>
                        </GameCard>
                    </>
                )}

                <SectionHeader as="h2" title="국가 방침 &amp; 임관 권유 메시지" />

                <GameCard>
                    <SectionHeader as="h2" title="국가 방침" />
                    {editingNotice ? (
                        <RichTextEditor
                            ariaLabel="국가 방침"
                            disabled={savingMessages.notice}
                            maxTextLength={NOTICE_MAX_LENGTH}
                            onChange={setNoticeDraft}
                            value={noticeDraft}
                        />
                    ) : (
                        <div style={{ minHeight: 80, whiteSpace: 'pre-wrap' }}>
                            <SafeHtml html={data.nationMsg || '등록된 국가 방침이 없습니다.'} />
                        </div>
                    )}
                    {editable && (editingNotice ? (
                        <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
                            <button disabled={savingMessages.notice || noticeTooLong} onClick={() => void saveMessage('notice')}>저장</button>
                            <button disabled={savingMessages.notice} onClick={() => { setNoticeDraft(data.nationMsg ?? ''); setEditingNotice(false); }}>취소</button>
                        </div>
                    ) : (
                        <button onClick={() => { setNoticeDraft(data.nationMsg ?? ''); setEditingNotice(true); }}>국가방침 수정</button>
                    ))}
                </GameCard>

                <GameCard>
                    <SectionHeader as="h2" title="임관 권유" />
                    <p className="text-muted" style={{ marginTop: 0 }}>870px x 200px를 넘어서는 내용은 표시되지 않습니다.</p>
                    {editingScout ? (
                        <RichTextEditor
                            ariaLabel="임관 권유문"
                            disabled={savingMessages.scout}
                            maxTextLength={SCOUT_MAX_LENGTH}
                            onChange={setScoutDraft}
                            value={scoutDraft}
                        />
                    ) : (
                        <div style={{ minHeight: 80, whiteSpace: 'pre-wrap' }}>
                            <SafeHtml html={data.scoutMsg || '등록된 임관 권유문이 없습니다.'} />
                        </div>
                    )}
                    {editable && (editingScout ? (
                        <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
                            <button disabled={savingMessages.scout || scoutTooLong} onClick={() => void saveMessage('scout')}>저장</button>
                            <button disabled={savingMessages.scout} onClick={() => { setScoutDraft(data.scoutMsg ?? ''); setEditingScout(false); }}>취소</button>
                        </div>
                    ) : (
                        <button onClick={() => { setScoutDraft(data.scoutMsg ?? ''); setEditingScout(true); }}>임관 권유문 수정</button>
                    ))}
                </GameCard>

                {/* 예산&정책 */}
                <SectionHeader as="h2" title="예산&amp;정책" />

                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
                        gap: 'var(--space-md)',
                    }}
                >
                    <GameCard>
                        <SectionHeader as="h2" title="자금 예산" />
                        <GameTable headers={['항목', '금']} rows={goldRows} />
                    </GameCard>

                    <GameCard>
                        <SectionHeader as="h2" title="군량 예산" />
                        <GameTable headers={['항목', '쌀']} rows={riceRows} />
                    </GameCard>
                </div>

                {/* 정책 (F4 C2: 설정 버튼 → CommandModal). The setter buttons launch the intake commands. */}
                <SectionHeader as="h2" title="정책" />
                <GameCard>
                    <div className="stat-grid">
                        <div className="stat-item">
                            <span className="stat-label">세율 (5 ~ 30%)</span>
                            <span className="stat-value">
                                {/* policy.rate: meta 미기재(P1-077) → null → '-' */}
                                {policy.rate != null ? `${policy.rate}%` : '-'}{' '}
                                {editable && (
                                    <button onClick={() => setFinanceModal({ command: 'setRate', label: '세율 설정', argType: 'amount', amountMin: 5, amountMax: 30 })}>설정</button>
                                )}
                            </span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">지급률 (20 ~ 200%)</span>
                            <span className="stat-value">
                                {/* policy.bill: meta 미기재(P1-077) → null → '-' */}
                                {policy.bill != null ? `${policy.bill}%` : '-'}{' '}
                                {editable && (
                                    <button onClick={() => setFinanceModal({ command: 'setBill', label: '지급률 설정', argType: 'amount', amountMin: 20, amountMax: 200 })}>설정</button>
                                )}
                            </span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">기밀 권한 (1 ~ 99년)</span>
                            <span className="stat-value">
                                {/* policy.secretLimit: meta 미기재(P1-077) → null → '-' */}
                                {policy.secretLimit != null ? `${policy.secretLimit}년` : '-'}{' '}
                                {editable && (
                                    <button onClick={() => setFinanceModal({ command: 'setSecretLimit', label: '기밀 제한 설정', argType: 'amount', amountMin: 1, amountMax: 99 })}>설정</button>
                                )}
                            </span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">전쟁 금지 설정</span>
                            <span className="stat-value">
                                {/* warSettingCnt.remain: nation_env KV 미배선(P0-53) → null → '-' */}
                                {warSettingCnt.remain != null ? warSettingCnt.remain : '-'} 회(월 +{warSettingCnt.inc}회, 최대{warSettingCnt.max}회)
                            </span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">전쟁 금지</span>
                            <span className="stat-value">
                                <StatusBadge variant={policy.blockWar ? 'crimson' : 'muted'}>
                                    {policy.blockWar ? '설정' : '해제'}
                                </StatusBadge>{' '}
                                {editable && (
                                    <button onClick={() => setFinanceModal({ command: 'setBlockWar', label: policy.blockWar ? '전쟁 금지 해제' : '전쟁 금지 설정', argType: null, extraArgs: { value: !policy.blockWar } })}>
                                        {policy.blockWar ? '해제' : '설정'}
                                    </button>
                                )}
                            </span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">임관 금지</span>
                            <span className="stat-value">
                                <StatusBadge variant={policy.blockScout ? 'crimson' : 'muted'}>
                                    {policy.blockScout ? '설정' : '해제'}
                                </StatusBadge>{' '}
                                {editable && (
                                    <button onClick={() => setFinanceModal({ command: 'setBlockScout', label: policy.blockScout ? '임관 금지 해제' : '임관 금지 설정', argType: null, extraArgs: { value: !policy.blockScout } })}>
                                        {policy.blockScout ? '해제' : '설정'}
                                    </button>
                                )}
                            </span>
                        </div>
                    </div>
                </GameCard>
            </div>

            {financeModal && generalId != null && (
                <CommandModal
                    onClose={() => setFinanceModal(null)}
                    onToast={(msg) => setToast(msg)}
                    generalId={generalId}
                    nationId={nationId ?? undefined}
                    pinnedCommand={financeModal.command}
                    pinnedLabel={financeModal.label}
                    pinnedArgType={financeModal.argType}
                    amountMin={financeModal.amountMin}
                    amountMax={financeModal.amountMax}
                    extraArgs={financeModal.extraArgs}
                    onReserved={() => void fetchData(true)}
                />
            )}
            {toast && (
                <div role="status" style={{ position: 'fixed', bottom: 16, left: '50%', transform: 'translateX(-50%)', background: 'var(--surface-raised)', padding: '8px 16px', borderRadius: 8 }} onClick={() => setToast(null)}>
                    {toast}
                </div>
            )}
        </Shell>
    );
}
