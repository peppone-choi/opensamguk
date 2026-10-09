import { fetchGame } from '@/lib/api';

export interface HierarchySuperior {
  readonly generalId: number;
  readonly name: string;
  readonly parentId: number | null;
}
export interface HierarchyNode extends HierarchySuperior {
  readonly directCount: number;
  readonly descendantCount: number;
}
export interface RetinueHierarchy {
  readonly status: 'READY' | 'UNAVAILABLE';
  readonly actorGeneralId: number;
  readonly superiors: readonly HierarchySuperior[];
  readonly nodes: readonly HierarchyNode[];
}

const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) > 0;
const count = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 0;

function superior(v: unknown): HierarchySuperior | null {
  if (!record(v) || !positive(v.generalId) || typeof v.name !== 'string' || !v.name.trim()
    || !(v.parentId === null || positive(v.parentId))) return null;
  return { generalId: v.generalId, name: v.name, parentId: v.parentId };
}

/** Only the actor's ancestor chain and complete subtree are accepted. Extra private fields are discarded. */
export function parseRetinueHierarchy(body: unknown, actor: number): RetinueHierarchy | null {
  if (!record(body) || body.actorGeneralId !== actor || !positive(actor)
    || !Array.isArray(body.superiors) || !Array.isArray(body.nodes)) return null;
  if (body.status === 'UNAVAILABLE') return body.superiors.length === 0 && body.nodes.length === 0
    ? { status: 'UNAVAILABLE', actorGeneralId: actor, superiors: [], nodes: [] } : null;
  if (body.status !== 'READY') return null;
  const superiors: HierarchySuperior[] = [];
  const seen = new Set<number>([actor]);
  for (const value of body.superiors) {
    const row = superior(value);
    if (!row || seen.has(row.generalId)) return null;
    seen.add(row.generalId);
    superiors.push(row);
  }
  if (superiors.some((s, i) => s.parentId !== (superiors[i + 1]?.generalId ?? null))) return null;
  const nodes: HierarchyNode[] = [];
  for (const value of body.nodes) {
    const row = superior(value);
    if (!row || !record(value) || !count(value.directCount) || !count(value.descendantCount)) return null;
    if (nodes.length === 0 ? row.generalId !== actor : seen.has(row.generalId)) return null;
    seen.add(row.generalId);
    nodes.push({ ...row, directCount: value.directCount, descendantCount: value.descendantCount });
  }
  if (!nodes.length || nodes[0].parentId !== (superiors[0]?.generalId ?? null)) return null;
  const stack: number[] = [];
  const direct = new Map<number, number>();
  const descendants = new Map<number, number>();
  const lastChild = new Map<number, number>();
  for (const [i, node] of nodes.entries()) {
    if (i > 0) {
      while (stack.length && stack.at(-1) !== node.parentId) stack.pop();
      if (!stack.length || node.parentId === null || (lastChild.get(node.parentId) ?? 0) >= node.generalId) return null;
      lastChild.set(node.parentId, node.generalId);
      direct.set(node.parentId, (direct.get(node.parentId) ?? 0) + 1);
    }
    stack.push(node.generalId);
    descendants.set(node.generalId, 0);
  }
  for (let i = nodes.length - 1; i >= 0; i--) {
    const node = nodes[i];
    const total = descendants.get(node.generalId) ?? 0;
    if (node.directCount !== (direct.get(node.generalId) ?? 0) || node.descendantCount !== total) return null;
    if (node.parentId !== null && descendants.has(node.parentId)) {
      descendants.set(node.parentId, (descendants.get(node.parentId) ?? 0) + total + 1);
    }
  }
  return { status: 'READY', actorGeneralId: actor, superiors, nodes };
}

export class HierarchyReadError extends Error {
  constructor(readonly status: number | null) {
    super(status === 401 ? '로그인이 만료되었습니다. 다시 로그인해 주세요.'
      : status === 403 ? '본인의 부 조직도만 볼 수 있습니다.' : '조직도를 불러오지 못했습니다.');
  }
}

export async function readRetinueHierarchy(actor: number, signal?: AbortSignal): Promise<RetinueHierarchy> {
  if (!positive(actor)) throw new HierarchyReadError(403);
  const response = await fetchGame(`/api/retinue/hierarchy?generalId=${encodeURIComponent(String(actor))}`, { cache: 'no-store', signal });
  if (response.status !== 200) throw new HierarchyReadError(response.status);
  let body: unknown;
  try { body = await response.json(); } catch { throw new HierarchyReadError(null); }
  const data = parseRetinueHierarchy(body, actor);
  if (!data) throw new HierarchyReadError(null);
  return data;
}
