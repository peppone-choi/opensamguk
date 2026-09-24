'use client';
// 기록 화면 사이를 오가는 탭. 휘하 제품의 부서 메뉴를 그대로 따른다.
import { usePathname } from 'next/navigation';
import { resolveDeptHref } from '../DeptNav';
import { DEPT_GROUPS } from '../../lib/dept-menu-config';
import { normalizeGamePathname, useServerId } from '../../lib/serverGameUrl';

export interface RecordsTab {
    readonly label: string;
    readonly href: string;
}

/** 기록 탭 목록(정적). */
export function recordsTabs(): RecordsTab[] {
    const group = DEPT_GROUPS.find((g) => g.key === 'records');
    return (group?.entries ?? []).map((entry) => ({ label: entry.label, href: entry.href }));
}

export default function RecordsTabs() {
    const pathname = usePathname();
    const serverId = useServerId();
    const current = normalizeGamePathname(pathname ?? '', serverId).split('?')[0];
    return (
        <nav className="os-pill-tabs records-tabs" aria-label="기록">
            {recordsTabs().map((tab) => {
                const active = tab.href === current;
                return (
                    <a key={tab.href} href={resolveDeptHref(tab.href, serverId)} aria-current={active ? 'page' : undefined}>
                        {tab.label}
                    </a>
                );
            })}
        </nav>
    );
}
