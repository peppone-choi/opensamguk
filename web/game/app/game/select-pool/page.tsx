'use client';

import Link from 'next/link';
import Shell from '../../../components/Shell';

export default function SelectPoolPage() {
    return (
        <Shell>
            <div className="page-content claim-screen">
                <h1>장수 선택</h1>
                <p className="page-subtitle">서버가 준비한 장수 풀에서 선택하는 등록 방식입니다.</p>

                <div className="claim-error" role="alert">
                    장수 선택 풀의 읽기/선택/수정 API는 아직 완성되지 않았습니다. 현재 엔진은 선택/수정을 미구현으로
                    반환하므로, 이 화면은 QA용 차단 표면으로만 노출합니다.
                </div>

                <div className="claim-mode-grid">
                    <div className="claim-mode-card game-card is-disabled">
                        <h2>풀에서 선택</h2>
                        <p>선택 가능한 장수 후보를 읽고 하나를 확정하는 기능입니다.</p>
                        <span className="claim-mode-blocked">미이식</span>
                    </div>
                    <div className="claim-mode-card game-card is-disabled">
                        <h2>선택 장수 수정</h2>
                        <p>선택한 장수의 성격과 능력치를 허용 옵션 안에서 조정하는 기능입니다.</p>
                        <span className="claim-mode-blocked">미이식</span>
                    </div>
                    <div className="claim-mode-card game-card">
                        <h2>직접 생성</h2>
                        <p>직접 생성이 허용된 서버라면 기존 생성 화면을 사용할 수 있습니다.</p>
                        <Link className="claim-mode-action" href="/game/join">
                            생성 화면
                        </Link>
                    </div>
                </div>
            </div>
        </Shell>
    );
}
