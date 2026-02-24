"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { historyApi } from "@/lib/gameApi";
import type { Message } from "@/types";
import { Crown, Scroll, Swords, Flag, Landmark } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { NationBadge } from "@/components/game/nation-badge";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";

const LEVEL_LABELS: Record<number, string> = {
  0: "주자사",
  1: "주목",
  2: "자사",
  3: "목",
  4: "공",
  5: "왕",
  6: "왕",
  7: "황제",
};

function getLevelLabel(level: number): string {
  return LEVEL_LABELS[level] ?? `레벨 ${level}`;
}

/* ── Timeline event parsing from world records ── */

interface DynastyEvent {
  year: number;
  month: number;
  type: "founded" | "destroyed" | "united" | "levelUp" | "war" | "other";
  nationName: string;
  nationColor: string;
  description: string;
  detail?: string;
}

function classifyEvent(msg: Message): DynastyEvent | null {
  const p = msg.payload;
  const text = (p.text as string) ?? (p.message as string) ?? (p.content as string) ?? "";
  const nationName = (p.nationName as string) ?? (p.nation as string) ?? "";
  const nationColor = (p.nationColor as string) ?? (p.color as string) ?? "#888";
  const year = (p.year as number) ?? 0;
  const month = (p.month as number) ?? 1;

  // Detect event type from message content or type field
  const msgType = (p.type as string) ?? msg.messageType ?? "";
  let type: DynastyEvent["type"] = "other";

  if (msgType.includes("found") || text.includes("건국") || text.includes("세력을 세웠")) {
    type = "founded";
  } else if (msgType.includes("destroy") || text.includes("멸망") || text.includes("멸했")) {
    type = "destroyed";
  } else if (msgType.includes("unite") || text.includes("통일") || text.includes("천하통일")) {
    type = "united";
  } else if (msgType.includes("level") || text.includes("작위") || text.includes("칭제")) {
    type = "levelUp";
  } else if (msgType.includes("war") || text.includes("선전포고") || text.includes("전쟁")) {
    type = "war";
  } else if (nationName) {
    type = "other";
  } else {
    return null; // skip non-nation events
  }

  return {
    year,
    month,
    type,
    nationName,
    nationColor,
    description: text || msgType,
    detail: (p.detail as string) ?? undefined,
  };
}

const EVENT_ICONS: Record<DynastyEvent["type"], React.ReactNode> = {
  founded: <Flag className="size-4 text-green-400" />,
  destroyed: <Swords className="size-4 text-red-400" />,
  united: <Crown className="size-4 text-amber-400" />,
  levelUp: <Landmark className="size-4 text-purple-400" />,
  war: <Swords className="size-4 text-orange-400" />,
  other: <Scroll className="size-4 text-muted-foreground" />,
};

const EVENT_LABELS: Record<DynastyEvent["type"], string> = {
  founded: "건국",
  destroyed: "멸망",
  united: "통일",
  levelUp: "작위",
  war: "전쟁",
  other: "기타",
};

export default function DynastyPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, generals, cities, loading, loadAll } = useGameStore();
  const [records, setRecords] = useState<Message[]>([]);
  const [recordsLoading, setRecordsLoading] = useState(true);

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
    setRecordsLoading(true);
    historyApi
      .getWorldRecords(currentWorld.id)
      .then(({ data }) => setRecords(data))
      .catch(() => setRecords([]))
      .finally(() => setRecordsLoading(false));
  }, [currentWorld, loadAll]);

  /* ── Current nation details ── */
  const nationDetails = useMemo(() => {
    return nations.map((nation) => {
      const ruler = generals.find((g) => g.id === nation.chiefGeneralId);
      const nationGenerals = generals.filter((g) => g.nationId === nation.id);
      const nationCities = cities.filter((c) => c.nationId === nation.id);
      const totalPop = nationCities.reduce((sum, c) => sum + c.pop, 0);
      return { nation, ruler, generalCount: nationGenerals.length, cityCount: nationCities.length, totalPop };
    });
  }, [nations, generals, cities]);

  const sorted = useMemo(
    () => [...nationDetails].sort((a, b) => b.nation.power - a.nation.power),
    [nationDetails],
  );

  /* ── Dynasty timeline events ── */
  const dynastyEvents = useMemo(() => {
    const events: DynastyEvent[] = [];
    for (const msg of records) {
      const evt = classifyEvent(msg);
      if (evt) events.push(evt);
    }
    // Sort by year desc, month desc
    events.sort((a, b) => b.year - a.year || b.month - a.month);
    return events;
  }, [records]);

  /* ── Group events by phase (year ranges) ── */
  const phases = useMemo(() => {
    if (dynastyEvents.length === 0) return [];
    const phaseMap = new Map<string, DynastyEvent[]>();
    for (const evt of dynastyEvents) {
      // Group by decades or by significant range
      const decade = Math.floor(evt.year / 10) * 10;
      const key = `${decade}~${decade + 9}년`;
      if (!phaseMap.has(key)) phaseMap.set(key, []);
      phaseMap.get(key)!.push(evt);
    }
    return Array.from(phaseMap.entries()).map(([label, events]) => ({ label, events }));
  }, [dynastyEvents]);

  if (!currentWorld)
    return <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>;
  if (loading && recordsLoading) return <LoadingState />;

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <PageHeader icon={Crown} title="왕조일람" />

      <Tabs defaultValue="current">
        <TabsList>
          <TabsTrigger value="current">현재 세력</TabsTrigger>
          <TabsTrigger value="timeline">역대 왕조 기록</TabsTrigger>
          <TabsTrigger value="comparison">세력 비교</TabsTrigger>
        </TabsList>

        {/* ── Current dynasties tab ── */}
        <TabsContent value="current" className="space-y-3 mt-4">
          {/* Level progression reference */}
          <Card>
            <CardHeader className="py-2 px-4">
              <CardTitle className="text-sm">작위 등급</CardTitle>
            </CardHeader>
            <CardContent className="py-2 px-4">
              <div className="flex flex-wrap gap-1.5 text-xs text-muted-foreground">
                {["주자사", "주목", "자사", "목", "공", "왕", "황제"].map(
                  (label, i, arr) => (
                    <span key={label}>
                      {label}
                      {i < arr.length - 1 && (
                        <span className="mx-1 text-muted-foreground/50">→</span>
                      )}
                    </span>
                  ),
                )}
              </div>
            </CardContent>
          </Card>

          {sorted.length === 0 ? (
            <EmptyState icon={Crown} title="세력이 없습니다." />
          ) : (
            <div className="space-y-3">
              {sorted.map(({ nation, ruler, generalCount, cityCount, totalPop }) => (
                <Card key={nation.id}>
                  <CardContent className="flex items-start gap-3 py-3">
                    <GeneralPortrait picture={ruler?.picture} name={ruler?.name ?? "?"} size="md" />
                    <div className="flex-1 min-w-0 space-y-1.5">
                      <div className="flex items-center gap-2 flex-wrap">
                        <NationBadge name={nation.name} color={nation.color} />
                        <Badge variant="secondary">{getLevelLabel(nation.level)}</Badge>
                      </div>
                      <div className="text-xs">
                        <span className="text-muted-foreground">군주:</span>{" "}
                        <span className="font-medium">{ruler?.name ?? "없음"}</span>
                      </div>
                      <div className="grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-0.5 text-xs">
                        <div><span className="text-muted-foreground">장수:</span> {generalCount}명</div>
                        <div><span className="text-muted-foreground">도시:</span> {cityCount}개</div>
                        <div><span className="text-muted-foreground">국력:</span> {nation.power.toLocaleString()}</div>
                        <div><span className="text-muted-foreground">기술:</span> {nation.tech.toLocaleString()}</div>
                        <div><span className="text-muted-foreground">금:</span> {nation.gold.toLocaleString()}</div>
                        <div><span className="text-muted-foreground">쌀:</span> {nation.rice.toLocaleString()}</div>
                        <div><span className="text-muted-foreground">인구:</span> {totalPop.toLocaleString()}</div>
                        <div><span className="text-muted-foreground">건국:</span> {nation.createdAt?.substring(0, 10) ?? "-"}</div>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          )}
        </TabsContent>

        {/* ── Dynasty timeline tab ── */}
        <TabsContent value="timeline" className="space-y-4 mt-4">
          {recordsLoading ? (
            <LoadingState />
          ) : dynastyEvents.length === 0 ? (
            <EmptyState icon={Scroll} title="역대 왕조 기록이 없습니다." />
          ) : (
            <>
              {/* Summary counts */}
              <div className="flex flex-wrap gap-2">
                {(Object.keys(EVENT_LABELS) as DynastyEvent["type"][]).map((type) => {
                  const count = dynastyEvents.filter((e) => e.type === type).length;
                  if (count === 0) return null;
                  return (
                    <Badge key={type} variant="secondary" className="gap-1.5">
                      {EVENT_ICONS[type]}
                      {EVENT_LABELS[type]}: {count}
                    </Badge>
                  );
                })}
              </div>

              {/* Phase-grouped timeline */}
              {phases.map((phase) => (
                <Card key={phase.label}>
                  <CardHeader className="py-2 px-4">
                    <CardTitle className="text-sm flex items-center gap-2">
                      <Clock className="size-4" />
                      {phase.label}
                      <Badge variant="outline" className="text-xs">{phase.events.length}건</Badge>
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="py-2 px-4">
                    <div className="relative border-l-2 border-muted ml-2 space-y-3 pl-4">
                      {phase.events.map((evt, idx) => (
                        <div key={idx} className="relative">
                          {/* Timeline dot */}
                          <div className="absolute -left-[22px] top-1 size-3 rounded-full bg-muted border-2 border-background" />
                          <div className="flex items-start gap-2">
                            {EVENT_ICONS[evt.type]}
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 flex-wrap">
                                <span className="text-xs text-muted-foreground font-mono">
                                  {evt.year}년 {evt.month}월
                                </span>
                                {evt.nationName && (
                                  <Badge
                                    variant="outline"
                                    className="gap-1 text-xs"
                                  >
                                    <span
                                      className="inline-block size-2 rounded-full"
                                      style={{ backgroundColor: evt.nationColor }}
                                    />
                                    <span style={{ color: evt.nationColor }}>
                                      {evt.nationName}
                                    </span>
                                  </Badge>
                                )}
                                <Badge variant="secondary" className="text-xs">
                                  {EVENT_LABELS[evt.type]}
                                </Badge>
                              </div>
                              <p className="text-sm mt-0.5">{evt.description}</p>
                              {evt.detail && (
                                <p className="text-xs text-muted-foreground mt-0.5">{evt.detail}</p>
                              )}
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </CardContent>
                </Card>
              ))}
            </>
          )}
        </TabsContent>

        {/* ── Comparison table tab ── */}
        <TabsContent value="comparison" className="mt-4">
          {sorted.length === 0 ? (
            <EmptyState icon={Crown} title="세력이 없습니다." />
          ) : (
            <Card>
              <CardHeader className="py-2 px-4">
                <CardTitle className="text-sm">세력 비교</CardTitle>
              </CardHeader>
              <CardContent className="p-0 overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="text-xs">세력</TableHead>
                      <TableHead className="text-xs text-right">작위</TableHead>
                      <TableHead className="text-xs text-right">국력</TableHead>
                      <TableHead className="text-xs text-right">기술</TableHead>
                      <TableHead className="text-xs text-right">장수</TableHead>
                      <TableHead className="text-xs text-right">도시</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {sorted.map(({ nation, generalCount, cityCount }) => (
                      <TableRow key={nation.id}>
                        <TableCell className="py-1.5">
                          <NationBadge name={nation.name} color={nation.color} />
                        </TableCell>
                        <TableCell className="text-xs text-right">{getLevelLabel(nation.level)}</TableCell>
                        <TableCell className="text-xs text-right">{nation.power.toLocaleString()}</TableCell>
                        <TableCell className="text-xs text-right">{nation.tech.toLocaleString()}</TableCell>
                        <TableCell className="text-xs text-right">{generalCount}</TableCell>
                        <TableCell className="text-xs text-right">{cityCount}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}
