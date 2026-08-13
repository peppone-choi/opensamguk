'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import React from 'react';
import BoardPostForm from '@/components/board/BoardPostForm';
import BoardShell from '@/components/board/BoardShell';
import { createBoardPost, type BoardCategory } from '@/lib/board';
import { useAuth } from '@/lib/auth-context';

export default function BoardWritePage(): React.ReactElement {
  const router = useRouter();
  const { user, loading } = useAuth();

  async function submit(input: { readonly category: BoardCategory; readonly title: string; readonly content: string }): Promise<void> {
    const post = await createBoardPost(input);
    router.push(`/board/posts/${post.id}`);
  }

  if (loading) return <BoardShell><div className="board-loading" role="status">로그인 상태를 확인하는 중…</div></BoardShell>;
  if (!user) {
    return (
      <BoardShell>
        <section className="board-empty">
          <p>글을 작성하려면 로그인이 필요합니다.</p>
          <Link className="btn-primary" href="/login?next=%2Fboard%2Fwrite">로그인</Link>
        </section>
      </BoardShell>
    );
  }

  return (
    <BoardShell>
      <section className="board-heading compact">
        <div>
          <p className="board-eyebrow">NEW POST</p>
          <h1>게시글 작성</h1>
          <p>굵게, 기울임, 취소선 등 기본 서식을 사용할 수 있습니다.</p>
        </div>
        <Link className="btn-ghost" href="/board">취소</Link>
      </section>
      <BoardPostForm allowNotice={user.role === 'ADMIN'} onSubmit={submit} />
    </BoardShell>
  );
}
