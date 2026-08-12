import Link from 'next/link';
import React, { type ReactNode } from 'react';

export default function BoardShell({ children }: { readonly children: ReactNode }) {
  return (
    <div className="board-shell">
      <header className="board-topbar">
        <Link href="/lobby" className="board-brand">오픈삼국</Link>
        <nav aria-label="커뮤니티 탐색" className="board-nav">
          <Link href="/board">커뮤니티</Link>
          <Link href="/lobby">로비로 돌아가기</Link>
        </nav>
      </header>
      <main className="board-main fade-in">{children}</main>
    </div>
  );
}
