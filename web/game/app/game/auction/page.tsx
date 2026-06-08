'use client';

// v_auction(경매장) — legacy hwe/PageAuction.vue 충실 포팅(FE grand truth, PHP가 이김).
// '금/쌀 ↔ 유니크' 모드 토글 셸이 C1 컴포넌트 2종(AuctionResource / AuctionUniqueItem)을 조립한다.
// read/write는 각 컴포넌트가 game-api(D1-D3) + api.commands(wire 기존)로 수행. read-only 페이지 셸.

import { useState } from 'react';
import Shell from '../../../components/Shell';
import AuctionResource from '../../../components/auction/AuctionResource';
import AuctionUniqueItem from '../../../components/auction/AuctionUniqueItem';
import { useFrontInfo } from '../../../hooks/useFrontInfo';

export default function AuctionPage() {
    const { frontInfo } = useFrontInfo();
    const generalId = frontInfo?.general.generalId ?? null;

    // legacy isResAuction(기본 true=금/쌀 거래장, false=유니크 경매장).
    const [isResAuction, setIsResAuction] = useState(true);
    const [toast, setToast] = useState<string>('');

    function showToast(msg: string) {
        setToast(msg);
        setTimeout(() => setToast(''), 3000);
    }

    return (
        <Shell>
            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-md)', marginBottom: 'var(--space-md)' }}>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700 }}>
                    {isResAuction ? '경매장' : '유니크 경매장'}
                </h1>
                <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
                    <button
                        aria-pressed={isResAuction}
                        style={{ fontWeight: isResAuction ? 700 : 400 }}
                        onClick={() => setIsResAuction(true)}
                    >금/쌀</button>
                    <button
                        aria-pressed={!isResAuction}
                        style={{ fontWeight: !isResAuction ? 700 : 400 }}
                        onClick={() => setIsResAuction(false)}
                    >유니크</button>
                </div>
            </div>

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            {isResAuction
                ? <AuctionResource generalId={generalId} onToast={showToast} />
                : <AuctionUniqueItem generalId={generalId} onToast={showToast} />}
        </Shell>
    );
}
