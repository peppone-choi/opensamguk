import type { ReactNode } from 'react';
import AdminDenied from '@/components/AdminDenied';
import { getSession } from '@/lib/auth';

/**
 * 운영 콘솔 서버 래퍼. 서버가 비운영자로 확정한 세션에는 운영 콘솔 화면을 내려보내지 않고 같은 「권한 없음」을 그린다.
 * 세션을 확정하지 못하면(access 만료 — 서버 렌더에선 갱신할 수 없다) 지금처럼 화면 래퍼(AuthGate)가 /api/auth/me 로 확인한다.
 * requireAdmin 의 redirect 는 쓰지 않는다 — 비운영자 화면(권한 없음 + 로비 고리)과 만료된 운영자 흐름이 바뀌기 때문이다.
 * 관리 API 의 권한 확인은 서버(gateway-api ROLE_ADMIN)가 한다.
 */
export default async function AdminLayout({ children }: { readonly children: ReactNode }) {
    const user = await getSession();
    if (user && user.role !== 'ADMIN') return <AdminDenied />;
    return <>{children}</>;
}
