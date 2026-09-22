'use client';

import Link from 'next/link';
import { Chip, HanMapCanvas, Panel, PlaceNameWithGloss, SectionHeader, splitCountyGloss } from '@opensamguk/ui';
import HwihaShell from '../../../components/HwihaShell';
import {
    HWIHA_HOME_CITY_ID,
    HWIHA_MAP_CITIES,
    HWIHA_MOCK_TERRAIN_URL,
} from '../../../lib/hwiha-map-view';
import { hwihaHref, hwihaScreensOfTab } from '../../../lib/hwiha-screens';
import {
    MOCK_IDENTITY,
    MOCK_LAST_TURN,
    MOCK_MAP_LEGEND,
    MOCK_MAP_PROVINCES,
    MOCK_STANDING,
    MOCK_TURN_SLOTS,
} from '../../../lib/hwiha-mock';

const SLOT_TONE = (state: string) => (state === '실행됨' ? 'moss' : state === '예약' ? 'info' : 'neutral');

/**
 * 작전실 — 시안 WarRoom(메인).
 *
 * 삼모의 「현황」 자리이지만 구조가 다르다. 삼모는 24칸 명령 큐에 명령을 쌓고 턴마다 순차 실행하는데,
 * 여기서는 **개인 턴 12순**이고 한 순에 직접 행동 하나이며 칸마다 실행 날짜·시각이 박힌다. 그리고
 * 맡겨 둔 일(배치·방침·공사·설치 계책·발령)은 개인 턴을 쓰지 않고 스스로 굴러간다 — 「걸려 있는 것」.
 *
 * 지도는 성이 아니라 **구역**이 단위다. 미정찰 구역은 빗금으로 두고 이름만 보인다.
 * 실제 지도 렌더는 `HanMapCanvas` 로 붙일 자리이며, 목 단계에서는 구역 이름표만 둔다.
 */
export default function WarRoomPage() {
    return (
        <HwihaShell title="작전실" tab={null} identity={MOCK_IDENTITY} showBack={false}>
            <div
                style={{
                    padding: 12,
                    display: 'grid',
                    gridTemplateColumns: 'minmax(0, 1fr) 420px',
                    gap: 12,
                    alignItems: 'start',
                }}
            >
                <div style={{ display: 'grid', gap: 12 }}>
                    <Panel style={{ padding: 12 }}>
                        <SectionHeader
                            title="천하 형세"
                            sub="구역 단위 · 보이는 만큼만"
                            actions={
                                <Link className="os-button os-button--ghost os-button--sm" href={hwihaHref('map-layers')}>
                                    레이어 열기
                                </Link>
                            }
                        />
                        {/* 저장소의 실제 1133 판 城 표 + 실제 지형(han-tiles.json). 세력 배정만 목이다.
                            郡縣 한 급으로 보이도록 내가 있는 縣 기준으로 당겨서 연다. */}
                        <div style={{ marginTop: 8 }}>
                            <HanMapCanvas
                                mapCode="han-world-v3"
                                terrainUrl={HWIHA_MOCK_TERRAIN_URL}
                                cities={HWIHA_MAP_CITIES}
                                currentCityId={HWIHA_HOME_CITY_ID}
                                initialFocus="current-city-close"
                                ariaLabel="천하 형세 — 영천군 방면"
                                style={{ width: '100%', height: 420 }}
                            />
                        </div>
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 10 }}>
                            {MOCK_MAP_LEGEND.map((l) => (
                                <Chip key={l}>{l}</Chip>
                            ))}
                        </div>
                        <p style={{ fontSize: 12, color: 'var(--muted)', paddingTop: 8 }}>
                            하후돈 군단 · 성 없는 구역 · 적 군단 · 설치 계책(매복)은 나에게만 보인다.
                        </p>

                        <div style={{ paddingTop: 12 }}>
                            <SectionHeader title="이번 순에 보이는 구역" as="h4" />
                            <div
                                style={{
                                    display: 'grid',
                                    gridTemplateColumns: 'repeat(auto-fill, minmax(170px, 1fr))',
                                    gap: 6,
                                    paddingTop: 8,
                                }}
                            >
                                {MOCK_MAP_PROVINCES.map((p) => {
                                    const unscouted = p.note.includes('미정찰');
                                    // 이름에 붙은 괄호(동명이지 구분 郡)는 괄호로 두지 않고 뱃지로 뗀다 — 시안 표기 규칙.
                                    const { name, gloss } = splitCountyGloss(p.name);
                                    return (
                                        <div
                                            key={p.name}
                                            style={{
                                                padding: '4px 8px',
                                                minHeight: 44,
                                                display: 'flex',
                                                flexDirection: 'column',
                                                justifyContent: 'center',
                                                borderLeft: `3px solid ${unscouted ? '#3d4740' : 'var(--bronze, #c9a656)'}`,
                                                background: unscouted
                                                    ? 'repeating-linear-gradient(45deg, #161a18, #161a18 6px, #1b201d 6px, #1b201d 12px)'
                                                    : '#161a18',
                                            }}
                                        >
                                            <div style={{ fontSize: 13, fontWeight: 700, whiteSpace: 'nowrap' }}>
                                                <PlaceNameWithGloss name={name} gloss={gloss} />
                                            </div>
                                            <div style={{ fontSize: 11, color: 'var(--muted)' }}>{p.note}</div>
                                        </div>
                                    );
                                })}
                            </div>
                        </div>
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="걸려 있는 것" sub="맡겨 둔 일 · 턴마다 스스로 굴러간다" />
                        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', paddingTop: 8 }}>
                            {MOCK_STANDING.map((s) => {
                                const landing = hwihaScreensOfTab(s.tab)[0];
                                const label = `${s.k} ${s.v}`;
                                return landing ? (
                                    <Link key={s.k} className="os-button os-button--ghost os-button--sm" href={hwihaHref(landing.slug)}>
                                        {label}
                                    </Link>
                                ) : (
                                    <Chip key={s.k}>{label}</Chip>
                                );
                            })}
                        </div>
                        <p style={{ fontSize: 12, color: 'var(--muted)', paddingTop: 8 }}>
                            이 일들은 개인 턴을 쓰지 않는다. 직접 행동은 한 순에 하나다.
                        </p>
                    </Panel>

                    <Panel style={{ padding: 12 }}>
                        <SectionHeader title="지난 순" sub={MOCK_LAST_TURN.range} actions={<Chip tone="moss">{MOCK_LAST_TURN.state}</Chip>} />
                        <div style={{ paddingTop: 8, fontSize: 13 }}>{MOCK_LAST_TURN.what}</div>
                    </Panel>
                </div>

                <Panel style={{ padding: 12 }}>
                    <SectionHeader
                        title={`명령 목록 ${MOCK_TURN_SLOTS.length}순`}
                        sub="직접 행동 · 한 순에 하나"
                        actions={
                            <span style={{ display: 'flex', gap: 6 }}>
                                <button type="button" className="os-button os-button--ghost os-button--sm">
                                    당기기
                                </button>
                                <button type="button" className="os-button os-button--ghost os-button--sm">
                                    밀기
                                </button>
                            </span>
                        }
                    />
                    <div style={{ display: 'grid', gap: 4, paddingTop: 8 }}>
                        {MOCK_TURN_SLOTS.map((s) => {
                            const empty = s.state === '빈 순';
                            return (
                                <div
                                    key={s.no}
                                    style={{
                                        display: 'grid',
                                        gridTemplateColumns: '28px 1fr auto',
                                        gap: 8,
                                        alignItems: 'center',
                                        minHeight: 44,
                                        padding: '4px 6px',
                                        border: empty ? '1px dotted #3d4740' : '1px solid var(--line)',
                                        borderRadius: 3,
                                        background: empty ? 'transparent' : '#161a18',
                                    }}
                                >
                                    <span className="mono" style={{ color: 'var(--muted)', fontSize: 12 }}>
                                        {s.no}
                                    </span>
                                    <span style={{ minWidth: 0 }}>
                                        <div style={{ fontSize: 13, fontWeight: empty ? 400 : 700, color: empty ? 'var(--muted)' : undefined }}>
                                            {s.what}
                                        </div>
                                        <div style={{ fontSize: 11, color: 'var(--muted)' }}>
                                            {s.when}
                                            {s.target ? ` · ${s.target}` : ''}
                                        </div>
                                    </span>
                                    {empty ? (
                                        <button type="button" className="os-button os-button--ghost os-button--sm">
                                            + 예약
                                        </button>
                                    ) : (
                                        <Chip tone={SLOT_TONE(s.state)}>{s.state}</Chip>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                    <div style={{ paddingTop: 10, borderTop: '1px solid var(--line)', marginTop: 10 }}>
                        <Link className="os-button os-button--primary os-button--sm" href={hwihaHref('command')}>
                            이번 순에 할 일
                        </Link>
                    </div>
                </Panel>
            </div>
        </HwihaShell>
    );
}
