'use client';

import { Chip, Gauge, Panel, SectionHeader, juDisplayName } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import type { FrontCityInfo } from '@/lib/types';
import { Empty } from './GameStates';

/**
 * 특산 한 칩 — 우리 세력 현이면 이번 달 실제 몫, 남의 현(재야이거나 남의 현에 서 있을 때 포함)이면 설계값만(사용자 결정 D40).
 * 서버(`/api/county`)가 아직 시야 · 소속과 무관하게 실제 몫을 주므로 화면이 먼저 막는다(K4 → C0 계약판 메모).
 */
function specialtyChip(s: { readonly label: string; readonly monthly: number | null; readonly ledgerMonthly?: number | null }, mine: boolean): string {
    if (!mine) return `${s.label} 설계 ${s.ledgerMonthly == null ? '?' : s.ledgerMonthly.toLocaleString('ko-KR')}/월`;
    return `${s.label} ${s.monthly == null ? '[미정]' : s.monthly.toLocaleString('ko-KR')}/월`;
}

/**
 * 내가 선 현 — `front-info.city` 의 실제 값과 특산 조회. 내정·징세·징병은 현 단위다.
 */
export default function CountyPanel({ city }: { city: FrontCityInfo | null }) {
    const { frontInfo } = useGameSession();
    const myNationId = frontInfo?.nation?.id ?? null;
    const county = useCampaignRead(
        (generalId, signal) => (city ? api.campaignCounty(generalId, city.id, signal) : Promise.resolve(null)),
        [city?.id],
    );
    if (!city) {
        return (
            <Panel style={{ padding: 12 }}>
                <SectionHeader title="지금 선 곳" />
                <Empty>장수가 성에 있지 않습니다.</Empty>
            </Panel>
        );
    }
    const specialties = county.data?.specialties ?? [];
    const mine = myNationId != null && myNationId > 0 && city.nationId === myNationId;
    return (
        <Panel style={{ padding: 12 }}>
            <SectionHeader
                title={
                    <span style={{ whiteSpace: 'nowrap' }}>
                        {city.name}{' '}
                        {city.levelName ? <Chip tone="bronze">{city.levelName}</Chip> : null}{' '}
                        <Chip tone="info">지금 여기</Chip>
                    </span>
                }
                // 州 이름은 화면 표기로(원장 D25: 涼州 「서량」 · 司隸 「사례」) — 서버 키(「량주」 · 「사예」)는 그대로 받는다.
                sub={[city.regionName ? juDisplayName(city.regionName) : null, city.nationName ?? '무주'].filter(Boolean).join(' · ')}
            />
            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                    gap: 12,
                    paddingTop: 10,
                }}
            >
                <Gauge label="호구" value={city.population} max={city.populationMax} />
                <Gauge label="전답" value={city.agriculture} max={city.agricultureMax} />
                <Gauge label="시장" value={city.commerce} max={city.commerceMax} />
                <Gauge label="민심" value={city.trust} max={100} tone={city.trust < 50 ? 'rust' : 'moss'} />
                <Gauge label="치안" value={city.security} max={city.securityMax} />
            </div>
            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                    gap: 12,
                    paddingTop: 12,
                    borderTop: '1px solid var(--line)',
                    marginTop: 12,
                }}
            >
                <Gauge label="방비" value={city.defense} max={city.defenseMax} tone="bronze" />
                <Gauge label="성벽" value={city.wall} max={city.wallMax} tone="bronze" />
                <div>
                    <div style={{ fontSize: 12, color: 'var(--muted)' }}>특산</div>
                    <div style={{ paddingTop: 4, display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                        {county.loading ? (
                            <span style={{ fontSize: 12, color: 'var(--muted)' }}>불러오는 중</span>
                        ) : county.error ? (
                            <span style={{ fontSize: 12, color: 'var(--rust)' }}>불러오지 못했습니다</span>
                        ) : specialties.length === 0 ? (
                            <span style={{ fontSize: 12, color: 'var(--muted)' }}>없음</span>
                        ) : (
                            specialties.map((s) => (
                                <Chip key={s.resource}>{specialtyChip(s, mine)}</Chip>
                            ))
                        )}
                    </div>
                </div>
            </div>
        </Panel>
    );
}
