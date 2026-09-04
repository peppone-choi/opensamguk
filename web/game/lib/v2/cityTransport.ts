export interface StrategicTransportPath {
    nodeKeys: string[];
    edgeIds: string[];
    modes: string[];
    totalCost: number;
    capacity: number;
    topologyRevision: string;
    topologyHash: string;
    pathHash: string;
}

export interface TransportRoutePreview {
    status: 'AVAILABLE' | 'BLOCKED';
    code?: string;
    reason?: string;
    route?: StrategicTransportPath | null;
}

/** A server path is authoritative; the browser never derives a path or its hash. */
export function transportRoutePins(preview: TransportRoutePreview): {
    topologyRevision?: string;
    routePathHash?: string;
} {
    if (preview.status !== 'AVAILABLE') {
        throw new Error(preview.reason || '수송 경로를 사용할 수 없습니다.');
    }
    if (preview.route === null) return {}; // Explicit legacy response only.
    const route = preview.route;
    if (!route || typeof route.topologyRevision !== 'string' || !route.topologyRevision.trim()
        || typeof route.pathHash !== 'string' || !route.pathHash.trim()
        || !Array.isArray(route.modes) || !Number.isSafeInteger(route.totalCost)) {
        throw new Error('경로 확인 응답이 올바르지 않습니다.');
    }
    return { topologyRevision: route.topologyRevision, routePathHash: route.pathHash };
}

const modeLabels: Record<string, string> = {
    LAND: '육로', FORD: '여울', BRIDGE: '다리', FERRY: '나루',
    EMBARK: '승선', DISEMBARK: '상륙', RIVER_UP: '강 상행', RIVER_DOWN: '강 하행',
    LAKE: '호수', COASTAL: '연안',
};

export function transportRouteSummary(route: StrategicTransportPath): string {
    return `서버 경로: ${route.modes.map(mode => modeLabels[mode] ?? mode).join(' → ')} · 비용 ${route.totalCost}`;
}
