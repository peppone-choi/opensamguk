"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useGameStore } from "@/stores/gameStore";
import type { CityConst } from "@/types";

const MAP_WIDTH = 800;
const MAP_HEIGHT = 600;
const CITY_RADIUS = 10;

interface MapViewerProps {
  worldId: number;
  mapCode?: string;
  compact?: boolean;
}

export function MapViewer({
  worldId,
  mapCode = "che",
  compact = false,
}: MapViewerProps) {
  const router = useRouter();
  const { cities, nations, mapData, loadAll, loadMap } = useGameStore();
  const [showNames, setShowNames] = useState(true);
  const [tooltip, setTooltip] = useState<{
    cityName: string;
    nationName: string;
    nationColor: string;
    x: number;
    y: number;
  } | null>(null);

  useEffect(() => {
    loadAll(worldId);
    loadMap(mapCode);
  }, [worldId, mapCode, loadAll, loadMap]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  const constMap = useMemo(
    () => new Map(mapData?.cities.map((c) => [c.id, c]) ?? []),
    [mapData],
  );

  const { scaleX, scaleY, offsetX, offsetY } = useMemo(() => {
    if (!mapData || mapData.cities.length === 0)
      return { scaleX: 1, scaleY: 1, offsetX: 0, offsetY: 0 };
    let minX = Infinity,
      maxX = -Infinity,
      minY = Infinity,
      maxY = -Infinity;
    for (const c of mapData.cities) {
      if (c.x < minX) minX = c.x;
      if (c.x > maxX) maxX = c.x;
      if (c.y < minY) minY = c.y;
      if (c.y > maxY) maxY = c.y;
    }
    const pad = 40;
    const rangeX = maxX - minX || 1;
    const rangeY = maxY - minY || 1;
    return {
      scaleX: (MAP_WIDTH - pad * 2) / rangeX,
      scaleY: (MAP_HEIGHT - pad * 2) / rangeY,
      offsetX: -minX,
      offsetY: -minY,
    };
  }, [mapData]);

  const toSvgX = useCallback(
    (x: number) => (x + offsetX) * scaleX + 40,
    [offsetX, scaleX],
  );
  const toSvgY = useCallback(
    (y: number) => (y + offsetY) * scaleY + 40,
    [offsetY, scaleY],
  );

  const getCityColor = (cityId: number): string => {
    const city = cityMap.get(cityId);
    if (!city || city.nationId === 0) return "#555";
    return nationMap.get(city.nationId)?.color ?? "#555";
  };

  const connections = useMemo(() => {
    if (!mapData?.cities) return [];
    const seen = new Set<string>();
    const lines: { x1: number; y1: number; x2: number; y2: number }[] = [];
    for (const city of mapData.cities) {
      for (const connId of city.connections) {
        const key =
          city.id < connId ? `${city.id}-${connId}` : `${connId}-${city.id}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const target = constMap.get(connId);
        if (target) {
          lines.push({
            x1: toSvgX(city.x),
            y1: toSvgY(city.y),
            x2: toSvgX(target.x),
            y2: toSvgY(target.y),
          });
        }
      }
    }
    return lines;
  }, [mapData, constMap, toSvgX, toSvgY]);

  const handleCityClick = (cc: CityConst, e: React.MouseEvent) => {
    e.stopPropagation();
    if (compact) {
      const city = cityMap.get(cc.id);
      const nation = city?.nationId ? nationMap.get(city.nationId) : null;
      setTooltip({
        cityName: cc.name,
        nationName: nation?.name ?? "공백지",
        nationColor: nation?.color ?? "#555",
        x: e.clientX,
        y: e.clientY,
      });
    } else {
      router.push(`/city?id=${cc.id}`);
    }
  };

  if (!mapData) {
    return (
      <div className="flex items-center justify-center h-32 text-xs text-muted-foreground">
        지도 로딩중...
      </div>
    );
  }

  return (
    <div className="relative">
      {!compact && (
        <button
          onClick={() => setShowNames(!showNames)}
          className="absolute right-1 top-1 z-10 border border-gray-600 bg-[#111] px-1.5 py-0.5 text-[10px] text-gray-300"
        >
          {showNames ? "이름 숨김" : "이름 표시"}
        </button>
      )}
      <svg
        viewBox={`0 0 ${MAP_WIDTH} ${MAP_HEIGHT}`}
        className="h-auto w-full border border-gray-600 bg-black"
        onClick={() => setTooltip(null)}
      >
        {connections.map((l, i) => (
          <line
            key={i}
            x1={l.x1}
            y1={l.y1}
            x2={l.x2}
            y2={l.y2}
            stroke="#333"
            strokeWidth={0.8}
          />
        ))}
        {mapData.cities.map((cc) => {
          const cx = toSvgX(cc.x);
          const cy = toSvgY(cc.y);
          const color = getCityColor(cc.id);
          return (
            <g
              key={cc.id}
              className="cursor-pointer"
              onClick={(e) => handleCityClick(cc, e)}
            >
              <circle
                cx={cx}
                cy={cy}
                r={compact ? CITY_RADIUS * 0.8 : CITY_RADIUS}
                fill={color}
                stroke="#000"
                strokeWidth={1}
                opacity={0.85}
              />
              {showNames && (
                <text
                  x={cx}
                  y={cy + CITY_RADIUS + 11}
                  textAnchor="middle"
                  fill="#ccc"
                  fontSize={compact ? 8 : 10}
                >
                  {cc.name}
                </text>
              )}
            </g>
          );
        })}
      </svg>
        {tooltip && (
          <div
            className="fixed z-50 border border-gray-600 bg-[#111] p-2 text-xs shadow-lg"
            style={{ left: tooltip.x + 10, top: tooltip.y - 8 }}
          >
          <div className="font-medium flex items-center gap-1.5">
            <span
              className="w-2.5 h-2.5 rounded-full"
              style={{ backgroundColor: tooltip.nationColor }}
            />
            {tooltip.cityName}
          </div>
          <div className="text-muted-foreground">{tooltip.nationName}</div>
        </div>
      )}
    </div>
  );
}
