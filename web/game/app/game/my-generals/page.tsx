'use client';

// b_myGenInfo.php(세력장수, fid 25) 충실 이식 — 내 국가 장수 15컬럼 표 + 정렬 셀렉터.
//  - 백엔드 MyController(/api/my-generals → MyGeneralsResponse) 소비.
//  - 컬럼 set/순서/라벨은 hwe/b_myGenInfo.php의 echo 테이블 그대로:
//    얼굴 / 이름(NPC색) / 관직 / 계급 / 명성 / 봉록 / 통솔(+lbonus cyan, 부상 적색) / 무력 / 지력 /
//    자금 / 군량 / 성격 / 특기(내정/전투) / 사관 / 벌점.
//  - raw 코드 → 한글은 백엔드가 이식 헬퍼로 내려준다(officerLevelText/dedLevelText/honorText/
//    personalText/specialDomesticText/specialWarText/bill/lbonus).
//  - 부상 반영 스탯 = PHP intdiv(stat*(100-injury), 100) (절사). utilGame/calcInjury는 Math.round라
//    PHP와 발산하므로 사용하지 않고 인라인 절사한다.
//  - 기본 정렬 = PHP type=1(관직 DESC) — 백엔드가 officer_level DESC로 정렬해 내려준다(서버 순서 기본).
//  - READ-ONLY. EMPTY-SAFE(소속 없음 → 안내 / 빈 표).

import { useEffect, useMemo, useState } from 'react';
import Shell from '../../../components/Shell';
import GameTable from '../../../components/GameTable';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import { formatRefreshScore, getNPCColor } from '../../../lib/utilGame';
import { portraitUrl, onPortraitError } from '../../../lib/portrait';
import type { MyGeneralSummary, MyGeneralsResponse } from '../../../types/game';

type SortKey =
    | 'officerLevel'        // 1 관직(기본)
    | 'dedication'
    | 'experience'
    | 'leadership'          // 4 통솔
    | 'strength'            // 5 무력
    | 'intel'               // 6 지력
    | 'gold'                // 7 자금
    | 'rice'                // 8 군량
    | 'crew'                // 9 병사
    | 'refreshScoreTotal'
    | 'personal'            // 11 성격
    | 'special'             // 12 내특
    | 'special2'            // 13 전특
    | 'belong'              // 14 사관
    | 'npcState';           // 15 NPC

const SORTS: { value: SortKey; label: string; text?: boolean }[] = [
    { value: 'officerLevel', label: '관직' },
    { value: 'dedication', label: '계급' },
    { value: 'experience', label: '명성' },
    { value: 'leadership', label: '통솔' },
    { value: 'strength', label: '무력' },
    { value: 'intel', label: '지력' },
    { value: 'gold', label: '자금' },
    { value: 'rice', label: '군량' },
    { value: 'crew', label: '병사' },
    { value: 'refreshScoreTotal', label: '벌점' },
    { value: 'personal', label: '성격', text: true },
    { value: 'special', label: '내특', text: true },
    { value: 'special2', label: '전특', text: true },
    { value: 'belong', label: '사관' },
    { value: 'npcState', label: 'NPC' },
];

// 부상 반영 스탯 — PHP intdiv(stat*(100-injury), 100): 절사(truncate toward zero).
function injuredStat(stat: number, injury: number): number {
    if (injury <= 0) return stat;
    return Math.trunc((stat * (100 - injury)) / 100);
}

export default function MyGeneralsPage() {
    const [generals, setGenerals] = useState<MyGeneralSummary[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [sortIdx, setSortIdx] = useState<number | null>(null);

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.myGenerals<MyGeneralsResponse>();
            setGenerals(Array.isArray(res?.generals) ? res.generals : []);
        } catch {
            setError('장수 목록을 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    const sorted = useMemo(() => {
        if (sortIdx === null) return generals;
        const { value, text } = SORTS[sortIdx];
        const arr = [...generals];
        const dir = -1; // PHP: 전 정렬 DESC.
        arr.sort((a, b) => {
            if (text) {
                return String(a[value] ?? '').localeCompare(String(b[value] ?? '')) * dir;
            }
            return (((a[value] as number) ?? 0) - ((b[value] as number) ?? 0)) * dir;
        });
        return arr;
    }, [generals, sortIdx]);

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>세력 장수</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>세력 장수</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    const headers = ['얼굴', '이름', '관직', '계급', '명성', '봉록', '통솔', '무력', '지력', '정치', '매력', '자금', '군량', '성격', '특기', '사관', '벌점'];

    const rows = sorted.map((g) => {
        const wounded = g.injury > 0;
        const lead = injuredStat(g.leadership, g.injury);
        const str = injuredStat(g.strength, g.injury);
        const intel = injuredStat(g.intel, g.injury);
        const lbonusText = g.lbonus > 0 ? <span style={{ color: 'cyan' }}> +{g.lbonus}</span> : null;
        return [
            // 얼굴 — 초상(getIconPath 포팅: icons/<picture>.jpg, onError→default).
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
            <span key={`nm-${g.generalId}`} style={{ color: getNPCColor(g.npcState) ?? undefined }}>{g.name}</span>,
            g.officerLevelText,                 // 관직
            g.dedLevelText,                     // 계급
            g.honorText,                        // 명성
            formatNumber(g.bill),               // 봉록
            <span key={`l-${g.generalId}`}>
                <span style={{ color: wounded ? 'red' : undefined }}>{lead}</span>{lbonusText}
            </span>,
            <span key={`s-${g.generalId}`} style={{ color: wounded ? 'red' : undefined }}>{str}</span>,
            <span key={`i-${g.generalId}`} style={{ color: wounded ? 'red' : undefined }}>{intel}</span>,
            g.politics ?? '-',                  // 정치(부상색/lbonus 미적용 — 통솔/무력/지력 전용)
            g.charm ?? '-',                     // 매력(부상색/lbonus 미적용 — 통솔/무력/지력 전용)
            formatNumber(g.gold),               // 자금
            formatNumber(g.rice),               // 군량
            g.personalText,                     // 성격
            `${g.specialDomesticText} / ${g.specialWarText}`, // 특기(내정 / 전투)
            g.belong,                           // 사관(belong)
            <span key={`r-${g.generalId}`}>{g.refreshScoreTotal}<br />({formatRefreshScore(g.refreshScoreTotal)})</span>,
        ];
    });

    return (
        <Shell>
            <div className="page-content">
                <h1>세력 장수</h1>
                <p className="page-subtitle">총 {generals.length}명</p>
                <div style={{ marginBottom: 'var(--space-md)', display: 'flex', gap: 'var(--space-sm)', alignItems: 'center', flexWrap: 'wrap' }}>
                    <span className="text-muted">정렬순서 :</span>
                    <select
                        value={sortIdx ?? ''}
                        onChange={(e) => setSortIdx(e.target.value === '' ? null : Number(e.target.value))}
                    >
                        <option value="">기본(관직)</option>
                        {SORTS.map((s, i) => (
                            <option key={`${s.value}-${i}`} value={i}>{s.label}</option>
                        ))}
                    </select>
                </div>
                {generals.length === 0 ? (
                    <p className="text-muted">소속 장수가 없습니다.</p>
                ) : (
                    <GameTable headers={headers} rows={rows} />
                )}
            </div>
        </Shell>
    );
}
