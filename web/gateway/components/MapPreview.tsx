'use client';

import { useEffect, useState } from 'react';
import { MAP_CDN } from '../lib/constants';

/**
 * 서버 세계지도 프리뷰 (서버마다 10분 캐싱).
 *
 * 흐름(결정 로그 §9): game-engine 10분 스냅샷 → game-api `GET /api/map/preview`(10분 캐시) →
 * 게이트웨이 route handler `/api/server-map/[id]`가 해당 서버 game-api로 서버사이드 프록시 →
 * 여기서 opensamguk-images CDN 추상 게임맵(che 700×500) 위에 클라이언트 SVG로 도시점 렌더
 * (국가색 dot, hover=도시명/레벨). 좌표 = 시나리오 scenario/map 게임 x/y(서버가 응답에 포함).
 */

const NEUTRAL_COLOR = '#555555';

interface MapCity {
    id: number;
    name: string;
    level: number;
    nationId: number;
    x: number;
    y: number;
}
interface MapNation {
    id: number;
    name: string;
    color: string;
}
interface MapData {
    serverName: string;
    year: number;
    month: number;
    mapCode: string;
    width: number;
    height: number;
    cities: MapCity[];
    nations: MapNation[];
}

function seasonOf(month: number): string {
    if (month >= 3 && month <= 5) return 'spring';
    if (month >= 6 && month <= 8) return 'summer';
    if (month >= 9 && month <= 11) return 'fall';
    return 'winter';
}

export default function MapPreview({ serverId = 'main' }: { serverId?: string }) {
    const [data, setData] = useState<MapData | null>(null);
    const [failed, setFailed] = useState(false);

    useEffect(() => {
        let on = true;
        setData(null);
        setFailed(false);
        fetch(`/api/server-map/${serverId}`, { cache: 'no-store' })
            .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then((d: MapData) => {
                if (on) setData(d);
            })
            .catch(() => {
                if (on) setFailed(true);
            });
        return () => {
            on = false;
        };
    }, [serverId]);

    // 미시드/실패/빈 세계 → placeholder
    if (failed || (data && data.cities.length === 0)) {
        return (
            <div className="map-preview" aria-label="서버 지도 프리뷰">
                <div className="map-preview-ph">맵 프리뷰 (준비 중)</div>
            </div>
        );
    }
    if (!data) {
        return (
            <div className="map-preview" aria-label="서버 지도 프리뷰">
                <div className="map-preview-ph">
                    <div className="spinner" />
                </div>
            </div>
        );
    }

    const mapCode = data.mapCode || 'che';
    const w = data.width || 700;
    const h = data.height || 500;
    const bg = `${MAP_CDN}/${mapCode}/bg_${seasonOf(data.month || 1)}.jpg`;
    const road = `${MAP_CDN}/${mapCode}/${mapCode}_road.png`;
    const colorOf = (nid: number) => data.nations.find((n) => n.id === nid)?.color ?? NEUTRAL_COLOR;

    return (
        <div className="map-preview" aria-label="서버 지도 프리뷰">
            <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="xMidYMid meet" role="img">
                <image href={bg} x={0} y={0} width={w} height={h} />
                <image href={road} x={0} y={0} width={w} height={h} opacity={0.5} />
                {data.cities.map((c) => (
                    <circle
                        key={c.id}
                        cx={c.x}
                        cy={c.y}
                        r={c.level >= 7 ? 6 : 4}
                        fill={colorOf(c.nationId)}
                        stroke="#0a0a0a"
                        strokeWidth={1}
                    >
                        <title>{`${c.name} (lv${c.level})`}</title>
                    </circle>
                ))}
            </svg>
            <div className="map-preview-cap">{`${data.serverName} · ${data.year}年 ${data.month}月`}</div>
        </div>
    );
}
