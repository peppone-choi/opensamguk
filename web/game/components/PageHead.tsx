'use client';
// 서브 페이지 공통 머리(ADR-LITE-049 05·06·12): 세리프 제목(h1 라벨 그대로) · 연월 칩 · 탭 · 우측 액션.
// 돌아가기/갱신은 Shell 의 BackBar 가 이미 그리므로 여기서는 반복하지 않는다.
import type { ReactNode } from 'react';

export interface PageHeadProps {
    readonly title: ReactNode;
    readonly chip?: ReactNode;
    readonly tabs?: ReactNode;
    readonly actions?: ReactNode;
    readonly className?: string;
}

export default function PageHead({ title, chip, tabs, actions, className = '' }: PageHeadProps) {
    return (
        <header className={`page-head ${className}`.trim()}>
            <h1 className="page-head__title">{title}</h1>
            {chip != null && <span className="os-chip os-num">{chip}</span>}
            {tabs}
            {actions != null && (
                <>
                    <span className="page-head__spacer" />
                    <div className="page-head__actions">{actions}</div>
                </>
            )}
        </header>
    );
}
