'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import GeneralBasicCard from '../../../components/game/GeneralBasicCard';
import MyInfoLogPanel from '../../../components/game/MyInfoLogPanel';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import { formatDefenceTrain } from '../../../lib/utilGame';
import type { FrontInfoResponse, MyPageResponse } from '../../../lib/types';

function InfoGrid({ rows }: { rows: [string, React.ReactNode][] }) {
    return (
        <div
            style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                gap: '1px',
                background: 'var(--border-subtle)',
                fontSize: 'var(--text-sm)',
            }}
        >
            {rows.map(([label, value]) => (
                <div key={label} className="basic-card-row" style={{ display: 'contents' }}>
                    <div className="basic-card-head">{label}</div>
                    <div className="basic-card-body">{value}</div>
                </div>
            ))}
        </div>
    );
}

function currentDefenceTrain(frontInfo: FrontInfoResponse | null): string {
    const value = frontInfo?.general.defenceTrain;
    if (value == null) return '-';
    return `${formatDefenceTrain(value)}(훈사${value})`;
}

export default function MyPage() {
    const [frontInfo, setFrontInfo] = useState<FrontInfoResponse | null>(null);
    const [myPage, setMyPage] = useState<MyPageResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = () => {
        setLoading(true);
        setError('');
        Promise.all([api.frontInfo(), api.myPage<MyPageResponse>()])
            .then(([front, mine]) => {
                setFrontInfo(front);
                setMyPage(mine);
            })
            .catch(() => setError('내 정보를 불러올 수 없습니다.'))
            .finally(() => setLoading(false));
    };

    useEffect(fetchData, []);

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 정보&설정</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error || !frontInfo || !myPage || !frontInfo.general.hasGeneral) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 정보&설정</h1>
                    <div className="error-state">
                        <p>{error || '장수 정보가 없습니다.'}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    const generalRows: [string, React.ReactNode][] = [
        ['소속', myPage.nationName ?? '재야'],
        ['현재 도시', myPage.cityName ?? '-'],
        ['통솔', myPage.leadership],
        ['무력', myPage.strength],
        ['지력', myPage.intel],
        ['정치', myPage.politics ?? '-'],
        ['매력', myPage.charm ?? '-'],
        ['부상', `${myPage.injury}%`],
        ['자금', formatNumber(myPage.gold)],
        ['군량', formatNumber(myPage.rice)],
        ['병사', formatNumber(myPage.crew)],
        ['훈련/사기', `${myPage.train} / ${myPage.atmos}`],
    ];

    const settingsRows: [string, React.ReactNode][] = [
        ['토너먼트', '-'],
        ['환약 사용', '-'],
        ['자동 사령턴 허용', '-'],
        ['수비', currentDefenceTrain(frontInfo)],
        ['500px/1000px 모드', '-'],
        ['개인용 CSS', '-'],
    ];

    return (
        <Shell>
            <div className="page-content">
                <h1>내 정보&설정</h1>
                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
                        gap: 'var(--space-md)',
                        alignItems: 'start',
                        marginBottom: 'var(--space-md)',
                    }}
                >
                    <GeneralBasicCard general={frontInfo.general} nation={frontInfo.nation} />
                    <GameCard>
                        <div className="basic-card-name">{myPage.name}</div>
                        <InfoGrid rows={generalRows} />
                    </GameCard>
                    <GameCard>
                        <div className="basic-card-name">설정</div>
                        <InfoGrid rows={settingsRows} />
                    </GameCard>
                </div>
                <MyInfoLogPanel generalId={myPage.generalId} />
            </div>
        </Shell>
    );
}
