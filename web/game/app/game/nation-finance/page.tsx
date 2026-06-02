'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import GameTable from '../../../components/GameTable';
import StatusBadge from '../../../components/StatusBadge';
import CommandModal from '../../../components/CommandModal';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import type { FrontInfoResponse } from '../../../lib/types';
import type { NationFinanceResponse } from '../../../types/game';
import type { CommandArgType } from '../../../types/game';

// ── F4 Wave C2 (slice A) — 내무부 finance-setter launch descriptor ────────────────
// Each "설정" button pins the CommandModal to one intake command code (matches the C1
// betting/inherit pattern). The amount setters (세율/지급률/기밀) use the `amount` sub-form
// (min/max from the PHP Validator range); the boolean toggles (전쟁/임관 금지) + the
// notice/scout-msg edits pass their value via extraArgs (no-arg confirm).
interface FinanceModalSpec {
    command: string;
    label: string;
    argType: CommandArgType | null;
    amountMin?: number;
    amountMax?: number;
    extraArgs?: Record<string, unknown>;
}

// 내무부 (Nation strategy / finance) — READ-ONLY this wave.
// Mirrors legacy hwe/ts/PageNationStratFinan.vue:
//  - 예산&정책 budget tables (자금 예산 / 군량 예산) with verbatim labels + computed rows
//  - 정책(세율/지급률/기밀 권한/전쟁 금지 설정) read-only display
//  - 국가 방침 & 임관 권유 메시지 (nationMsg / scoutMsg) plaintext (TipTap deferred — spec OQ-3)
// Identity (nationId) resolved from api.frontInfo().general.nationId, then api.nationFinance(id).
// EMPTY-SAFE: a no-nation viewer (재야, nationId 0) renders an INFO empty state, never crashes.

// PHP truncate-toward-zero parity (Math.floor on non-negative budget figures, matching
// the Vue's Math.floor(...) over the same positive accumulations).
function floor(n: number): number {
    return Math.floor(n);
}

export default function NationFinancePage() {
    const [data, setData] = useState<NationFinanceResponse | null>(null);
    const [noNation, setNoNation] = useState(false);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    // identity for the CommandModal (own general/nation), + the open finance modal spec.
    const [generalId, setGeneralId] = useState<number | null>(null);
    const [nationId, setNationId] = useState<number | null>(null);
    const [financeModal, setFinanceModal] = useState<FinanceModalSpec | null>(null);
    const [toast, setToast] = useState<string | null>(null);
    // draft text for the notice / scout-message edits (passed via extraArgs.msg).
    const [noticeDraft, setNoticeDraft] = useState('');
    const [scoutDraft, setScoutDraft] = useState('');

    const fetchData = useCallback(async () => {
        setLoading(true);
        setError('');
        setNoNation(false);
        try {
            const fi: FrontInfoResponse = await api.frontInfo();
            const nid = fi.general.nationId;
            setGeneralId(fi.general.generalId);
            setNationId(nid);
            // 재야(무소속): nationId 0 → 내무부 없음.
            if (!nid) {
                setNoNation(true);
                setData(null);
                return;
            }
            const res = await api.nationFinance(nid);
            setData(res);
            setNoticeDraft(res.nationMsg ?? '');
            setScoutDraft(res.scoutMsg ?? '');
        } catch {
            setError('내무부 정보를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchData());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchData]);

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내무부</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내무부</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    if (noNation) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내무부</h1>
                    <GameCard>
                        <p className="text-muted">국가에 소속되어있지 않습니다.</p>
                    </GameCard>
                </div>
            </Shell>
        );
    }

    if (!data) return null;

    const { income, outcome, policy, warSettingCnt } = data;

    // Computed budget figures — byte-for-byte the legacy Vue computed() chain
    // (incomeGoldCity = income.gold.city * rate / 100, etc).
    const incomeGoldCity = (income.gold.city * policy.rate) / 100;
    const incomeGold = incomeGoldCity + income.gold.war;
    const incomeRiceCity = (income.rice.city * policy.rate) / 100;
    const incomeRiceWall = (income.rice.wall * policy.rate) / 100;
    const incomeRice = incomeRiceCity + incomeRiceWall;
    const outcomeByBill = (outcome * policy.bill) / 100;

    const goldBudget = data.gold + incomeGold - outcomeByBill;
    const goldDelta = incomeGold - outcomeByBill;
    const riceBudget = data.rice + incomeRice - outcomeByBill;
    const riceDelta = incomeRice - outcomeByBill;

    // 자금 예산 — labels verbatim from legacy template (현 재 / 단기수입 / 세 금 / 수입/지출 / 국고 예산).
    const goldRows: (string | number | React.ReactNode)[][] = [
        ['현 재', formatNumber(data.gold)],
        ['단기수입', formatNumber(income.gold.war)],
        ['세 금', formatNumber(floor(incomeGoldCity))],
        ['수입/지출', `+${formatNumber(floor(incomeGold))} / ${formatNumber(floor(-outcomeByBill))}`],
        [
            '국고 예산',
            `${formatNumber(floor(goldBudget))} (${incomeGold >= outcomeByBill ? '+' : ''}${formatNumber(floor(goldDelta))})`,
        ],
    ];

    // 군량 예산 — labels verbatim (현 재 / 둔전수입 / 세 금 / 수입/지출 / 국고 예산).
    const riceRows: (string | number | React.ReactNode)[][] = [
        ['현 재', formatNumber(data.rice)],
        ['둔전수입', formatNumber(floor(incomeRiceWall))],
        ['세 금', formatNumber(floor(incomeRiceCity))],
        ['수입/지출', `+${formatNumber(floor(incomeRice))} / ${formatNumber(floor(-outcomeByBill))}`],
        [
            '국고 예산',
            `${formatNumber(floor(riceBudget))} (${incomeRice >= outcomeByBill ? '+' : ''}${formatNumber(floor(riceDelta))})`,
        ],
    ];

    return (
        <Shell>
            <div className="page-content">
                <h1>내무부</h1>

                {/* 국가 방침 & 임관 권유 메시지 (plaintext display; TipTap rich editor deferred per spec OQ-3) */}
                <h2>국가 방침 &amp; 임관 권유 메시지</h2>

                <GameCard>
                    <div className="card-header">
                        <h2>국가 방침</h2>
                    </div>
                    <textarea
                        value={noticeDraft}
                        onChange={(e) => setNoticeDraft(e.target.value)}
                        maxLength={16384}
                        placeholder="등록된 국가 방침이 없습니다."
                        style={{ width: '100%', minHeight: 80, whiteSpace: 'pre-wrap' }}
                    />
                    <button onClick={() => setFinanceModal({ command: 'setNotice', label: '국가 방침 설정', argType: null, extraArgs: { msg: noticeDraft } })}>방침 설정</button>
                </GameCard>

                <GameCard>
                    <div className="card-header">
                        <h2>임관 권유</h2>
                    </div>
                    <p className="text-muted" style={{ marginTop: 0 }}>870px x 200px를 넘어서는 내용은 표시되지 않습니다.</p>
                    <textarea
                        value={scoutDraft}
                        onChange={(e) => setScoutDraft(e.target.value)}
                        maxLength={1000}
                        placeholder="등록된 임관 권유문이 없습니다."
                        style={{ width: '100%', minHeight: 80, whiteSpace: 'pre-wrap' }}
                    />
                    <button onClick={() => setFinanceModal({ command: 'setScoutMsg', label: '임관 권유문 설정', argType: null, extraArgs: { msg: scoutDraft } })}>권유문 설정</button>
                </GameCard>

                {/* 예산&정책 */}
                <h2>예산&amp;정책</h2>

                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
                        gap: 'var(--space-md)',
                    }}
                >
                    <GameCard>
                        <div className="card-header">
                            <h2>자금 예산</h2>
                        </div>
                        <GameTable headers={['항목', '금']} rows={goldRows} />
                    </GameCard>

                    <GameCard>
                        <div className="card-header">
                            <h2>군량 예산</h2>
                        </div>
                        <GameTable headers={['항목', '쌀']} rows={riceRows} />
                    </GameCard>
                </div>

                {/* 정책 (F4 C2: 설정 버튼 → CommandModal). The setter buttons launch the intake commands. */}
                <h2>정책</h2>
                <GameCard>
                    <div className="stat-grid">
                        <div className="stat-item">
                            <span className="stat-label">세율 (5 ~ 30%)</span>
                            <span className="stat-value">
                                {policy.rate}%{' '}
                                <button onClick={() => setFinanceModal({ command: 'setRate', label: '세율 설정', argType: 'amount', amountMin: 5, amountMax: 30 })}>설정</button>
                            </span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">지급률 (20 ~ 200%)</span>
                            <span className="stat-value">
                                {policy.bill}%{' '}
                                <button onClick={() => setFinanceModal({ command: 'setBill', label: '지급률 설정', argType: 'amount', amountMin: 20, amountMax: 200 })}>설정</button>
                            </span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">기밀 권한 (1 ~ 99년)</span>
                            <span className="stat-value">
                                {policy.secretLimit}년{' '}
                                <button onClick={() => setFinanceModal({ command: 'setSecretLimit', label: '기밀 제한 설정', argType: 'amount', amountMin: 1, amountMax: 99 })}>설정</button>
                            </span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">전쟁 금지 설정</span>
                            <span className="stat-value">
                                {warSettingCnt.remain} 회(월 +{warSettingCnt.inc}회, 최대{warSettingCnt.max}회)
                            </span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">전쟁 금지</span>
                            <span className="stat-value">
                                <StatusBadge variant={policy.blockWar ? 'crimson' : 'muted'}>
                                    {policy.blockWar ? '설정' : '해제'}
                                </StatusBadge>{' '}
                                <button onClick={() => setFinanceModal({ command: 'setBlockWar', label: policy.blockWar ? '전쟁 금지 해제' : '전쟁 금지 설정', argType: null, extraArgs: { value: !policy.blockWar } })}>
                                    {policy.blockWar ? '해제' : '설정'}
                                </button>
                            </span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">임관 금지</span>
                            <span className="stat-value">
                                <StatusBadge variant={policy.blockScout ? 'crimson' : 'muted'}>
                                    {policy.blockScout ? '설정' : '해제'}
                                </StatusBadge>{' '}
                                <button onClick={() => setFinanceModal({ command: 'setBlockScout', label: policy.blockScout ? '임관 금지 해제' : '임관 금지 설정', argType: null, extraArgs: { value: !policy.blockScout } })}>
                                    {policy.blockScout ? '해제' : '설정'}
                                </button>
                            </span>
                        </div>
                    </div>
                </GameCard>
            </div>

            {/* F4 C2 — finance-setter CommandModal (pinnedCommand + extraArgs, C1 pattern).
                The notice/scout edits (string `msg`) carry their textarea draft via extraArgs.msg and
                open as a no-arg confirm; the amount setters use the modal's amount sub-form. */}
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
                    onReserved={() => fetchData()}
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
