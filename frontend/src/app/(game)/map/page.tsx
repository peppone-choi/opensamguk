"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import type { CityConst } from "@/types";

interface Tooltip {
  cityName: string;
  nationName: string;
  nationColor: string;
  level: number;
  pop: number;
  screenX: number;
  screenY: number;
}

const MAP_WIDTH = 1200;
const MAP_HEIGHT = 900;
const CITY_RADIUS = 14;

export default function MapPage() {
  const { currentWorld } = useWorldStore();
  const { cities, nations, mapData, loadAll, loadMap } = useGameStore();
  const [tooltip, setTooltip] = useState<Tooltip | null>(null);

  useEffect(() => {
    if (currentWorld) {
      loadAll(currentWorld.id);
      const mapCode =
        (currentWorld.config as Record<string, string>)?.mapCode ?? "che";
      loadMap(mapCode);
    }
  }, [currentWorld, loadAll, loadMap]);

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

  // Scale map data to SVG viewport
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
    const pad = 60;
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
    (x: number) => (x + offsetX) * scaleX + 60,
    [offsetX, scaleX],
  );
  const toSvgY = useCallback(
    (y: number) => (y + offsetY) * scaleY + 60,
    [offsetY, scaleY],
  );

  const getCityColor = (cityId: number): string => {
    const city = cityMap.get(cityId);
    if (!city || city.nationId === 0) return "#555";
    return nationMap.get(city.nationId)?.color ?? "#555";
  };

  // Connection lines (deduplicated)
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

  // Nations that own at least one city
  const legendNations = useMemo(() => {
    const ids = new Set(
      cities.filter((c) => c.nationId).map((c) => c.nationId),
    );
    return nations.filter((n) => ids.has(n.id));
  }, [cities, nations]);

  const handleCityClick = (cc: CityConst, e: React.MouseEvent) => {
    e.stopPropagation();
    const city = cityMap.get(cc.id);
    const nation = city?.nationId ? nationMap.get(city.nationId) : null;
    setTooltip({
      cityName: cc.name,
      nationName: nation?.name ?? "공백지",
      nationColor: nation?.color ?? "#555",
      level: city?.level ?? cc.level,
      pop: city?.pop ?? 0,
      screenX: e.clientX,
      screenY: e.clientY,
    });
  };

  if (!mapData) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500">
        지도를 불러오는 중...
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold">세계지도</h1>

      {/* Legend */}
      <div className="flex flex-wrap gap-3 text-xs text-gray-400">
        {legendNations.map((n) => (
          <div key={n.id} className="flex items-center gap-1.5">
            <span
              className="w-3 h-3 rounded-full"
              style={{ backgroundColor: n.color }}
            />
            <span>{n.name}</span>
          </div>
        ))}
        <div className="flex items-center gap-1.5">
          <span className="w-3 h-3 rounded-full bg-gray-600" />
          <span>공백지</span>
        </div>
      </div>

      {/* SVG Map */}
      <div
        className="relative bg-gray-900 border border-gray-800 rounded-lg overflow-hidden"
        onClick={() => setTooltip(null)}
      >
        <svg
          viewBox={`0 0 ${MAP_WIDTH} ${MAP_HEIGHT}`}
          className="w-full h-auto"
        >
          {/* Connection lines */}
          {connections.map((l, i) => (
            <line
              key={i}
              x1={l.x1}
              y1={l.y1}
              x2={l.x2}
              y2={l.y2}
              stroke="#333"
              strokeWidth={1}
            />
          ))}

          {/* City circles and labels */}
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
                  r={CITY_RADIUS}
                  fill={color}
                  stroke="#000"
                  strokeWidth={1.5}
                  opacity={0.85}
                />
                <text
                  x={cx}
                  y={cy + CITY_RADIUS + 14}
                  textAnchor="middle"
                  fill="#ccc"
                  fontSize={11}
                >
                  {cc.name}
                </text>
              </g>
            );
          })}
        </svg>

        {/* Tooltip popup */}
        {tooltip && (
          <div
            className="fixed z-50 bg-gray-800 border border-gray-700 rounded-lg p-3 shadow-lg text-sm space-y-1"
            style={{ left: tooltip.screenX + 12, top: tooltip.screenY - 10 }}
          >
            <div className="font-semibold flex items-center gap-2">
              <span
                className="w-3 h-3 rounded-full"
                style={{ backgroundColor: tooltip.nationColor }}
              />
              {tooltip.cityName}
            </div>
            <div className="text-gray-400">소속: {tooltip.nationName}</div>
            <div className="text-gray-400">레벨: {tooltip.level}</div>
            <div className="text-gray-400">
              인구: {tooltip.pop.toLocaleString()}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
