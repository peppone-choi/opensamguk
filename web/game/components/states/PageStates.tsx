'use client';

// 페이지 수준 상태(보드 V31SystemStates · MNotFound, P-X01) — 영역 상태는 공용 StatusView 그대로, 여기는 화면 전체를 대신할 때만.
// 없는 화면(404)은 셸 안에서 「작전실로 · 기록으로」, 화면이 그리다 깨지면(오류 경계) 「다시 시도 + 오류 번호」.
import { StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { ShellIcon } from '@/components/shell/ShellIcon';

export function NotFoundScreen() {
    return (
        <StatusView
            kind="not-found"
            scope="page"
            actions={
                <>
                    <CampaignLink slug="" className="os-button os-button--primary os-status__action">
                        <ShellIcon name="war" size={16} />작전실로
                    </CampaignLink>
                    <CampaignLink slug="records" className="os-button os-status__action">
                        <ShellIcon name="records" size={16} />기록으로
                    </CampaignLink>
                </>
            }
        />
    );
}

/** 화면을 그리다 깨졌을 때(Next 오류 경계). 오류 번호는 서버가 붙인 digest 가 있을 때만 보인다. */
export function CrashScreen({ digest, onRetry }: { readonly digest?: string; readonly onRetry: () => void }) {
    return <StatusView kind="error" scope="page" title="화면을 불러오지 못했습니다" errorCode={digest} onRetry={onRetry} />;
}
