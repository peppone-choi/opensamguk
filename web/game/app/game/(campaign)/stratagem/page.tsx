'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useState } from 'react';
import GameShell from '@/components/GameShell';
import { StratagemDeck } from '@/components/stratagem/StratagemDeck';
import { StratagemPlaySheet } from '@/components/stratagem/StratagemPlaySheet';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { parseCardParam, toHandView } from '@/lib/stratagem/hand';

/**
 * 계책 덱(P-S01) — K6 설계서 §3.2, 보드 V31K6Hand · MHand. 2026-09-23 사용자 결정으로 이름은 「계책 덱」.
 * 계책 쓰기 · 걸기(P-S02) — 설계서 §3.3, 보드 V31K6Stratagem · MStratagem. 덱의 「쓰기 · 걸기」가 `?card=<instanceId>`로 시트를 연다.
 *
 * 손패는 소유 장수만 본다(`GET /api/commands/stratagem-hand`). 공급은 개인 턴의 드로우 단계에서 일어나고, 조회는 카드를
 * 만들지 않는다. 카드 쓰기 · 걸기(`stratagem.play`)는 원장 PLANNED라 시트 아래 단추가 「준비 중」이고, 대상 후보 · 비용 · 사거리는
 * 서버가 주기 전엔 그리지 않는다(계약판 K6-10). 카드 그림은 opensamguk-images 정본 export만(public/stratagem-cards).
 * 시트는 주소에 둔다 — 닫기 · Esc · 기기 뒤로 가기가 같은 곳(덱)으로 돌아온다. 시트 안에서 카드를 바꾸면 replace(기록을 늘리지 않는다).
 */
export default function StratagemPage() {
    const [seq, setSeq] = useState(0);
    const read = useCampaignRead((id, signal) => api.stratagemHand(id, signal), [seq]);
    const router = useRouter();
    const pathname = usePathname() ?? '';
    const search = useSearchParams();
    const cardParam = search?.get('card') ?? null;
    const hand = toHandView(read);

    const withCard = useCallback((instanceId: number | null) => {
        const query = new URLSearchParams(search?.toString() ?? '');
        if (instanceId == null) query.delete('card');
        else query.set('card', String(instanceId));
        const rest = query.toString();
        return rest ? `${pathname}?${rest}` : pathname;
    }, [pathname, search]);

    const retry = useCallback(() => setSeq((n) => n + 1), []);

    return (
        <GameShell title="계책 덱">
            <StratagemDeck hand={hand} onRetry={retry} onOpen={(id) => router.push(withCard(id), { scroll: false })} />
            {cardParam != null ? (
                <StratagemPlaySheet
                    hand={hand}
                    cardId={parseCardParam(cardParam)}
                    onPick={(id) => router.replace(withCard(id), { scroll: false })}
                    onClose={() => router.push(withCard(null), { scroll: false })}
                    onRetry={retry}
                />
            ) : null}
        </GameShell>
    );
}
