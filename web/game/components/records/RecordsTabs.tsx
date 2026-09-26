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

const RANKING_TABS: readonly RecordsTab[] = [
    { label: '명장 순위', href: '/game/rankings/best-generals' },
    { label: '장수 일람', href: '/game/rankings/generals' },
    { label: '세력 순위', href: '/game/rankings/kingdoms' },
];

/** 기록 탭 목록(정적). */
export function recordsTabs(): RecordsTab[] {
    const group = DEPT_GROUPS.find((g) => g.key === 'records');
    return (group?.entries ?? []).map((entry) => ({ label: entry.label, href: entry.href }));
}

export default function RecordsTabs() {
    const pathname = usePathname();
    const serverId = useServerId();
    const current = normalizeGamePathname(pathname ?? '', serverId).split('?')[0];
    const rankActive = (href: string) => current === href || current.startsWith(`${href}/`);
    return (
        <>
            <nav className="os-pill-tabs records-tabs" aria-label="기록">
                {recordsTabs().map((tab) => {
                    const active = rankActive(tab.href);
                    return (
                        <a key={tab.href} href={resolveDeptHref(tab.href, serverId)} aria-current={active ? 'page' : undefined}>
                            {tab.label}
                        </a>
                    );
                })}
            </nav>
            {rankActive('/game/rankings') && (
                <nav className="os-pill-tabs records-tabs" aria-label="랭킹 상세">
                    {RANKING_TABS.map((tab) => (
                        <a key={tab.href} href={resolveDeptHref(tab.href, serverId)} aria-current={rankActive(tab.href) ? 'page' : undefined}>
                            {tab.label}
                        </a>
                    ))}
                </nav>
            )}
        </>
    );
}
