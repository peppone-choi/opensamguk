"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { rankingApi } from "@/lib/gameApi";
import type { Message } from "@/types";
import { Award, Trophy } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
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
  { key: "dex1", label: "보병숙련" },
  { key: "dex2", label: "궁병숙련" },
  { key: "dex3", label: "기병숙련" },
  { key: "dex4", label: "귀병숙련" },
  { key: "dex5", label: "차병숙련" },
  { key: "ttw", label: "전력전 승률" },
  { key: "tlw", label: "통솔전 승률" },
  { key: "tsw", label: "일기토 승률" },
  { key: "tiw", label: "설전 승률" },
  { key: "betgold", label: "베팅투자" },
  { key: "betwingold", label: "베팅수익" },
];

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
    nationColor: (p.nationColor as string) ?? (p.color as string) ?? "#888",
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

/* ── Component ── */

export default function HallOfFamePage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [categoryKey, setCategoryKey] = useState("all");
  const [seasons, setSeasons] = useState<{ id: number; label: string; scenarios: { code: string; label: string }[] }[]>([]);
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

  // Detect available categories from data
  const availableCategories = useMemo(() => {
    const found = new Set(allEntries.map((e) => e.category));
    return HALL_CATEGORIES.filter((c) => found.has(c.key));
  }, [allEntries]);

  // Unique scenarios
  const scenarios = useMemo(() => {
    const set = new Set<string>();
    for (const e of allEntries) {
      if (e.scenario) set.add(e.scenario);
    }
    return Array.from(set);
  }, [allEntries]);

  // Filter + sort
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

  // If no structured data, fall back to the original card-based display
  const hasStructuredData = availableCategories.length > 0;

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Award} title="명예의 전당" />

      {/* Season / Scenario selectors */}
      <div className="flex items-center gap-3 flex-wrap">
        <Select value={selectedSeason} onValueChange={(v) => { setSelectedSeason(v); setSelectedScenario("all"); }}>
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

        {selectedSeason !== "all" && (() => {
          const season = seasons.find((s) => String(s.id) === selectedSeason);
          const scenarioList = season?.scenarios ?? [];
          return scenarioList.length > 0 ? (
            <Select value={selectedScenario} onValueChange={setSelectedScenario}>
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

      {/* Scenario badges */}
      {scenarios.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {scenarios.map((s) => (
            <Badge key={s} variant="secondary">
              {s}
            </Badge>
          ))}
        </div>
      )}

      {hasStructuredData ? (
        <>
          {/* Category selector */}
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

          {/* Rankings table */}
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
                      {entry.generalName}
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
                        {HALL_CATEGORIES.find((c) => c.key === entry.category)
                          ?.label ?? entry.category}
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
        </>
      ) : (
        /* Fallback: unstructured message display */
        <>
          {allEntries.length === 0 ? (
            <EmptyState icon={Award} title="명예전당 데이터가 없습니다." />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-12">#</TableHead>
                  <TableHead>장수</TableHead>
                  <TableHead>소속</TableHead>
                  <TableHead>내용</TableHead>
                  {allEntries[0]?.year != null && <TableHead>연도</TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {allEntries.map((entry, idx) => (
                  <TableRow key={idx}>
                    <TableCell>{rankMedal(idx)}</TableCell>
                    <TableCell className="font-medium">
                      {entry.generalName}
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
                    <TableCell>
                      {entry.scenario || entry.printValue || "-"}
                    </TableCell>
                    {entry.year != null && (
                      <TableCell>
                        <Badge variant="secondary">{entry.year}년</Badge>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </>
      )}
    </div>
  );
}
