'use client';

import { Chip, Gauge, Panel, SectionHeader } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { useHwihaRead } from '@/lib/hwiha-reads';
import type { FrontCityInfo } from '@/lib/types';
import { Empty } from './GameStates';

/**
 * 내가 선 현 — `front-info.city` 의 실제 값과 특산 조회. 내정·징세·징병은 현 단위다.
 */
export default function CountyPanel({ city, isHwihaWorld }: { city: FrontCityInfo | null; isHwihaWorld: boolean }) {
    const county = useHwihaRead(
        (generalId, signal) => (city ? api.hwihaCounty(generalId, city.id, signal) : Promise.resolve(null)),
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
                sub={[city.regionName, city.nationName ?? '무주'].filter(Boolean).join(' · ')}
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
                        {!isHwihaWorld ? (
                            <span style={{ fontSize: 12, color: 'var(--muted)' }}>휘하 규칙 서버에서 보입니다</span>
                        ) : county.loading ? (
                            <span style={{ fontSize: 12, color: 'var(--muted)' }}>불러오는 중</span>
                        ) : county.error ? (
                            <span style={{ fontSize: 12, color: 'var(--rust)' }}>불러오지 못했습니다</span>
                        ) : specialties.length === 0 ? (
                            <span style={{ fontSize: 12, color: 'var(--muted)' }}>없음</span>
                        ) : (
                            specialties.map((s) => (
                                <Chip key={s.resource}>{`${s.label} ${s.monthly == null ? '[미정]' : s.monthly.toLocaleString('ko-KR')}/월`}</Chip>
                            ))
                        )}
                    </div>
                </div>
            </div>
        </Panel>
    );
}
