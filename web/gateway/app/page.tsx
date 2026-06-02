import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { ACCESS_COOKIE } from '@/lib/cookies';

// 레거시 `/`(index.php) = 로그인 진입. 세션 쿠키가 있으면 로비, 없으면 로그인으로 보낸다.
// (임의 랜딩 카피 없음 — 렌더 텍스트 패러티.)
export default async function Home() {
    const store = await cookies();
    if (store.get(ACCESS_COOKIE)) {
        redirect('/lobby');
    }
    redirect('/login');
}
