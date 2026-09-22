// 새 시대(휘하) 화면은 시안처럼 고정 높이 한 장으로 산다 — 헤더 56 + 본문.
export default function HwihaLayout({ children }: { children: React.ReactNode }) {
    return (
        <div
            style={{
                minHeight: '100dvh',
                display: 'flex',
                flexDirection: 'column',
                background: 'var(--bg, #0c0f0e)',
                color: 'var(--fg, #ece6d8)',
            }}
        >
            {children}
        </div>
    );
}
