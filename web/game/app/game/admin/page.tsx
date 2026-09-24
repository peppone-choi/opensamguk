'use client';

import Shell from '@/components/Shell';
import ServerStatusPanel from '@/components/admin/ServerStatusPanel';
import { useAuthOptional } from '@/lib/auth-context';

export default function GameAdminPage() {
  const auth = useAuthOptional();
  return (
    <Shell>
      <div className="admin-hub">
        <div className="os-section-header admin-hub__head">
          <span className="os-section-header__bar" aria-hidden="true" />
          <h1 className="os-section-header__title">게임 관리</h1>
        </div>
        {auth?.user?.role === 'ADMIN' ? (
          <ServerStatusPanel />
        ) : (
          <p role="alert">관리자 권한이 필요합니다.</p>
        )}
      </div>
    </Shell>
  );
}
