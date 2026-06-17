'use client';

import { useCallback, useEffect, useState } from 'react';
import AuthGate from '@/components/AuthGate';
import Topbar from '@/components/Topbar';
import ConfirmModal from '@/components/ConfirmModal';
import MemberControl from '@/components/admin/MemberControl';

// F5 어드민 = 가드 + 셸 + "서버 제어" 탭(버전 표시/버전-선택 재배포) + "회원 관리" 탭(B2f).
// "게임 환경"(B1e)은 락(걸기/풀기 + 동결중/가동중) 부분만 우선 배선. 시간조정/봉급/운영자메시지/
// 시작시간/최대장수·국가/시작년도/턴시간 등 나머지는 후속 웨이브 — '준비 중' 플레이스홀더 유지.
// 섹션명은 verbatim 패러티 대상, 본문은 탭별로 분기.
const ADMIN_SECTIONS = [
    { id: 'members', label: '회원 관리' },
    { id: 'server', label: '서버 제어' },
    { id: 'game', label: '게임 환경' },
] as const;

const PLACEHOLDER = '준비 중';

// ===== 백엔드 DTO 미러 (admin/version, admin/deploy) =====
interface ServiceVersion {
    reachable: boolean;
    version: string | null;
    imageTag: string | null;
    buildTime: string | null;
}
interface ServerVersion {
    id: string;
    name: string;
    generation?: number | null;
    gameApi: ServiceVersion;
    gameEngine: ServiceVersion;
    skew: boolean;
}
interface VersionResponse {
    gateway: ServiceVersion;
    servers: ServerVersion[];
    skew: boolean;
}
interface DeployStatus {
    configured: boolean;
    serverId: string | null;
    currentTag: string | null;
    availableTags: string[];
    message?: string | null;
}
interface DeployResult {
    ok: boolean;
    message: string;
    detail?: string | null;
}
interface EnvField {
    key: string;
    value: string | null;
    configured: boolean;
    writeOnly: boolean;
    masked: boolean;
    metadata?: {
        description?: string;
    };
}
interface EnvConfigResponse {
    ok?: boolean;
    configured?: boolean;
    scope: 'shared' | 'server';
    id?: string;
    restartRequired?: boolean;
    affectedServices?: string[];
    fields: Record<string, EnvField>;
    message?: string | null;
}
interface ServerCreateResponse {
    ok: boolean;
    id?: string;
    name?: string;
    project?: string;
    restartRequired?: boolean;
    affectedServices?: string[];
    detail?: string | null;
    message?: string | null;
    error?: string | null;
}
interface ServerResetOptions {
    generation: string;
    scenarioCode: string;
    scenarioSeedEnabled: boolean;
    turnTerm: string;
    sync: string;
    fiction: string;
    extend: string;
    blockGeneralCreate: string;
    npcMode: string;
    showImgLevel: string;
    autorunUserOptions: string[];
    autorunUserMinutes: string;
    joinMode: string;
    tournamentTrig: string;
    reserveOpen: string;
    preReserveOpen: string;
}

// B1e 게임 설정 — world_state.config 에서 읽고 PATCH로 수정 가능한 항목.
interface AdminFieldOption {
    value: string;
    label: string;
}
interface AdminEditableField {
    key: string;
    label: string;
    type: string;
    value: unknown;
    options?: AdminFieldOption[];
}
interface AdminBlockedWrite {
    label: string;
    reason: string;
}
interface AdminGameSettingsResponse {
    msg: string;
    logWritable: boolean;
    scenarioCode?: string;
    year?: number;
    month?: number;
    starttime?: string;
    startyear?: number;
    maxgeneral?: number;
    maxnation?: number;
    turntime?: string;
    turnterm?: number;
    turnOptions: number[];
    blockedWrites: AdminBlockedWrite[];
    editableFields: AdminEditableField[];
}
interface ScenarioOption {
    code: string;
    title: string;
}
interface ScenarioListResponse {
    scenarios: ScenarioOption[];
}

// ===== B1b 락(동결) — game-engine StatusController DTO 미러 =====
// GET  /admin/turn-daemon/status → TurnDaemonStatus
// POST /admin/turn-daemon/pause  → TurnDaemonControlResult (락걸기)
// POST /admin/turn-daemon/resume → TurnDaemonControlResult (락풀기)
interface TurnDaemonStatus {
    profile: string;
    state: string;
    running: boolean;
    paused: boolean;
    loopAlive: boolean;
    statusLabel: string; // PHP `_119.php:36` verbatim: "동결중" / "가동중"
}
interface TurnDaemonControlResult {
    paused: boolean;
    changed: boolean;
    statusLabel: string;
}

// 버전 불일치 경고 — game-engine은 자동 재배포 제외라 시즌 경계에서 수동 갱신 필요.
const SKEW_WARNING =
    '⚠ 버전 불일치 — game-engine은 자동 재배포 제외, 시즌 경계에서 수동 갱신 필요';
const AUTORUN_OPTIONS = [
    ['develop', '내정'],
    ['warp', '순간이동'],
    ['recruit', '징병'],
    ['recruit_high', '모병'],
    ['train', '훈사'],
    ['battle', '출병'],
    ['chief', '기본 사령턴'],
] as const;
const AUTORUN_MINUTES = [
    ['0', '꺼짐'],
    ['43200', '항상'],
    ['10', '10분'],
    ['20', '20분'],
    ['30', '30분'],
    ['60', '1시간'],
    ['120', '2시간'],
    ['180', '3시간'],
    ['240', '4시간'],
    ['360', '6시간'],
    ['480', '8시간'],
    ['600', '10시간'],
    ['720', '12시간'],
    ['1440', '24시간'],
    ['2160', '36시간'],
    ['2880', '48시간'],
    ['3600', '60시간'],
    ['4320', '72시간'],
] as const;

/** 인증 프록시 GET — JSON 파싱. 비-2xx면 throw. */
async function getJson<T>(path: string): Promise<T> {
    const res = await fetch(`/api/proxy/${path}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`요청 실패 (${res.status})`);
    return (await res.json()) as T;
}

const BOOLEAN_ENV_KEYS = new Set(['COOKIE_SECURE', 'SCENARIO_SEED_ENABLED']);

function fieldInitialValue(field: EnvField): string {
    if (field.writeOnly) return '';
    return field.value ?? '';
}

function EnvFieldInput({
    field,
    value,
    disabled,
    onChange,
}: {
    field: EnvField;
    value: string;
    disabled: boolean;
    onChange: (value: string) => void;
}) {
    if (BOOLEAN_ENV_KEYS.has(field.key)) {
        return (
            <label className="env-toggle">
                <input
                    type="checkbox"
                    checked={value === 'true'}
                    disabled={disabled}
                    onChange={(e) => onChange(e.target.checked ? 'true' : 'false')}
                />
                <span>{value === 'true' ? 'true' : 'false'}</span>
            </label>
        );
    }

    return (
        <input
            type={field.writeOnly ? 'password' : field.key.endsWith('_PORT') ? 'number' : 'text'}
            inputMode={field.key.endsWith('_PORT') ? 'numeric' : undefined}
            value={value}
            disabled={disabled}
            placeholder={field.writeOnly && field.configured ? '새 값 입력' : undefined}
            onChange={(e) => onChange(e.target.value)}
        />
    );
}

/** 한 서비스 버전 셀 — reachable=false면 '응답 없음' 뱃지, 아니면 버전/태그/빌드시각. */
function ServiceCell({ svc }: { svc: ServiceVersion }) {
    if (!svc.reachable) {
        return <span className="status-badge status-crimson">응답 없음</span>;
    }
    return (
        <div className="svc-cell">
            <span className="svc-version">{svc.version ?? '-'}</span>
            <span className="svc-meta">태그 {svc.imageTag ?? '-'}</span>
            {svc.buildTime && <span className="svc-meta">빌드 {svc.buildTime}</span>}
        </div>
    );
}

function EnvConfigEditor({
    title,
    config,
    drafts,
    busy,
    onChange,
    onSave,
}: {
    title: string;
    config: EnvConfigResponse | null;
    drafts: Record<string, string>;
    busy: boolean;
    onChange: (key: string, value: string) => void;
    onSave: () => void;
}) {
    if (!config) {
        return <p className="svc-meta">환경 설정 조회 중…</p>;
    }
    if (config.configured === false) {
        return <p className="deploy-note">{config.message ?? 'deployer가 설정되지 않았습니다.'}</p>;
    }
    const fields = Object.values(config.fields).sort((a, b) => a.key.localeCompare(b.key));
    const changed = fields.some((field) => {
        const value = drafts[field.key] ?? '';
        if (field.writeOnly) return value.trim() !== '';
        return value !== fieldInitialValue(field);
    });

    return (
        <div className="env-config-editor">
            <div className="env-config-head">
                <h3 className="lobby-section-title">{title}</h3>
                {config.restartRequired && (
                    <span className="status-badge status-gold">재시작 필요</span>
                )}
            </div>
            <div className="env-field-list">
                {fields.map((field) => (
                    <label key={field.key} className="env-field-row">
                        <span className="env-field-meta">
                            <strong>{field.key}</strong>
                            <small>{field.metadata?.description ?? (field.writeOnly ? '비밀값' : '설정값')}</small>
                        </span>
                        <EnvFieldInput
                            field={field}
                            value={drafts[field.key] ?? fieldInitialValue(field)}
                            disabled={busy}
                            onChange={(value) => onChange(field.key, value)}
                        />
                        {field.masked && <span className="status-badge status-jade">숨김</span>}
                    </label>
                ))}
            </div>
            {config.affectedServices && config.affectedServices.length > 0 && (
                <p className="deploy-note">
                    적용 대상 {config.affectedServices.join(', ')} · game-engine은 자동 재기동하지 않습니다.
                </p>
            )}
            <button type="button" className="btn-primary" disabled={busy || !changed} onClick={onSave}>
                저장
            </button>
        </div>
    );
}

/** 서버별 배포 제어 — 현재 태그 + 배포 가능한 태그 선택 → 확인 모달 → POST. */
function DeployControl({
    server,
    status,
    onReload,
}: {
    server: ServerVersion;
    status: DeployStatus | undefined;
    onReload: (serverId: string) => void;
}) {
    const [selected, setSelected] = useState<string>('');
    const [confirming, setConfirming] = useState(false);
    const [busy, setBusy] = useState(false);
    const [result, setResult] = useState<DeployResult | null>(null);

    // 상태 로드/변경 시 현재 태그를 기본 선택으로 동기화.
    useEffect(() => {
        if (status?.currentTag) setSelected(status.currentTag);
    }, [status?.currentTag]);

    if (!status) {
        return <p className="svc-meta">상태 조회 중…</p>;
    }

    // deployer 미설정 — 컨트롤 숨기고 안내만.
    if (!status.configured) {
        return (
            <p className="deploy-note">
                {status.message ?? '배포 deployer가 설정되지 않았습니다 (로컬/미배포 환경).'}
            </p>
        );
    }

    const isCurrent = selected === status.currentTag;

    async function runDeploy() {
        setBusy(true);
        setResult(null);
        try {
            const res = await fetch('/api/proxy/admin/deploy', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ serverId: server.id, tag: selected }),
            });
            const data = (await res.json()) as DeployResult;
            setResult(data);
            if (data.ok) onReload(server.id); // 성공 시 해당 서버 상태 재조회.
        } catch {
            setResult({ ok: false, message: '재배포 요청에 실패했습니다.' });
        } finally {
            setBusy(false);
            setConfirming(false);
        }
    }

    return (
        <div className="deploy-control">
            <div className="deploy-row">
                <span className="svc-meta">
                    현재 버전 <strong>{status.currentTag ?? '-'}</strong>
                </span>
                <select
                    aria-label={`${server.name} 배포 태그 선택`}
                    value={selected}
                    onChange={(e) => setSelected(e.target.value)}
                    disabled={busy}
                >
                    {status.availableTags.length === 0 && <option value="">(가능한 태그 없음)</option>}
                    {status.availableTags.map((tag) => (
                        <option key={tag} value={tag}>
                            {tag}
                            {tag === status.currentTag ? ' (현재)' : ''}
                        </option>
                    ))}
                </select>
                <button
                    type="button"
                    className="btn-primary"
                    disabled={busy || isCurrent || !selected}
                    onClick={() => setConfirming(true)}
                >
                    {isCurrent ? '현재 버전' : '이 버전으로 배포'}
                </button>
            </div>

            {result && (
                <p className={`deploy-result ${result.ok ? 'ok' : 'fail'}`}>
                    {result.message}
                    {result.detail && <span className="svc-meta"> · {result.detail}</span>}
                </p>
            )}

            <ConfirmModal
                open={confirming}
                title="버전 재배포 확인"
                danger
                busy={busy}
                confirmLabel="배포 실행"
                message={
                    <>
                        서버 &apos;{server.name}&apos;을(를) &apos;{selected}&apos; 버전으로 재배포합니다.
                        <br />
                        game-engine(진행 중 턴 상태)은 영향받지 않습니다. 계속할까요?
                    </>
                }
                onConfirm={runDeploy}
                onCancel={() => setConfirming(false)}
            />
        </div>
    );
}

function ServerLifecycleControl({
    server,
    scenarios,
    onChanged,
}: {
    server: ServerVersion;
    scenarios: ScenarioOption[];
    onChanged: () => void;
}) {
    const defaultScenario = scenarios.find((scenario) => scenario.code === 'scenario_1010')?.code || scenarios[0]?.code || '';
    const [mode, setMode] = useState<'reset' | 'delete' | null>(null);
    const [busy, setBusy] = useState(false);
    const [result, setResult] = useState<ServerCreateResponse | null>(null);
    const [resetOptions, setResetOptions] = useState<ServerResetOptions>({
        generation: String((server.generation ?? 0) + 1 || 1),
        scenarioCode: defaultScenario,
        scenarioSeedEnabled: true,
        turnTerm: '60',
        sync: '1',
        fiction: '1',
        extend: '1',
        blockGeneralCreate: '0',
        npcMode: '0',
        showImgLevel: '3',
        autorunUserOptions: ['develop', 'warp', 'recruit', 'recruit_high', 'train', 'battle', 'chief'],
        autorunUserMinutes: '1440',
        joinMode: 'full',
        tournamentTrig: '1',
        reserveOpen: '',
        preReserveOpen: '',
    });

    useEffect(() => {
        if (!resetOptions.scenarioCode && defaultScenario) {
            setResetOptions((prev) => ({ ...prev, scenarioCode: defaultScenario }));
        }
    }, [defaultScenario, resetOptions.scenarioCode]);

    function setReset<K extends keyof ServerResetOptions>(key: K, value: ServerResetOptions[K]) {
        setResetOptions((prev) => ({ ...prev, [key]: value }));
    }

    function toggleAutorun(option: string, checked: boolean) {
        setResetOptions((prev) => ({
            ...prev,
            autorunUserOptions: checked
                ? Array.from(new Set([...prev.autorunUserOptions, option]))
                : prev.autorunUserOptions.filter((item) => item !== option),
        }));
    }

    async function runDelete() {
        setBusy(true);
        setResult(null);
        try {
            const res = await fetch(`/api/proxy/admin/servers/${encodeURIComponent(server.id)}`, {
                method: 'DELETE',
            });
            const data = (await res.json()) as ServerCreateResponse;
            setResult(data);
            if (res.ok && data.ok) onChanged();
        } catch {
            setResult({ ok: false, message: '서버 삭제 요청에 실패했습니다.' });
        } finally {
            setBusy(false);
            setMode(null);
        }
    }

    async function runReset() {
        if (!resetOptions.scenarioCode) {
            setResult({ ok: false, message: '시나리오를 선택해야 리셋할 수 있습니다.' });
            setMode(null);
            return;
        }
        const generationNumber = Number.parseInt(resetOptions.generation, 10);
        if (!Number.isInteger(generationNumber) || generationNumber < 1) {
            setResult({ ok: false, message: '기수는 1 이상의 숫자여야 합니다.' });
            setMode(null);
            return;
        }
        setBusy(true);
        setResult(null);
        try {
            const res = await fetch(`/api/proxy/admin/servers/${encodeURIComponent(server.id)}/reset`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ...resetOptions, confirm: `RESET ${server.id}` }),
            });
            const data = (await res.json()) as ServerCreateResponse;
            setResult(data);
            if (res.ok && data.ok) onChanged();
        } catch {
            setResult({ ok: false, message: '서버 리셋 요청에 실패했습니다.' });
        } finally {
            setBusy(false);
            setMode(null);
        }
    }

    const resetForm = (
        <div className="server-reset-grid">
            <label className="field">
                <span>기수</span>
                <input
                    type="number"
                    min="1"
                    value={resetOptions.generation}
                    disabled={busy}
                    onChange={(e) => setReset('generation', e.target.value)}
                />
            </label>
            <label className="field">
                <span>턴 시간(분)</span>
                <select value={resetOptions.turnTerm} disabled={busy} onChange={(e) => setReset('turnTerm', e.target.value)}>
                    {['120', '60', '30', '20', '10', '5', '2', '1'].map((value) => (
                        <option key={value} value={value}>{value}</option>
                    ))}
                </select>
            </label>
            <label className="field">
                <span>시간 동기화</span>
                <select value={resetOptions.sync} disabled={busy} onChange={(e) => setReset('sync', e.target.value)}>
                    <option value="1">Y</option>
                    <option value="0">N</option>
                </select>
            </label>
            <label className="field">
                <span>시나리오 선택</span>
                <select
                    value={resetOptions.scenarioCode}
                    disabled={busy}
                    onChange={(e) => setReset('scenarioCode', e.target.value)}
                >
                    {scenarios.map((scenario) => (
                        <option key={scenario.code} value={scenario.code}>
                            {scenario.title || scenario.code} ({scenario.code})
                        </option>
                    ))}
                </select>
            </label>
            <label className="field">
                <span>NPC 상성</span>
                <select value={resetOptions.fiction} disabled={busy} onChange={(e) => setReset('fiction', e.target.value)}>
                    <option value="0">연의</option>
                    <option value="1">가상</option>
                </select>
            </label>
            <label className="field">
                <span>확장 NPC</span>
                <select value={resetOptions.extend} disabled={busy} onChange={(e) => setReset('extend', e.target.value)}>
                    <option value="1">포함</option>
                    <option value="0">미포함</option>
                </select>
            </label>
            <label className="field">
                <span>장수 임의 생성</span>
                <select
                    value={resetOptions.blockGeneralCreate}
                    disabled={busy}
                    onChange={(e) => setReset('blockGeneralCreate', e.target.value)}
                >
                    <option value="0">가능</option>
                    <option value="2">장수명무작위</option>
                    <option value="1">불가</option>
                </select>
            </label>
            <label className="field">
                <span>NPC 빙의</span>
                <select value={resetOptions.npcMode} disabled={busy} onChange={(e) => setReset('npcMode', e.target.value)}>
                    <option value="1">가능</option>
                    <option value="0">불가</option>
                    <option value="2">선택 생성 가능</option>
                </select>
            </label>
            <label className="field">
                <span>이미지 표기</span>
                <select
                    value={resetOptions.showImgLevel}
                    disabled={busy}
                    onChange={(e) => setReset('showImgLevel', e.target.value)}
                >
                    <option value="0">안함</option>
                    <option value="1">전콘</option>
                    <option value="2">전콘, 병종</option>
                    <option value="3">전콘, 병종, NPC</option>
                </select>
            </label>
            <fieldset className="field reset-wide">
                <legend>휴식 턴 시 장수 턴</legend>
                <div className="reset-checks">
                    {AUTORUN_OPTIONS.map(([value, label]) => (
                        <label key={value} className="env-toggle">
                            <input
                                type="checkbox"
                                checked={resetOptions.autorunUserOptions.includes(value)}
                                disabled={busy}
                                onChange={(e) => toggleAutorun(value, e.target.checked)}
                            />
                            {label}
                        </label>
                    ))}
                </div>
            </fieldset>
            <label className="field">
                <span>자동 행동 유효 시간</span>
                <select
                    value={resetOptions.autorunUserMinutes}
                    disabled={busy}
                    onChange={(e) => setReset('autorunUserMinutes', e.target.value)}
                >
                    {AUTORUN_MINUTES.map(([value, label]) => (
                        <option key={value} value={value}>{label}</option>
                    ))}
                </select>
            </label>
            <label className="field">
                <span>임관 모드</span>
                <select value={resetOptions.joinMode} disabled={busy} onChange={(e) => setReset('joinMode', e.target.value)}>
                    <option value="full">일반</option>
                    <option value="onlyRandom">랜덤 임관</option>
                </select>
            </label>
            <label className="field">
                <span>토너먼트 자동 시작</span>
                <select
                    value={resetOptions.tournamentTrig}
                    disabled={busy}
                    onChange={(e) => setReset('tournamentTrig', e.target.value)}
                >
                    <option value="0">수동</option>
                    <option value="1">자동</option>
                </select>
            </label>
            <label className="field">
                <span>오픈 예약</span>
                <input
                    value={resetOptions.reserveOpen}
                    disabled={busy}
                    placeholder="YYYY-MM-DD hh:mm"
                    onChange={(e) => setReset('reserveOpen', e.target.value)}
                />
            </label>
            <label className="field">
                <span>가오픈 예약</span>
                <input
                    value={resetOptions.preReserveOpen}
                    disabled={busy}
                    placeholder="YYYY-MM-DD hh:mm"
                    onChange={(e) => setReset('preReserveOpen', e.target.value)}
                />
            </label>
            <label className="env-toggle server-create-toggle">
                <input
                    type="checkbox"
                    checked={resetOptions.scenarioSeedEnabled}
                    disabled={busy}
                    onChange={(e) => setReset('scenarioSeedEnabled', e.target.checked)}
                />
                시나리오 자동 시드
            </label>
        </div>
    );

    return (
        <div className="deploy-control">
            <div className="deploy-row">
                <button type="button" className="btn-primary" disabled={busy} onClick={() => setMode('reset')}>
                    리셋
                </button>
                <button type="button" className="btn-danger" disabled={busy} onClick={() => setMode('delete')}>
                    삭제
                </button>
                <span className="deploy-note">리셋은 해당 서버 DB/Redis 볼륨을 초기화합니다.</span>
            </div>
            {result && (
                <p className={`deploy-result ${result.ok ? 'ok' : 'fail'}`}>
                    {result.ok
                        ? `${result.name ?? result.id} 처리 완료 · ${result.project ?? ''}`
                        : result.message || result.error || result.detail || '서버 제어 요청에 실패했습니다.'}
                </p>
            )}
            <ConfirmModal
                open={mode === 'reset'}
                title={`${server.name} 리셋`}
                danger
                busy={busy}
                confirmLabel="리셋 실행"
                message={
                    <>
                        <p className="deploy-note">PHP 리셋 화면 항목 기준으로 서버를 다시 초기화합니다.</p>
                        {resetForm}
                    </>
                }
                onConfirm={runReset}
                onCancel={() => setMode(null)}
            />
            <ConfirmModal
                open={mode === 'delete'}
                title={`${server.name} 삭제`}
                danger
                busy={busy}
                confirmLabel="삭제 실행"
                message={
                    <>
                        서버 &apos;{server.name}&apos;의 컨테이너, DB/Redis 볼륨, env, gateway registry 항목을 삭제합니다.
                        <br />
                        이 작업은 되돌릴 수 없습니다.
                    </>
                }
                onConfirm={runDelete}
                onCancel={() => setMode(null)}
            />
        </div>
    );
}

function CreateServerControl({ onCreated }: { onCreated: () => void }) {
    const [id, setId] = useState('s1');
    const [name, setName] = useState('통일 서버');
    const [generation, setGeneration] = useState('1');
    const [gameApiPort, setGameApiPort] = useState('8101');
    const [webGamePort, setWebGamePort] = useState('3101');
    const [imageTag, setImageTag] = useState('');
    const [scenarioCode, setScenarioCode] = useState('');
    const [scenarios, setScenarios] = useState<ScenarioOption[]>([]);
    const [scenarioSeedEnabled, setScenarioSeedEnabled] = useState(true);
    const [jwtSecret, setJwtSecret] = useState('');
    const [busy, setBusy] = useState(false);
    const [result, setResult] = useState<ServerCreateResponse | null>(null);

    useEffect(() => {
        let alive = true;
        getJson<ScenarioListResponse>('admin/scenarios')
            .then((data) => {
                if (!alive) return;
                setScenarios(data.scenarios);
                setScenarioCode((current) => {
                    const defaultScenario = data.scenarios.find((scenario) => scenario.code === 'scenario_1010');
                    return current || defaultScenario?.code || data.scenarios[0]?.code || '';
                });
            })
            .catch(() => {
                if (alive) setResult({ ok: false, message: '시나리오 목록을 불러오지 못했습니다.' });
            });
        return () => {
            alive = false;
        };
    }, []);

    async function createServer() {
        setBusy(true);
        setResult(null);
        try {
            const res = await fetch('/api/proxy/admin/servers', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    id,
                    name,
                    generation,
                    gameApiPort,
                    webGamePort,
                    imageTag,
                    scenarioCode,
                    scenarioSeedEnabled,
                    jwtSecret,
                }),
            });
            const data = (await res.json()) as ServerCreateResponse;
            setResult(data);
            if (res.ok && data.ok) onCreated();
        } catch {
            setResult({ ok: false, message: '서버 생성 요청에 실패했습니다.' });
        } finally {
            setBusy(false);
        }
    }

    const generationNumber = Number.parseInt(generation, 10);
    const valid =
        id.trim() !== '' &&
        name.trim() !== '' &&
        Number.isInteger(generationNumber) &&
        generationNumber >= 1 &&
        gameApiPort.trim() !== '' &&
        webGamePort.trim() !== '' &&
        scenarioCode.trim() !== '';

    return (
        <div className="deploy-server">
            <div className="deploy-server-head">새 서버 생성</div>
            <div className="server-create-grid">
                <label className="field">
                    <span>서버 ID</span>
                    <input value={id} disabled={busy} onChange={(e) => setId(e.target.value)} placeholder="s1" />
                </label>
                <label className="field">
                    <span>서버 이름</span>
                    <input value={name} disabled={busy} onChange={(e) => setName(e.target.value)} />
                </label>
                <label className="field">
                    <span>기수</span>
                    <input
                        type="number"
                        min="1"
                        value={generation}
                        disabled={busy}
                        onChange={(e) => setGeneration(e.target.value)}
                    />
                </label>
                <label className="field">
                    <span>game-api 포트</span>
                    <input
                        type="number"
                        min="1"
                        max="65535"
                        value={gameApiPort}
                        disabled={busy}
                        onChange={(e) => setGameApiPort(e.target.value)}
                    />
                </label>
                <label className="field">
                    <span>web-game 포트</span>
                    <input
                        type="number"
                        min="1"
                        max="65535"
                        value={webGamePort}
                        disabled={busy}
                        onChange={(e) => setWebGamePort(e.target.value)}
                    />
                </label>
                <label className="field">
                    <span>이미지 태그</span>
                    <input value={imageTag} disabled={busy} onChange={(e) => setImageTag(e.target.value)} />
                    <small className="field-hint">비우면 공유 스택 IMAGE_TAG를 사용합니다.</small>
                </label>
                <label className="field">
                    <span>시나리오</span>
                    <select value={scenarioCode} disabled={busy} onChange={(e) => setScenarioCode(e.target.value)}>
                        {scenarios.map((scenario) => (
                            <option key={scenario.code} value={scenario.code}>
                                {scenario.title}
                            </option>
                        ))}
                    </select>
                </label>
                <label className="field">
                    <span>JWT_SECRET</span>
                    <input
                        type="password"
                        value={jwtSecret}
                        disabled={busy}
                        onChange={(e) => setJwtSecret(e.target.value)}
                    />
                    <small className="field-hint">비우면 공유 스택 JWT_SECRET을 복사합니다.</small>
                </label>
                <label className="env-toggle server-create-toggle">
                    <input
                        type="checkbox"
                        checked={scenarioSeedEnabled}
                        disabled={busy}
                        onChange={(e) => setScenarioSeedEnabled(e.target.checked)}
                    />
                    시나리오 자동 시드
                </label>
            </div>
            <div className="deploy-row">
                <button type="button" className="btn-primary" disabled={busy || !valid} onClick={createServer}>
                    {busy ? '생성 중…' : '서버 생성'}
                </button>
                <span className="deploy-note">생성 후 gateway-api/web-gateway가 레지스트리를 다시 읽습니다.</span>
            </div>
            {result && (
                <p className={`deploy-result ${result.ok ? 'ok' : 'fail'}`}>
                    {result.ok
                        ? `${result.name ?? result.id} 생성 완료 · ${result.project ?? ''}`
                        : result.message || result.error || result.detail || '서버 생성에 실패했습니다.'}
                </p>
            )}
        </div>
    );
}

/** "서버 제어" 탭 — 전 서비스 버전 표 + 서버별 버전-선택 재배포. */
function ServerControl() {
    const [version, setVersion] = useState<VersionResponse | null>(null);
    const [scenarios, setScenarios] = useState<ScenarioOption[]>([]);
    const [statuses, setStatuses] = useState<Record<string, DeployStatus>>({});
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // 단일 서버 deploy/status 재조회 (배포 성공 후 갱신).
    const reloadStatus = useCallback(async (serverId: string) => {
        try {
            const st = await getJson<DeployStatus>(`admin/deploy/status?serverId=${encodeURIComponent(serverId)}`);
            setStatuses((prev) => ({ ...prev, [serverId]: st }));
        } catch {
            // 개별 서버 상태 실패는 전체를 막지 않는다 — 해당 서버만 '조회 중' 유지.
        }
    }, []);

    const loadVersion = useCallback(async (showSpinner = true) => {
        if (showSpinner) setLoading(true);
        setError(null);
        try {
            const [ver, scenarioData] = await Promise.all([
                getJson<VersionResponse>('admin/version'),
                getJson<ScenarioListResponse>('admin/scenarios'),
            ]);
            setVersion(ver);
            setScenarios(scenarioData.scenarios);
            const entries = await Promise.all(
                ver.servers.map(async (s) => {
                    try {
                        const st = await getJson<DeployStatus>(
                            `admin/deploy/status?serverId=${encodeURIComponent(s.id)}`,
                        );
                        return [s.id, st] as const;
                    } catch {
                        return null;
                    }
                }),
            );
            const map: Record<string, DeployStatus> = {};
            for (const e of entries) if (e) map[e[0]] = e[1];
            setStatuses(map);
        } catch {
            setError('서버 버전 정보를 불러오지 못했습니다.');
        } finally {
            if (showSpinner) setLoading(false);
        }
    }, []);

    useEffect(() => {
        let alive = true;
        (async () => {
            if (alive) await loadVersion(true);
        })();
        return () => {
            alive = false;
        };
    }, [loadVersion]);

    if (loading) {
        return (
            <div className="center-inline">
                <div className="spinner" />
            </div>
        );
    }
    if (error || !version) {
        return <p className="deploy-result fail">{error ?? '데이터가 없습니다.'}</p>;
    }

    return (
        <div className="server-control">
            {version.skew && <div className="skew-banner">{SKEW_WARNING}</div>}

            <div className="deploy-section">
                <CreateServerControl onCreated={() => loadVersion(false)} />
            </div>

            <div className="game-table-wrap">
                <table className="game-table">
                    <caption>실행 버전</caption>
                    <thead>
                        <tr>
                            <th>서버</th>
                            <th>서비스</th>
                            <th>버전</th>
                        </tr>
                    </thead>
                    <tbody>
                        {/* gateway는 서버 무관 단일 행. */}
                        <tr>
                            <td>게이트웨이</td>
                            <td>gateway</td>
                            <td>
                                <ServiceCell svc={version.gateway} />
                            </td>
                        </tr>
                        {version.servers.map((server) =>
                            (
                                [
                                    ['game-api', server.gameApi],
                                    ['game-engine', server.gameEngine],
                                ] as const
                            ).map(([svcName, svc], i) => (
                                <tr key={`${server.id}-${svcName}`}>
                                    {/* 서버명은 첫 서비스 행에만(rowSpan). */}
                                    {i === 0 && (
                                        <td rowSpan={2}>
                                            {server.name}
                                            {server.generation != null && (
                                                <span className="status-badge status-gold">{server.generation}기</span>
                                            )}
                                            {server.skew && (
                                                <span className="status-badge status-gold skew-tag">불일치</span>
                                            )}
                                        </td>
                                    )}
                                    <td>{svcName}</td>
                                    <td>
                                        <ServiceCell svc={svc} />
                                    </td>
                                </tr>
                            )),
                        )}
                    </tbody>
                </table>
            </div>

            <div className="deploy-section">
                <h3 className="lobby-section-title">버전 배포</h3>
                {version.servers.map((server) => (
                    <div key={server.id} className="deploy-server">
                        <div className="deploy-server-head">{server.name}</div>
                        <DeployControl server={server} status={statuses[server.id]} onReload={reloadStatus} />
                        <ServerLifecycleControl server={server} scenarios={scenarios} onChanged={() => loadVersion(false)} />
                    </div>
                ))}
            </div>
        </div>
    );
}

/** world_state.config 에서 라이브 수정 가능한 게임 환경 설정. */
function GameSettingsControl() {
    const [settings, setSettings] = useState<AdminGameSettingsResponse | null>(null);
    const [drafts, setDrafts] = useState<Record<string, string>>({});
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState<string | null>(null);

    const load = useCallback(async () => {
        try {
            const res = await fetch('/api/admin/game-settings', { cache: 'no-store' });
            if (!res.ok) throw new Error(`요청 실패 (${res.status})`);
            const data = (await res.json()) as AdminGameSettingsResponse;
            setSettings(data);
            setDrafts(
                Object.fromEntries(
                    data.editableFields.map((field) => [field.key, String(field.value ?? '')]),
                ),
            );
            setMessage(null);
        } catch {
            setMessage('게임 설정을 불러오지 못했습니다.');
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    async function save() {
        if (!settings) return;
        const values: Record<string, string | number> = {};
        for (const field of settings.editableFields) {
            const raw = drafts[field.key]?.trim() ?? '';
            if (field.type === 'text') {
                values[field.key] = raw;
            } else if (field.type === 'number' || field.type === 'select') {
                const parsed = raw ? parseInt(raw, 10) : NaN;
                if (Number.isNaN(parsed)) {
                    setMessage(`${field.label} 값이 올바르지 않습니다.`);
                    return;
                }
                values[field.key] = parsed;
            }
        }
        if (Object.keys(values).length === 0) return;

        setBusy(true);
        setMessage(null);
        try {
            const res = await fetch('/api/admin/game-settings', {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ values }),
            });
            const data = (await res.json()) as { result?: boolean; reason?: string; restartRequired?: boolean };
            if (!res.ok || data.result === false) {
                setMessage(data.reason ?? '게임 설정 저장에 실패했습니다.');
                return;
            }
            setMessage(data.restartRequired ? '저장되었습니다. 턴 시간 변경은 엔진 재시작 후 적용됩니다.' : '저장되었습니다.');
            await load();
        } catch {
            setMessage('게임 설정 저장에 실패했습니다.');
        } finally {
            setBusy(false);
        }
    }

    const changed = settings
        ? settings.editableFields.some((field) => drafts[field.key] !== String(field.value ?? ''))
        : false;

    return (
        <div className="env-section">
            <h3 className="lobby-section-title">입장 설정</h3>
            {message && <p className={`deploy-result ${message.startsWith('저장되었습니다') ? 'ok' : 'fail'}`}>{message}</p>}
            {!settings ? (
                <p className="svc-meta">설정 조회 중…</p>
            ) : (
                <div className="server-reset-grid">
                    {settings.editableFields.map((field) => (
                        <label key={field.key} className="field">
                            <span>{field.label}</span>
                            {field.type === 'select' && field.options ? (
                                <select
                                    value={drafts[field.key] ?? String(field.value ?? '')}
                                    disabled={busy}
                                    onChange={(e) => setDrafts((prev) => ({ ...prev, [field.key]: e.target.value }))}
                                >
                                    {field.options.map((opt) => (
                                        <option key={opt.value} value={opt.value}>
                                            {opt.label}
                                        </option>
                                    ))}
                                </select>
                            ) : (
                                <input
                                    type={field.type === 'number' ? 'number' : 'text'}
                                    value={drafts[field.key] ?? String(field.value ?? '')}
                                    disabled={busy}
                                    onChange={(e) => setDrafts((prev) => ({ ...prev, [field.key]: e.target.value }))}
                                />
                            )}
                        </label>
                    ))}
                    <div className="deploy-row reset-wide">
                        <button type="button" className="btn-primary" disabled={busy || !changed} onClick={save}>
                            {busy ? '저장 중…' : '저장'}
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}

/**
 * "게임 환경" 탭 — B1e 락(동결) 부분만 배선.
 * PHP `_119.php:36` `락 풀 기 : [락걸기][락풀기] 현재 : (plock>0?동결중:가동중)` 등가.
 * 시간조정/봉급/운영자메시지/시작시간/최대장수·국가/시작년도/턴시간은 후속 웨이브 — PLACEHOLDER.
 */
function GameEnvControl() {
    const [status, setStatus] = useState<TurnDaemonStatus | null>(null);
    const [version, setVersion] = useState<VersionResponse | null>(null);
    const [selectedServer, setSelectedServer] = useState<string>('');
    const [sharedEnv, setSharedEnv] = useState<EnvConfigResponse | null>(null);
    const [serverEnv, setServerEnv] = useState<EnvConfigResponse | null>(null);
    const [sharedDrafts, setSharedDrafts] = useState<Record<string, string>>({});
    const [serverDrafts, setServerDrafts] = useState<Record<string, string>>({});
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [envBusy, setEnvBusy] = useState(false);
    const [envMessage, setEnvMessage] = useState<string | null>(null);

    // 데몬 락 상태 재조회. 진입 + 락걸기/락풀기 후 호출.
    const reload = useCallback(async () => {
        try {
            const st = await getJson<TurnDaemonStatus>('admin/turn-daemon/status');
            setStatus(st);
            setError(null);
        } catch {
            setError('데몬 상태를 불러오지 못했습니다.');
        }
    }, []);

    const loadSharedEnv = useCallback(async () => {
        const data = await getJson<EnvConfigResponse>('admin/env/shared');
        setSharedEnv(data);
        setSharedDrafts(
            Object.fromEntries(Object.entries(data.fields ?? {}).map(([key, field]) => [key, fieldInitialValue(field)])),
        );
    }, []);

    const loadServerEnv = useCallback(async (serverId: string) => {
        if (!serverId) {
            setServerEnv(null);
            setServerDrafts({});
            return;
        }
        const data = await getJson<EnvConfigResponse>(`admin/env/servers/${encodeURIComponent(serverId)}`);
        setServerEnv(data);
        setServerDrafts(
            Object.fromEntries(Object.entries(data.fields ?? {}).map(([key, field]) => [key, fieldInitialValue(field)])),
        );
    }, []);

    useEffect(() => {
        let alive = true;
        (async () => {
            setLoading(true);
            try {
                const ver = await getJson<VersionResponse>('admin/version');
                if (!alive) return;
                setVersion(ver);
                const firstServer = ver.servers[0]?.id ?? '';
                setSelectedServer(firstServer);
                await Promise.all([reload(), loadSharedEnv(), loadServerEnv(firstServer)]);
            } catch {
                if (alive) setError('게임 환경 정보를 불러오지 못했습니다.');
            }
            if (alive) setLoading(false);
        })();
        return () => {
            alive = false;
        };
    }, [loadServerEnv, loadSharedEnv, reload]);

    useEffect(() => {
        if (!selectedServer) return;
        let alive = true;
        (async () => {
            setEnvBusy(true);
            try {
                await loadServerEnv(selectedServer);
                if (alive) setEnvMessage(null);
            } catch {
                if (alive) setEnvMessage('서버 환경 설정을 불러오지 못했습니다.');
            } finally {
                if (alive) setEnvBusy(false);
            }
        })();
        return () => {
            alive = false;
        };
    }, [loadServerEnv, selectedServer]);

    // 락걸기(pause) / 락풀기(resume) — POST 후 실 상태로 갱신.
    async function toggleLock(action: 'pause' | 'resume') {
        setBusy(true);
        try {
            const res = await fetch(`/api/proxy/admin/turn-daemon/${action}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
            });
            if (res.ok) {
                const data = (await res.json()) as TurnDaemonControlResult;
                // 반환 결과로 즉시 라벨 반영 후, 권위 상태로 재조회.
                setStatus((prev) =>
                    prev ? { ...prev, paused: data.paused, statusLabel: data.statusLabel } : prev,
                );
                setError(null);
            } else {
                setError(action === 'pause' ? '락걸기에 실패했습니다.' : '락풀기에 실패했습니다.');
            }
        } catch {
            setError(action === 'pause' ? '락걸기에 실패했습니다.' : '락풀기에 실패했습니다.');
        } finally {
            await reload();
            setBusy(false);
        }
    }

    async function saveEnv(scope: 'shared' | 'server') {
        const config = scope === 'shared' ? sharedEnv : serverEnv;
        const drafts = scope === 'shared' ? sharedDrafts : serverDrafts;
        if (!config) return;
        const values: Record<string, string> = {};
        for (const [key, field] of Object.entries(config.fields ?? {})) {
            const value = drafts[key] ?? '';
            if (field.writeOnly) {
                if (value.trim() !== '') values[key] = value;
            } else if (value !== fieldInitialValue(field)) {
                values[key] = value;
            }
        }
        if (Object.keys(values).length === 0) return;
        setEnvBusy(true);
        setEnvMessage(null);
        try {
            const path =
                scope === 'shared'
                    ? 'admin/env/shared'
                    : `admin/env/servers/${encodeURIComponent(selectedServer)}`;
            const res = await fetch(`/api/proxy/${path}`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ values }),
            });
            const data = (await res.json()) as EnvConfigResponse;
            if (!res.ok || data.ok === false) {
                setEnvMessage(data.message ?? '환경 설정 저장에 실패했습니다.');
                return;
            }
            if (scope === 'shared') {
                setSharedEnv(data);
                setSharedDrafts(
                    Object.fromEntries(
                        Object.entries(data.fields ?? {}).map(([key, field]) => [key, fieldInitialValue(field)]),
                    ),
                );
            } else {
                setServerEnv(data);
                setServerDrafts(
                    Object.fromEntries(
                        Object.entries(data.fields ?? {}).map(([key, field]) => [key, fieldInitialValue(field)]),
                    ),
                );
            }
            setEnvMessage('저장되었습니다.');
        } catch {
            setEnvMessage('환경 설정 저장에 실패했습니다.');
        } finally {
            setEnvBusy(false);
        }
    }

    return (
        <div className="game-env-control">
            {/* B1b 락 — PHP `_119.php:36` verbatim 라벨/표시. */}
            <div className="env-section">
                <h3 className="lobby-section-title">락 풀 기</h3>
                {loading ? (
                    <div className="center-inline">
                        <div className="spinner" />
                    </div>
                ) : (
                    <>
                        <div className="env-lock-row">
                            <button
                                type="button"
                                className="btn-danger"
                                disabled={busy || status?.paused === true}
                                onClick={() => toggleLock('pause')}
                            >
                                락걸기
                            </button>
                            <button
                                type="button"
                                className="btn-primary"
                                disabled={busy || status?.paused === false}
                                onClick={() => toggleLock('resume')}
                            >
                                락풀기
                            </button>
                            <span className="env-lock-status">
                                현재 :{' '}
                                <span
                                    className={`status-badge ${status?.paused ? 'status-crimson' : 'status-jade'}`}
                                >
                                    {/* PHP `_119.php:36` `plock>0?"동결중":"가동중"` verbatim. */}
                                    {status?.statusLabel ?? (status?.paused ? '동결중' : '가동중')}
                                </span>
                            </span>
                        </div>
                        {error && <p className="deploy-result fail">{error}</p>}
                    </>
                )}
            </div>

            <GameSettingsControl />

            {/* 후속 웨이브 — 시간조정 / 토너시간 / 봉급(금·쌀) / 운영자메시지 / 중원정세추가 /
                시작시간 / 최대장수·국가 / 시작년도 / 턴시간. 아직 미구현. */}
            <div className="env-section env-pending">
                <h3 className="lobby-section-title">시간 · 봉급 · 환경 설정</h3>
                <div className="env-server-selector">
                    <label className="field">
                        <span>게임 서버</span>
                        <select
                            value={selectedServer}
                            disabled={envBusy || !version?.servers.length}
                            onChange={(e) => setSelectedServer(e.target.value)}
                        >
                            {version?.servers.map((server) => (
                                <option key={server.id} value={server.id}>
                                    {server.name}
                                </option>
                            ))}
                        </select>
                    </label>
                </div>
                {envMessage && <p className="deploy-result ok">{envMessage}</p>}
                <div className="env-config-grid">
                    <EnvConfigEditor
                        title="공유 스택"
                        config={sharedEnv}
                        drafts={sharedDrafts}
                        busy={envBusy}
                        onChange={(key, value) => setSharedDrafts((prev) => ({ ...prev, [key]: value }))}
                        onSave={() => saveEnv('shared')}
                    />
                    <EnvConfigEditor
                        title="게임 서버"
                        config={serverEnv}
                        drafts={serverDrafts}
                        busy={envBusy || !selectedServer}
                        onChange={(key, value) => setServerDrafts((prev) => ({ ...prev, [key]: value }))}
                        onSave={() => saveEnv('server')}
                    />
                </div>
            </div>
        </div>
    );
}

function AdminView() {
    const [active, setActive] = useState<string>(ADMIN_SECTIONS[0].id);
    const section = ADMIN_SECTIONS.find((s) => s.id === active) ?? ADMIN_SECTIONS[0];

    return (
        <div className="admin-shell">
            <Topbar />
            <main className="admin-main fade-in">
                <div className="admin-tabs">
                    {ADMIN_SECTIONS.map((s) => (
                        <button
                            key={s.id}
                            type="button"
                            className={`admin-tab${s.id === active ? ' active' : ''}`}
                            onClick={() => setActive(s.id)}
                        >
                            {s.label}
                        </button>
                    ))}
                </div>
                <section className="admin-panel">
                    <h2 className="lobby-section-title">{section.label}</h2>
                    {active === 'server' ? (
                        <ServerControl />
                    ) : active === 'members' ? (
                        <MemberControl />
                    ) : active === 'game' ? (
                        <GameEnvControl />
                    ) : (
                        <p>{PLACEHOLDER}</p>
                    )}
                </section>
            </main>
        </div>
    );
}

export default function AdminPage() {
    return (
        <AuthGate admin>
            <AdminView />
        </AuthGate>
    );
}
