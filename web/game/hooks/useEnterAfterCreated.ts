'use client';

// 장수를 만든 뒤(결과 CREATED) 다음 화면으로 — 세션(front-info)에 새 장수가 보인 다음에 간다(P-E02, #1329 리뷰).
// 세션은 /game 레이아웃(GameFrame)에 남아 create → join 사이에 그대로다. refresh() 는 loading 을 다시 켜지 않으므로
// 바로 가면 출사(join)가 옛 front-info(장수 없음)를 loading=false 로 받아 입구로 되돌린다. 그래서
//  ① front-info 를 직접 다시 물어 장수가 생긴 것을 본 뒤 ② 세션을 다시 읽히고 ③ 세션에도 장수가 보이면 간다.
// 몇 번 물어도 장수가 안 보이면 'late' — 화면은 「아직 반영되지 않음 · 입구에서 이어 보기」를 낸다(넘어간 척하지 않는다).

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';
import { useGameSession } from '@/lib/campaign-session';
import { RESULT_POLL_MS } from './useCreationRequest';

/** front-info 를 다시 묻는 횟수(간격 RESULT_POLL_MS) — 결과는 이미 CREATED 라 짧게 본다. */
export const ENTER_TRIES = 5;

export type EnterPhase = 'idle' | 'checking' | 'late';

export function useEnterAfterCreated(created: boolean, href: string): EnterPhase {
    const session = useGameSession();
    const router = useRouter();
    const [phase, setPhase] = useState<EnterPhase>('idle');
    const [confirmed, setConfirmed] = useState(false);
    const { refresh } = session;

    useEffect(() => {
        if (!created) return undefined;
        let alive = true;
        setPhase('checking');
        void (async () => {
            for (let tries = 0; tries < ENTER_TRIES; tries += 1) {
                if (tries > 0) await new Promise((r) => setTimeout(r, RESULT_POLL_MS));
                if (!alive) return;
                const info = await api.frontInfo().catch(() => null);
                if (!alive) return;
                if (info?.general.hasGeneral) {
                    setConfirmed(true);
                    refresh();
                    return;
                }
            }
            if (alive) setPhase('late');
        })();
        return () => { alive = false; };
    }, [created, refresh]);

    useEffect(() => {
        if (confirmed && session.generalId !== null) router.push(href);
    }, [confirmed, session.generalId, href, router]);

    return phase;
}
