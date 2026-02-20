"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { messageApi } from "@/lib/gameApi";
import type { Message } from "@/types";
import { Eye, Trash2 } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Button } from "@/components/ui/button";

export default function SpyPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, cities, nations, loading, loadAll } = useGameStore();
  const [reports, setReports] = useState<Message[]>([]);
  const [mailLoading, setMailLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    if (!currentWorld) return;
    fetchMyGeneral(currentWorld.id);
    loadAll(currentWorld.id);
  }, [currentWorld, fetchMyGeneral, loadAll]);

  const fetchReports = useCallback(async () => {
    if (!myGeneral) return;
    setRefreshing(true);
    try {
      const { data } = await messageApi.getMine(myGeneral.id);
      const filtered = data
        .filter(isSpyReport)
        .sort(
          (a, b) => new Date(b.sentAt).getTime() - new Date(a.sentAt).getTime(),
        );
      setReports(filtered);
    } finally {
      setMailLoading(false);
      setRefreshing(false);
    }
  }, [myGeneral]);

  useEffect(() => {
    if (!myGeneral) return;
    fetchReports();
  }, [myGeneral, fetchReports]);

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );
  const generalMap = useMemo(
    () => new Map(generals.map((g) => [g.id, g])),
    [generals],
  );

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;
  if (mailLoading) return <LoadingState message="첩보함을 불러오는 중..." />;

  const unreadCount = reports.filter((m) => !getReadAt(m.meta)).length;

  const handleMarkAsRead = async (id: number) => {
    try {
      await messageApi.markAsRead(id);
      const now = new Date().toISOString();
      setReports((prev) =>
        prev.map((m) =>
          m.id === id ? { ...m, meta: { ...m.meta, readAt: now } } : m,
        ),
      );
    } catch {}
  };

  const handleDelete = async (id: number) => {
    try {
      await messageApi.delete(id);
      setReports((prev) => prev.filter((m) => m.id !== id));
    } catch {}
  };

  return (
    <div className="p-4 space-y-6 max-w-3xl mx-auto">
      <div className="flex items-center justify-between">
        <PageHeader icon={Eye} title="첩보함" />
        <Button size="sm" variant="outline" onClick={fetchReports}>
          새로고침
        </Button>
      </div>

      <Card>
        <CardContent className="pt-6 text-sm text-muted-foreground flex items-center gap-2">
          <Badge variant="outline">수신 첩보 {reports.length}건</Badge>
          <Badge className="bg-amber-500 text-black">
            미확인 {unreadCount}건
          </Badge>
          {refreshing && <span>첩보를 갱신하고 있습니다...</span>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>첩보 결과</CardTitle>
        </CardHeader>
        <CardContent>
          {reports.length === 0 ? (
            <div className="space-y-4">
              <EmptyState icon={Eye} title="수신된 첩보 결과가 없습니다." />
              <div className="rounded-md border border-dashed p-3 text-sm text-muted-foreground">
                <p className="font-medium text-foreground mb-1">
                  첩보 기능 준비 중
                </p>
                <p>
                  전용 첩보 API가 준비되면 목표 도시/장수별 상세 보고서가
                  자동으로 분류됩니다.
                </p>
              </div>
            </div>
          ) : (
            <div className="space-y-2">
              {reports.map((report) => {
                const payload = report.payload;
                const targetCityId = extractNumber(payload, [
                  "destCityId",
                  "targetCityId",
                ]);
                const targetGeneralId = extractNumber(payload, [
                  "targetGeneralId",
                  "destGeneralId",
                ]);
                const nestedSpy = getRecord(payload, "spyResult");
                const nestedScout = getRecord(payload, "scoutResult");
                const nestedTargetCity =
                  extractNumber(nestedSpy, ["destCityId", "targetCityId"]) ??
                  extractNumber(nestedScout, ["destCityId", "targetCityId"]);

                const city = cityMap.get(
                  targetCityId ?? nestedTargetCity ?? -1,
                );
                const targetGeneral = generalMap.get(targetGeneralId ?? -1);
                const sender = report.srcId
                  ? generalMap.get(report.srcId)
                  : null;
                const senderNation = report.srcId
                  ? nationMap.get(sender?.nationId ?? -1)
                  : null;

                return (
                  <Card
                    key={report.id}
                    className="cursor-pointer"
                    onClick={() => handleMarkAsRead(report.id)}
                  >
                    <CardContent className="pt-4 space-y-2">
                      <div className="flex items-center gap-2 text-xs text-muted-foreground">
                        <span className="font-medium text-foreground">
                          {sender?.name ?? senderNation?.name ?? "첩보 보고"}
                        </span>
                        <span>
                          {new Date(report.sentAt).toLocaleString("ko-KR")}
                        </span>
                        {!getReadAt(report.meta) && (
                          <Badge className="bg-amber-500 text-black">NEW</Badge>
                        )}
                        <Button
                          variant="ghost"
                          size="sm"
                          className="ml-auto h-6 w-6 p-0"
                          onClick={(event) => {
                            event.stopPropagation();
                            handleDelete(report.id);
                          }}
                        >
                          <Trash2 className="size-3.5" />
                        </Button>
                      </div>

                      <div className="text-sm space-y-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-muted-foreground">
                            목표 도시
                          </span>
                          <Badge variant="outline">
                            {city?.name ?? "미상"}
                          </Badge>
                          <span className="text-muted-foreground">
                            목표 장수
                          </span>
                          <Badge variant="outline">
                            {targetGeneral?.name ?? "정보 없음"}
                          </Badge>
                        </div>
                        <p className="text-foreground break-all">
                          {formatScoutResult(report.payload)}
                        </p>
                      </div>
                    </CardContent>
                  </Card>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function getString(value: Record<string, unknown>, key: string): string | null {
  const raw = value[key];
  return typeof raw === "string" ? raw : null;
}

function getRecord(
  value: Record<string, unknown>,
  key: string,
): Record<string, unknown> {
  const raw = value[key];
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    return raw as Record<string, unknown>;
  }
  return {};
}

function extractNumber(
  value: Record<string, unknown>,
  keys: string[],
): number | null {
  for (const key of keys) {
    const raw = value[key];
    if (typeof raw === "number" && Number.isFinite(raw)) return raw;
    if (typeof raw === "string") {
      const parsed = Number(raw);
      if (Number.isFinite(parsed)) return parsed;
    }
  }
  return null;
}

function getReadAt(meta: Record<string, unknown>): string | null {
  return getString(meta, "readAt");
}

function hasSpyKeyword(value: string | null): boolean {
  if (!value) return false;
  return /(spy|scout|첩보)/i.test(value);
}

function isSpyReport(message: Message): boolean {
  const payload = message.payload;
  const spyResult = getRecord(payload, "spyResult");
  const scoutResult = getRecord(payload, "scoutResult");

  if (
    Object.keys(spyResult).length > 0 ||
    Object.keys(scoutResult).length > 0
  ) {
    return true;
  }

  if (extractNumber(payload, ["destCityId", "targetCityId"]) != null) {
    return true;
  }

  if (extractNumber(payload, ["targetGeneralId", "destGeneralId"]) != null) {
    return true;
  }

  const content = getString(payload, "content");
  return (
    hasSpyKeyword(message.mailboxCode) ||
    hasSpyKeyword(message.messageType) ||
    hasSpyKeyword(content)
  );
}

function formatScoutResult(payload: Record<string, unknown>): string {
  const content = getString(payload, "content");
  if (content) return content;

  const spyResult = getRecord(payload, "spyResult");
  if (Object.keys(spyResult).length > 0) {
    return Object.entries(spyResult)
      .map(([key, value]) => `${key}:${String(value)}`)
      .join(" / ");
  }

  const scoutResult = getRecord(payload, "scoutResult");
  if (Object.keys(scoutResult).length > 0) {
    return Object.entries(scoutResult)
      .map(([key, value]) => `${key}:${String(value)}`)
      .join(" / ");
  }

  return "첩보 결과 형식을 확인할 수 없습니다.";
}
