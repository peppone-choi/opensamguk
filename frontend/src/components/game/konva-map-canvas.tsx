"use client";

import { useState, useEffect, useMemo, useRef, useCallback } from "react";
import { Stage, Layer, Circle, Line, Text, Group } from "react-konva";
import type Konva from "konva";
import type { City, Nation, MapData } from "@/types";

interface MovePath {
  from: number;
  to: number;
  color?: string;
}

interface KonvaMapCanvasProps {
  mapData: MapData;
  cities: City[];
  nations: Nation[];
  width: number;
  height: number;
  showLabels?: boolean;
  battleCities?: number[];
  activePaths?: MovePath[];
  onCityClick?: (cityId: number) => void;
  onCityHover?: (
    cityId: number | null,
    screenX: number,
    screenY: number,
  ) => void;
}

const MIN_SCALE = 0.5;
const MAX_SCALE = 3;
const PAD = 50;

export default function KonvaMapCanvas({
  mapData,
  cities,
  nations,
  width,
  height,
  showLabels = true,
  battleCities = [],
  activePaths = [],
  onCityClick,
  onCityHover,
}: KonvaMapCanvasProps) {
  const stageRef = useRef<Konva.Stage>(null);
  const [battlePulse, setBattlePulse] = useState(false);

  // Pulsing animation for battle indicators
  useEffect(() => {
    if (battleCities.length === 0) return;
    const interval = setInterval(() => setBattlePulse((v) => !v), 500);
    return () => clearInterval(interval);
  }, [battleCities.length]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  const constMap = useMemo(
    () => new Map(mapData.cities.map((c) => [c.id, c])),
    [mapData],
  );

  const { scaleX, scaleY, offsetX, offsetY } = useMemo(() => {
    if (mapData.cities.length === 0)
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
    const rangeX = maxX - minX || 1;
    const rangeY = maxY - minY || 1;
    return {
      scaleX: (width - PAD * 2) / rangeX,
      scaleY: (height - PAD * 2) / rangeY,
      offsetX: -minX,
      offsetY: -minY,
    };
  }, [mapData, width, height]);

  const toX = useCallback(
    (x: number) => (x + offsetX) * scaleX + PAD,
    [offsetX, scaleX],
  );
  const toY = useCallback(
    (y: number) => (y + offsetY) * scaleY + PAD,
    [offsetY, scaleY],
  );

  const getCityColor = useCallback(
    (cityId: number): string => {
      const city = cityMap.get(cityId);
      if (!city || city.nationId === 0) return "#555";
      return nationMap.get(city.nationId)?.color ?? "#555";
    },
    [cityMap, nationMap],
  );

  const getCityRadius = (level: number): number => 6 + level * 1.2;

  const connections = useMemo(() => {
    const seen = new Set<string>();
    const result: number[][] = [];
    for (const city of mapData.cities) {
      for (const connId of city.connections) {
        const key =
          city.id < connId ? `${city.id}-${connId}` : `${connId}-${city.id}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const target = constMap.get(connId);
        if (target) {
          result.push([toX(city.x), toY(city.y), toX(target.x), toY(target.y)]);
        }
      }
    }
    return result;
  }, [mapData, constMap, toX, toY]);

  // Highlighted movement/battle paths
  const highlightedPaths = useMemo(() => {
    return activePaths
      .map((p) => {
        const from = constMap.get(p.from);
        const to = constMap.get(p.to);
        if (!from || !to) return null;
        return [toX(from.x), toY(from.y), toX(to.x), toY(to.y)];
      })
      .filter((p): p is number[] => p !== null);
  }, [activePaths, constMap, toX, toY]);

  const battleCitySet = useMemo(() => new Set(battleCities), [battleCities]);

  const handleWheel = (e: Konva.KonvaEventObject<WheelEvent>) => {
    e.evt.preventDefault();
    const stage = stageRef.current;
    if (!stage) return;
    const oldScale = stage.scaleX();
    const pointer = stage.getPointerPosition();
    if (!pointer) return;
    const scaleBy = 1.08;
    const newScale =
      e.evt.deltaY < 0
        ? Math.min(oldScale * scaleBy, MAX_SCALE)
        : Math.max(oldScale / scaleBy, MIN_SCALE);
    const mousePointTo = {
      x: (pointer.x - stage.x()) / oldScale,
      y: (pointer.y - stage.y()) / oldScale,
    };
    stage.scale({ x: newScale, y: newScale });
    stage.position({
      x: pointer.x - mousePointTo.x * newScale,
      y: pointer.y - mousePointTo.y * newScale,
    });
  };

  return (
    <Stage
      ref={stageRef}
      width={width}
      height={height}
      draggable
      onWheel={handleWheel}
      style={{ background: "rgba(17,24,39,0.8)", borderRadius: 8 }}
    >
      {/* Connection lines */}
      <Layer listening={false}>
        {connections.map((pts, i) => (
          <Line key={i} points={pts} stroke="#333" strokeWidth={0.8} />
        ))}
        {/* Highlighted movement paths */}
        {highlightedPaths.map((pts, i) => (
          <Line
            key={`path-${i}`}
            points={pts}
            stroke="#ffaa00"
            strokeWidth={3}
            opacity={0.7}
            dash={[8, 4]}
          />
        ))}
      </Layer>

      {/* City nodes */}
      <Layer>
        {mapData.cities.map((cc) => {
          const cx = toX(cc.x);
          const cy = toY(cc.y);
          const color = getCityColor(cc.id);
          const radius = getCityRadius(cc.level);
          const isBattle = battleCitySet.has(cc.id);
          return (
            <Group key={cc.id}>
              {/* Battle effect: pulsing red ring */}
              {isBattle && (
                <Circle
                  x={cx}
                  y={cy}
                  radius={radius + 8}
                  stroke="#ff4444"
                  strokeWidth={2}
                  opacity={battlePulse ? 0.9 : 0.2}
                  listening={false}
                />
              )}
              <Circle
                x={cx}
                y={cy}
                radius={radius}
                fill={color}
                stroke={isBattle ? "#ff4444" : "#000"}
                strokeWidth={isBattle ? 2 : 1}
                opacity={0.85}
                onMouseEnter={(e: Konva.KonvaEventObject<MouseEvent>) => {
                  const container = e.target.getStage()?.container();
                  if (container) container.style.cursor = "pointer";
                  onCityHover?.(cc.id, e.evt.clientX, e.evt.clientY);
                }}
                onMouseLeave={(e: Konva.KonvaEventObject<MouseEvent>) => {
                  const container = e.target.getStage()?.container();
                  if (container) container.style.cursor = "default";
                  onCityHover?.(null, 0, 0);
                }}
                onClick={() => onCityClick?.(cc.id)}
                onTap={() => onCityClick?.(cc.id)}
              />
              {showLabels && (
                <Text
                  x={cx - 30}
                  y={cy + radius + 3}
                  width={60}
                  text={cc.name}
                  fontSize={10}
                  fill="#ccc"
                  align="center"
                  listening={false}
                />
              )}
            </Group>
          );
        })}
      </Layer>
    </Stage>
  );
}
