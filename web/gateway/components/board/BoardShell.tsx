import type { ReactNode } from 'react';
import Topbar from '@/components/Topbar';

/** 커뮤니티 껍데기 — 게이트웨이 공통 상단바(로비·커뮤니티·계정·관리) 위에 본문. 서버 밖·계정 단위 공간(ADR-LITE-049 13). */
export default function BoardShell({ children }: { readonly children: ReactNode }) {
  return (
    <div className="board-shell">
      <Topbar current="board" />
      <main className="board-main fade-in">{children}</main>
    </div>
  );
}
