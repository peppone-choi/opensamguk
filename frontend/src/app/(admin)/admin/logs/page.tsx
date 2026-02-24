"use client";

import { useCallback, useState } from "react";
import {
  ScrollText,
  Search,
  ChevronDown,
  ChevronRight,
  Clock,
  RefreshCw,
  ChevronLeft,
} from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { adminApi } from "@/lib/gameApi";
import { toast } from "sonner";
import type { Message } from "@/types";
import { cn } from "@/lib/utils";

const LOG_CATEGORIES = [
  { key: "all", label: "전체" },
  { key: "battle", label: "전투", patterns: ["battle", "war", "attack", "combat", "전투"] },
  { key: "domestic", label: "내정", patterns: ["domestic", "build", "develop", "trade", "내정", "개발"] },
  { key: "diplomacy", label: "외교", patterns: ["diplomacy", "ally", "peace", "외교"] },
  { key: "system", label: "시스템", patterns: ["system", "turn", "event", "시스템"] },
] as const;

type CategoryKey = (typeof LOG_CATEGORIES)[number]["key"];

const ITEMS_PER_PAGE = 20;

function categorizeLog(log: Message): CategoryKey {
  const type = (log.messageType ?? "").toLowerCase();
  const payloadStr = JSON.stringify(log.payload ?? {}).toLowerCase();
  const combined = `${type} ${payloadStr}`;

  for (const cat of LOG_CATEGORIES) {
    if (cat.key === "all") continue;
    if ("patterns" in cat && cat.patterns.some((p) => combined.includes(p))) {
      return cat.key;
    }
  }
  return "system";
}

const categoryColor: Record<CategoryKey, string> = {
  all: "",
  battle: "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300",
  domestic:
    "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300",
  diplomacy:
    "bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-300",
  system:
    "bg-gray-100 text-gray-800 dark:bg-gray-900/30 dark:text-gray-300",
};

const categoryLabel: Record<CategoryKey, string> = {
  all: "전체",
  battle: "전투",
  domestic: "내정",
  diplomacy: "외교",
  system: "시스템",
};

function LogEntry({ log }: { log: Message }) {
  const [expanded, setExpanded] = useState(false);
  const category = categorizeLog(log);
  const sentDate = new Date(log.sentAt);
  const payloadStr = JSON.stringify(log.payload, null, 2);
  const hasPayload = payloadStr !== "{}" && payloadStr !== "null";

  // Try to extract a readable message from payload
  const displayMessage =
    (log.payload as Record<string, unknown>)?.message ??
    (log.payload as Record<string, unknown>)?.msg ??
    (log.payload as Record<string, unknown>)?.text ??
    log.messageType;

  return (
    <Card
      className={cn(
        "transition-colors",
        expanded && "ring-1 ring-primary/20"
      )}
    >
      <CardContent className="p-3">
        <div
          className="flex items-start gap-2 cursor-pointer"
          onClick={() => hasPayload && setExpanded(!expanded)}
        >
          <div className="mt-0.5">
            {hasPayload ? (
              expanded ? (
                <ChevronDown className="size-4 text-muted-foreground" />
              ) : (
                <ChevronRight className="size-4 text-muted-foreground" />
              )
            ) : (
              <div className="size-4" />
            )}
          </div>
          <div className="flex-1 min-w-0 space-y-1">
            <div className="flex items-center gap-2 flex-wrap">
              <Badge
                variant="outline"
                className={cn("text-[10px] px-1.5 py-0", categoryColor[category])}
              >
                {categoryLabel[category]}
              </Badge>
              <span className="text-xs font-mono text-muted-foreground">
                #{log.id}
              </span>
              <span className="text-xs text-muted-foreground">
                {log.messageType}
              </span>
            </div>
            <p className="text-sm break-all">{String(displayMessage)}</p>
          </div>
          <div className="flex items-center gap-1 text-xs text-muted-foreground shrink-0">
            <Clock className="size-3" />
            <time dateTime={log.sentAt}>
              {sentDate.toLocaleDateString("ko-KR")}{" "}
              {sentDate.toLocaleTimeString("ko-KR", {
                hour: "2-digit",
                minute: "2-digit",
                second: "2-digit",
              })}
            </time>
          </div>
        </div>
        {expanded && hasPayload && (
          <div className="mt-2 ml-6">
            <pre className="text-xs whitespace-pre-wrap break-all bg-muted/50 rounded p-2 max-h-64 overflow-auto">
              {payloadStr}
            </pre>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default function AdminLogsPage() {
  const [generalId, setGeneralId] = useState("");
  const [allLogs, setAllLogs] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [activeCategory, setActiveCategory] = useState<CategoryKey>("all");
  const [page, setPage] = useState(1);

  const handleSearch = useCallback(async () => {
    const id = Number(generalId);
    if (!id) {
      toast.error("장수 ID를 입력하세요.");
      return;
    }
    setLoading(true);
    setPage(1);
    setActiveCategory("all");
    try {
      const res = await adminApi.getGeneralLogs(id);
      setAllLogs(res.data);
      setSearched(true);
      if (res.data.length === 0) {
        toast.info("로그가 없습니다.");
      } else {
        toast.success(`${res.data.length}건의 로그를 조회했습니다.`);
      }
    } catch {
      toast.error("로그 조회 실패");
    } finally {
      setLoading(false);
    }
  }, [generalId]);

  // Filter by category
  const filteredLogs =
    activeCategory === "all"
      ? allLogs
      : allLogs.filter((log) => categorizeLog(log) === activeCategory);

  // Pagination
  const totalPages = Math.max(1, Math.ceil(filteredLogs.length / ITEMS_PER_PAGE));
  const safePage = Math.min(page, totalPages);
  const pagedLogs = filteredLogs.slice(
    (safePage - 1) * ITEMS_PER_PAGE,
    safePage * ITEMS_PER_PAGE
  );

  // Category counts
  const categoryCounts = allLogs.reduce(
    (acc, log) => {
      const cat = categorizeLog(log);
      acc[cat] = (acc[cat] || 0) + 1;
      acc.all = (acc.all || 0) + 1;
      return acc;
    },
    {} as Record<string, number>
  );

  return (
    <div className="space-y-4">
      <PageHeader icon={ScrollText} title="장수 로그" />

      {/* Search bar */}
      <div className="flex items-center gap-2">
        <div className="relative w-48">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
          <Input
            type="number"
            placeholder="장수 ID"
            value={generalId}
            onChange={(e) => setGeneralId(e.target.value)}
            className="pl-8"
            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
          />
        </div>
        <Button
          onClick={handleSearch}
          disabled={loading}
          className="bg-red-400 hover:bg-red-500 text-white"
        >
          조회
        </Button>
        {searched && (
          <Button
            variant="outline"
            size="sm"
            onClick={handleSearch}
            disabled={loading}
          >
            <RefreshCw className="size-4 mr-1" />
            새로고침
          </Button>
        )}
      </div>

      {loading && <LoadingState />}

      {/* Category tabs */}
      {!loading && searched && allLogs.length > 0 && (
        <>
          <Tabs
            value={activeCategory}
            onValueChange={(v) => {
              setActiveCategory(v as CategoryKey);
              setPage(1);
            }}
          >
            <TabsList>
              {LOG_CATEGORIES.map((cat) => (
                <TabsTrigger key={cat.key} value={cat.key} className="gap-1">
                  {cat.label}
                  <span className="text-[10px] text-muted-foreground">
                    ({categoryCounts[cat.key] ?? 0})
                  </span>
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>

          {/* Log entries */}
          <div className="space-y-2">
            {pagedLogs.map((log) => (
              <LogEntry key={log.id} log={log} />
            ))}
          </div>

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-2 pt-2">
              <Button
                variant="outline"
                size="sm"
                disabled={safePage <= 1}
                onClick={() => setPage(safePage - 1)}
              >
                <ChevronLeft className="size-4" />
                이전
              </Button>
              <span className="text-sm text-muted-foreground">
                {safePage} / {totalPages} 페이지 (총 {filteredLogs.length}건)
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={safePage >= totalPages}
                onClick={() => setPage(safePage + 1)}
              >
                다음
                <ChevronRight className="size-4" />
              </Button>
            </div>
          )}
        </>
      )}

      {!loading && searched && allLogs.length === 0 && (
        <div className="text-center py-8 text-muted-foreground">
          <ScrollText className="size-8 mx-auto mb-2 opacity-50" />
          <p>해당 장수의 로그가 없습니다.</p>
        </div>
      )}
    </div>
  );
}
