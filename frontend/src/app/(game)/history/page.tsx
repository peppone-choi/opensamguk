"use client";

import {
  useEffect,
  useMemo,
  useState,
  useCallback,
  lazy,
  Suspense,
} from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { historyApi, mapRecentApi, worldApi } from "@/lib/gameApi";
import type {
  Message,
  YearbookSummary,
  PublicCachedMapResponse,
  WorldSnapshot,
} from "@/types";
import { ScrollText, Clock, Search, Map as MapIcon } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

const KonvaMapCanvas = lazy(() => import("@/components/game/konva-map-canvas"));

type EventType = "war" | "diplomacy" | "nation" | "general" | "city" | "other";

const EVENT_LABELS: Record<EventType, string> = {
  war: "전쟁",
  diplomacy: "외교",
  nation: "국가",
  general: "장수",
  city: "도시",
  other: "기타",
};

const EVENT_VARIANT: Record<
  EventType,
  "destructive" | "default" | "secondary" | "outline"
> = {
  war: "destructive",
  diplomacy: "default",
  nation: "secondary",
  general: "outline",
  city: "outline",
  other: "outline",
};

function classifyEvent(msg: Message): EventType {
  const t = msg.messageType?.toLowerCase() ?? "";
  const text = getEventText(msg).toLowerCase();
  if (
    t.includes("war") ||
    text.includes("전쟁") ||
    text.includes("출병") ||
    text.includes("전투") ||
    text.includes("교전")
  )
    return "war";
  if (
    t.includes("diplom") ||
    text.includes("동맹") ||
    text.includes("불가침") ||
    text.includes("휴전")
  )
    return "diplomacy";
  if (
    t.includes("nation") ||
    text.includes("건국") ||
    text.includes("멸망") ||
    text.includes("즉위") ||
    text.includes("항복")
  )
    return "nation";
  if (
    t.includes("general") ||
    text.includes("사망") ||
    text.includes("등용") ||
    text.includes("등장") ||
    text.includes("하야")
  )
    return "general";
  if (
    t.includes("city") ||
    text.includes("점령") ||
    text.includes("함락") ||
    text.includes("탈환")
  )
    return "city";
  return "other";
}

function getEventText(msg: Message): string {
  if (typeof msg.payload?.description === "string")
    return msg.payload.description;
  if (typeof msg.payload?.content === "string") return msg.payload.content;
  if (typeof msg.payload?.message === "string") return msg.payload.message;
  return JSON.stringify(msg.payload);
}

function getDateLabel(msg: Message): string {
  const year = msg.payload?.year as number | undefined;
  const month = msg.payload?.month as number | undefined;
  if (year != null && month != null) return `${year}년 ${month}월`;
  return new Date(msg.sentAt).toLocaleDateString("ko-KR");
}

export default function HistoryPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const [history, setHistory] = useState<Message[]>([]);
  const [records, setRecords] = useState<Message[]>([]);
  const [yearbook, setYearbook] = useState<YearbookSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [filterLoading, setFilterLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedYear, setSelectedYear] = useState<number | null>(null);
  const [selectedMonth, setSelectedMonth] = useState<number | null>(null);
  const [activeFilters, setActiveFilters] = useState<Set<EventType>>(
    new Set(["war", "diplomacy", "nation", "general", "city", "other"]),
  );
  const [tab, setTab] = useState("timeline");
  const [historyView, setHistoryView] = useState<"all" | "global" | "action">(
    "all",
  );

  // Map snapshot state (맵 재현/스냅샷 브라우징)
  const { nations, cities, mapData, loadAll } = useGameStore();
  const [cachedMap, setCachedMap] = useState<PublicCachedMapResponse | null>(
    null,
  );
  const [worldSnapshots, setWorldSnapshots] = useState<WorldSnapshot[]>([]);
  const [mapLoading, setMapLoading] = useState(false);
  const [mapSnapshotIdx, setMapSnapshotIdx] = useState(0);

  const startYear = useMemo(() => {
    if (!currentWorld) return 0;
    const fromConfig = currentWorld.config["startYear"];
    if (typeof fromConfig === "number") return fromConfig;
    const fromMeta = currentWorld.meta["scenarioMeta"] as
      | Record<string, unknown>
      | undefined;
    if (fromMeta && typeof fromMeta.startYear === "number") {
      return fromMeta.startYear;
    }
    return currentWorld.currentYear;
  }, [currentWorld]);

  const yearOptions = useMemo(() => {
    if (!currentWorld || startYear > currentWorld.currentYear) return [];
    const years: number[] = [];
    for (let y = startYear; y <= currentWorld.currentYear; y += 1) {
      years.push(y);
    }
    return years;
  }, [currentWorld, startYear]);

  const monthOptions = useMemo(() => {
    return Array.from({ length: 12 }, (_, i) => i + 1);
  }, []);

  const loadData = useCallback(async () => {
    if (!currentWorld) return;
    setLoading(true);
    try {
      const [histRes, recRes] = await Promise.all([
        historyApi.getWorldHistory(currentWorld.id),
        historyApi.getWorldRecords(currentWorld.id),
      ]);
      setHistory(histRes.data);
      setRecords(recRes.data);
      setYearbook(null);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld]);

  const loadByYearMonth = useCallback(async () => {
    if (!currentWorld || selectedYear == null || selectedMonth == null) return;
    setFilterLoading(true);
    try {
      const [historyRes, yearbookRes] = await Promise.all([
        historyApi.getWorldHistoryByYearMonth(
          currentWorld.id,
          selectedYear,
          selectedMonth,
        ),
        historyApi.getYearbook(currentWorld.id, selectedYear),
      ]);
      setHistory(historyRes.data);
      setRecords([]);
      setYearbook(yearbookRes.data);
    } catch {
      setHistory([]);
      setRecords([]);
      setYearbook(null);
    } finally {
      setFilterLoading(false);
    }
  }, [currentWorld, selectedYear, selectedMonth]);

  const loadMapSnapshot = useCallback(async () => {
    if (!currentWorld) return;
    setMapLoading(true);
    try {
      const [snapshotRes, recentRes] = await Promise.allSettled([
        worldApi.getSnapshots(currentWorld.id),
        mapRecentApi.getMapRecent(currentWorld.id),
      ]);

      if (snapshotRes.status === "fulfilled") {
        setWorldSnapshots(snapshotRes.value.data);
        if (snapshotRes.value.data.length > 0) {
          setMapSnapshotIdx(snapshotRes.value.data.length - 1);
        }
      } else {
        setWorldSnapshots([]);
      }

      if (recentRes.status === "fulfilled") {
        setCachedMap(recentRes.value.data);
      } else {
        setCachedMap(null);
      }
    } catch {
      setWorldSnapshots([]);
      setCachedMap(null);
    } finally {
      setMapLoading(false);
    }
  }, [currentWorld]);

  useEffect(() => {
    loadData();
    if (currentWorld) {
      loadAll(currentWorld.id);
      loadMapSnapshot();
    }
  }, [loadData, currentWorld, loadAll, loadMapSnapshot]);

  useEffect(() => {
    if (!currentWorld) return;
    setSelectedYear(currentWorld.currentYear);
    setSelectedMonth(currentWorld.currentMonth);
  }, [currentWorld]);

  // Auto-load when prev/next buttons change year/month
  useEffect(() => {
    if (selectedYear != null && selectedMonth != null && currentWorld) {
      loadByYearMonth();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedYear, selectedMonth]);

  const toggleFilter = (type: EventType) => {
    setActiveFilters((prev) => {
      const next = new Set(prev);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return next;
    });
  };

  // Combine, classify, sort
  const allEvents = useMemo(() => {
    const combined = [...history, ...records].map((msg) => ({
      msg,
      type: classifyEvent(msg),
      text: getEventText(msg),
      dateLabel: getDateLabel(msg),
    }));
    combined.sort((a, b) => a.msg.id - b.msg.id);
    return combined;
  }, [history, records]);

  // Filter + search + history view (중원 정세 vs 장수 동향)
  const filteredEvents = useMemo(() => {
    const q = searchQuery.toLowerCase();
    return allEvents.filter((e) => {
      if (!activeFilters.has(e.type)) return false;
      if (q && !e.text.toLowerCase().includes(q)) return false;
      if (historyView === "global" && e.type === "general") return false;
      if (historyView === "action" && e.type !== "general") return false;
      return true;
    });
  }, [allEvents, activeFilters, searchQuery, historyView]);

  // Group by date label
  const grouped = useMemo(() => {
    const map: Record<string, typeof filteredEvents> = {};
    for (const event of filteredEvents) {
      const key = event.dateLabel;
      if (!map[key]) map[key] = [];
      map[key].push(event);
    }
    return Object.entries(map);
  }, [filteredEvents]);

  const mapHistory =
    worldSnapshots.length > 0 ? worldSnapshots : (cachedMap?.history ?? []);
  const currentSnapshot = mapHistory[mapSnapshotIdx] ?? null;
  const snapshotCities = useMemo(() => {
    if (!currentSnapshot) return cities;
    const entries = (currentSnapshot.cityOwnership ?? []).map(
      (co) => [co.cityId, co.nationId] as [number, number],
    );
    const ownerMap = new Map(entries);
    return cities.map((c) => ({
      ...c,
      nationId: ownerMap.get(c.id) ?? c.nationId,
    }));
  }, [cities, currentSnapshot]);

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <PageHeader icon={ScrollText} title="연감" />

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="timeline">
            <ScrollText className="size-3.5 mr-1" />
            연대기
          </TabsTrigger>
          <TabsTrigger value="map">
            <MapIcon className="size-3.5 mr-1" />맵 재현
          </TabsTrigger>
        </TabsList>

        {/* ═══ Map Snapshot Tab ═══ */}
        <TabsContent value="map" className="mt-4 space-y-4">
          {mapLoading ? (
            <LoadingState message="맵 데이터 불러오는 중..." />
          ) : mapHistory.length === 0 ? (
            <EmptyState icon={MapIcon} title="맵 스냅샷 데이터가 없습니다." />
          ) : (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">역사 맵 스냅샷</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {/* Navigation */}
                {mapHistory.length > 0 && (
                  <div className="flex items-center gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={mapSnapshotIdx <= 0}
                      onClick={() =>
                        setMapSnapshotIdx((i) => Math.max(0, i - 1))
                      }
                    >
                      ◀ 이전
                    </Button>
                    <div className="flex-1 text-center text-sm">
                      <span className="font-medium">
                        {currentSnapshot?.year ?? "?"}년{" "}
                        {currentSnapshot?.month ?? "?"}월
                      </span>
                      <span className="text-xs text-muted-foreground ml-2">
                        ({mapSnapshotIdx + 1} / {mapHistory.length})
                      </span>
                    </div>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={mapSnapshotIdx >= mapHistory.length - 1}
                      onClick={() =>
                        setMapSnapshotIdx((i) =>
                          Math.min(mapHistory.length - 1, i + 1),
                        )
                      }
                    >
                      다음 ▶
                    </Button>
                  </div>
                )}

                {/* Slider for quick browsing */}
                {mapHistory.length > 1 && (
                  <input
                    type="range"
                    min={0}
                    max={mapHistory.length - 1}
                    value={mapSnapshotIdx}
                    onChange={(e) => setMapSnapshotIdx(Number(e.target.value))}
                    className="w-full accent-primary"
                  />
                )}

                {/* Map canvas */}
                {mapData && (
                  <div className="border border-gray-700 rounded overflow-hidden">
                    <Suspense fallback={<LoadingState message="맵 로딩..." />}>
                      <KonvaMapCanvas
                        mapData={mapData}
                        cities={snapshotCities}
                        nations={nations}
                        width={Math.min(
                          700,
                          typeof window !== "undefined"
                            ? window.innerWidth - 64
                            : 700,
                        )}
                        height={500}
                        showLabels
                      />
                    </Suspense>
                  </div>
                )}

                {/* Snapshot info */}
                {currentSnapshot && (
                  <div className="text-xs text-muted-foreground space-y-1">
                    {currentSnapshot.events &&
                      currentSnapshot.events.length > 0 && (
                        <div>
                          <span className="font-medium text-foreground">
                            주요 사건:
                          </span>
                          <ul className="ml-3 list-disc">
                            {currentSnapshot.events
                              .slice(0, 5)
                              .map((evt: string, i: number) => (
                                <li key={i}>{evt}</li>
                              ))}
                          </ul>
                        </div>
                      )}
                  </div>
                )}
              </CardContent>
            </Card>
          )}
        </TabsContent>

        {/* ═══ Timeline Tab ═══ */}
        <TabsContent value="timeline" className="mt-4 space-y-4">
          <Card>
            <CardContent className="space-y-3">
              <div className="flex flex-wrap items-end gap-2">
                <div className="space-y-1">
                  <div className="text-xs text-muted-foreground">년</div>
                  <Select
                    value={selectedYear != null ? String(selectedYear) : ""}
                    onValueChange={(value) => setSelectedYear(Number(value))}
                  >
                    <SelectTrigger className="w-28">
                      <SelectValue placeholder="년" />
                    </SelectTrigger>
                    <SelectContent>
                      {yearOptions.map((year) => (
                        <SelectItem key={year} value={String(year)}>
                          {year}년
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1">
                  <div className="text-xs text-muted-foreground">월</div>
                  <Select
                    value={selectedMonth != null ? String(selectedMonth) : ""}
                    onValueChange={(value) => setSelectedMonth(Number(value))}
                  >
                    <SelectTrigger className="w-24">
                      <SelectValue placeholder="월" />
                    </SelectTrigger>
                    <SelectContent>
                      {monthOptions.map((month) => (
                        <SelectItem key={month} value={String(month)}>
                          {month}월
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={
                    filterLoading ||
                    selectedYear == null ||
                    selectedMonth == null
                  }
                  onClick={() => {
                    if (selectedYear == null || selectedMonth == null) return;
                    let y = selectedYear,
                      m = selectedMonth - 1;
                    if (m < 1) {
                      m = 12;
                      y -= 1;
                    }
                    if (y >= startYear) {
                      setSelectedYear(y);
                      setSelectedMonth(m);
                    }
                  }}
                >
                  ◀ 이전달
                </Button>
                <Button
                  size="sm"
                  onClick={loadByYearMonth}
                  disabled={
                    filterLoading ||
                    selectedYear == null ||
                    selectedMonth == null
                  }
                >
                  {filterLoading ? "조회 중..." : "기록 조회"}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={
                    filterLoading ||
                    selectedYear == null ||
                    selectedMonth == null
                  }
                  onClick={() => {
                    if (
                      selectedYear == null ||
                      selectedMonth == null ||
                      !currentWorld
                    )
                      return;
                    let y = selectedYear,
                      m = selectedMonth + 1;
                    if (m > 12) {
                      m = 1;
                      y += 1;
                    }
                    if (
                      y < currentWorld.currentYear ||
                      (y === currentWorld.currentYear &&
                        m <= currentWorld.currentMonth)
                    ) {
                      setSelectedYear(y);
                      setSelectedMonth(m);
                    }
                  }}
                >
                  다음달 ▶
                </Button>
              </div>
              {yearbook && (
                <div className="rounded-md border border-border p-3 space-y-2">
                  <div className="text-xs font-semibold">
                    연감 ({yearbook.year}년 {yearbook.month}월)
                  </div>
                  <div className="space-y-1">
                    {yearbook.nations.map((nation) => (
                      <div
                        key={nation.id}
                        className="text-xs flex flex-wrap items-center gap-x-2 gap-y-1"
                      >
                        <span
                          className="font-medium"
                          style={{ color: nation.color }}
                        >
                          {nation.name}
                        </span>
                        <span className="text-muted-foreground">
                          영토 {nation.territoryCount}
                        </span>
                        <span className="text-muted-foreground">
                          장수 {nation.generalCount ?? "-"}
                        </span>
                      </div>
                    ))}
                  </div>
                  <div className="pt-1 border-t border-border">
                    <div className="text-[11px] text-muted-foreground mb-1">
                      주요 사건
                    </div>
                    {yearbook.keyEvents.length === 0 ? (
                      <div className="text-[11px] text-muted-foreground">
                        해당 연도의 주요 사건이 없습니다.
                      </div>
                    ) : (
                      <div className="space-y-1">
                        {yearbook.keyEvents.slice(0, 5).map((event) => (
                          <div
                            key={event.id}
                            className="text-[11px] text-muted-foreground"
                          >
                            {getDateLabel(event)} - {getEventText(event)}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          {/* History view toggle (legacy parity: 중원 정세 vs 장수 동향) */}
          <div className="flex gap-1.5">
            {(["all", "global", "action"] as const).map((view) => (
              <Button
                key={view}
                size="sm"
                variant={historyView === view ? "default" : "outline"}
                onClick={() => setHistoryView(view)}
              >
                {view === "all"
                  ? "전체"
                  : view === "global"
                    ? "중원 정세"
                    : "장수 동향"}
              </Button>
            ))}
          </div>

          {/* Search + filters */}
          <Card>
            <CardContent className="space-y-3">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
                <Input
                  placeholder="사건 검색..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-9"
                />
              </div>
              <div className="flex flex-wrap gap-1.5">
                {(Object.keys(EVENT_LABELS) as EventType[]).map((type) => (
                  <Badge
                    key={type}
                    variant={
                      activeFilters.has(type) ? EVENT_VARIANT[type] : "outline"
                    }
                    className={`cursor-pointer select-none ${!activeFilters.has(type) ? "opacity-40" : ""}`}
                    onClick={() => toggleFilter(type)}
                  >
                    {EVENT_LABELS[type]}
                  </Badge>
                ))}
              </div>
              <div className="text-xs text-muted-foreground">
                총 {filteredEvents.length}건
              </div>
            </CardContent>
          </Card>

          {/* Timeline */}
          {filteredEvents.length === 0 ? (
            <EmptyState icon={Clock} title="기록된 역사가 없습니다." />
          ) : (
            <div className="space-y-4">
              {grouped.map(([dateLabel, events]) => (
                <Card key={dateLabel}>
                  <CardHeader className="py-2 px-4">
                    <CardTitle className="text-sm">{dateLabel}</CardTitle>
                  </CardHeader>
                  <CardContent className="p-0">
                    <div className="relative pl-6 border-l-2 border-muted ml-4">
                      {events.map((event, idx) => (
                        <div
                          key={`${event.msg.id}-${idx}`}
                          className="relative pb-3 last:pb-1"
                        >
                          {/* Dot */}
                          <div
                            className="absolute -left-[9px] top-1 size-4 rounded-full border-2 border-background"
                            style={{
                              backgroundColor:
                                event.type === "war"
                                  ? "#e11d48"
                                  : event.type === "diplomacy"
                                    ? "#3b82f6"
                                    : event.type === "nation"
                                      ? "#a855f7"
                                      : "#6b7280",
                            }}
                          />
                          <div className="flex items-start gap-2 pl-2">
                            <Badge
                              variant={EVENT_VARIANT[event.type]}
                              className="shrink-0 text-[10px] px-1.5"
                            >
                              {EVENT_LABELS[event.type]}
                            </Badge>
                            <span className="text-xs leading-relaxed break-all">
                              {event.text}
                            </span>
                          </div>
                        </div>
                      ))}
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}
