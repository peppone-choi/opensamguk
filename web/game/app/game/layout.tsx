import AuthGate from '@/components/AuthGate';
import GameFrame from '@/components/shell/GameFrame';

export const dynamic = 'force-dynamic';
export const revalidate = 0;

/**
 * /game/** 전체를 게이트한다. AuthGate가 /api/auth/me로 로그인 사용자를 확정하고, 미인증이면
 * 게이트웨이 로그인(`${NEXT_PUBLIC_GATEWAY_URL}/login?next=<현재>`)으로 보낸다. 비-게임 랜딩(app/page.tsx)은
 * 이 레이아웃 밖이므로 게이트되지 않는다.
 *
 * 로그인 뒤에는 게임 안 셸 하나(GameFrame — 머리줄 · 알림 띠 · 레일 / 모바일 하단 탭)가 모든 화면을 감싼다.
 */
export default function GameLayout({ children }: { children: React.ReactNode }) {
    return (
        <AuthGate>
            <GameFrame>{children}</GameFrame>
        </AuthGate>
    );
}
