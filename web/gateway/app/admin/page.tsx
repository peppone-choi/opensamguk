'use client';

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import AuthGate from '@/components/AuthGate';
import { Button, Chip, Icon, KV, Modal, Panel, SectionHeader, Seg } from '@opensamguk/ui';
import StateLine from '@/components/status/StateLine';
import MemberHeader from '@/components/gateway/MemberHeader';
import ConfirmModal from '@/components/ConfirmModal';
import BoardControl from '@/components/admin/BoardControl';
import BoardReportControl from '@/components/admin/BoardReportControl';
import MemberControl from '@/components/admin/MemberControl';
import NoticeControl from '@/components/admin/NoticeControl';
import { AdminServerPicker, CatchUpTab, TurnControl, serverLabel } from '@/components/admin/TurnControl';
import AdminOverview from '@/components/admin/AdminOverview';
import {
    runServerLifecycleOperation,
    type ServerLifecycleOperationStatus,
    type ServerLifecycleResponse,
} from '@/lib/admin-server-lifecycle';

// 운영 콘솔 탭 8개(설계서 §3.4, 보드 V31K5Admin*): 「게시판 관리」 안의 신고, 「게임 환경」 안의 락 · 따라잡기를 탭으로 꺼냈다.
// 「게임 환경」의 게임 설정 · 환경값은 서버 탭으로 옮겼다. 위험 등급(docs/admin/README.md): 조회 / 가역 / 배포 / 파괴적.
const ADMIN_SECTIONS = [
    { id: 'overview', label: '개요', risk: '조회' },
    { id: 'members', label: '회원', risk: '가역 · 파괴적' },
    { id: 'board', label: '게시판', risk: '가역' },
    { id: 'reports', label: '신고', risk: '가역' },
    { id: 'notice', label: '공지', risk: '가역' },
    { id: 'turn', label: '턴', risk: '가역' },
    { id: 'catchup', label: '따라잡기', risk: '가역' },
    { id: 'server', label: '서버', risk: '배포 · 파괴적' },
] as const;
type AdminSectionId = (typeof ADMIN_SECTIONS)[number]['id'];

const ADMIN_GAME_SETTINGS_PATH = '/api/game/api/admin/game-settings';

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
    scenarioCode?: string | null;
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
    latestTag?: string | null;
    promotionAvailable?: boolean;
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
type ServerLifecycleViewResult =
    | { phase: 'progress'; response: ServerLifecycleResponse }
    | { phase: 'success'; response: ServerLifecycleResponse }
    | { phase: 'error'; message: string };
interface ServerResetOptions {
    generation: string;
    scenarioCode: string;
    scenarioSeedEnabled: boolean;
    turnTerm: string;
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
    scenarioText?: string | null;
    mapCode?: string | null;
    year?: number;
    month?: number;
    turnPhase?: number | null;
    turnPhaseText?: string | null;
    status?: string | null;
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

// 턴 데몬(멈추기 · 다시 돌리기 · 따라잡기) DTO 는 components/admin/TurnControl.tsx 로 옮겼다.

// 버전 불일치 경고(설계서 §3.4 S1) — game-engine은 자동 재배포 제외라 시즌 경계에서 수동 갱신 필요. 「⚠」 글자 대신 경고 아이콘.
const SKEW_WARNING = '버전 불일치 — game-engine은 자동 재배포 제외, 시즌 경계에서 수동 갱신 필요';
const PUBLIC_SERVER_ID_PATTERN = /^[A-Za-z0-9]+$/;
const MAX_PUBLIC_SERVER_ID_LENGTH = 48;
const RESERVED_PUBLIC_SERVER_IDS = new Set([
    'all',
    'main',
    'admin1',
    'admin2',
    'admin5',
    'admin7',
    'admin8',
    'auction',
    'battle-center',
    'betting',
    'board',
    'chief-center',
    'city',
    'coming-soon',
    'diplomacy',
    'generals',
    'global-diplomacy',
    'history',
    'inherit',
    'join',
    'mailbox',
    'map',
    'my',
    'my-boss',
    'my-cities',
    'my-generals',
    'my-nation',
    'nation',
    'nation-betting',
    'nation-finance',
    'npc-control',
    'rankings',
    'register',
    'select-pool',
    'simulator',
    'tournament',
    'tournament-admin',
    'troop',
    'v2-lab',
    'vote',
    'court',
    'hand',
    'orders',
    'posts',
    'retinue',
    'siege',
    'supply',
    'war-room',
    'yuedan',
    'world-log',
    'stratagem',
    'territory',
    'corps',
    'records',
    'council',
    'mail',
    'help',
]);

function lifecycleProgressLabel(status: ServerLifecycleOperationStatus): string {
    if (status === 'recovery_required') return '복구 확인 중';
    return '처리 중';
}

function lifecycleErrorMessage(error: unknown, fallback: string): string {
    return error instanceof Error && error.message ? error.message : fallback;
}

/** 인증 프록시 GET — JSON 파싱. 비-2xx면 throw. */
async function getJson<T>(path: string): Promise<T> {
    const res = await fetch(`/api/proxy/${path}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`요청 실패 (${res.status})`);
    return (await res.json()) as T;
}

const BUSY = '처리 중입니다';

/** 조작 결과 한 줄 — 성공은 이끼, 실패는 적갈(설계서 S73: 옛 화면은 실패도 초록이었다). */
function ResultLine({ ok, children }: { readonly ok: boolean; readonly children: ReactNode }) {
    return <p className={ok ? 'admin31-srv-ok' : 'gw31-alert'} role={ok ? 'status' : 'alert'}>{children}</p>;
}

function LifecycleResult({ result, done }: {
    readonly result: ServerLifecycleViewResult;
    readonly done: (response: ServerLifecycleResponse) => string;
}) {
    if (result.phase === 'progress') return <p className="admin31-srv-note" role="status">{lifecycleProgressLabel(result.response.operationStatus)}</p>;
    if (result.phase === 'success') return <ResultLine ok>{done(result.response)}</ResultLine>;
    return <ResultLine ok={false}>{result.message}</ResultLine>;
}

/** 막힌 단추는 사유와 함께(ADR-LITE-049 (7)) — 사유가 없으면 누를 수 있다. */
function ActButton({ block, variant = 'ghost', onClick, children }: {
    readonly block: string | null;
    readonly variant?: 'primary' | 'ghost' | 'danger';
    readonly onClick: () => void;
    readonly children: ReactNode;
}) {
    return block
        ? <Button variant={variant} disabled reason={block}>{children}</Button>
        : <Button variant={variant} onClick={onClick}>{children}</Button>;
}

/** 서버 탭 대화상자 — 폼이 들어가 확인 대화상자(440)보다 넓다(보드 560). 모바일은 아래에 붙는 시트. */
function SrvDialog({ title, open, busy, danger = false, confirmLabel, onConfirm, onCancel, children }: {
    readonly title: string;
    readonly open: boolean;
    readonly busy: boolean;
    readonly danger?: boolean;
    readonly confirmLabel: string;
    readonly onConfirm: () => void;
    readonly onCancel: () => void;
    readonly children: ReactNode;
}) {
    if (!open) return null;
    return (
        <Modal ariaLabel={title} className="admin31-srv-dialog" overlayClassName="admin31-srv-overlay" closeOnBackdrop={!busy} closeOnEscape={!busy} onClose={onCancel}>
            <div className="admin31-srv-dialog__body">
                <h2 className="admin31-srv-dialog__title os-serif">{title}</h2>
                {children}
                <div className="admin31-srv-dialog__actions">
                    <ActButton block={busy ? BUSY : null} onClick={onCancel}>취소</ActButton>
                    <ActButton block={busy ? BUSY : null} variant={danger ? 'danger' : 'primary'} onClick={onConfirm}>{busy ? '처리 중…' : confirmLabel}</ActButton>
                </div>
            </div>
        </Modal>
    );
}

const BOOLEAN_ENV_KEYS = new Set(['COOKIE_SECURE', 'SCENARIO_SEED_ENABLED']);

// 뺀 리셋 칸(S41 · S43–S53)의 원시 값 `RESET_*`(설계서 S82) — 화면에서만 숨긴다. 서버 허용 목록 정리는 C8 몫.
function hiddenEnvKey(key: string): boolean {
    return key.startsWith('RESET_') && key !== 'RESET_TURNTERM' && !key.startsWith('RESET_SCENARIO_');
}

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
            <label className="os-check">
                <input
                    type="checkbox"
                    aria-label={field.key}
                    checked={value === 'true'}
                    disabled={disabled}
                    onChange={(e) => onChange(e.target.checked ? 'true' : 'false')}
                />
                <span>{value === 'true' ? '켬' : '끔'}</span>
            </label>
        );
    }

    return (
        <input
            className="os-input"
            aria-label={field.key}
            type={field.writeOnly ? 'password' : field.key.endsWith('_PORT') ? 'number' : 'text'}
            inputMode={field.key.endsWith('_PORT') ? 'numeric' : undefined}
            value={value}
            disabled={disabled}
            placeholder={field.writeOnly && field.configured ? '새 값 입력' : undefined}
            onChange={(e) => onChange(e.target.value)}
        />
    );
}

/** 한 서비스 버전 칸 — 응답이 없으면 칩, 아니면 버전 · 태그 · 빌드 시각. */
function ServiceCell({ svc }: { svc: ServiceVersion }) {
    if (!svc.reachable) return <Chip tone="rust">응답 없음</Chip>;
    return (
        <span className="admin31-srv-svc">
            <span className="os-num">{svc.version ?? '-'}</span>
            <small className="os-num admin31-code">태그 {svc.imageTag ?? '-'}{svc.buildTime ? ` · 빌드 ${svc.buildTime}` : ''}</small>
        </span>
    );
}

/** 환경값 편집기(설계서 S73–S82) — 조회 실패는 「조회 중」에 묻히지 않게 오류 줄로. */
function EnvConfigEditor({
    title,
    config,
    failed,
    drafts,
    block,
    onChange,
    onSave,
    onRetry,
}: {
    title: string;
    config: EnvConfigResponse | null;
    failed: boolean;
    drafts: Record<string, string>;
    block: string | null;
    onChange: (key: string, value: string) => void;
    onSave: () => void;
    onRetry: () => void;
}) {
    if (failed) return <StateLine kind="error" title={`${title} 환경값을 불러오지 못했습니다`} onRetry={onRetry} />;
    if (!config) return <StateLine kind="loading" title={`${title} 환경값을 확인하는 중`} />;
    if (config.configured === false) {
        return <p className="admin31-srv-note">{config.message ?? 'deployer가 설정되지 않았습니다.'}</p>;
    }
    const fields = Object.values(config.fields)
        .filter((field) => !hiddenEnvKey(field.key))
        .sort((a, b) => a.key.localeCompare(b.key));
    const changed = fields.some((field) => {
        const value = drafts[field.key] ?? '';
        if (field.writeOnly) return value.trim() !== '';
        return value !== fieldInitialValue(field);
    });

    return (
        <section className="admin31-srv-env" aria-label={title}>
            <div className="admin31-row">
                <h3 className="admin31-srv-sub os-serif">{title}</h3>
                {config.restartRequired && <Chip tone="bronze">재시작 필요</Chip>}
            </div>
            <div className="admin31-srv-envlist">
                {fields.map((field) => (
                    <div key={field.key} className="admin31-srv-envrow">
                        <span className="admin31-srv-envmeta">
                            <strong className="os-num">{field.key}</strong>
                            <small>{field.metadata?.description ?? (field.writeOnly ? '비밀값' : '설정값')}</small>
                        </span>
                        <EnvFieldInput
                            field={field}
                            value={drafts[field.key] ?? fieldInitialValue(field)}
                            disabled={block === BUSY}
                            onChange={(value) => onChange(field.key, value)}
                        />
                        {field.masked && <Chip tone="moss">숨김</Chip>}
                    </div>
                ))}
            </div>
            {config.affectedServices && config.affectedServices.length > 0 && (
                <p className="admin31-srv-note">
                    적용 대상 {config.affectedServices.join(', ')} · game-engine은 자동 재기동하지 않습니다.
                </p>
            )}
            <div className="admin31-row admin31-srv-actions">
                <ActButton block={block ?? (changed ? null : '바꾼 값이 없습니다')} variant="primary" onClick={onSave}>저장</ActButton>
            </div>
        </section>
    );
}

/** 서버별 배포 제어(설계서 S23–S33) — 현재 태그 + 배포 가능한 태그 선택 → 확인 → POST. */
function DeployControl({
    server,
    status,
    onReload,
}: {
    server: ServerVersion;
    /** undefined = 확인하는 중, null = 조회 실패. */
    status: DeployStatus | null | undefined;
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

    // 옛 화면은 조회가 실패해도 「상태 조회 중…」이 남았다 — 확인 중과 실패를 가른다.
    if (status === undefined) return <StateLine kind="loading" title="배포 상태를 확인하는 중" />;
    if (status === null) {
        return <StateLine kind="error" title="배포 상태를 불러오지 못했습니다" onRetry={() => onReload(server.id)} />;
    }

    // deployer 미설정 — 컨트롤 숨기고 안내만.
    if (!status.configured) {
        return <p className="admin31-srv-note">{status.message ?? '배포 deployer가 설정되지 않았습니다 (로컬/미배포 환경).'}</p>;
    }

    const latestTag = status.latestTag ?? status.availableTags[0] ?? null;
    const promotionAvailable = Boolean(status.promotionAvailable ?? (latestTag && latestTag !== status.currentTag));
    const isCurrent = selected === status.currentTag;
    const isLatestSelected = latestTag != null && selected === latestTag;
    const deployBlock = busy ? BUSY : !selected ? '고를 수 있는 버전이 없습니다' : isCurrent ? '지금 버전입니다' : null;

    function selectLatest() {
        if (!latestTag) return;
        setSelected(latestTag);
        setConfirming(true);
    }

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
        <div className="admin31-srv-block">
            <div className="admin31-row">
                <span className="os-num">지금 {status.currentTag ?? '-'}</span>
                {promotionAvailable && latestTag && <Chip tone="info">새 버전 {latestTag}</Chip>}
            </div>
            <div className="admin31-row admin31-srv-actions">
                <select
                    className="os-input admin31-srv-select"
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
                {promotionAvailable && latestTag && (
                    <ActButton block={busy ? BUSY : null} onClick={selectLatest}>최신으로 승격</ActButton>
                )}
                <ActButton block={deployBlock} variant="primary" onClick={() => setConfirming(true)}>
                    {isLatestSelected && !isCurrent ? '최신 버전 배포' : '이 버전으로 배포'}
                </ActButton>
            </div>

            {result && (
                <ResultLine ok={result.ok}>
                    {result.message}
                    {result.detail && <span className="admin31-code"> · {result.detail}</span>}
                </ResultLine>
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

// 한 순 길이 선택지(설계서 S40) — 값은 옛 화면 그대로, 보드처럼 짧은 것부터.
const TURN_TERMS = ['1', '2', '5', '10', '20', '30', '60', '120'] as const;

function ServerLifecycleControl({
    server,
    scenarios,
    onChanged,
}: {
    server: ServerVersion;
    scenarios: ScenarioOption[];
    onChanged: () => void;
}) {
    const serverScenario = server.scenarioCode && scenarios.some((scenario) => scenario.code === server.scenarioCode)
        ? server.scenarioCode
        : '';
    const defaultScenario = serverScenario || scenarios[0]?.code || '';
    const [mode, setMode] = useState<'reset' | 'delete' | null>(null);
    const [busy, setBusy] = useState(false);
    const [result, setResult] = useState<ServerLifecycleViewResult | null>(null);
    const operationController = useRef<AbortController | null>(null);
    const [resetOptions, setResetOptions] = useState<ServerResetOptions>({
        generation: String(server.generation ?? 1),
        scenarioCode: defaultScenario,
        scenarioSeedEnabled: true,
        turnTerm: '60',
    });

    useEffect(() => {
        if (!resetOptions.scenarioCode && defaultScenario) {
            setResetOptions((prev) => ({ ...prev, scenarioCode: defaultScenario }));
        }
    }, [defaultScenario, resetOptions.scenarioCode]);

    useEffect(() => () => {
        operationController.current?.abort();
        operationController.current = null;
    }, []);

    function setReset<K extends keyof ServerResetOptions>(key: K, value: ServerResetOptions[K]) {
        setResetOptions((prev) => ({ ...prev, [key]: value }));
    }

    async function runDelete() {
        operationController.current?.abort();
        const controller = new AbortController();
        operationController.current = controller;
        setBusy(true);
        setResult(null);
        try {
            const response = await runServerLifecycleOperation({
                url: `/api/proxy/admin/servers/${encodeURIComponent(server.id)}`,
                method: 'DELETE',
                signal: controller.signal,
                onProgress: (progress) => {
                    if (operationController.current === controller && progress.operationStatus !== 'succeeded') {
                        setResult({ phase: 'progress', response: progress });
                    }
                },
            });
            if (operationController.current !== controller) return;
            setResult({ phase: 'success', response });
            onChanged();
        } catch (error) {
            if (operationController.current === controller && !controller.signal.aborted) {
                setResult({ phase: 'error', message: lifecycleErrorMessage(error, '서버 삭제 요청에 실패했습니다.') });
            }
        } finally {
            if (operationController.current === controller) {
                operationController.current = null;
                setBusy(false);
                setMode(null);
            }
        }
    }

    async function runReset() {
        if (!resetOptions.scenarioCode) {
            setResult({ phase: 'error', message: '시나리오를 선택해야 리셋할 수 있습니다.' });
            setMode(null);
            return;
        }
        const generationNumber = Number.parseInt(resetOptions.generation, 10);
        if (!Number.isInteger(generationNumber) || generationNumber < 0) {
            setResult({ phase: 'error', message: '기수는 0 이상의 숫자여야 합니다.' });
            setMode(null);
            return;
        }
        operationController.current?.abort();
        const controller = new AbortController();
        operationController.current = controller;
        setBusy(true);
        setResult(null);
        try {
            const response = await runServerLifecycleOperation({
                url: `/api/proxy/admin/servers/${encodeURIComponent(server.id)}/reset`,
                method: 'POST',
                body: {
                    ...resetOptions,
                    confirm: `RESET ${server.id}`,
                },
                signal: controller.signal,
                onProgress: (progress) => {
                    if (operationController.current === controller && progress.operationStatus !== 'succeeded') {
                        setResult({ phase: 'progress', response: progress });
                    }
                },
            });
            if (operationController.current !== controller) return;
            setResult({ phase: 'success', response });
            onChanged();
        } catch (error) {
            if (operationController.current === controller && !controller.signal.aborted) {
                setResult({ phase: 'error', message: lifecycleErrorMessage(error, '서버 리셋 요청에 실패했습니다.') });
            }
        } finally {
            if (operationController.current === controller) {
                operationController.current = null;
                setBusy(false);
                setMode(null);
            }
        }
    }

    // 리셋 칸은 휘하 엔진이 실제로 읽는 넷만(설계서 §3.4 S39 · S40 · S42 · S54). 삼모 install.php 칸(S41 · S43–S53)은 뺐다 —
    // 엔진이 읽지 않거나(시간 동기화 · 자동 행동 · 임관 모드) 은퇴했거나(빙의 · 토너먼트) 결정과 어긋난다(정사/연의 월드 분리 금지).
    // 서버 검사는 뺀 칸이 없어도 받는다(DeployService.validateResetServer — 모든 칸이 선택).
    const resetForm = (
        <div className="admin31-srv-form">
            <p className="gw31-alert">이 서버를 아래 설정으로 처음부터 다시 시작합니다. 되돌릴 수 없습니다.</p>
            <div className="admin31-srv-grid admin31-srv-grid--reset">
                <label className="gw31-field">
                    <span className="gw31-field__label">기수</span>
                    <input
                        className="os-input"
                        type="number"
                        min="0"
                        value={resetOptions.generation}
                        disabled={busy}
                        onChange={(e) => setReset('generation', e.target.value)}
                    />
                </label>
                <div className="gw31-field">
                    <span className="gw31-field__label">한 순 길이(분)</span>
                    <Seg
                        label="한 순 길이(분)"
                        options={TURN_TERMS.map((value) => ({ value, label: value }))}
                        value={resetOptions.turnTerm}
                        onChange={(value) => { if (!busy) setReset('turnTerm', value); }}
                        scroll
                    />
                </div>
            </div>
            <fieldset className="gw31-field admin31-srv-fieldset">
                <legend className="gw31-field__label">시나리오</legend>
                <div className="admin31-srv-scenarios">
                    {scenarios.map((scenario) => {
                        const on = resetOptions.scenarioCode === scenario.code;
                        return (
                            <label key={scenario.code} className={`os-opt${on ? ' os-opt--sel' : ''}`}>
                                <input
                                    type="radio"
                                    name={`reset-scenario-${server.id}`}
                                    value={scenario.code}
                                    checked={on}
                                    disabled={busy}
                                    onChange={() => setReset('scenarioCode', scenario.code)}
                                />
                                <span className="os-opt__text">
                                    <span className="os-opt__name">{scenario.title || scenario.code}</span>
                                    <span className="os-opt__sub os-num">{scenario.code}</span>
                                </span>
                            </label>
                        );
                    })}
                </div>
            </fieldset>
            <label className="os-check">
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
        <div className="admin31-srv-block">
            <div className="admin31-row admin31-srv-actions">
                <ActButton block={busy ? BUSY : null} variant="danger" onClick={() => setMode('reset')}>리셋</ActButton>
                <ActButton block={busy ? BUSY : null} variant="danger" onClick={() => setMode('delete')}>삭제</ActButton>
            </div>
            <p className="admin31-srv-note">리셋은 해당 서버 DB/Redis 볼륨을 초기화합니다.</p>
            {result && (
                <LifecycleResult
                    result={result}
                    done={(response) => response.publicMessage || `${response.name ?? response.id} 처리 완료`}
                />
            )}
            <SrvDialog
                open={mode === 'reset'}
                title={`${server.name} 리셋`}
                danger
                busy={busy}
                confirmLabel="리셋 실행"
                onConfirm={runReset}
                onCancel={() => setMode(null)}
            >
                {resetForm}
            </SrvDialog>
            <ConfirmModal
                open={mode === 'delete'}
                title={`${server.name} 삭제`}
                danger
                busy={busy}
                confirmLabel="삭제 실행"
                message={
                    <>
                        서버 &apos;{server.name}&apos;의 컨테이너, DB/Redis 볼륨, env, gateway registry 항목을
                        삭제합니다.
                        <br />이 작업은 되돌릴 수 없습니다.
                    </>
                }
                onConfirm={runDelete}
                onCancel={() => setMode(null)}
            />
        </div>
    );
}

function CreateServerControl({ onCreated }: { onCreated: () => void }) {
    const [id, setId] = useState('pep');
    // 기본 이름은 빈 칸(설계서 S2–S14) — 옛 기본값 「통일 서버」가 그대로 등록되던 것을 막는다.
    const [name, setName] = useState('');
    const [generation, setGeneration] = useState('1');
    const [gameApiPort, setGameApiPort] = useState('8101');
    const [webGamePort, setWebGamePort] = useState('3101');
    const [imageTag, setImageTag] = useState('');
    const [scenarioCode, setScenarioCode] = useState('');
    const [scenarios, setScenarios] = useState<ScenarioOption[]>([]);
    const [scenarioSeedEnabled, setScenarioSeedEnabled] = useState(true);
    const [jwtPublicKey, setJwtPublicKey] = useState('');
    const [busy, setBusy] = useState(false);
    const [confirming, setConfirming] = useState(false);
    const [result, setResult] = useState<ServerLifecycleViewResult | null>(null);
    const operationController = useRef<AbortController | null>(null);

    useEffect(() => {
        let alive = true;
        getJson<ScenarioListResponse>('admin/scenarios')
            .then((data) => {
                if (!alive) return;
                setScenarios(data.scenarios);
                setScenarioCode((current) => {
                    return current || data.scenarios[0]?.code || '';
                });
            })
            .catch(() => {
                if (alive)
                    setResult({
                        phase: 'error',
                        message: '시나리오 목록을 불러오지 못했습니다.',
                    });
            });
        return () => {
            alive = false;
        };
    }, []);

    useEffect(() => () => {
        operationController.current?.abort();
        operationController.current = null;
    }, []);

    async function createServer() {
        operationController.current?.abort();
        const controller = new AbortController();
        operationController.current = controller;
        setBusy(true);
        setResult(null);
        try {
            const response = await runServerLifecycleOperation({
                url: '/api/proxy/admin/servers',
                method: 'POST',
                body: {
                    id,
                    name,
                    generation,
                    gameApiPort,
                    webGamePort,
                    imageTag,
                    scenarioCode,
                    scenarioSeedEnabled,
                    jwtPublicKey,
                },
                signal: controller.signal,
                onProgress: (progress) => {
                    if (operationController.current === controller && progress.operationStatus !== 'succeeded') {
                        setResult({ phase: 'progress', response: progress });
                    }
                },
            });
            if (operationController.current !== controller) return;
            setResult({ phase: 'success', response });
            onCreated();
        } catch (error) {
            if (operationController.current === controller && !controller.signal.aborted) {
                setResult({ phase: 'error', message: lifecycleErrorMessage(error, '서버 생성 요청에 실패했습니다.') });
            }
        } finally {
            if (operationController.current === controller) {
                operationController.current = null;
                setBusy(false);
                setConfirming(false);
            }
        }
    }

    const generationNumber = Number.parseInt(generation, 10);
    // 막힌 「서버 생성」의 사유 — 첫 번째로 걸린 칸 하나만 말한다.
    const block = busy
        ? BUSY
        : !PUBLIC_SERVER_ID_PATTERN.test(id) || id.length > MAX_PUBLIC_SERVER_ID_LENGTH
            ? '서버 ID는 영문과 숫자 48자 이내입니다'
            : RESERVED_PUBLIC_SERVER_IDS.has(id.toLowerCase())
                ? '게임 경로 예약어는 서버 ID로 쓸 수 없습니다'
                : name.trim() === ''
                    ? '서버 이름을 넣어 주세요'
                    : !Number.isInteger(generationNumber) || generationNumber < 0
                        ? '기수는 0 이상의 숫자입니다'
                        : gameApiPort.trim() === '' || webGamePort.trim() === ''
                            ? '포트를 넣어 주세요'
                            : scenarioCode.trim() === ''
                                ? '시나리오를 골라 주세요'
                                : null;
    const scenarioTitle = scenarios.find((scenario) => scenario.code === scenarioCode)?.title;

    return (
        <Panel className="admin31-panel" aria-label="새 서버 생성">
            <SectionHeader as="h2" title="새 서버 생성" />
            <div className="admin31-body">
                <div className="admin31-srv-grid">
                    <label className="gw31-field" htmlFor="server-id">
                        <span className="gw31-field__label">서버 ID</span>
                        <input
                            id="server-id"
                            className="os-input"
                            aria-describedby="server-id-hint"
                            pattern="[A-Za-z0-9]+"
                            maxLength={MAX_PUBLIC_SERVER_ID_LENGTH}
                            value={id}
                            disabled={busy}
                            onChange={(e) => setId(e.target.value)}
                            placeholder="pep"
                        />
                        <small id="server-id-hint" className="gw31-field__help">
                            영문과 숫자 48자 이내로 사용할 수 있습니다. 예: pep, A1, s1. 대문자는 소문자로 저장되며 all과 게임 경로 예약어는 사용할 수 없습니다.
                        </small>
                    </label>
                    <label className="gw31-field">
                        <span className="gw31-field__label">서버 이름</span>
                        <input className="os-input" value={name} disabled={busy} onChange={(e) => setName(e.target.value)} />
                    </label>
                    <label className="gw31-field">
                        <span className="gw31-field__label">기수</span>
                        <input
                            className="os-input"
                            type="number"
                            min="0"
                            value={generation}
                            disabled={busy}
                            onChange={(e) => setGeneration(e.target.value)}
                        />
                    </label>
                    <label className="gw31-field">
                        <span className="gw31-field__label">game-api 포트</span>
                        <input
                            className="os-input"
                            type="number"
                            min="1"
                            max="65535"
                            value={gameApiPort}
                            disabled={busy}
                            onChange={(e) => setGameApiPort(e.target.value)}
                        />
                    </label>
                    <label className="gw31-field">
                        <span className="gw31-field__label">web-game 포트</span>
                        <input
                            className="os-input"
                            type="number"
                            min="1"
                            max="65535"
                            value={webGamePort}
                            disabled={busy}
                            onChange={(e) => setWebGamePort(e.target.value)}
                        />
                    </label>
                    <label className="gw31-field">
                        <span className="gw31-field__label">이미지 태그</span>
                        <input className="os-input" value={imageTag} disabled={busy} onChange={(e) => setImageTag(e.target.value)} />
                        <small className="gw31-field__help">비우면 공유 스택 IMAGE_TAG를 사용합니다.</small>
                    </label>
                    <label className="gw31-field">
                        <span className="gw31-field__label">시나리오</span>
                        <select className="os-input" value={scenarioCode} disabled={busy} onChange={(e) => setScenarioCode(e.target.value)}>
                            {scenarios.map((scenario) => (
                                <option key={scenario.code} value={scenario.code}>
                                    {scenario.title || scenario.code} ({scenario.code})
                                </option>
                            ))}
                        </select>
                    </label>
                    <label className="gw31-field">
                        <span className="gw31-field__label">JWT 공개키</span>
                        <input
                            className="os-input"
                            value={jwtPublicKey}
                            disabled={busy}
                            onChange={(e) => setJwtPublicKey(e.target.value)}
                        />
                        <small className="gw31-field__help">비우면 호스트에 설치된 gateway 공개키를 사용합니다.</small>
                    </label>
                    <label className="os-check">
                        <input
                            type="checkbox"
                            checked={scenarioSeedEnabled}
                            disabled={busy}
                            onChange={(e) => setScenarioSeedEnabled(e.target.checked)}
                        />
                        시나리오 자동 시드
                    </label>
                </div>
                <div className="admin31-row admin31-srv-actions">
                    <ActButton block={block} variant="primary" onClick={() => setConfirming(true)}>서버 생성</ActButton>
                </div>
                <p className="admin31-srv-note">생성 후 gateway-api/web-gateway가 레지스트리를 다시 읽습니다.</p>
                {result && (
                    <LifecycleResult
                        result={result}
                        done={(response) => response.publicMessage || `${response.name ?? response.id} 생성 완료`}
                    />
                )}
            </div>
            {/* 옛 화면은 확인 없이 바로 만들었다(설계서 S2–S14) — 넣은 값을 한 번 보여 주고 만든다. */}
            <SrvDialog
                open={confirming}
                title="서버 생성 확인"
                busy={busy}
                confirmLabel="생성 실행"
                onConfirm={() => void createServer()}
                onCancel={() => setConfirming(false)}
            >
                <KV
                    items={[
                        { k: '서버 ID', v: id.toLowerCase() },
                        { k: '서버 이름', v: name },
                        { k: '기수', v: `${generation}기` },
                        { k: 'game-api 포트', v: gameApiPort },
                        { k: 'web-game 포트', v: webGamePort },
                        { k: '이미지 태그', v: imageTag || '공유 스택 IMAGE_TAG' },
                        { k: '시나리오', v: scenarioTitle ? `${scenarioTitle} (${scenarioCode})` : scenarioCode },
                        { k: '시나리오 자동 시드', v: scenarioSeedEnabled ? '켬' : '끔' },
                    ]}
                />
            </SrvDialog>
        </Panel>
    );
}

/** 서버 탭 위 절(설계서 §3.4 S1–S55) — 실행 버전 · 버전 배포 · 리셋 · 삭제 · 새 서버 생성. */
function ServerControl({ onVersion }: { readonly onVersion?: (version: VersionResponse) => void } = {}) {
    const [version, setVersion] = useState<VersionResponse | null>(null);
    const [scenarios, setScenarios] = useState<ScenarioOption[]>([]);
    // undefined = 확인하는 중, null = 조회 실패(옛 화면은 실패해도 「상태 조회 중…」이 남았다).
    const [statuses, setStatuses] = useState<Record<string, DeployStatus | null>>({});
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // 단일 서버 deploy/status 재조회 (배포 성공 후 · 「다시 시도」).
    const reloadStatus = useCallback(async (serverId: string) => {
        setStatuses((prev) => {
            const next = { ...prev };
            delete next[serverId];
            return next;
        });
        try {
            const st = await getJson<DeployStatus>(`admin/deploy/status?serverId=${encodeURIComponent(serverId)}`);
            setStatuses((prev) => ({ ...prev, [serverId]: st }));
        } catch {
            setStatuses((prev) => ({ ...prev, [serverId]: null }));
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
            onVersion?.(ver);
            setScenarios(scenarioData.scenarios);
            const entries = await Promise.all(
                ver.servers.map(async (s) => {
                    try {
                        const st = await getJson<DeployStatus>(
                            `admin/deploy/status?serverId=${encodeURIComponent(s.id)}`,
                        );
                        return [s.id, st] as const;
                    } catch {
                        return [s.id, null] as const;
                    }
                }),
            );
            setStatuses(Object.fromEntries(entries));
        } catch {
            setError('서버 버전 정보를 불러오지 못했습니다');
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

    if (loading) return <StateLine kind="loading" title="서버 버전을 확인하는 중" />;
    if (error || !version) {
        return <StateLine kind="error" title={error ?? '서버 버전 정보를 불러오지 못했습니다'} onRetry={() => void loadVersion(true)} />;
    }

    return (
        <div className="admin31-stack">
            {version.skew && (
                <p className="gw31-alert admin31-srv-warn" role="alert">
                    <Icon name="alert" size={16} />
                    {SKEW_WARNING}
                </p>
            )}

            <Panel className="admin31-panel" aria-label="실행 버전">
                <SectionHeader as="h2" title="실행 버전" />
                <div className="admin31-body">
                    <div className="admin31-row">
                        <span className="os-num gw31-card__line--muted">게이트웨이</span>
                        <ServiceCell svc={version.gateway} />
                    </div>
                    {version.servers.length === 0 ? (
                        <StateLine kind="empty" title="등록된 게임 서버가 없습니다." />
                    ) : (
                        <table className="admin31-table">
                            <thead>
                                <tr><th>서버</th><th>game-api</th><th>game-engine</th></tr>
                            </thead>
                            <tbody>
                                {version.servers.map((server) => (
                                    <tr key={server.id}>
                                        <td data-label="서버">
                                            <span className="admin31-row">
                                                <b>{server.name}</b>
                                                {server.generation != null && <Chip tone="bronze">{server.generation}기</Chip>}
                                                {server.skew && <Chip tone="rust">불일치</Chip>}
                                            </span>
                                        </td>
                                        <td data-label="game-api"><ServiceCell svc={server.gameApi} /></td>
                                        <td data-label="game-engine"><ServiceCell svc={server.gameEngine} /></td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </div>
            </Panel>

            {version.servers.length > 0 && (
                <Panel className="admin31-panel" aria-label="버전 배포 · 리셋 · 삭제">
                    <SectionHeader as="h2" title="버전 배포 · 리셋 · 삭제" />
                    <div className="admin31-body">
                        {version.servers.map((server) => (
                            <section key={server.id} className="admin31-srv-server" aria-label={server.name}>
                                <h3 className="admin31-srv-name os-serif">
                                    {server.name}
                                    {server.generation != null && <Chip tone="bronze">{server.generation}기</Chip>}
                                </h3>
                                <DeployControl server={server} status={statuses[server.id]} onReload={reloadStatus} />
                                <ServerLifecycleControl
                                    server={server}
                                    scenarios={scenarios}
                                    onChanged={() => loadVersion(false)}
                                />
                            </section>
                        ))}
                        <p className="admin31-srv-note">이 배포는 game-api · web-game 만 바꿉니다. game-engine(진행 중 턴 상태)은 엔진 포함 승격 워크플로(콘솔 밖)로 바꿉니다.</p>
                    </div>
                </Panel>
            )}

            <CreateServerControl onCreated={() => loadVersion(false)} />
        </div>
    );
}

function adminGameSettingsPath(serverId: string): string {
    return `${ADMIN_GAME_SETTINGS_PATH}?server=${encodeURIComponent(serverId)}`;
}

// 게임 설정 칸(설계서 S63–S70) — null 은 화면에서 뺀다(서버 응답엔 남아 있다). 모르는 새 칸은 서버 이름 그대로 보인다.
const GAME_SETTING_VIEW: Record<string, { label: string; help?: string; restart?: boolean } | null> = {
    msg: null, // S63 운영자 메시지 — web/game 에 보이는 곳이 없다. 알릴 말은 공지로.
    npcmode: null, // S64 빙의 은퇴(#985).
    block_general_create: null, // S65 생성 허용은 선택 정책 원장이 정한다.
    maxnation: null, // S67 휘하 규칙에 세력 수 상한이 없다.
    startyear: null, // S68 시나리오가 정한다 — 요약 칸에 읽기만.
    maxgeneral: { label: '사람 장수 상한' },
    starttime: { label: '시작 시각', help: '형식 2026-10-03 20:00' },
    turnterm: { label: '한 순 길이', restart: true },
};

function settingLabel(field: AdminEditableField): string {
    return GAME_SETTING_VIEW[field.key]?.label ?? field.label;
}

function settingValueText(field: AdminEditableField, raw: string): string {
    return field.options?.find((option) => option.value === raw)?.label ?? (raw || '(빈 칸)');
}

/** 게임 설정(설계서 S56–S71) — world_state.config 에서 읽고 고른 서버에만 저장한다. 저장 전에 바뀐 칸을 보여 준다. */
function GameSettingsControl({ selectedServer, serverName }: { selectedServer: string; serverName: string }) {
    // undefined = 확인하는 중, null = 조회 실패.
    const [settings, setSettings] = useState<AdminGameSettingsResponse | null | undefined>(undefined);
    const [drafts, setDrafts] = useState<Record<string, string>>({});
    const [busy, setBusy] = useState(false);
    const [confirming, setConfirming] = useState(false);
    const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

    const load = useCallback(async () => {
        if (!selectedServer) return;
        setSettings(undefined);
        try {
            const res = await fetch(adminGameSettingsPath(selectedServer), {
                cache: 'no-store',
            });
            if (!res.ok) throw new Error(`요청 실패 (${res.status})`);
            const data = (await res.json()) as AdminGameSettingsResponse;
            setSettings(data);
            setDrafts(Object.fromEntries(data.editableFields.map((field) => [field.key, String(field.value ?? '')])));
        } catch {
            setSettings(null);
        }
    }, [selectedServer]);

    useEffect(() => {
        setMessage(null);
        void load();
    }, [load]);

    const fields = settings ? settings.editableFields.filter((field) => GAME_SETTING_VIEW[field.key] !== null) : [];
    const changes = fields.filter((field) => (drafts[field.key] ?? '').trim() !== String(field.value ?? ''));

    async function save() {
        if (!settings || !selectedServer) return;
        const values: Record<string, string | number> = {};
        for (const field of changes) {
            const raw = drafts[field.key]?.trim() ?? '';
            if (field.type === 'text') {
                values[field.key] = raw;
            } else if (field.type === 'number' || field.type === 'select') {
                const parsed = raw ? parseInt(raw, 10) : NaN;
                if (Number.isNaN(parsed)) {
                    setMessage({ ok: false, text: `${settingLabel(field)} 값이 올바르지 않습니다.` });
                    setConfirming(false);
                    return;
                }
                values[field.key] = parsed;
            }
        }
        if (Object.keys(values).length === 0) return;

        setBusy(true);
        setMessage(null);
        try {
            const res = await fetch(adminGameSettingsPath(selectedServer), {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ values }),
            });
            const data = (await res.json()) as {
                result?: boolean;
                reason?: string;
                restartRequired?: boolean;
            };
            if (!res.ok || data.result === false) {
                throw new Error(data.reason ?? `게임 설정 저장 실패 (${selectedServer})`);
            }
            setMessage({
                ok: true,
                text: data.restartRequired ? '저장했습니다. 한 순 길이는 엔진을 다시 띄워야 적용됩니다.' : '저장했습니다.',
            });
            await load();
        } catch (e) {
            setMessage({ ok: false, text: e instanceof Error ? e.message : '게임 설정 저장에 실패했습니다.' });
        } finally {
            setBusy(false);
            setConfirming(false);
        }
    }

    const currentDate = settings?.year && settings.month
        ? `${settings.year}년 ${settings.month}월${settings.turnPhaseText ? ` ${settings.turnPhaseText}` : ''}`
        : '-';

    return (
        <Panel className="admin31-panel" aria-label="게임 설정">
            <SectionHeader as="h2" title={`게임 설정${serverName ? ` · ${serverName}` : ''}`} />
            <div className="admin31-body">
                {message && <ResultLine ok={message.ok}>{message.text}</ResultLine>}
                {settings === undefined && <StateLine kind="loading" title="게임 설정을 확인하는 중" />}
                {settings === null && <StateLine kind="error" title="게임 설정을 불러오지 못했습니다" onRetry={() => void load()} />}
                {settings && (
                    <>
                        <KV
                            className="admin31-srv-kv"
                            items={[
                                { k: '상태', v: settings.status ?? '-' },
                                { k: '시나리오', v: settings.scenarioText ?? settings.scenarioCode ?? '-' },
                                { k: '맵', v: settings.mapCode ?? '-' },
                                { k: '지금', v: currentDate },
                                { k: '한 순', v: settings.turnterm ? `${settings.turnterm}분` : '-' },
                                { k: '시작 연도', v: settings.startyear ?? '-' },
                            ]}
                        />
                        <div className="admin31-srv-grid">
                            {fields.map((field) => {
                                const view = GAME_SETTING_VIEW[field.key];
                                return (
                                    <label key={field.key} className="gw31-field">
                                        <span className="gw31-field__label">
                                            {settingLabel(field)}
                                            {view?.restart && <Chip tone="bronze">엔진을 다시 띄워야 적용됩니다</Chip>}
                                        </span>
                                        {field.type === 'select' && field.options ? (
                                            <select
                                                className="os-input"
                                                value={drafts[field.key] ?? String(field.value ?? '')}
                                                disabled={busy}
                                                onChange={(e) =>
                                                    setDrafts((prev) => ({
                                                        ...prev,
                                                        [field.key]: e.target.value,
                                                    }))
                                                }
                                            >
                                                {field.options.map((opt) => (
                                                    <option key={opt.value} value={opt.value}>
                                                        {opt.label}
                                                    </option>
                                                ))}
                                            </select>
                                        ) : (
                                            <input
                                                className="os-input"
                                                type={field.type === 'number' ? 'number' : 'text'}
                                                value={drafts[field.key] ?? String(field.value ?? '')}
                                                disabled={busy}
                                                onChange={(e) =>
                                                    setDrafts((prev) => ({
                                                        ...prev,
                                                        [field.key]: e.target.value,
                                                    }))
                                                }
                                            />
                                        )}
                                        {view?.help && <small className="gw31-field__help">{view.help}</small>}
                                    </label>
                                );
                            })}
                        </div>
                        <div className="admin31-row admin31-srv-actions">
                            <ActButton
                                block={busy ? BUSY : changes.length === 0 ? '바꾼 값이 없습니다' : null}
                                variant="primary"
                                onClick={() => setConfirming(true)}
                            >
                                저장
                            </ActButton>
                        </div>
                    </>
                )}
            </div>
            <ConfirmModal
                open={confirming}
                title="게임 설정 저장"
                busy={busy}
                confirmLabel="저장"
                message={
                    <>
                        <p className="admin31-srv-note">{serverName || selectedServer} 서버에 아래처럼 저장합니다.</p>
                        <ul className="admin31-srv-changes">
                            {changes.map((field) => (
                                <li key={field.key}>
                                    {settingLabel(field)}: {settingValueText(field, String(field.value ?? ''))} → {settingValueText(field, (drafts[field.key] ?? '').trim())}
                                </li>
                            ))}
                        </ul>
                    </>
                }
                onConfirm={() => void save()}
                onCancel={() => setConfirming(false)}
            />
        </Panel>
    );
}

/** 서버 탭 아래 절 — 고른 서버의 게임 설정 · 환경값(설계서 §3.4 S56–S82). 옛 「게임 환경」 탭의 락 · 따라잡기는 턴 · 따라잡기 탭으로 옮겼다. */
function ServerEnvSection({ servers, selectedServer, onSelect }: {
    readonly servers: ServerVersion[] | null;
    readonly selectedServer: string;
    readonly onSelect: (serverId: string) => void;
}) {
    const [sharedEnv, setSharedEnv] = useState<EnvConfigResponse | null>(null);
    const [serverEnv, setServerEnv] = useState<EnvConfigResponse | null>(null);
    const [sharedFailed, setSharedFailed] = useState(false);
    const [serverFailed, setServerFailed] = useState(false);
    const [sharedDrafts, setSharedDrafts] = useState<Record<string, string>>({});
    const [serverDrafts, setServerDrafts] = useState<Record<string, string>>({});
    const [envBusy, setEnvBusy] = useState(false);
    const [envMessage, setEnvMessage] = useState<{ ok: boolean; text: string } | null>(null);
    const [secretSave, setSecretSave] = useState<{ scope: 'shared' | 'server'; keys: string[] } | null>(null);

    const loadSharedEnv = useCallback(async () => {
        setSharedFailed(false);
        setSharedEnv(null);
        try {
            const data = await getJson<EnvConfigResponse>('admin/env/shared');
            setSharedEnv(data);
            setSharedDrafts(
                Object.fromEntries(
                    Object.entries(data.fields ?? {}).map(([key, field]) => [key, fieldInitialValue(field)]),
                ),
            );
        } catch {
            setSharedFailed(true);
        }
    }, []);

    const loadServerEnv = useCallback(async (serverId: string) => {
        setServerFailed(false);
        setServerEnv(null);
        if (!serverId) {
            setServerDrafts({});
            return;
        }
        try {
            const data = await getJson<EnvConfigResponse>(`admin/env/servers/${encodeURIComponent(serverId)}`);
            setServerEnv(data);
            setServerDrafts(
                Object.fromEntries(
                    Object.entries(data.fields ?? {}).map(([key, field]) => [key, fieldInitialValue(field)]),
                ),
            );
        } catch {
            setServerFailed(true);
        }
    }, []);

    useEffect(() => {
        void loadSharedEnv();
    }, [loadSharedEnv]);

    useEffect(() => {
        if (!selectedServer) return;
        setEnvMessage(null);
        void loadServerEnv(selectedServer);
    }, [loadServerEnv, selectedServer]);

    function pendingValues(scope: 'shared' | 'server'): { values: Record<string, string>; secrets: string[] } {
        const config = scope === 'shared' ? sharedEnv : serverEnv;
        const drafts = scope === 'shared' ? sharedDrafts : serverDrafts;
        const values: Record<string, string> = {};
        const secrets: string[] = [];
        for (const [key, field] of Object.entries(config?.fields ?? {})) {
            if (hiddenEnvKey(key)) continue;
            const value = drafts[key] ?? '';
            if (field.writeOnly) {
                if (value.trim() !== '') {
                    values[key] = value;
                    secrets.push(key);
                }
            } else if (value !== fieldInitialValue(field)) {
                values[key] = value;
            }
        }
        return { values, secrets };
    }

    // 비밀값(ADMIN_PASSWORD · JWT · GHCR_TOKEN 등)을 바꾸는 저장은 확인을 받는다(설계서 S73–S81). 값은 보여 주지 않는다.
    function requestSave(scope: 'shared' | 'server') {
        const { secrets } = pendingValues(scope);
        if (secrets.length > 0) setSecretSave({ scope, keys: secrets });
        else void saveEnv(scope);
    }

    async function saveEnv(scope: 'shared' | 'server') {
        const { values } = pendingValues(scope);
        if (Object.keys(values).length === 0) return;
        setEnvBusy(true);
        setEnvMessage(null);
        try {
            const path =
                scope === 'shared' ? 'admin/env/shared' : `admin/env/servers/${encodeURIComponent(selectedServer)}`;
            const res = await fetch(`/api/proxy/${path}`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ values }),
            });
            const data = (await res.json()) as EnvConfigResponse;
            if (!res.ok || data.ok === false) {
                setEnvMessage({ ok: false, text: data.message ?? '환경값 저장에 실패했습니다.' });
                return;
            }
            const drafts = Object.fromEntries(
                Object.entries(data.fields ?? {}).map(([key, field]) => [key, fieldInitialValue(field)]),
            );
            if (scope === 'shared') {
                setSharedEnv(data);
                setSharedDrafts(drafts);
            } else {
                setServerEnv(data);
                setServerDrafts(drafts);
            }
            setEnvMessage({ ok: true, text: '저장했습니다.' });
        } catch {
            setEnvMessage({ ok: false, text: '환경값 저장에 실패했습니다.' });
        } finally {
            setEnvBusy(false);
            setSecretSave(null);
        }
    }

    const selectedServerInfo = servers?.find((server) => server.id === selectedServer);
    const serverName = selectedServerInfo ? serverLabel(selectedServerInfo) : '';

    return (
        <div className="admin31-stack">
            <AdminServerPicker servers={servers} value={selectedServer} onChange={onSelect} />
            {selectedServer && <GameSettingsControl selectedServer={selectedServer} serverName={serverName} />}
            <Panel className="admin31-panel" aria-label="환경값">
                <SectionHeader as="h2" title={`환경값${serverName ? ` · ${serverName}` : ''}`} />
                <div className="admin31-body">
                    {envMessage && <ResultLine ok={envMessage.ok}>{envMessage.text}</ResultLine>}
                    <div className="admin31-srv-envgrid">
                        <EnvConfigEditor
                            title="공유 스택"
                            config={sharedEnv}
                            failed={sharedFailed}
                            drafts={sharedDrafts}
                            block={envBusy ? BUSY : null}
                            onChange={(key, value) => setSharedDrafts((prev) => ({ ...prev, [key]: value }))}
                            onSave={() => requestSave('shared')}
                            onRetry={() => void loadSharedEnv()}
                        />
                        {selectedServer ? (
                            <EnvConfigEditor
                                title="게임 서버"
                                config={serverEnv}
                                failed={serverFailed}
                                drafts={serverDrafts}
                                block={envBusy ? BUSY : null}
                                onChange={(key, value) => setServerDrafts((prev) => ({ ...prev, [key]: value }))}
                                onSave={() => requestSave('server')}
                                onRetry={() => void loadServerEnv(selectedServer)}
                            />
                        ) : (
                            <p className="admin31-srv-note">게임 서버를 고르면 그 서버의 환경값이 보입니다.</p>
                        )}
                    </div>
                </div>
            </Panel>
            <ConfirmModal
                open={secretSave !== null}
                title="비밀값 저장"
                danger
                busy={envBusy}
                confirmLabel="저장"
                message={
                    <>
                        <p className="admin31-srv-note">
                            {secretSave?.scope === 'shared' ? '공유 스택' : serverName || selectedServer}의 비밀값을 새 값으로 바꿉니다. 이전 값은 되돌릴 수 없습니다.
                        </p>
                        <ul className="admin31-srv-changes">
                            {secretSave?.keys.map((key) => <li key={key} className="os-num">{key}</li>)}
                        </ul>
                    </>
                }
                onConfirm={() => { if (secretSave) void saveEnv(secretSave.scope); }}
                onCancel={() => setSecretSave(null)}
            />
        </div>
    );
}

function AdminView() {
    // 기본 탭은 「개요」(설계서 §3.4). 버전 목록은 개요 · 서버 탭이 읽은 것을 함께 쓰고, 턴 · 따라잡기에서만 없으면 따로 읽는다.
    const [active, setActive] = useState<AdminSectionId>('overview');
    const [servers, setServers] = useState<ServerVersion[] | null>(null);
    const [selected, setSelected] = useState('');
    const section = ADMIN_SECTIONS.find((s) => s.id === active) ?? ADMIN_SECTIONS[0];
    const acceptVersion = useCallback((version: VersionResponse) => {
        setServers(version.servers);
        setSelected((current) => (current && version.servers.some((s) => s.id === current) ? current : version.servers[0]?.id ?? ''));
    }, []);
    useEffect(() => {
        if (servers !== null || (active !== 'turn' && active !== 'catchup')) return;
        let alive = true;
        getJson<VersionResponse>('admin/version').then((v) => { if (alive) acceptVersion(v); }).catch(() => { if (alive) setServers([]); });
        return () => { alive = false; };
    }, [active, servers, acceptVersion]);

    return (
        <div className="gw31-page">
            <MemberHeader current="admin" />
            <main className="admin31">
                <nav className="admin31-rail" aria-label="운영 콘솔">
                    <span className="admin31-rail__title os-serif">운영 콘솔</span>
                    {ADMIN_SECTIONS.map((s) => (
                        <button
                            key={s.id}
                            type="button"
                            className={`admin31-tab${s.id === active ? ' is-on' : ''}`}
                            aria-current={s.id === active ? 'page' : undefined}
                            onClick={() => setActive(s.id)}
                        >
                            <span className="admin31-tab__name">{s.label}</span>
                            <span className="admin31-tab__risk" aria-hidden="true">{s.risk}</span>
                        </button>
                    ))}
                </nav>
                <section className="admin31-main" aria-labelledby="admin-section-title">
                    <div className="admin31-head">
                        <h1 id="admin-section-title" className="admin31-head__title os-serif">{section.label}</h1>
                        <Chip tone={section.risk.includes('파괴') ? 'rust' : 'neutral'}>위험 등급 · {section.risk}</Chip>
                    </div>
                    <div className="admin31-content">
                        {active === 'overview' && <AdminOverview onNavigate={(id) => setActive(id as AdminSectionId)} onVersion={acceptVersion} />}
                        {active === 'members' && <MemberControl />}
                        {active === 'board' && <BoardControl />}
                        {active === 'reports' && <BoardReportControl />}
                        {active === 'notice' && <NoticeControl />}
                        {active === 'turn' && <TurnControl servers={servers} serverId={selected} onSelect={setSelected} />}
                        {active === 'catchup' && <CatchUpTab servers={servers} serverId={selected} onSelect={setSelected} />}
                        {active === 'server' && (
                            <div className="admin31-stack">
                                <ServerControl onVersion={acceptVersion} />
                                <ServerEnvSection servers={servers} selectedServer={selected} onSelect={setSelected} />
                            </div>
                        )}
                    </div>
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
