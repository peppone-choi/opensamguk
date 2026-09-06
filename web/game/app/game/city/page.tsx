'use client';
import { Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Chip, Divider, Gauge, Panel, Portrait, SectionHeader } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import GeneralName from '../../../components/game/GeneralName';
import { api } from '../../../lib/api';
import type { CityDetailResponse, CityGeneralRow } from '../../../types/game';
import { useServerGameUrl } from '../../../lib/serverGameUrl';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';

// 도시 상세 본문(b_currentCity.php 패러티 · ADR-LITE-049 06 도시) — 쿼리 ?id=<도시번호>로 특정 도시를 조회한다
// (MapViewer 도시 클릭 진입). id가 없거나 0이면 현재 장수 소재 도시(서버가 0을 현재 도시로 해석).
//
// 섹션(레거시 b_currentCity.php 순서를 06 아트보드 배치로):
//   1) 머리: 제목 + 도시선택 셀렉터(citySelector) — 재야=현재도시만 / 관직자=아국+주둔타국+첩보. 선택 시 ?id= 라우팅.
//   2) 히어로: 도시명(세리프) ·【지역 | 등급】· 공백/지배/보급 칩 · 갱신시각(lastExecute) · 태수/군사/종사 · 주둔.
//   3) 좌: 내정 8게이지(주민/민심/농업/상업/치안/수비/성벽/시세) + 관직 3칸 + 주둔 집계(도시명·적군·병장(총)·90/60병장·수비○·장수).
//   4) 우: 주둔 장수 표(얼 굴 · 이 름 · 통솔 · 무력 · 지력 · 정치 · 매력 · 관 직 · 守 · 병 종 · 병 사 · 훈련 · 사기 · 명 령 — 14열 verbatim).
//
// 첩보(fog) 패러티: visible=false면 서버가 내정/방어 수치를 null로 마스킹 → 게이지 대신 안내(수치 날조 없음).
// showDetailedInfo=false면 장수표/장수명을 숨긴다(visible과 별개 게이트).
// 인접 도시 표(06 아트보드)는 백엔드 원천(도시 간 거리·인접 병력)이 없어 그리지 않는다.
// 통무지 부상 색·병종 마스킹("?")·NPC색 등 표시 규칙은 cityGeneral.php를 충실 포팅.

// formatWounded(leadership/strength/intel, injury) — 부상 시 색 span. 레거시 formatWounded는 injury>0이면
// formatInjury 색으로 능력치를 물들인다(값은 그대로). injury==0이면 plain.
function StatCell({ value, injury }: { value: number; injury: number }) {
    if (injury > 0) {
        const woundedValue = Math.trunc(value * (100 - injury) / 100);
        return <span style={{ color: 'var(--rust-2)' }}>{woundedValue}</span>;
    }
    return <>{value}</>;
}

// 장수명 셀 — NPC색(getNPCColor) 적용. 비-NPC는 기본색.
function NameSpan({ name, npc }: { name: string; npc: number }) {
    return <GeneralName name={name} npcType={npc} />;
}

// 도시선택 relation → 한글(b_currentCity.php: 공백지/본국/타국).
const RELATION_TEXT: Record<number, string> = { 0: '공백지', 1: '본국', 2: '타국' };

const fmt = (n: number | null | undefined) => (n ?? 0).toLocaleString();

function CityDetail() {
    const searchParams = useSearchParams();
    const router = useRouter();
    const cityBaseHref = useServerGameUrl('city');
    const idParam = searchParams.get('id');
    const cityId = idParam != null && idParam !== '' ? Number(idParam) : 0;

    const [city, setCity] = useState<CityDetailResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    // background=true(턴 갱신)면 로딩 스켈레톤을 띄우지 않는다(OPENSAM-196).
    const fetchData = async (background = false) => {
        if (!background) setLoading(true);
        setError('');
        try {
            const res = await api.city<CityDetailResponse>(Number.isFinite(cityId) ? cityId : 0);
            setCity(res);
        } catch {
            setError('도시 정보를 불러올 수 없습니다.');
        } finally {
            if (!background) setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [cityId]);

    // 턴 완료 시 현재 도시 정보만 조용히 재조회(OPENSAM-196).
    useTurnRefresh(() => {
        fetchData(true);
    });

    if (loading) {
        return (
            <>
                <PageHead title="도시 정보" />
                <p className="text-muted">로딩 중...</p>
            </>
        );
    }
    if (error) {
        return (
            <>
                <PageHead title="도시 정보" />
                <div className="error-state">
                    <p>{error}</p>
                    <button type="button" className="os-button os-button--sm" onClick={() => fetchData()}>다시 시도</button>
                </div>
            </>
        );
    }
    if (!city) {
        return (
            <>
                <PageHead title="도시 정보" />
                <p className="text-muted">도시 정보가 없습니다.</p>
            </>
        );
    }

    const neutral = city.nationId === 0;
    const m = city.military;
    const supplyCut = city.supplyState === 0 && !neutral;

    return (
        <div className="page-wide">
            <PageHead
                title="도시 정보"
                actions={city.citySelector.length > 0 ? (
                    // ── 1) 도시선택 셀렉터(b_currentCity.php:73-159) ──────────────────────────
                    <label className="city-select">
                        <span>도시선택:</span>
                        <select
                            value={city.id}
                            onChange={(e) => router.push(`${cityBaseHref}?id=${encodeURIComponent(e.target.value)}`)}
                        >
                            {city.citySelector.map((o) => (
                                <option key={o.cityId} value={o.cityId}>
                                    {`【${o.cityName}】${RELATION_TEXT[o.relation] ?? ''}`}
                                </option>
                            ))}
                        </select>
                        <span className="text-muted">명령 화면에서 도시를 클릭하셔도 됩니다.</span>
                    </label>
                ) : undefined}
            />

            {/* ── 2) 히어로 —【지역 | 등급】도시명 + 갱신시각(lastExecute) + 공백/지배/보급 배지 ── */}
            <section className="city-hero" aria-label={`${city.name} 개요`}>
                <div>
                    <div className="city-hero__name">{city.name}</div>
                    <div className="city-hero__meta">【{city.regionName} | {city.levelName}】</div>
                </div>
                <div className="city-hero__chips">
                    <Chip tone={neutral ? 'neutral' : 'moss'}>{neutral ? '공 백 지' : '지배 도시'}</Chip>
                    {supplyCut && <Chip tone="rust">보급 끊김</Chip>}
                </div>
                {/* 갱신시각(lastExecute) — config["turntime"] 미배선 시 null → 미표시(날조 없음). */}
                {city.lastExecute && <span className="city-hero__stamp">갱신 {city.lastExecute}</span>}
                <div className="city-hero__facts">
                    <span>태수 <b><NameSpan name={city.officerGovernor.name} npc={city.officerGovernor.npc} /></b></span>
                    <span>군사 <b><NameSpan name={city.officerStrategist.name} npc={city.officerStrategist.npc} /></b></span>
                    <span>종사 <b><NameSpan name={city.officerSecretary.name} npc={city.officerSecretary.npc} /></b></span>
                    {city.visible && <span>주둔 <b className="os-num">{city.officers ?? 0}명 · {fmt(m.crewTotal)}</b></span>}
                </div>
            </section>

            <div className="city-grid">
                <div className="city-grid__side">
                    {/* ── 3) 게이지(주민/민심/농업/상업/치안/수비/성벽/시세) — visible=false면 마스킹 안내 ── */}
                    <Panel className="record-panel">
                        <SectionHeader title="내정" sub="8항목" />
                        {city.visible ? (
                            <>
                                <div className="city-gauges">
                                    <Gauge label="주민" value={city.population ?? 0} max={city.populationMax ?? 0} display={`${fmt(city.population)} / ${fmt(city.populationMax)}`} />
                                    <Gauge label="민심" value={city.trust ?? 0} max={100} tone="bronze" display={(city.trust ?? 0).toLocaleString(undefined, { maximumFractionDigits: 1 })} />
                                    <Gauge label="농업" value={city.agriculture ?? 0} max={city.agricultureMax ?? 0} display={`${fmt(city.agriculture)} / ${fmt(city.agricultureMax)}`} />
                                    <Gauge label="상업" value={city.commerce ?? 0} max={city.commerceMax ?? 0} display={`${fmt(city.commerce)} / ${fmt(city.commerceMax)}`} />
                                    <Gauge label="치안" value={city.security ?? 0} max={city.securityMax ?? 0} display={`${fmt(city.security)} / ${fmt(city.securityMax)}`} />
                                    <Gauge label="수비" value={city.defense ?? 0} max={city.defenseMax ?? 0} display={`${fmt(city.defense)} / ${fmt(city.defenseMax)}`} />
                                    <Gauge label="성벽" value={city.wall ?? 0} max={city.wallMax ?? 0} display={`${fmt(city.wall)} / ${fmt(city.wallMax)}`} />
                                    {/* 시세(trade %) — 레거시 (trade-95)*10 클램프. trade==null(상인 없음)이면 텍스트만. */}
                                    <Gauge
                                        label="시세"
                                        value={city.trade != null ? Math.min(100, Math.max(0, (city.trade - 95) * 10)) : 0}
                                        max={100}
                                        display={city.trade != null ? `${city.trade}%` : '상인 없음'}
                                    />
                                </div>
                                <Divider style={{ margin: '0 14px 12px' }} />
                                {/* ── 4a) 관직자(태수/군사/종사) — b_currentCity.php:475-480 ── */}
                                <div className="city-officers">
                                    {([['태수', city.officerGovernor], ['군사', city.officerStrategist], ['종사', city.officerSecretary]] as const).map(([k, o]) => (
                                        <div key={k} className="os-inset">
                                            <span className="city-officers__k">{k}</span>
                                            <span className="city-officers__v"><NameSpan name={o.name} npc={o.npc} /></span>
                                        </div>
                                    ))}
                                </div>
                            </>
                        ) : (
                            <div className="city-fog">
                                {neutral ? '공백지입니다.' : '다른 세력의 도시입니다.'} 첩보가 없어 내정 정보를 볼 수 없습니다.
                            </div>
                        )}
                    </Panel>

                    {city.visible && (
                        // ── 4b) 군사 집계행(적군/병장총/90·60병장/수비○) + 장수명 CSV — b_currentCity.php:481-500 ──
                        <Panel className="record-panel">
                            <SectionHeader title="주둔" sub={`${city.officers ?? 0}명 · ${fmt(m.crewTotal)}`} tone="rust" />
                            <dl className="os-kv city-military">
                                <dt>도시명</dt><dd>{city.cityName}</dd>
                                {/* 적군 = 병력/무장장수수(장수수). number_format = toLocaleString. */}
                                <dt>적군</dt><dd>{`${fmt(m.enemyCrew)}/${fmt(m.enemyArmedCnt)}(${fmt(m.enemyCnt)})`}</dd>
                                <dt>병장(총)</dt><dd>{`${fmt(m.crewTotal)}/${fmt(m.armedGenTotal)}(${fmt(m.genTotal)})`}</dd>
                                <dt>90병장</dt><dd>{`${fmt(m.crew90)}/${fmt(m.gen90)}`}</dd>
                                <dt>60병장</dt><dd>{`${fmt(m.crew60)}/${fmt(m.gen60)}`}</dd>
                                {/* 수비○ = min(훈련,사기)>=defence_train 집계. defence_train 원천 미배선(BE 0 하드코딩)
                                    → 무장 장수 전원이 집계돼 과집계 위조. 배선 전 '-' 마스킹(수치 날조 없음). */}
                                <dt>수비○</dt><dd>-</dd>
                                <dt>장수</dt>
                                <dd className="city-military__names">
                                    {city.showDetailedInfo
                                        ? city.generalNames.map((g, i) => (
                                            <span key={`${g.name}-${i}`}>
                                                {i > 0 && ', '}
                                                <NameSpan name={g.name} npc={g.npc} />
                                            </span>
                                        ))
                                        : <span className="text-muted">알 수 없음</span>}
                                </dd>
                            </dl>
                        </Panel>
                    )}
                </div>

                {/* ── 5) 장수 상세표(b_currentCity.php:503-529 + cityGeneral.php) — 14열 verbatim ── */}
                {city.showDetailedInfo && city.generals.length > 0 && (
                    <Panel className="record-panel city-generals">
                        <SectionHeader title="주둔 장수" sub={`${city.generals.length}명`} />
                        <div className="os-table-wrap">
                            <table className="os-table os-table--nowrap">
                                <thead>
                                    <tr>
                                        <th scope="col">얼 굴</th>
                                        <th scope="col">이 름</th>
                                        <th scope="col">통솔</th>
                                        <th scope="col">무력</th>
                                        <th scope="col">지력</th>
                                        <th scope="col">정치</th>
                                        <th scope="col">매력</th>
                                        <th scope="col">관 직</th>
                                        <th scope="col">守</th>
                                        <th scope="col">병 종</th>
                                        <th scope="col">병 사</th>
                                        <th scope="col">훈련</th>
                                        <th scope="col">사기</th>
                                        <th scope="col">명 령</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {city.generals.map((g) => <GeneralRow key={g.no} g={g} />)}
                                </tbody>
                            </table>
                        </div>
                    </Panel>
                )}
            </div>
        </div>
    );
}

// 장수 상세표 행(cityGeneral.php). ourGeneral이면 병종/병사/훈련/사기/守(defenceTrain) 노출, 타국은 "?",
// 재야는 "재 야". defenceTrain은 BE 원천 미배선(GeneralReadEntity defence_train 컬럼 부재) →
// 守는 '-' 마스킹(P0-14 — 전원 '△' 위조 표기 제거). 원천 배선 후 formatDefenceTrain 복원.
function GeneralRow({ g }: { g: CityGeneralRow }) {
    return (
        <tr>
            {/* 얼굴 — 초상 아이콘 28(초상 3종 규칙: 표는 96 아이콘 변형). iconPath 가 비면 기본 초상. */}
            <td><Portrait picture={g.iconPath || null} imageServer={0} size="icon-28" alt="" /></td>
            <td><NameSpan name={g.name} npc={g.npc} /></td>
            {/* 통솔 — 통솔 + leadershipBonus(있으면 +N). 부상 시 색. */}
            <td className="os-num">
                <StatCell value={g.leadership} injury={g.wounded} />
                {g.leadershipBonus > 0 && <span style={{ color: 'var(--info)' }}>+{g.leadershipBonus}</span>}
            </td>
            <td className="os-num"><StatCell value={g.strength} injury={g.wounded} /></td>
            <td className="os-num"><StatCell value={g.intel} injury={g.wounded} /></td>
            {/* 정치·매력 — RTK 표준 순서(통무지정매). 부상색·보너스는 통무지 전용이라 미적용, plain 값(필드 optional → '-'). */}
            <td className="os-num">{g.politics ?? '-'}</td>
            <td className="os-num">{g.charm ?? '-'}</td>
            <td>{g.officerLevelText}</td>
            {g.ourGeneral ? (
                <>
                    {/* 守 = 수비 훈련도 기호(formatDefenceTrain). defence_train 원천 미배선이라 전원 '△'로
                        위조 표기되던 것을 '-' 마스킹(수치 날조 없음). 원천 배선 시 formatDefenceTrain 복원. */}
                    <td>-</td>
                    <td>{g.crewTypeName}</td>
                    <td className="os-num">{g.crew}</td>
                    <td className="os-num">{g.train}</td>
                    <td className="os-num">{g.atmos}</td>
                    {/* 명령 — turnText(예약명령 brief). BE 미emit(general_turn brief 미배선) → NPC 표기/공란. */}
                    <td>{g.isNPC ? 'NPC 장수' : ''}</td>
                </>
            ) : (
                <>
                    <td>?</td>
                    <td>?</td>
                    <td className="os-num">{g.crew >= 0 ? g.crew : '?'}</td>
                    <td>?</td>
                    <td>?</td>
                    <td>{g.nation !== 0 ? `【${g.nationName}】 장수` : '재 야'}</td>
                </>
            )}
        </tr>
    );
}

export default function CityPage() {
    // useSearchParams는 Suspense 경계가 필요(app router CSR bailout).
    return (
        <Shell>
            <Suspense fallback={<><PageHead title="도시 정보" /><p className="text-muted">로딩 중...</p></>}>
                <CityDetail />
            </Suspense>
        </Shell>
    );
}
