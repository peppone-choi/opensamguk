import type { CellPoint } from '@opensamguk/ui/map/topdown';
import type { TargetCandidate } from '@opensamguk/ui';
import type { MapPreviewResponse } from '../types';
import type { ArgField } from './options';
import { toTargetCandidates } from './parts-adapter';

/** Only the actual preview binding can join a province identifier to a baked map cell. */
export function destinationMapCandidates(field: ArgField, preview: MapPreviewResponse | null, centers: readonly (CellPoint | null)[] | null): TargetCandidate[] {
    const bindings = new Map<string, number | null>();
    for (const row of preview?.provinceOccupancy ?? []) {
        const prior = bindings.get(row.provinceRecordId);
        bindings.set(row.provinceRecordId, prior === undefined || prior === row.provinceIndex ? row.provinceIndex : null);
    }
    return toTargetCandidates(field).map((candidate, i) => {
        const index = candidate.provinceId ? bindings.get(candidate.provinceId) : null;
        const at = index == null ? null : centers?.[index];
        const cell = at && Number.isFinite(at.col) && Number.isFinite(at.row)
            ? { col: Math.floor(at.col), row: Math.floor(at.row) } : undefined;
        const range = field.candidates[i].rangeLabel;
        return { ...candidate, ...(cell ? { cell } : {}), name: range ? `${candidate.name} · ${range}` : candidate.name };
    });
}
