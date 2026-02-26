"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { rankingApi } from "@/lib/gameApi";
import type { Message } from "@/types";
import { Award, Trophy } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

/* ── Category definitions (legacy parity: a_hallOfFame.php) ── */

const HALL_CATEGORIES: { key: string; label: string }[] = [
  { key: "experience", label: "명성" },
  { key: "dedication", label: "계급" },
  { key: "firenum", label: "계략성공" },
  { key: "warnum", label: "전투횟수" },
  { key: "killnum", label: "승리" },
  { key: "winrate", label: "승률" },
  { key: "occupied", label: "점령" },
  { key: "killcrew", label: "사살" },
  { key: "killrate", label: "살상률" },
  { key: "killcrew_person", label: "대인사살" },
  { key: "killrate_person", label: "대인살상률" },
  { key: "dex1", label: "보병숙련" },
  { key: "dex2", label: "궁병숙련" },
  { key: "dex3", label: "기병숙련" },
  { key: "dex4", label: "귀병숙련" },
  { key: "dex5", label: "차병숙련" },
  { key: "ttrate", label: "전력전 승률" },
  { key: "tlrate", label: "통솔전 승률" },
  { key: "tsrate", label: "일기토 승률" },
  { key: "tirate", label: "설전 승률" },
  { key: "betgold", label: "베팅투자액" },
  { key: "betwin", label: "베팅당첨" },
  { key: "betwingold", label: "베팅수익금" },
  { key: "betrate", label: "베팅수익률" },
];

/* Also support legacy keys without rate suffix */
const CATEGORY_MAP = new Map(HALL_CATEGORIES.map((c) => [c.key, c]));
// Add aliases
for (const [alias, canonical] of [
  ["ttw", "ttrate"],
  ["tlw", "tlrate"],
  ["tsw", "tsrate"],
  ["tiw", "tirate"],
]) {
  const cat = CATEGORY_MAP.get(canonical);
  if (cat) CATEGORY_MAP.set(alias, cat);
}

/* ── Parse hall entry from Message payload ── */

interface HallEntry {
  category: string;
  generalName: string;
  nationName: string;
  nationColor: string;
  value: number;
  printValue: string;
  ownerName: string | null;
  scenario: string;
  year: number | null;
  picture?: string;
  serverName?: string;
}

function parseEntry(msg: Message): HallEntry {
  const p = msg.payload;
  const value = (p.value as number) ?? 0;
  return {
    category: (p.category as string) ?? (p.type as string) ?? "unknown",
    generalName:
      (p.generalName as string) ??
      (p.winner as string) ??
      (p.name as string) ??
      "???",
    nationName: (p.nationName as string) ?? (p.nation as string) ?? "재야",
    nationColor:
      (p.nationColor as string) ??
      (p.color as string) ??
      (p.bgColor as string) ??
      "#888",
    value,
    printValue: (p.printValue as string) ?? formatVal(value),
    ownerName: (p.ownerName as string) ?? null,
    scenario:
      (p.scenario as string) ??
      (p.phase as string) ??
      (p.description as string) ??
      (p.content as string) ??
      "",
    year: (p.year as number) ?? null,
    picture:
      (p.picture as string) ?? (p.pictureFullPath as string) ?? undefined,
    serverName: (p.serverName as string) ?? undefined,
  };
}

function formatVal(v: unknown): string {
  if (typeof v === "number") return v.toLocaleString();
  return String(v ?? "-");
}

function rankMedal(idx: number) {
  if (idx === 0)
    return <Trophy className="size-5 text-amber-400 inline-block" />;
  if (idx === 1)
    return <Trophy className="size-5 text-gray-300 inline-block" />;
  if (idx === 2)
    return <Trophy className="size-5 text-amber-700 inline-block" />;
  return <Badge variant="outline">#{idx + 1}</Badge>;
}

/* ── Card frame for a category (legacy hallOfFrame parity) ── */

function HallFrame({
  categoryLabel,
  entries,
}: {
  categoryLabel: string;
  entries: HallEntry[];
}) {
  if (entries.length === 0) return null;

  return (
    <Card className="overflow-hidden">
      <CardHeader className="py-2 px-4 bg-muted/30">
        <CardTitle className="text-sm font-bold flex items-center gap-2">
          <Award className="size-4 text-amber-400" />
          {categoryLabel}
        </CardTitle>
      </CardHeader>
      <CardContent className="p-3">
        <div className="flex gap-2 overflow-x-auto pb-1">
          {entries.slice(0, 10).map((entry, idx) => (
            <div
              key={`${entry.generalName}-${idx}`}
              className="flex-shrink-0 w-[100px] flex flex-col items-center text-center rounded-lg border p-2 space-y-1"
              style={{
                borderColor:
                  idx < 3
                    ? idx === 0
                      ? "#f59e0b"
                      : idx === 1
                        ? "#9ca3af"
                        : "#b45309"
                    : undefined,
              }}
            >
              {/* Rank */}
              <div className="text-xs">{rankMedal(idx)}</div>
              {/* Portrait */}
              <GeneralPortrait
                picture={entry.picture}
                name={entry.generalName}
                size="md"
              />
              {/* Nation badge */}
              <div
                className="text-[10px] px-1.5 py-0.5 rounded truncate w-full font-medium"
                style={{
                  backgroundColor: entry.nationColor,
                  color: getContrastColor(entry.nationColor),
                }}
              >
                {entry.nationName}
              </div>
              {/* Name */}
              <div
                className="text-xs font-bold truncate w-full"
                style={{ color: entry.nationColor }}
              >
                {entry.generalName}
              </div>
              {/* Owner */}
              {entry.ownerName && (
                <div className="text-[10px] text-muted-foreground truncate w-full">
                  ({entry.ownerName})
                </div>
              )}
              {/* Value */}
              <div className="text-xs font-bold text-amber-400">
                {entry.printValue}
              </div>
              {/* Server info */}
              {entry.serverName && (
                <div className="text-[10px] text-muted-foreground">
                  {entry.serverName}
                </div>
              )}
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

function getContrastColor(hex: string): string {
  const c = hex.replace("#", "");
  const r = parseInt(c.substring(0, 2), 16);
  const g = parseInt(c.substring(2, 4), 16);
  const b = parseInt(c.substring(4, 6), 16);
  const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  return luminance > 0.5 ? "#000" : "#fff";
}

/* ── Component ── */

export default function HallOfFamePage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [viewMode, setViewMode] = useState<"card" | "table">("card");
  const [categoryKey, setCategoryKey] = useState("all");
  const [seasons, setSeasons] = useState<
    {
      id: number;
      label: string;
      scenarios: { code: string; label: string }[];
    }[]
  >([]);
  const [selectedSeason, setSelectedSeason] = useState<string>("all");
  const [selectedScenario, setSelectedScenario] = useState<string>("all");

  // Load season/scenario options
  useEffect(() => {
    if (!currentWorld) return;
    rankingApi
      .hallOfFameOptions(currentWorld.id)
      .then(({ data }) => setSeasons(data.seasons ?? []))
      .catch(() => setSeasons([]));
  }, [currentWorld]);

  // Load hall of fame data filtered by season/scenario
  useEffect(() => {
    if (!currentWorld) return;
    setLoading(true);
    const params: { season?: number; scenario?: string } = {};
    if (selectedSeason !== "all") params.season = Number(selectedSeason);
    if (selectedScenario !== "all") params.scenario = selectedScenario;
    rankingApi
      .hallOfFame(currentWorld.id, params)
      .then(({ data }) => setMessages(data))
      .catch(() => setMessages([]))
      .finally(() => setLoading(false));
  }, [currentWorld, selectedSeason, selectedScenario]);

  // Parse all entries
  const allEntries = useMemo(() => messages.map(parseEntry), [messages]);

  // Group by category
  const entriesByCategory = useMemo(() => {
    const map = new Map<string, HallEntry[]>();
    for (const entry of allEntries) {
      const key = entry.category;
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(entry);
    }
    // Sort each group by value desc
    for (const [, entries] of map) {
      entries.sort((a, b) => b.value - a.value);
    }
    return map;
  }, [allEntries]);

  // Detect available categories from data
  const availableCategories = useMemo(() => {
    const found = new Set(allEntries.map((e) => e.category));
    // Use HALL_CATEGORIES order, include any unknown at end
    const ordered = HALL_CATEGORIES.filter((c) => found.has(c.key));
    const knownKeys = new Set(HALL_CATEGORIES.map((c) => c.key));
    for (const key of found) {
      if (!knownKeys.has(key)) {
        ordered.push({ key, label: CATEGORY_MAP.get(key)?.label ?? key });
      }
    }
    return ordered;
  }, [allEntries]);

  // Filter for table view
  const displayEntries = useMemo(() => {
    const filtered =
      categoryKey === "all"
        ? allEntries
        : allEntries.filter((e) => e.category === categoryKey);
    return [...filtered].sort((a, b) => b.value - a.value);
  }, [allEntries, categoryKey]);

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading)
    return (
      <div className="p-4">
        <LoadingState />
      </div>
    );

  const hasData = availableCategories.length > 0;

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Award} title="명예의 전당" />

      {/* Season / Scenario selectors */}
      <div className="flex items-center gap-3 flex-wrap">
        <Select
          value={selectedSeason}
          onValueChange={(v) => {
            setSelectedSeason(v);
            setSelectedScenario("all");
          }}
        >
          <SelectTrigger className="w-44">
            <SelectValue placeholder="시즌 선택" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체 시즌</SelectItem>
            {seasons.map((s) => (
              <SelectItem key={s.id} value={String(s.id)}>
                {s.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {selectedSeason !== "all" &&
          (() => {
            const season = seasons.find((s) => String(s.id) === selectedSeason);
            const scenarioList = season?.scenarios ?? [];
            return scenarioList.length > 0 ? (
              <Select
                value={selectedScenario}
                onValueChange={setSelectedScenario}
              >
                <SelectTrigger className="w-44">
                  <SelectValue placeholder="시나리오 선택" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">전체 시나리오</SelectItem>
                  {scenarioList.map((sc) => (
                    <SelectItem key={sc.code} value={sc.code}>
                      {sc.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            ) : null;
          })()}
      </div>

      {hasData ? (
        <Tabs
          value={viewMode}
          onValueChange={(v) => setViewMode(v as "card" | "table")}
        >
          <TabsList>
            <TabsTrigger value="card">카드 보기</TabsTrigger>
            <TabsTrigger value="table">표 보기</TabsTrigger>
          </TabsList>

          {/* ── Card view (legacy hallOfFrame style) ── */}
          <TabsContent value="card" className="space-y-4 mt-4">
            {availableCategories.map((cat) => {
              const entries = entriesByCategory.get(cat.key) ?? [];
              return (
                <HallFrame
                  key={cat.key}
                  categoryLabel={cat.label}
                  entries={entries}
                />
              );
            })}
          </TabsContent>

          {/* ── Table view ── */}
          <TabsContent value="table" className="space-y-4 mt-4">
            <div className="flex items-center gap-3">
              <Select value={categoryKey} onValueChange={setCategoryKey}>
                <SelectTrigger className="w-48">
                  <SelectValue placeholder="분야 선택" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">전체</SelectItem>
                  {availableCategories.map((c) => (
                    <SelectItem key={c.key} value={c.key}>
                      {c.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <span className="text-sm text-muted-foreground">
                {displayEntries.length}명
              </span>
            </div>

            {displayEntries.length === 0 ? (
              <EmptyState icon={Award} title="데이터가 없습니다." />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-12">#</TableHead>
                    <TableHead>장수</TableHead>
                    <TableHead>소속</TableHead>
                    {categoryKey === "all" && <TableHead>분야</TableHead>}
                    <TableHead>기록</TableHead>
                    <TableHead>유저</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {displayEntries.map((entry, idx) => (
                    <TableRow
                      key={`${entry.generalName}-${entry.category}-${idx}`}
                    >
                      <TableCell>{rankMedal(idx)}</TableCell>
                      <TableCell className="font-medium">
                        <div className="flex items-center gap-2">
                          <GeneralPortrait
                            picture={entry.picture}
                            name={entry.generalName}
                            size="sm"
                          />
                          {entry.generalName}
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className="gap-1.5">
                          <span
                            className="inline-block size-2 rounded-full shrink-0"
                            style={{ backgroundColor: entry.nationColor }}
                          />
                          <span style={{ color: entry.nationColor }}>
                            {entry.nationName}
                          </span>
                        </Badge>
                      </TableCell>
                      {categoryKey === "all" && (
                        <TableCell className="text-xs text-muted-foreground">
                          {CATEGORY_MAP.get(entry.category)?.label ??
                            entry.category}
                        </TableCell>
                      )}
                      <TableCell className="text-amber-400 font-medium">
                        {entry.printValue}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {entry.ownerName || "-"}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </TabsContent>
        </Tabs>
      ) : (
        <EmptyState icon={Award} title="명예전당 데이터가 없습니다." />
      )}
    </div>
  );
}
