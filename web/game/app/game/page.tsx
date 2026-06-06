'use client';

import { useEffect, useState } from 'react';
import Shell from '../../components/Shell';
import GameCard from '../../components/GameCard';
import StatusBadge from '../../components/StatusBadge';
import GameChrome from '../../components/game/GameChrome';
import { api } from '../../lib/api';
import { formatNumber } from '../../lib/format';
import type { FrontInfoResponse } from '../../lib/types';
import Gauge from '../../components/game/Gauge';

/**
 * /game 메인 화면 (spec §1.1 PageFront). GameChrome가 chrome spine(GameInfo + GlobalMenu +
 * MainControlBar)을 그리고, hasGeneral=false면 CharacterClaim으로 게이트한다. 빙의 완료(또는 이미
 * 빙의됨) 후 placeholder 슬롯에 기존 내 정보 카드를 렌더한다 — 풀 메인 화면 조립(MapViewer/예약명령/
 * info 카드)은 W4/W5의 책임. 기존 /game/* 서브페이지는 그대로 동작한다.
 */
export default function GameMainPage() {
    return (
        <Shell>
            <GameChrome>
                <MyPageContent />
            </GameChrome>
        </Shell>
    );
}

function MyPageContent() {
    // 내 정보 = front-info 단일 호출(GetFrontInfo 패러티). general/nation/city/global 전부 실데이터.
    // nation 은 재야(무소속)면 null, city 도 무배치면 null — 모두 null-safe 가드(날조 금지). 과거엔
    // /api/my-page(평면 MyPageResponse)를 nested MyPageData 로 잘못 읽어 data.general 가 undefined →
    // '.name' 크래시(빙의 직후)였다. front-info 의 nested 실계약으로 교체해 근본 해소.
    const [fi, setFi] = useState<FrontInfoResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.frontInfo();
            setFi(res);
        } catch {
            setError('내 정보를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    if (loading) {
        return (
            <div className="page-content">
                <h1>내 정보</h1>
                <p className="text-muted">로딩 중...</p>
            </div>
        );
    }

    if (error) {
        return (
            <div className="page-content">
                <h1>내 정보</h1>
                <div className="error-state">
                    <p>{error}</p>
                    <button onClick={fetchData}>다시 시도</button>
                </div>
            </div>
        );
    }

    if (!fi) return null;

    const { general, nation, city, global } = fi;

    return (
        <div className="page-content">
                <h1>내 정보</h1>

                <div className="page-grid">
                    {/* General card */}
                    <GameCard className="general-card">
                        <div className="card-header">
                            <h2>{general.name ?? '-'}</h2>
                            <StatusBadge variant="gold">
                                {general.officerLevelText ?? `${general.officerLevel}급`}
                            </StatusBadge>
                        </div>
                        <div className="stat-grid">
                            <div className="stat-item">
                                <span className="stat-label">통솔</span>
                                <span className="stat-value">{general.leadership}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">무력</span>
                                <span className="stat-value">{general.strength}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">지력</span>
                                <span className="stat-value">{general.intel}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">경험</span>
                                <span className="stat-value">{formatNumber(general.experience ?? 0)}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">충성</span>
                                <span className="stat-value">{general.dedication ?? 0}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">병사</span>
                                <span className="stat-value">{formatNumber(general.crew)}</span>
                            </div>
                        </div>
                        <div className="resource-bar">
                            <span>금: {formatNumber(general.gold)}</span>
                            <span>쌀: {formatNumber(general.rice)}</span>
                        </div>
                    </GameCard>

                    {/* Nation card — 재야(nation null)면 무소속 표시(스탯 날조 금지). */}
                    <GameCard className="nation-card">
                        {nation ? (
                            <>
                                <div className="card-header">
                                    <h2>{nation.name}</h2>
                                    <StatusBadge variant="jade">Lv.{nation.level}</StatusBadge>
                                </div>
                                <div className="stat-grid">
                                    <div className="stat-item">
                                        <span className="stat-label">장수</span>
                                        <span className="stat-value">{nation.crew?.generalCnt ?? '-'}명</span>
                                    </div>
                                    <div className="stat-item">
                                        <span className="stat-label">세력</span>
                                        <span className="stat-value">{formatNumber(nation.power ?? 0)}</span>
                                    </div>
                                    <div className="stat-item">
                                        <span className="stat-label">인구</span>
                                        <span className="stat-value">
                                            {formatNumber(nation.population?.now ?? 0)}
                                        </span>
                                    </div>
                                </div>
                                <div className="resource-bar">
                                    <span>국고: {formatNumber(nation.gold)}</span>
                                    <span>국고미: {formatNumber(nation.rice)}</span>
                                </div>
                            </>
                        ) : (
                            <>
                                <div className="card-header">
                                    <h2>재야</h2>
                                    <StatusBadge variant="muted">무소속</StatusBadge>
                                </div>
                                <p className="text-muted">소속된 국가가 없습니다.</p>
                            </>
                        )}
                    </GameCard>

                    {/* City card — front-info 의 FrontCityInfo(분모 populationMax/... 포함)로 레거시
                        CityBasicCard.vue 처럼 now/max 게이지 렌더. city 가 null(무배치)이면 안내만(날조 금지). */}
                    <GameCard className="city-card">
                        {city ? (
                            <>
                                <div className="card-header">
                                    <h2>{city.name}</h2>
                                    <StatusBadge variant="muted">Lv.{city.level}</StatusBadge>
                                </div>
                                <div className="gauge-metrics">
                                    <Gauge label="인구" now={city.population} max={city.populationMax} />
                                    {/* 민심(trust) — 막대는 cur/100, 텍스트는 레거시대로 단독 숫자(소수 최대 1자리, '%' 없음). */}
                                    <Gauge label="민심" now={city.trust} max={100} barOnly />
                                    <Gauge label="농업" now={city.agriculture} max={city.agricultureMax} />
                                    <Gauge label="상업" now={city.commerce} max={city.commerceMax} />
                                    <Gauge label="치안" now={city.security} max={city.securityMax} />
                                    <Gauge label="수비" now={city.defense} max={city.defenseMax} />
                                    <Gauge label="성벽" now={city.wall} max={city.wallMax} />
                                    {/* 시세(trade %) — 레거시 CityBasicCard.vue tradeBarPercent=(trade-95)*10(0..100 클램프).
                                        trade==null(상인 없음)이면 막대 없이 텍스트만 — 분모 없는 단독 표시(날조 금지). */}
                                    <div className="mcd-metric gauge-metric">
                                        <div className="mcd-metric-head">시세</div>
                                        <div className="mcd-metric-body">
                                            {city.trade != null && (
                                                <div className="mcd-bar">
                                                    <div
                                                        className="mcd-bar-fill"
                                                        style={{
                                                            width: `${Math.min(100, Math.max(0, (city.trade - 95) * 10))}%`,
                                                        }}
                                                    />
                                                </div>
                                            )}
                                            <div className="mcd-metric-text">
                                                {city.trade != null ? `${city.trade}%` : '상인 없음'}
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            </>
                        ) : (
                            <>
                                <div className="card-header">
                                    <h2>도시</h2>
                                    <StatusBadge variant="muted">-</StatusBadge>
                                </div>
                                <p className="text-muted">배치된 도시가 없습니다.</p>
                            </>
                        )}
                    </GameCard>

                    {/* Turn state — global(연/월)은 실 world_state. 턴 번호/시각은 헤더(GameInfo)가 담당. */}
                    <GameCard className="turn-card">
                        <div className="card-header">
                            <h2>턴 정보</h2>
                            <StatusBadge variant="gold">{global.year}년 {global.month}월</StatusBadge>
                        </div>
                        <div className="turn-detail">
                            <p>년도: {global.year}년</p>
                            <p>월: {global.month}월</p>
                            <p>턴 주기: {global.turnterm}분</p>
                        </div>
                    </GameCard>
                </div>
        </div>
    );
}
