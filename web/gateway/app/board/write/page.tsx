import type { Metadata } from 'next';
import CommunityWrite from '@/components/community/CommunityWrite';

export const metadata: Metadata = {
    title: '게시글 작성 — 오픈삼국',
};

// P-G08 커뮤니티 글쓰기(설계서 §3.3). `?edit=<글 번호>` 면 수정 모드(설계서 §3.2 32).
export default async function BoardWritePage({ searchParams }: { readonly searchParams: Promise<{ edit?: string | string[] }> }) {
    const { edit } = await searchParams;
    const editId = typeof edit === 'string' && /^\d+$/.test(edit) ? edit : null;
    return <CommunityWrite editId={editId} />;
}
