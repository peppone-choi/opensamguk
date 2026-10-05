// 실시간 전투 클라이언트 프레임(K6-15, v2 초안) — **초안 — C2 병합 때 맞춤.** 입구는 protocol.ts(여기 것을 다시 내보낸다).
// DEPLOYMENT_MOVE(배치 옮기기) · COMMAND(6명령 + 집결점) · 명령 번호.
import { sourceKeyId, type BattleOrder, type Cell, type RallyPoint, type SourceKey } from './wire';

export interface DeploymentMoveArgs {
    readonly clientCommandId: string;
    readonly sourceKey: SourceKey;
    readonly targetCell: Cell;
    readonly expectedEpoch: string;
    readonly expectedAuthorityRevision: string;
    readonly expectedDeploymentRevision: string;
}

/** 배치 옮기기 — 한 부곡 · 한 칸. 기대 값(epoch · 지휘권 · 배치 revision)은 마지막 SNAPSHOT/ACK 의 값이다. */
export function deploymentMove(args: DeploymentMoveArgs): string {
    return JSON.stringify({
        schemaVersion: 2,
        t: 'DEPLOYMENT_MOVE',
        clientCommandId: args.clientCommandId,
        sourceKey: args.sourceKey,
        targetCell: { row: args.targetCell.row, col: args.targetCell.col },
        expectedEpoch: args.expectedEpoch,
        expectedAuthorityRevision: args.expectedAuthorityRevision,
        expectedDeploymentRevision: args.expectedDeploymentRevision,
    });
}

export type CommandScope = { readonly sourceKeys: readonly SourceKey[] } | { readonly allMine: true };

export interface BattleCommandArgs {
    readonly clientCommandId: string;
    readonly expectedEpoch: string;
    readonly expectedAuthorityRevision: string;
    /** 마지막으로 본 틱(JSON number). */
    readonly issuedTick: number;
    readonly scope: CommandScope;
    readonly order: BattleOrder;
    readonly rally: RallyPoint;
}

/** 전투 명령 — 고른 부곡(sourceKeys, 빈 목록 · 중복 금지) 또는 내 부곡 전부(allMine). 전부 받거나 전부 거절된다(C2 v2 #10). */
export function battleCommand(args: BattleCommandArgs): string {
    const scope = 'allMine' in args.scope
        ? { allMine: true as const }
        : { sourceKeys: [...new Map(args.scope.sourceKeys.map((k) => [sourceKeyId(k), k])).values()].sort((a, b) => sourceKeyId(a).localeCompare(sourceKeyId(b))) };
    return JSON.stringify({
        schemaVersion: 2,
        t: 'COMMAND',
        clientCommandId: args.clientCommandId,
        expectedEpoch: args.expectedEpoch,
        expectedAuthorityRevision: args.expectedAuthorityRevision,
        issuedTick: args.issuedTick,
        scope,
        intentType: args.order,
        intentPayload: { rally: args.rally },
    });
}

/** 명령 번호 — 재전송 멱등의 열쇠(같은 번호 · 같은 내용은 첫 영수증을 다시 받는다). */
export function newClientCommandId(): string {
    return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `c-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
