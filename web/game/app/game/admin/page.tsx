'use client';

import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import { StatusView } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import GameAdminScreen, { ADMIN_SCREENS, adminTabLabel, adminTabOf } from '@/components/admin/GameAdminScreen';
import { useAuthOptional } from '@/lib/auth-context';
import { useGameSession } from '@/lib/campaign-session';

/**
 * 게임 관리(P-A03) — 보드 V31K5GameAdmin · GameAdminNations · MGameAdmin. 운영자만 연다(레일 아래 「관리」).
 * 운영자는 장수가 없을 수 있어 셸의 「장수 필요」 막음을 쓰지 않는다. 탭 다섯은 셸 머리 탭(`?tab=`)이다.
 */
function AdminPage() {
    const auth = useAuthOptional();
    const params = useSearchParams();
    const { serverId } = useGameSession();
    const tab = adminTabOf(params?.get('tab') ?? null);
    const isAdmin = auth?.user?.role === 'ADMIN';
    const title = serverId ? `게임 관리 · ${serverId}` : '게임 관리';

    let body;
    if (auth?.loading) body = <StatusView kind="loading" rows={3} />;
    else if (!isAdmin) body = <StatusView kind="denied" title="관리자 권한이 필요합니다." howTo="운영자 계정으로 로그인하면 이 화면을 쓸 수 있습니다." />;
    else body = <GameAdminScreen tab={tab} />;

    return (
        <GameShell title={title} requiresHwiha={false} screens={isAdmin ? ADMIN_SCREENS : undefined} screenOn={adminTabLabel(tab)}>
            {body}
        </GameShell>
    );
}

export default function GameAdminPage() {
    return (
        <Suspense fallback={null}>
            <AdminPage />
        </Suspense>
    );
}
