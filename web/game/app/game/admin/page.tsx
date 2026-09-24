'use client';

import { Suspense } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Shell from '@/components/Shell';
import { useAuthOptional } from '@/lib/auth-context';
import GameSettingsPanel from '@/components/admin/GameSettingsPanel';
import GeneralModerationPanel from '@/components/admin/GeneralModerationPanel';
import NationStatsPanel from '@/components/admin/NationStatsPanel';
import GeneralLogPanel from '@/components/admin/GeneralLogPanel';
import DiplomacyAllPanel from '@/components/admin/DiplomacyAllPanel';
import ServerStatusPanel from '@/components/admin/ServerStatusPanel';

const ADMIN_TABS = [
  { key: 'settings', label: '게임 설정' },
  { key: 'generals', label: '장수 조치' },
  { key: 'stats', label: '일제정보' },
  { key: 'logs', label: '로그정보' },
  { key: 'diplomacy', label: '외교정보' },
  { key: 'status', label: '서버 상태' },
] as const;
type TabKey = (typeof ADMIN_TABS)[number]['key'];

function AdminHub() {
  const params = useSearchParams();
  const router = useRouter();
  const raw = params.get('tab');
  const tab: TabKey = ADMIN_TABS.some((item) => item.key === raw) ? (raw as TabKey) : 'settings';
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
        <span className="os-section-header__sub">이 서버의 월드 설정·장수 조치·통계·로그·외교·상태</span>
      </div>
      <div className="os-pill-tabs admin-hub__tabs" role="tablist" aria-label="게임 관리 탭">
        {ADMIN_TABS.map((item) => (
          <button key={item.key} type="button" role="tab" aria-selected={tab === item.key} className={tab === item.key ? 'os-pill-tabs__on' : undefined} onClick={() => select(item.key)}>
            {item.label}
          </button>
        ))}
      </div>
      <div className="admin-hub__panel" role="tabpanel">
        {tab === 'settings' && <GameSettingsPanel />}
        {tab === 'generals' && <GeneralModerationPanel />}
        {tab === 'stats' && <NationStatsPanel />}
        {tab === 'logs' && <GeneralLogPanel />}
        {tab === 'diplomacy' && <DiplomacyAllPanel />}
        {tab === 'status' && <ServerStatusPanel />}
      </div>
    </div>
  );
}

export default function GameAdminPage() {
  const auth = useAuthOptional();
  return (
    <Shell>
      {auth?.loading ? <p>인증 확인 중...</p> : auth?.user?.role === 'ADMIN' ? (
        <Suspense fallback={null}><AdminHub /></Suspense>
      ) : (
        <p role="alert">관리자 권한이 필요합니다.</p>
      )}
    </Shell>
  );
}
