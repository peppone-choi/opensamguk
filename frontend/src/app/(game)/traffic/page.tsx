"use client";

import { useEffect, useState, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { Activity } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { trafficApi } from "@/lib/gameApi";

type TrafficEntry = {
  year: number;
  month: number;
  refresh: number;
  online: number;
  date: string;
};

type TopRefresher = {
  name: string;
  refresh: number;
  refreshScoreTotal: number;
};

function getTrafficColor(percent: number): string {
  const r = Math.round((percent * 255) / 100);
  const b = Math.round(((100 - percent) * 255) / 100);
  const toHex = (n: number) => n.toString(16).padStart(2, "0").toUpperCase();
  return `#${toHex(r)}00${toHex(b)}`;
}

function TrafficBar({
  value,
  maxValue,
  label,
}: {
  value: number;
  maxValue: number;
  label: string;
}) {
  const safeMax = Math.max(maxValue, 1);
  const pct = Math.min(Math.round((value / safeMax) * 1000) / 10, 100);
  const color = getTrafficColor(pct);

  return (
    <div className="flex items-center gap-2 h-8">
      <span className="text-xs text-muted-foreground w-24 text-right shrink-0">
        {label}
      </span>
      <div className="flex-1 relative h-7 bg-muted/30 rounded overflow-hidden">
        {pct > 0 && (
          <div
            className="absolute inset-y-0 left-0 rounded transition-all"
            style={{ width: `${pct}%`, backgroundColor: color }}
          />
        )}
        <span
          className={`relative z-10 text-xs font-mono leading-7 ${
            pct >= 10 ? "float-right pr-2" : "ml-2"
          }`}
          style={{ color: pct >= 10 ? "#fff" : undefined }}
        >
          {value.toLocaleString()}
        </span>
      </div>
    </div>
  );
}

export default function TrafficPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const [loading, setLoading] = useState(true);
  const [recentTraffic, setRecentTraffic] = useState<TrafficEntry[]>([]);
  const [maxRefresh, setMaxRefresh] = useState(1);
  const [maxOnline, setMaxOnline] = useState(1);
  const [topRefreshers, setTopRefreshers] = useState<TopRefresher[]>([]);
  const [totalRefresh, setTotalRefresh] = useState(0);
  const [totalRefreshScoreTotal, setTotalRefreshScoreTotal] = useState(0);

  const loadTraffic = useCallback(async () => {
    if (!currentWorld) return;
    setLoading(true);
    try {
      const { data } = await trafficApi.getTraffic(currentWorld.id);
      setRecentTraffic(data.recentTraffic ?? []);
      setMaxRefresh(Math.max(data.maxRefresh, 1));
      setMaxOnline(Math.max(data.maxOnline, 1));
      setTopRefreshers(data.topRefreshers ?? []);
      setTotalRefresh(data.totalRefresh ?? 0);
      setTotalRefreshScoreTotal(data.totalRefreshScoreTotal ?? 0);
    } catch {
      // API may not exist yet — show empty state
    } finally {
      setLoading(false);
    }
  }, [currentWorld]);

  useEffect(() => {
    loadTraffic();
  }, [loadTraffic]);

  if (!currentWorld) {
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  }

  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-4 max-w-5xl mx-auto">
      <PageHeader icon={Activity} title="트래픽 정보" />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* 접속량 (Refresh count) */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-lg text-center">접 속 량</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1">
            {recentTraffic.length === 0 ? (
              <div className="text-sm text-muted-foreground text-center py-4">
                트래픽 데이터가 없습니다.
              </div>
            ) : (
              recentTraffic.map((entry, idx) => {
                const timeStr = entry.date
                  ? entry.date.substring(11, 16)
                  : "-";
                const label = `${entry.year}년 ${entry.month}월 ${timeStr}`;
                return (
                  <TrafficBar
                    key={idx}
                    value={entry.refresh}
                    maxValue={maxRefresh}
                    label={label}
                  />
                );
              })
            )}
            <div className="text-center text-sm text-muted-foreground pt-2 border-t">
              최고기록: {maxRefresh.toLocaleString()}
            </div>
          </CardContent>
        </Card>

        {/* 접속자 (Online user count) */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-lg text-center">접 속 자</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1">
            {recentTraffic.length === 0 ? (
              <div className="text-sm text-muted-foreground text-center py-4">
                트래픽 데이터가 없습니다.
              </div>
            ) : (
              recentTraffic.map((entry, idx) => {
                const timeStr = entry.date
                  ? entry.date.substring(11, 16)
                  : "-";
                const label = `${entry.year}년 ${entry.month}월 ${timeStr}`;
                return (
                  <TrafficBar
                    key={idx}
                    value={entry.online}
                    maxValue={maxOnline}
                    label={label}
                  />
                );
              })
            )}
            <div className="text-center text-sm text-muted-foreground pt-2 border-t">
              최고기록: {maxOnline.toLocaleString()}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* 주의대상자 (Top refreshers) */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-lg text-center">
            주 의 대 상 자 (순간과도갱신)
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-1">
          {/* Total row */}
          <div className="flex items-center gap-2 h-5">
            <span className="text-xs w-24 text-right shrink-0 font-semibold">
              접속자 총합
            </span>
            <span className="text-xs w-28 text-center shrink-0">
              {totalRefreshScoreTotal.toLocaleString()}(
              {totalRefresh.toLocaleString()})
            </span>
            <div className="flex-1 relative h-4 bg-muted/30 rounded overflow-hidden">
              <div
                className="absolute inset-y-0 left-0 rounded"
                style={{
                  width: "100%",
                  backgroundColor: getTrafficColor(100),
                }}
              />
            </div>
          </div>

          {topRefreshers.map((user, idx) => {
            const pct =
              totalRefresh > 0
                ? Math.round((user.refresh / totalRefresh) * 1000) / 10
                : 0;
            const color = getTrafficColor(pct);
            return (
              <div key={idx} className="flex items-center gap-2 h-5">
                <span className="text-xs w-24 text-right shrink-0">
                  {user.name}
                </span>
                <span className="text-xs w-28 text-center shrink-0">
                  {user.refreshScoreTotal.toLocaleString()}(
                  {user.refresh.toLocaleString()})
                </span>
                <div className="flex-1 relative h-4 bg-muted/30 rounded overflow-hidden">
                  {pct > 0 && (
                    <div
                      className="absolute inset-y-0 left-0 rounded"
                      style={{
                        width: `${pct}%`,
                        backgroundColor: color,
                      }}
                    />
                  )}
                </div>
              </div>
            );
          })}

          {topRefreshers.length === 0 && (
            <div className="text-sm text-muted-foreground text-center py-4">
              데이터가 없습니다.
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
