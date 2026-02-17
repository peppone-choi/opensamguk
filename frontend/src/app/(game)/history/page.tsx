"use client";

import { useEffect, useMemo, useState, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { historyApi } from "@/lib/gameApi";
import type { Message } from "@/types";
import { ScrollText, Clock, Search } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";

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
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");
  const [activeFilters, setActiveFilters] = useState<Set<EventType>>(
    new Set(["war", "diplomacy", "nation", "general", "city", "other"]),
  );

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
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld]);

  useEffect(() => {
    loadData();
  }, [loadData]);

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
    combined.sort((a, b) => b.msg.id - a.msg.id);
    return combined;
  }, [history, records]);

  // Filter + search
  const filteredEvents = useMemo(() => {
    const q = searchQuery.toLowerCase();
    return allEvents.filter((e) => {
      if (!activeFilters.has(e.type)) return false;
      if (q && !e.text.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [allEvents, activeFilters, searchQuery]);

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

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <PageHeader icon={ScrollText} title="연감" />

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
    </div>
  );
}
