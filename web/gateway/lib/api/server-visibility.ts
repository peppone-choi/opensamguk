export interface ServerVisibility {
    readonly state: 'PUBLIC' | 'VERIFYING';
    readonly publiclyVisible: boolean;
    readonly revision: string;
}

async function readResponse(response: Response): Promise<ServerVisibility> {
    if (!response.ok) throw new Error(response.status === 409 ? '상태가 바뀌었습니다. 다시 조회해 주세요.' : '서버 공개 상태를 변경하지 못했습니다.');
    const body: unknown = await response.json();
    if (!body || typeof body !== 'object') throw new Error('공개 상태를 확인하지 못했습니다.');
    const value = body as Partial<ServerVisibility>;
    if ((value.state !== 'PUBLIC' && value.state !== 'VERIFYING') || typeof value.publiclyVisible !== 'boolean' ||
        typeof value.revision !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value.revision)) throw new Error('공개 상태를 확인하지 못했습니다.');
    return value as ServerVisibility;
}

export async function readServerVisibility(id: string): Promise<ServerVisibility> {
    return readResponse(await fetch(`/api/proxy/admin/servers/${encodeURIComponent(id)}/publication`, { cache: 'no-store' }));
}

export async function changeServerVisibility(id: string, current: ServerVisibility): Promise<ServerVisibility> {
    return readResponse(await fetch(`/api/proxy/admin/servers/${encodeURIComponent(id)}/visibility`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ publiclyVisible: !current.publiclyVisible, expectedRevision: current.revision }),
    }));
}
