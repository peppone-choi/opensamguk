import AuthGate from '@/components/AuthGate';

/**
 * /game/** 전체를 게이트한다. AuthGate가 /api/auth/me로 로그인 사용자를 확정하고, 미인증이면
 * 게이트웨이 로그인(`${NEXT_PUBLIC_GATEWAY_URL}/login?next=<현재>`)으로 보낸다. 비-게임 랜딩(app/page.tsx)은
 * 이 레이아웃 밖이므로 게이트되지 않는다.
 */
export default function GameLayout({ children }: { children: React.ReactNode }) {
    return <AuthGate>{children}</AuthGate>;
}
