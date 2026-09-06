'use client';

// 게임 관리 허브(ADR-LITE-049 Phase 3) — 옛 admin1/2/5/7/8·tournament-admin 을 7탭으로 모은다. 라벨·정렬 옵션은 BE verbatim.
// 옛 경로는 그대로 열리며 같은 패널을 그린다. 탭은 ?tab= 으로 공유 가능.
import { Suspense } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Shell from '@/components/Shell';
import GameSettingsPanel from '@/components/admin/GameSettingsPanel';
import GeneralModerationPanel from '@/components/admin/GeneralModerationPanel';
import NationStatsPanel from '@/components/admin/NationStatsPanel';
import GeneralLogPanel from '@/components/admin/GeneralLogPanel';
import DiplomacyAllPanel from '@/components/admin/DiplomacyAllPanel';
import TournamentAdminPanel from '@/components/admin/TournamentAdminPanel';
import ServerStatusPanel from '@/components/admin/ServerStatusPanel';

export const ADMIN_TABS = [
    { key: 'settings', label: '게임 설정', legacy: 'admin1' },
    { key: 'generals', label: '장수 조치', legacy: 'admin2' },
    { key: 'stats', label: '일제정보', legacy: 'admin5' },
    { key: 'logs', label: '로그정보', legacy: 'admin7' },
    { key: 'diplomacy', label: '외교정보', legacy: 'admin8' },
    { key: 'tournament', label: '토너먼트 관리', legacy: 'tournament-admin' },
    { key: 'status', label: '서버 상태', legacy: null },
] as const;
type TabKey = (typeof ADMIN_TABS)[number]['key'];

function AdminHub() {
    const params = useSearchParams();
    const router = useRouter();
    const raw = params.get('tab');
    const tab: TabKey = ADMIN_TABS.some((t) => t.key === raw) ? (raw as TabKey) : 'settings';
    const select = (key: TabKey) => {
        const next = new URLSearchParams(params.toString());
        next.set('tab', key);
        router.replace(`?${next.toString()}`);
    };
    return (
        <div className="admin-hub">
            <div className="os-section-header admin-hub__head">
                <span className="os-section-header__bar" aria-hidden="true" />
                <h1 className="os-section-header__title">게임 관리</h1>
                <span className="os-section-header__sub">이 서버의 월드 설정·장수 조치·통계·로그·외교·토너먼트</span>
            </div>
            <div className="os-pill-tabs admin-hub__tabs" role="tablist" aria-label="게임 관리 탭">
                {ADMIN_TABS.map((t) => (
                    <button key={t.key} type="button" role="tab" aria-selected={tab === t.key} className={tab === t.key ? 'os-pill-tabs__on' : undefined} onClick={() => select(t.key)}>
                        {t.label}
                    </button>
                ))}
            </div>
            <div className="admin-hub__panel" role="tabpanel">
                {tab === 'settings' && <GameSettingsPanel />}
                {tab === 'generals' && <GeneralModerationPanel />}
                {tab === 'stats' && <NationStatsPanel />}
                {tab === 'logs' && <GeneralLogPanel />}
                {tab === 'diplomacy' && <DiplomacyAllPanel />}
                {tab === 'tournament' && <TournamentAdminPanel />}
                {tab === 'status' && <ServerStatusPanel />}
            </div>
        </div>
    );
}

export default function GameAdminPage() {
    return (
        <Shell>
            <Suspense fallback={null}>
                <AdminHub />
            </Suspense>
        </Shell>
    );
}
