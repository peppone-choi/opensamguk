'use client';

import { useParams } from 'next/navigation';
import CommunityPost from '@/components/community/CommunityPost';

// P-G07 커뮤니티 글(설계서 §3.2). 손님도 읽고, 쓰기 조작은 로그인 · 서버 권한에 따른다.
export default function BoardPostPage() {
    const { postId } = useParams<{ postId: string }>();
    return <CommunityPost postId={postId} />;
}
