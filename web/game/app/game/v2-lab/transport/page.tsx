// OPENSAM-154 (v2 R5) — v2 도시 자원 수송 제출 화면.
//
// **서버 컴포넌트다.** 대화형 본문은 `components/v2/CityTransportForm`에 있다. 이 파일에 'use client'를
// 붙이면 라우트 코드가 그대로 프로덕션 클라이언트 번들에 들어가 OPENSAM-35 §7.6의 격리 전제
// ("v2-lab은 server-only라 클라이언트 청크가 빈 스텁")가 깨진다 — `/_next/static/**`는 middleware
// matcher 밖이라 404 게이트가 닿지 않는다. `components/v2/**`에 두면 V2_ENABLED가 아닌 빌드에서
// next.config.mjs가 빈 스텁으로 치환한다(OPENSAM-195).

import Shell from '@/components/Shell';
import CityTransportForm from '@/components/v2/CityTransportForm';

export default function V2CityTransportPage() {
    return (
        <Shell>
            <div className="page-content">
                <h1>v2-lab · 도시 자원 수송</h1>
                <CityTransportForm />
            </div>
        </Shell>
    );
}
