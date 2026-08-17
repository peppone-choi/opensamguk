'use client';

// a_genList.php(장수일람, fid 30) 충실 이식 — 전체 장수 15컬럼 표 + 정렬 셀렉터.
//  - 백엔드 GeneralsController(/api/generals → PublicGeneral[], bare 배열) 소비.
//  - 컬럼 set/순서/라벨/표시 규칙은 hwe/a_genList.php의 echo 테이블 그대로:
//    얼굴 / 이름(NPC색) / 연령 / 성격 / 특기(내정/전투) / 레벨(Lv) / 국가 / 명성 / 계급 / 관직 /
//    통솔(+lbonus cyan, 부상 시 적색) / 무력 / 지력 / 삭턴 / 벌점.
//  - raw 코드 → 한글 해석은 백엔드가 이미 이식된 헬퍼로 내려준다(personalText/specialDomesticText/
//    specialWarText/officerLevelText/honorText/dedLevelText). FE는 표시만.
//  - 부상 반영 스탯 = PHP intdiv(stat*(100-injury), 100) (truncate toward zero) — 절사로 계산한다
//    (utilGame/calcInjury는 Math.round라 PHP와 발산하므로 여기선 사용하지 않고 인라인 절사).
//  - READ-ONLY. EMPTY-SAFE(빈 seed → 빈 표).

import { useEffect, useMemo, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameTable from '../../../../components/GameTable';
import { api } from '../../../../lib/api';
import { formatRefreshScore, getNPCColor } from '../../../../lib/utilGame';
import { portraitUrl, onPortraitError } from '../../../../lib/portrait';
import { useTurnRefresh } from '../../../../hooks/useTurnRefresh';
import type { PublicGeneral } from '../../../../types/game';

const NO_NATION = 0;

type SortKey =
    | 'nationId'    // 1 국가(PHP ORDER BY nation = 국가 id ASC)
    | 'leadership'  // 2 통솔
    | 'strength'    // 3 무력
    | 'intel'       // 4 지력
    | 'explevel'    // 5 명성 / 10 Lv(동일 키: 둘 다 experience 버킷)
    | 'dedlevel'    // 6 계급
    | 'officerLevel'// 7 관직
    | 'killturn'    // 8 삭턴
    | 'refreshScoreTotal'
    | 'personalText'// 11 성격
    | 'specialDomesticText' // 12 내특
    | 'specialWarText'      // 13 전특
    | 'age'         // 14 연령
    | 'npc';        // 15 NPC

const SORTS: { value: SortKey; label: string; text?: boolean }[] = [
    { value: 'nationId', label: '국가' },
    { value: 'leadership', label: '통솔' },
    { value: 'strength', label: '무력' },
    { value: 'intel', label: '지력' },
    { value: 'explevel', label: '명성' },
    { value: 'dedlevel', label: '계급' },
    { value: 'officerLevel', label: '관직' },
    { value: 'killturn', label: '삭턴' },
    { value: 'refreshScoreTotal', label: '벌점' },
    { value: 'explevel', label: 'Lv' },
    { value: 'personalText', label: '성격', text: true },
    { value: 'specialDomesticText', label: '내특', text: true },
    { value: 'specialWarText', label: '전특', text: true },
    { value: 'age', label: '연령' },
    { value: 'npc', label: 'NPC' },
];

const DEFAULT_SORT_INDEX = SORTS.findIndex(({ value }) => value === 'refreshScoreTotal');

// 부상 반영 스탯 — PHP intdiv(stat*(100-injury), 100): 0 이하 절사(truncate toward zero).
function injuredStat(stat: number, injury: number): number {
    if (injury <= 0) return stat;
    return Math.trunc((stat * (100 - injury)) / 100);
}

export default function GeneralsListPage() {
    const [data, setData] = useState<PublicGeneral[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [sortIdx, setSortIdx] = useState(DEFAULT_SORT_INDEX);

    // OPENSAM-196 — background=true면 로딩 화면을 다시 띄우지 않는다.
    const fetchData = (background = false) => {
        if (!background) {
            setLoading(true);
            setError('');
        }
        api
            .generalsList()
            .then((res: PublicGeneral[]) => setData(Array.isArray(res) ? res : []))
            .catch(() => setError('데이터를 불러올 수 없습니다.'))
            .finally(() => { if (!background) setLoading(false); });
    };

    useEffect(fetchData, []);

    // OPENSAM-196 — 턴 종료 시 장수 일람 재조회.
    useTurnRefresh(() => fetchData(true));

    const sorted = useMemo(() => {
        const { value, text } = SORTS[sortIdx];
        const arr = [...data];
        // PHP: 정렬은 거의 전부 DESC(국가만 ASC). 텍스트 컬럼은 localeCompare DESC.
        const dir = value === 'nationId' || value === 'killturn' ? 1 : -1;
        arr.sort((a, b) => {
            if (text) {
                return String(a[value] ?? '').localeCompare(String(b[value] ?? '')) * dir;
            }
            const av = (a[value] as number | null) ?? 0;
            const bv = (b[value] as number | null) ?? 0;
            return (av - bv) * dir;
        });
        return arr;
    }, [data, sortIdx]);

    if (loading) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>장수 일람</h1>
                <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>장수 일람</h1>
                <p style={{ color: 'var(--crimson)' }}>{error}</p>
            </Shell>
        );
    }

    const headers = ['얼굴', '이름', '연령', '성격', '특기', '레벨', '국가', '명성', '계급', '관직', '통솔', '무력', '지력', '정치', '매력', '삭턴', '벌점'];

    const rows = sorted.map((g) => {
        const wounded = g.injury > 0;
        const lead = injuredStat(g.leadership, g.injury);
        const str = injuredStat(g.strength, g.injury);
        const intel = injuredStat(g.intel, g.injury);
        const roundedRefreshScoreTotal = Math.round(g.refreshScoreTotal / 10) * 10;
        const lbonusText = g.lbonus > 0 ? <span style={{ color: 'cyan' }}> +{g.lbonus}</span> : null;
        return [
            // 얼굴 — 초상(getIconPath 포팅: icons/<picture>.jpg, onError→default). PHP GetImageURL(imgsvr)/picture.
            // eslint-disable-next-line @next/next/no-img-element
            <img
                key={`pic-${g.generalId}`}
                src={portraitUrl(g.picture, g.imageServer)}
                onError={onPortraitError}
                alt=""
                width={32}
                height={40}
                style={{ objectFit: 'contain', borderRadius: 'var(--radius-sm)', verticalAlign: 'middle', background: 'var(--bg-hover)' }}
                draggable={false}
            />,
            // 이름 — PHP formatName(name, npc): NPC 타입별 색(getNPCColor).
            <span key={`nm-${g.generalId}`} style={{ color: getNPCColor(g.npc) ?? undefined }}>{g.name}</span>,
            `${g.age}세`,                       // 연령
            g.personalText,                     // 성격
            `${g.specialDomesticText} / ${g.specialWarText}`, // 특기(내정 / 전투)
            `Lv ${g.explevel}`,                 // 레벨
            // 국가 — 재야/무소속 표시.
            <span key={`nat-${g.generalId}`} style={{ color: g.nationId === NO_NATION ? 'var(--text-muted)' : (g.nationColor || 'var(--text-muted)') }}>
                {g.nationId === NO_NATION ? '재야' : (g.nationName || '재야')}
            </span>,
            g.honorText,                        // 명성(getHonor)
            g.dedLevelText,                     // 계급(getDed)
            g.officerLevelText,                 // 관직(getOfficerLevelText)
            // 통솔 — 부상 시 적색 + 통솔보너스(cyan "+N").
            <span key={`l-${g.generalId}`}>
                <span style={{ color: wounded ? 'red' : undefined }}>{lead}</span>{lbonusText}
            </span>,
            <span key={`s-${g.generalId}`} style={{ color: wounded ? 'red' : undefined }}>{str}</span>,
            <span key={`i-${g.generalId}`} style={{ color: wounded ? 'red' : undefined }}>{intel}</span>,
            // 정치/매력 — RTK14 divergence(부상/통솔보너스 미적용, 평문 표시). 필드 OPTIONAL이라 '-' 폴백.
            g.politics ?? '-',                  // 정치
            g.charm ?? '-',                     // 매력
            g.killturn ?? '-',                  // 삭턴(meta.killturn; 미기재 '-')
            <span key={`r-${g.generalId}`}>{roundedRefreshScoreTotal}<br />【{formatRefreshScore(roundedRefreshScoreTotal)}】</span>,
        ];
    });

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>장수 일람</h1>
            <div style={{ marginBottom: 'var(--space-md)', display: 'flex', gap: 'var(--space-sm)', alignItems: 'center', flexWrap: 'wrap' }}>
                <span style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>정렬순서 :</span>
                <select
                    value={sortIdx}
                    onChange={(e) => setSortIdx(Number(e.target.value))}
                    style={{ background: 'var(--bg-hover)', color: 'var(--text-primary)', fontSize: 'var(--text-sm)', padding: 'var(--space-xs) var(--space-sm)' }}
                >
                    {SORTS.map((s, i) => (
                        <option key={`${s.value}-${i}`} value={i}>{s.label}</option>
                    ))}
                </select>
            </div>
            <GameTable headers={headers} rows={rows} />
        </Shell>
    );
}
