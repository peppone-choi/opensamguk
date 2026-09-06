// 계정 대표 장수(ADR-LITE-049 13) — 커뮤니티 글·댓글의 서버 배지 원천. 후보는 계정이 가진 플레이어 장수뿐이다.
export type RepresentativeCandidate = { readonly generalId: number; readonly name: string; readonly worldId: number; readonly scenarioCode: string | null };
export type RepresentativeState = { readonly generalId: number | null; readonly name: string | null; readonly worldId: number | null };
export type RepresentativeResponse = { readonly current: RepresentativeState; readonly candidates: readonly RepresentativeCandidate[] };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
function parse(value: unknown): RepresentativeResponse {
  if (!isRecord(value) || !isRecord(value.current) || !Array.isArray(value.candidates)) {
    throw new Error('대표 장수 응답이 올바르지 않습니다.');
  }
  const current = value.current;
  return {
    current: {
      generalId: typeof current.generalId === 'number' ? current.generalId : null,
      name: typeof current.name === 'string' ? current.name : null,
      worldId: typeof current.worldId === 'number' ? current.worldId : null,
    },
    candidates: value.candidates.flatMap((item) => (
      isRecord(item) && typeof item.generalId === 'number' && typeof item.name === 'string' && typeof item.worldId === 'number'
        ? [{ generalId: item.generalId, name: item.name, worldId: item.worldId, scenarioCode: typeof item.scenarioCode === 'string' ? item.scenarioCode : null }]
        : []
    )),
  };
}
async function read(res: Response): Promise<unknown> {
  const text = await res.text();
  try { return text ? JSON.parse(text) : null; } catch { return null; }
}

export async function fetchRepresentative(): Promise<RepresentativeResponse> {
  const res = await fetch('/api/account/representative', { cache: 'no-store' });
  const data = await read(res);
  if (!res.ok) throw new Error((isRecord(data) && typeof data.error === 'string' ? data.error : null) ?? '대표 장수를 불러오지 못했습니다.');
  return parse(data);
}

export async function setRepresentative(generalId: number | null): Promise<RepresentativeResponse> {
  const res = await fetch('/api/account/representative', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ generalId }),
  });
  const data = await read(res);
  if (!res.ok) throw new Error((isRecord(data) && typeof data.error === 'string' ? data.error : null) ?? '대표 장수를 저장하지 못했습니다.');
  return parse(data);
}
