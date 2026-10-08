import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchGame } from '@/lib/api';
import { parseRetinueHierarchy, readRetinueHierarchy } from '@/lib/api/retinue-hierarchy';
import { hierarchyRows } from '@/lib/retinue-hierarchy-view';
import { hierarchyFixture } from './fixtures/retinue-hierarchy';

vi.mock('@/lib/api', () => ({ fetchGame: vi.fn() }));
const fetchMock = vi.mocked(fetchGame);
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
beforeEach(() => fetchMock.mockReset());

describe('D164 hierarchy contract', () => {
  it('preserves preorder, counts actual people and distinguishes ancestor chain from subtree', () => {
    const data = parseRetinueHierarchy(hierarchyFixture(), 7)!;
    expect(data.superiors.map((s) => s.generalId)).toEqual([2, 1]);
    expect(hierarchyRows(data).map((r) => [r.node.generalId, r.depth, r.parentName])).toEqual([
      [7, 0, '가상 상관'], [101, 1, '하후돈'], [201, 2, '가상 직속'], [102, 1, '하후돈'],
    ]);
    expect(data.nodes[0]).toMatchObject({ directCount: 2, descendantCount: 3 });
  });
  it('keeps self and superiors for zero direct people; accepts a root without superiors', () => {
    const body = hierarchyFixture(true);
    expect(parseRetinueHierarchy(body, 7)?.nodes).toHaveLength(1);
    expect(parseRetinueHierarchy({ ...body, superiors: [], nodes: [{ ...body.nodes[0], parentId: null }] }, 7)?.nodes[0].generalId).toBe(7);
  });
  it('UNAVAILABLE has empty arrays and never becomes an empty READY hierarchy', () => {
    expect(parseRetinueHierarchy({ status: 'UNAVAILABLE', actorGeneralId: 7, superiors: [], nodes: [] }, 7)?.status).toBe('UNAVAILABLE');
    expect(parseRetinueHierarchy({ ...hierarchyFixture(), status: 'UNAVAILABLE' }, 7)).toBeNull();
    expect(parseRetinueHierarchy({ status: 'READY', actorGeneralId: 7, superiors: [], nodes: [] }, 7)).toBeNull();
  });
  it.each([
    ['other actor', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, actorGeneralId: 8 })],
    ['unknown status', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, status: 'WRONG_RULE_PROFILE' })],
    ['duplicate', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, nodes: [...b.nodes, b.nodes[1]] })],
    ['cycle', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, nodes: [{ ...b.nodes[0], parentId: 201 }, ...b.nodes.slice(1)] })],
    ['missing parent', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, nodes: b.nodes.map((n) => n.generalId === 201 ? { ...n, parentId: 999 } : n) })],
    ['wrong count', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, nodes: [{ ...b.nodes[0], descendantCount: 2 }, ...b.nodes.slice(1)] })],
    ['wrong direct count', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, nodes: [{ ...b.nodes[0], directCount: 3 }, ...b.nodes.slice(1)] })],
    ['not preorder', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, nodes: [b.nodes[0], b.nodes[1], b.nodes[3], b.nodes[2]] })],
    ['child order', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, nodes: [b.nodes[0], b.nodes[3], b.nodes[1], b.nodes[2]] })],
    ['ancestor gap', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, superiors: [{ ...b.superiors[0], parentId: 999 }, b.superiors[1]] })],
    ['ancestor repeats actor', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, superiors: [{ generalId: 7, name: 'wrong', parentId: null }] })],
    ['null arrays', (b: ReturnType<typeof hierarchyFixture>) => ({ ...b, nodes: null })],
  ])('rejects %s without synthesizing a relationship', (_label, change) => {
    expect(parseRetinueHierarchy(change(hierarchyFixture()), 7)).toBeNull();
  });
  it('projects only structural fields even if extra private values arrive', () => {
    const b = hierarchyFixture();
    const data = parseRetinueHierarchy({ ...b, nodes: b.nodes.map((n) => ({ ...n, location: 'PRIVATE', stats: { strength: 99 }, permission: 9 })) }, 7)!;
    expect(data.nodes[0]).toEqual(b.nodes[0]);
    expect(JSON.stringify(data)).not.toContain('PRIVATE');
  });
  it('requests only the actor with no-store and an abort signal', async () => {
    fetchMock.mockResolvedValue(json(hierarchyFixture()));
    const signal = new AbortController().signal;
    await expect(readRetinueHierarchy(7, signal)).resolves.toMatchObject({ status: 'READY' });
    expect(fetchMock).toHaveBeenCalledWith('/api/retinue/hierarchy?generalId=7', { cache: 'no-store', signal });
  });
  it.each([400, 401, 403, 404, 503])('preserves HTTP %i as a read error', async (status) => {
    fetchMock.mockResolvedValue(json({}, status));
    await expect(readRetinueHierarchy(7)).rejects.toMatchObject({ status });
  });
  it('refuses malformed JSON, foreign actor and invalid local actor', async () => {
    fetchMock.mockResolvedValueOnce(new Response('{', { status: 200 })).mockResolvedValueOnce(json({ ...hierarchyFixture(), actorGeneralId: 8 }));
    await expect(readRetinueHierarchy(7)).rejects.toThrow();
    await expect(readRetinueHierarchy(7)).rejects.toThrow();
    fetchMock.mockClear();
    await expect(readRetinueHierarchy(0)).rejects.toMatchObject({ status: 403 });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
