'use client';

import { useState } from 'react';
import GameShell from '@/components/GameShell';
import { StratagemDeck } from '@/components/stratagem/StratagemDeck';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { toHandView } from '@/lib/stratagem/hand';

/**
 * 계책 덱(P-S01) — K6 설계서 §3.2, 보드 V31K6Hand · MHand. 2026-09-23 사용자 결정으로 이름은 「계책 덱」.
 *
 * 손패는 소유 장수만 본다(`GET /api/commands/stratagem-hand`). 공급은 개인 턴의 드로우 단계에서 일어나고, 조회는 카드를
 * 만들지 않는다. 카드 쓰기 · 걸기(`stratagem.play`)는 원장 PLANNED라 단추가 「준비 중」이고, 비용 · 사거리는 서버가 주기 전엔
 * 그리지 않는다(계약판 K6-10). 카드 그림은 opensamguk-images 정본 export만(public/stratagem-cards).
 */
export default function StratagemPage() {
    const [seq, setSeq] = useState(0);
    const read = useCampaignRead((id, signal) => api.stratagemHand(id, signal), [seq]);

    return (
        <GameShell title="계책 덱">
            <StratagemDeck hand={toHandView(read)} onRetry={() => setSeq((n) => n + 1)} />
        </GameShell>
    );
}
