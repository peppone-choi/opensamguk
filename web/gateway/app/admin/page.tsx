'use client';

import { useState } from 'react';
import AuthGate from '@/components/AuthGate';
import Topbar from '@/components/Topbar';

// F0 어드민 = 가드 + 셸뿐. 실 어드민 API(회원 차단/레벨, 서버 개폐, 게임 환경)는 후속 페이즈.
// 섹션명은 verbatim 패러티 대상, 본문은 '준비 중' 플레이스홀더.
const ADMIN_SECTIONS = [
    { id: 'members', label: '회원 관리' },
    { id: 'server', label: '서버 제어' },
    { id: 'game', label: '게임 환경' },
] as const;

const PLACEHOLDER = '준비 중';

function AdminView() {
    const [active, setActive] = useState<string>(ADMIN_SECTIONS[0].id);
    const section = ADMIN_SECTIONS.find((s) => s.id === active) ?? ADMIN_SECTIONS[0];

    return (
        <div className="admin-shell">
            <Topbar />
            <main className="admin-main fade-in">
                <div className="admin-tabs">
                    {ADMIN_SECTIONS.map((s) => (
                        <button
                            key={s.id}
                            type="button"
                            className={`admin-tab${s.id === active ? ' active' : ''}`}
                            onClick={() => setActive(s.id)}
                        >
                            {s.label}
                        </button>
                    ))}
                </div>
                <section className="admin-panel">
                    <h2 className="lobby-section-title">{section.label}</h2>
                    <p>{PLACEHOLDER}</p>
                </section>
            </main>
        </div>
    );
}

export default function AdminPage() {
    return (
        <AuthGate admin>
            <AdminView />
        </AuthGate>
    );
}
