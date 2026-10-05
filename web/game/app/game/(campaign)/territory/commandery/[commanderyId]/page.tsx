'use client';

import { useParams, useSearchParams } from 'next/navigation';
import GameShell from '@/components/GameShell';
import { CommanderyScreen } from '@/components/commandery/CommanderyScreen';
import { useGameSession } from '@/lib/campaign-session';
import { commanderyHrefs } from '../commandery-hrefs';

/** 군 id 조각 — 흐름 주소와 같은 글자만(영숫자 · _ · -, 64자). 아니면 군 없이(우리 세력 전체). */
const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

/**
 * 영지 › 군 내정 현황(P-T03) — `/game/<서버>/territory/commandery/<군 id>`(설계서 §3 P-T03). `?scope=nation`이면 우리 세력 전체로 연다.
 * 현 목록(K4-11 첫 판)에 방침 · 공사 · 창고를 잇는다. 7지표 · 민심 위험 · 적 군단은 서버 보강 전까지 준비 중.
 */
export default function CommanderyPage() {
    const { serverId } = useGameSession();
    const params = useParams<{ commanderyId: string }>();
    const raw = decodeURIComponent(params?.commanderyId ?? '');
    const scope = useSearchParams()?.get('scope') === 'nation' ? 'NATION' : 'COMMANDERY';
    return (
        <GameShell title="영지">
            <CommanderyScreen commanderyId={ID_RE.test(raw) ? raw : null} initialScope={scope} hrefs={commanderyHrefs(serverId)} />
        </GameShell>
    );
}
