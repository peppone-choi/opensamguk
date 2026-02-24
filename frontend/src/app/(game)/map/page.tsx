"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { mapRecentApi } from "@/lib/gameApi";
import { subscribeWebSocket } from "@/lib/websocket";
import { formatLog } from "@/lib/formatLog";
import type { CityConst, PublicCachedMapHistory } from "@/types";
import { useRouter } from "next/navigation";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";

type MapTheme = "default" | "spring" | "summer" | "autumn" | "winter";
const MAP_THEMES: { key: MapTheme; label: string; bg: string; line: string; text: string }[] = [
  { key: "default", label: "기본", bg: "#111827", line: "#333", text: "#ccc" },
  { key: "spring", label: "봄", bg: "#1a2e1a", line: "#4a7c4a", text: "#b0e0b0" },
  { key: "summer", label: "여름", bg: "#1a2e2e", line: "#2a6e6e", text: "#a0e0e0" },
  { key: "autumn", label: "가을", bg: "#2e1a0a", line: "#8e5e2e", text: "#e0c090" },
  { key: "winter", label: "겨울", bg: "#1e2030", line: "#6070a0", text: "#c0c8e0" },
];

type MapLayer = "nations" | "troops" | "supply" | "terrain";

interface CityTooltip {
  cityId: number;
  cityName: string;
  nationName: string;
  nationColor: string;
  level: number;
  pop: number;
  agri: string;
  comm: string;
  secu: string;
  def: string;
  wall: string;
  trust: number;
  generals: { name: string; nationColor: string; crew: number; crewType: string; isForeign: boolean }[];
  screenX: number;
  screenY: number;
}

const CREW_TYPES: Record<number, string> = {
  0: "보병", 1: "궁병", 2: "기병", 3: "귀병", 4: "차병", 5: "노병",
  6: "연노병", 7: "근위기병", 8: "무당병", 9: "서량기병", 10: "등갑병", 11: "수군",
};

const MAP_WIDTH = 1200;
const MAP_HEIGHT = 900;
const CITY_RADIUS = 14;

export default function MapPage() {
  const router = useRouter();
  const { currentWorld } = useWorldStore();
  const { cities, nations, generals, mapData, loadAll, loadMap } = useGameStore();
  const [tooltip, setTooltip] = useState<CityTooltip | null>(null);
  const [history, setHistory] = useState<PublicCachedMapHistory[]>([]);
  const [touchTapId, setTouchTapId] = useState<number | null>(null);
  // Auto-detect season from world month (legacy: spring 1-3, summer 4-6, autumn 7-9, winter 10-12)
  const autoTheme = useMemo<MapTheme>(() => {
    const month = (currentWorld?.config as Record<string, number>)?.month;
    if (!month) return "default";
    if (month <= 3) return "spring";
    if (month <= 6) return "summer";
    if (month <= 9) return "autumn";
    return "winter";
  }, [currentWorld]);
  const [theme, setTheme] = useState<MapTheme>("default");
  const [layers, setLayers] = useState<Set<MapLayer>>(new Set(["nations", "troops"]));
  const [historyBrowseIdx, setHistoryBrowseIdx] = useState<number | null>(null);
  const [historyFilterYear, setHistoryFilterYear] = useState<number | null>(null);
  const [historyFilterMonth, setHistoryFilterMonth] = useState<number | null>(null);

  const currentTheme = MAP_THEMES.find((t) => t.key === theme) ?? MAP_THEMES[0];
  const toggleLayer = (layer: MapLayer) => {
    setLayers((prev) => {
      const next = new Set(prev);
      if (next.has(layer)) next.delete(layer);
      else next.add(layer);
      return next;
    });
  };

  useEffect(() => {
    if (currentWorld) {
      loadAll(currentWorld.id);
      const mapCode =
        (currentWorld.config as Record<string, string>)?.mapCode ?? "che";
      loadMap(mapCode);

      mapRecentApi
        .getMapRecent(currentWorld.id)
        .then(({ data }) => {
          if (data.history) setHistory(data.history);
        })
        .catch(() => {});
    }
  }, [currentWorld, loadAll, loadMap]);

  useEffect(() => {
    if (!currentWorld) return;
    return subscribeWebSocket(`/topic/world/${currentWorld.id}/turn`, () => {
      loadAll(currentWorld.id);
      mapRecentApi
        .getMapRecent(currentWorld.id)
        .then(({ data }) => {
          if (data.history) setHistory(data.history);
        })
        .catch(() => {});
    });
  }, [currentWorld, loadAll]);

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

  // Build per-city general counts by nation (for troop indicators)
  const cityGeneralData = useMemo(() => {
    const map = new Map<number, { nationId: number; name: string; crew: number; crewType: number }[]>();
    for (const g of generals) {
      if (g.nationId <= 0 || g.crew <= 0) continue;
      if (!map.has(g.cityId)) map.set(g.cityId, []);
      map.get(g.cityId)!.push({ nationId: g.nationId, name: g.name, crew: g.crew, crewType: g.crewType });
    }
    return map;
  }, [generals]);

  // Identify cities with foreign troops (troops from a different nation than the city owner)
  const foreignTroopCities = useMemo(() => {
    const result = new Set<number>();
    for (const [cityId, gens] of cityGeneralData) {
      const city = cityMap.get(cityId);
      if (!city) continue;
      for (const g of gens) {
        if (g.nationId !== city.nationId && city.nationId > 0) {
          result.add(cityId);
          break;
        }
      }
    }
    return result;
  }, [cityGeneralData, cityMap]);

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

  // Save city info to localStorage for cross-page reference
  const saveCityInfo = useCallback((cityId: number) => {
    const city = cityMap.get(cityId);
    if (!city) return;
    const nation = city.nationId ? nationMap.get(city.nationId) : null;
    try {
      localStorage.setItem(
        `opensam:cityInfo:${cityId}`,
        JSON.stringify({
          id: cityId,
          name: constMap.get(cityId)?.name ?? "",
          nationName: nation?.name ?? "공백지",
          nationColor: nation?.color ?? "#555",
          pop: city.pop,
          level: city.level,
          ts: Date.now(),
        }),
      );
    } catch { /* ignore quota */ }
  }, [cityMap, nationMap, constMap]);

  const handleCityClick = (cc: CityConst, e: React.MouseEvent) => {
    e.stopPropagation();
    const city = cityMap.get(cc.id);
    const nation = city?.nationId ? nationMap.get(city.nationId) : null;
    const cityGens = cityGeneralData.get(cc.id) ?? [];
    const generalsInfo = cityGens.map((g) => ({
      name: g.name,
      nationColor: nationMap.get(g.nationId)?.color ?? "#555",
      crew: g.crew,
      crewType: CREW_TYPES[g.crewType] ?? `${g.crewType}`,
      isForeign: city ? g.nationId !== city.nationId : false,
    }));

    saveCityInfo(cc.id);

    setTooltip({
      cityId: cc.id,
      cityName: cc.name,
      nationName: nation?.name ?? "공백지",
      nationColor: nation?.color ?? "#555",
      level: city?.level ?? cc.level,
      pop: city?.pop ?? 0,
      agri: city ? `${city.agri}/${city.agriMax}` : "-",
      comm: city ? `${city.comm}/${city.commMax}` : "-",
      secu: city ? `${city.secu}/${city.secuMax}` : "-",
      def: city ? `${city.def}/${city.defMax}` : "-",
      wall: city ? `${city.wall}/${city.wallMax}` : "-",
      trust: city?.trust ?? 0,
      generals: generalsInfo,
      screenX: e.clientX,
      screenY: e.clientY,
    });
  };

  // Touch support: single-tap toggles tooltip, second tap navigates
  const handleCityTouch = useCallback((cc: CityConst, e: React.TouchEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (touchTapId === cc.id && tooltip?.cityId === cc.id) {
      // Second tap on same city: navigate
      saveCityInfo(cc.id);
      router.push(`/city?id=${cc.id}`);
      setTouchTapId(null);
    } else {
      // First tap: show tooltip
      const touch = e.touches[0] ?? e.changedTouches[0];
      const city = cityMap.get(cc.id);
      const nation = city?.nationId ? nationMap.get(city.nationId) : null;
      const cityGens = cityGeneralData.get(cc.id) ?? [];
      const generalsInfo = cityGens.map((g) => ({
        name: g.name,
        nationColor: nationMap.get(g.nationId)?.color ?? "#555",
        crew: g.crew,
        crewType: CREW_TYPES[g.crewType] ?? `${g.crewType}`,
        isForeign: city ? g.nationId !== city.nationId : false,
      }));
      saveCityInfo(cc.id);
      setTooltip({
        cityId: cc.id,
        cityName: cc.name,
        nationName: nation?.name ?? "공백지",
        nationColor: nation?.color ?? "#555",
        level: city?.level ?? cc.level,
        pop: city?.pop ?? 0,
        agri: city ? `${city.agri}/${city.agriMax}` : "-",
        comm: city ? `${city.comm}/${city.commMax}` : "-",
        secu: city ? `${city.secu}/${city.secuMax}` : "-",
        def: city ? `${city.def}/${city.defMax}` : "-",
        wall: city ? `${city.wall}/${city.wallMax}` : "-",
        trust: city?.trust ?? 0,
        generals: generalsInfo,
        screenX: touch?.clientX ?? 0,
        screenY: touch?.clientY ?? 0,
      });
      setTouchTapId(cc.id);
    }
  }, [touchTapId, tooltip, cityMap, nationMap, cityGeneralData, constMap, router, saveCityInfo]);

  if (!mapData) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500">
        지도를 불러오는 중...
      </div>
    );
  }

  const serverName = currentWorld?.name ?? "삼국지";

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold">{serverName} 현황</h1>

      {/* Theme & Layer Controls */}
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs text-muted-foreground">테마:</span>
        <Button
          size="sm"
          variant={theme === "default" && autoTheme !== "default" ? "default" : "outline"}
          className="h-6 px-2 text-xs"
          onClick={() => setTheme(autoTheme)}
        >
          자동({MAP_THEMES.find(t => t.key === autoTheme)?.label ?? "기본"})
        </Button>
        {MAP_THEMES.map((t) => (
          <Button
            key={t.key}
            size="sm"
            variant={theme === t.key ? "default" : "outline"}
            className="h-6 px-2 text-xs"
            onClick={() => setTheme(t.key)}
          >
            {t.label}
          </Button>
        ))}
        <span className="text-xs text-muted-foreground ml-4">레이어:</span>
        {([
          { key: "nations" as MapLayer, label: "국가색" },
          { key: "troops" as MapLayer, label: "병력" },
          { key: "supply" as MapLayer, label: "보급" },
          { key: "terrain" as MapLayer, label: "지형" },
        ]).map((l) => (
          <Button
            key={l.key}
            size="sm"
            variant={layers.has(l.key) ? "default" : "outline"}
            className="h-6 px-2 text-xs"
            onClick={() => toggleLayer(l.key)}
          >
            {l.label}
          </Button>
        ))}
      </div>

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
        <div className="flex items-center gap-1.5">
          <span className="w-3 h-3 rounded border border-red-500" style={{ background: "transparent" }} />
          <span className="text-red-400">외국 병력 주둔</span>
        </div>
      </div>

      {/* SVG Map */}
      <div
        className="relative border border-gray-800 rounded-lg overflow-hidden"
        style={{ backgroundColor: currentTheme.bg }}
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
              stroke={currentTheme.line}
              strokeWidth={1}
            />
          ))}

          {/* City circles, labels, and troop indicators */}
          {mapData.cities.map((cc) => {
            const cx = toSvgX(cc.x);
            const cy = toSvgY(cc.y);
            const color = layers.has("nations") ? getCityColor(cc.id) : "#555";
            const city = cityMap.get(cc.id);
            const hasForeignTroops = layers.has("troops") && foreignTroopCities.has(cc.id);
            const genCount = layers.has("troops") ? (cityGeneralData.get(cc.id)?.length ?? 0) : 0;
            const isSupplyBroken = layers.has("supply") && city && city.supplyState !== 1;
            const terrainLevel = layers.has("terrain") && city ? city.level : 0;

            return (
              <g
                key={cc.id}
                className="cursor-pointer"
                onClick={(e) => handleCityClick(cc, e)}
                onTouchEnd={(e) => handleCityTouch(cc, e)}
              >
                {/* Foreign troop warning ring */}
                {hasForeignTroops && (
                  <circle
                    cx={cx}
                    cy={cy}
                    r={CITY_RADIUS + 4}
                    fill="none"
                    stroke="#ef4444"
                    strokeWidth={2}
                    strokeDasharray="4 2"
                    opacity={0.8}
                  >
                    <animate attributeName="stroke-dashoffset" from="0" to="12" dur="1.5s" repeatCount="indefinite" />
                  </circle>
                )}

                {/* Supply broken indicator */}
                {isSupplyBroken && (
                  <circle
                    cx={cx}
                    cy={cy}
                    r={CITY_RADIUS + 6}
                    fill="none"
                    stroke="#f59e0b"
                    strokeWidth={1.5}
                    strokeDasharray="2 3"
                    opacity={0.7}
                  />
                )}

                {/* Terrain level ring */}
                {terrainLevel > 0 && (
                  <circle
                    cx={cx}
                    cy={cy}
                    r={CITY_RADIUS + (terrainLevel >= 5 ? 2 : 0)}
                    fill="none"
                    stroke={terrainLevel >= 5 ? "#a855f7" : "transparent"}
                    strokeWidth={1}
                    opacity={0.5}
                  />
                )}

                <circle
                  cx={cx}
                  cy={cy}
                  r={CITY_RADIUS}
                  fill={color}
                  stroke="#000"
                  strokeWidth={1.5}
                  opacity={0.85}
                />

                {/* Troop count badge */}
                {genCount > 0 && (
                  <>
                    <circle
                      cx={cx + CITY_RADIUS - 2}
                      cy={cy - CITY_RADIUS + 2}
                      r={7}
                      fill="#111"
                      stroke="#888"
                      strokeWidth={0.5}
                    />
                    <text
                      x={cx + CITY_RADIUS - 2}
                      y={cy - CITY_RADIUS + 5.5}
                      textAnchor="middle"
                      fill="#fff"
                      fontSize={9}
                      fontWeight="bold"
                    >
                      {genCount}
                    </text>
                  </>
                )}

                <text
                  x={cx}
                  y={cy + CITY_RADIUS + 14}
                  textAnchor="middle"
                  fill={currentTheme.text}
                  fontSize={11}
                >
                  {cc.name}
                </text>
              </g>
            );
          })}
        </svg>

        {/* City detail tooltip popup */}
        {tooltip && (
          <div
            className="fixed z-50 bg-gray-800 border border-gray-700 rounded-lg p-3 shadow-lg text-sm space-y-1 max-w-xs"
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
            <div className="text-gray-400">인구: {tooltip.pop.toLocaleString()}</div>
            <div className="text-gray-400">농업: {tooltip.agri}</div>
            <div className="text-gray-400">상업: {tooltip.comm}</div>
            <div className="text-gray-400">치안: {tooltip.secu}</div>
            <div className="text-gray-400">수비: {tooltip.def}</div>
            <div className="text-gray-400">성벽: {tooltip.wall}</div>
            <div className="text-gray-400">민심: {tooltip.trust}</div>

            {/* Link to city detail */}
            <button
              type="button"
              className="w-full text-center text-xs text-cyan-400 hover:text-cyan-300 border border-gray-600 rounded px-2 py-1 mt-1"
              onClick={(e) => {
                e.stopPropagation();
                router.push(`/city?id=${tooltip.cityId}`);
              }}
            >
              도시 상세 보기
            </button>

            {/* Generals in this city */}
            {tooltip.generals.length > 0 && (
              <div className="border-t border-gray-700 pt-1 mt-1">
                <div className="text-gray-300 font-medium text-xs mb-0.5">주둔 장수 ({tooltip.generals.length}명)</div>
                <div className="max-h-32 overflow-y-auto space-y-0.5">
                  {tooltip.generals.map((g, i) => (
                    <div key={i} className="flex items-center gap-1.5 text-xs">
                      <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: g.nationColor }} />
                      <span className={g.isForeign ? "text-red-400 font-bold" : "text-gray-300"}>{g.name}</span>
                      <span className="text-muted-foreground ml-auto">{g.crewType} {g.crew.toLocaleString()}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* History Log Panel with Year/Month Navigation */}
      {history.length > 0 && (() => {
        // Extract unique year/month pairs from history
        const yearMonthSet = new Map<string, { year: number; month: number }>();
        for (const h of history) {
          if (h.year != null && h.month != null) {
            const key = `${h.year}-${h.month}`;
            if (!yearMonthSet.has(key)) yearMonthSet.set(key, { year: h.year, month: h.month });
          }
        }
        const yearMonthList = Array.from(yearMonthSet.values()).sort((a, b) => b.year - a.year || b.month - a.month);
        const availableYears = [...new Set(yearMonthList.map((ym) => ym.year))].sort((a, b) => b - a);

        // Filter logic
        const filteredHistory = (historyFilterYear !== null)
          ? history.filter((h) =>
              h.year === historyFilterYear && (historyFilterMonth === null || h.month === historyFilterMonth)
            )
          : history;

        const monthsForYear = historyFilterYear !== null
          ? yearMonthList.filter((ym) => ym.year === historyFilterYear).map((ym) => ym.month).sort((a, b) => a - b)
          : [];

        const displayItems = historyBrowseIdx !== null
          ? filteredHistory.slice(historyBrowseIdx, historyBrowseIdx + 10)
          : filteredHistory.slice(0, 10);

        return (
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm flex items-center gap-2">
                최근 기록
                <Badge variant="outline" className="text-[10px]">{filteredHistory.length}건</Badge>
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              {/* Year/Month filter */}
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs text-muted-foreground">년도:</span>
                <Button
                  size="sm"
                  variant={historyFilterYear === null ? "default" : "outline"}
                  className="h-6 px-2 text-xs"
                  onClick={() => { setHistoryFilterYear(null); setHistoryFilterMonth(null); setHistoryBrowseIdx(null); }}
                >
                  전체
                </Button>
                {availableYears.map((y) => (
                  <Button
                    key={y}
                    size="sm"
                    variant={historyFilterYear === y ? "default" : "outline"}
                    className="h-6 px-2 text-xs"
                    onClick={() => { setHistoryFilterYear(y); setHistoryFilterMonth(null); setHistoryBrowseIdx(null); }}
                  >
                    {y}년
                  </Button>
                ))}
                {historyFilterYear !== null && monthsForYear.length > 0 && (
                  <>
                    <span className="text-xs text-muted-foreground ml-2">월:</span>
                    <Button
                      size="sm"
                      variant={historyFilterMonth === null ? "default" : "outline"}
                      className="h-6 px-2 text-xs"
                      onClick={() => { setHistoryFilterMonth(null); setHistoryBrowseIdx(null); }}
                    >
                      전체
                    </Button>
                    {monthsForYear.map((m) => (
                      <Button
                        key={m}
                        size="sm"
                        variant={historyFilterMonth === m ? "default" : "outline"}
                        className="h-6 px-2 text-xs"
                        onClick={() => { setHistoryFilterMonth(m); setHistoryBrowseIdx(null); }}
                      >
                        {m}월
                      </Button>
                    ))}
                  </>
                )}
              </div>

              {/* Pagination controls */}
              <div className="flex items-center gap-1">
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-6 px-2 text-xs"
                  disabled={historyBrowseIdx === null || historyBrowseIdx <= 0}
                  onClick={() => setHistoryBrowseIdx((prev) => Math.max(0, (prev ?? filteredHistory.length) - 10))}
                >
                  ← 이전
                </Button>
                <span className="text-[10px] text-muted-foreground">
                  {historyBrowseIdx !== null ? `${historyBrowseIdx + 1}~${Math.min(historyBrowseIdx + 10, filteredHistory.length)}` : `1~${Math.min(10, filteredHistory.length)}`} / {filteredHistory.length}
                </span>
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-6 px-2 text-xs"
                  disabled={
                    (historyBrowseIdx === null && filteredHistory.length <= 10) ||
                    (historyBrowseIdx !== null && historyBrowseIdx + 10 >= filteredHistory.length)
                  }
                  onClick={() => setHistoryBrowseIdx((prev) => Math.min(filteredHistory.length - 10, (prev ?? 0) + 10))}
                >
                  다음 →
                </Button>
                {historyBrowseIdx !== null && (
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-6 px-2 text-xs"
                    onClick={() => setHistoryBrowseIdx(null)}
                  >
                    최신으로
                  </Button>
                )}
              </div>

              {/* History items */}
              <div className="max-h-64 overflow-y-auto space-y-0.5 text-xs">
                {displayItems.map((item) => (
                  <div key={item.id} className="flex items-start gap-2 py-0.5 border-b border-gray-800 last:border-0">
                    <span className="text-muted-foreground whitespace-nowrap shrink-0 w-24">
                      {item.year != null && item.month != null
                        ? `${item.year}년 ${item.month}월`
                        : item.sentAt
                          ? new Date(item.sentAt).toLocaleDateString("ko-KR", {
                              month: "short",
                              day: "numeric",
                              hour: "2-digit",
                              minute: "2-digit",
                            })
                          : ""}
                    </span>
                    <span className="text-gray-300">{formatLog(item.text)}</span>
                  </div>
                ))}
                {displayItems.length === 0 && (
                  <div className="text-muted-foreground py-2">해당 기간의 기록이 없습니다.</div>
                )}
              </div>
            </CardContent>
          </Card>
        );
      })()}
    </div>
  );
}
