import { GameSessionProvider } from '@/lib/hwiha-session';

// 새 시대(휘하) 화면은 시안처럼 고정 높이 한 장으로 산다 — 헤더 56 + 본문.
// `/game/**` 의 AuthGate 안에 있으므로 여기 올 때는 로그인이 확정돼 있다.
export default function GameLayout({ children }: { children: React.ReactNode }) {
    return (
        <div
            style={{
                minHeight: '100dvh',
                display: 'flex',
                flexDirection: 'column',
                background: 'var(--bg)',
                color: 'var(--text)',
            }}
        >
            <GameSessionProvider>{children}</GameSessionProvider>
        </div>
    );
}
