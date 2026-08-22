'use client';

// 베팅장(b_betting) — frozen historical UI reference: hwe/ts (ADR-LITE-042; not current product authority).
// type='tournament' 필터: legacy GetBettingList req 화이트리스트 'tournament' 값.
// read: game-api `GET /api/bettings?type=tournament`(D4) {result, bettingList(Map<id,item>), year, month}.
//   상세는 BettingDetail 컴포넌트가 `GET /api/bettings/{id}/detail`(D5, 인증) 소비.
// write: 베팅 제출은 BettingDetail이 api.commands.placeBet(wire 기존)으로 수행.
// ↔ 국가 베팅장(v_nationBetting): /game/nation-betting (type='bettingNation').

import { useCallback, useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import BettingDetail from '../../../components/betting/BettingDetail';
import { api } from '../../../lib/api';
import { useFrontInfo } from '../../../hooks/useFrontInfo';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';

// ── D4 와이어 타입(game-api BettingDto.kt와 동형) ─────────────────────────────────────────
interface BettingListItem {
    id: number;
    type: string;
    name: string;
    finished: boolean;
    selectCnt: number;
    isExclusive: boolean | null;
    reqInheritancePoint: boolean;
    openYearMonth: number;
    closeYearMonth: number;
    winner: number[] | null;
    totalAmount: number;
}

interface BettingListResponse {
    result: boolean;
    bettingList: Record<number, BettingListItem>;
    year: number;
    month: number;
}

export default function BettingPage() {
    const { frontInfo } = useFrontInfo();
    const generalId = frontInfo?.general.generalId ?? null;

    const [list, setList] = useState<BettingListItem[]>([]);
    const [selectedId, setSelectedId] = useState<number | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');

    function showToast(msg: string) {
        setToast(msg);
        setTimeout(() => setToast(''), 3000);
    }

    const fetchData = useCallback(async () => {
        setLoading(true);
        try {
            const data = await api.betting<BettingListResponse>('tournament');
            // bettingList는 Map<id,item> — 값 배열로 펴서 역순 렌더.
            // legacy b_betting.php / type='tournament'. PageNationBetting.vue:9 패러티(역순).
            const items = Object.values(data.bettingList ?? {}).reverse();
            setList(items);
            // 진행 중 첫 베팅을 자동 선택(상세 자동 로드).
            setSelectedId(prev => prev ?? items.find(b => !b.finished)?.id ?? items[0]?.id ?? null);
            setError('');
        } catch {
            setError('베팅 목록을 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        void fetchData();
    }, [fetchData]);

    useTurnRefresh(() => { void fetchData(); });

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>베팅장</h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <button onClick={() => void fetchData()}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            {/* 베팅 목록 — 클릭 시 상세(BettingDetail) 전환. */}
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-sm)', marginBottom: 'var(--space-md)' }}>
                {list.length === 0 && !loading && (
                    <p style={{ color: 'var(--text-muted)' }}>진행 중인 베팅이 없습니다.</p>
                )}
                {list.map(b => (
                    <GameCard
                        key={b.id}
                        style={{
                            cursor: 'pointer',
                            border: selectedId === b.id ? '1px solid var(--gold)' : undefined,
                        }}
                    >
                        <div onClick={() => setSelectedId(b.id)} style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                            <StatusBadge variant={b.finished ? 'muted' : 'gold'}>
                                {b.finished ? '종료' : '진행 중'}
                            </StatusBadge>
                            <span style={{ fontWeight: 500 }}>{b.name}</span>
                            <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>
                                총액 {b.totalAmount.toLocaleString()}
                            </span>
                        </div>
                    </GameCard>
                ))}
            </div>

            {/* 상세 — D5(인증) 소비. 베팅 제출은 컴포넌트 내부 placeBet. */}
            {selectedId != null && (
                <BettingDetail bettingId={selectedId} generalId={generalId} onToast={showToast} />
            )}
        </Shell>
    );
}
