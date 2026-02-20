"use client";

import { useEffect, useMemo, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import type { PublicCachedMapResponse } from "@/types";

const MAP_WIDTH = 700;
const MAP_HEIGHT = 320;

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "-";
  }
  return date.toLocaleString("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function LoadingCard() {
  return (
    <Card className="w-full max-w-[700px]">
      <CardHeader>
        <CardTitle>서버 현황</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Skeleton className="h-44 w-full" />
        <Skeleton className="h-4 w-24" />
        <div className="space-y-2">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-5/6" />
        </div>
      </CardContent>
    </Card>
  );
}

export function ServerStatusCard() {
  const [data, setData] = useState<PublicCachedMapResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const apiBase =
          process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api";
        const response = await fetch(`${apiBase}/public/cached-map`, {
          method: "GET",
          cache: "no-store",
        });
        if (!response.ok) {
          throw new Error("공개 지도 조회 실패");
        }
        const payload = (await response.json()) as PublicCachedMapResponse;
        setData(payload);
      } catch {
        setData(null);
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, []);

  const points = useMemo(() => {
    if (!data || data.cities.length === 0) {
      return [];
    }

    let minX = Infinity;
    let maxX = -Infinity;
    let minY = Infinity;
    let maxY = -Infinity;

    for (const city of data.cities) {
      if (city.x < minX) minX = city.x;
      if (city.x > maxX) maxX = city.x;
      if (city.y < minY) minY = city.y;
      if (city.y > maxY) maxY = city.y;
    }

    const padding = 36;
    const rangeX = Math.max(1, maxX - minX);
    const rangeY = Math.max(1, maxY - minY);
    const scaleX = (MAP_WIDTH - padding * 2) / rangeX;
    const scaleY = (MAP_HEIGHT - padding * 2) / rangeY;

    return data.cities.map((city) => ({
      id: city.id,
      x: (city.x - minX) * scaleX + padding,
      y: (city.y - minY) * scaleY + padding,
      color: city.nationColor,
      name: city.name,
      nationName: city.nationName,
    }));
  }, [data]);

  if (loading) {
    return <LoadingCard />;
  }

  return (
    <Card className="w-full max-w-[700px]">
      <CardHeader>
        <CardTitle>서버 현황</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {!data?.available ? (
          <div className="flex h-44 items-center justify-center border border-gray-700 bg-black/30 text-sm text-muted-foreground">
            현재 가동 중인 서버가 없습니다
          </div>
        ) : (
          <div className="overflow-hidden border border-gray-700 bg-black/30">
            <svg
              viewBox={`0 0 ${MAP_WIDTH} ${MAP_HEIGHT}`}
              className="h-auto w-full"
              role="img"
            >
              <title>중립 지도</title>
              <rect width={MAP_WIDTH} height={MAP_HEIGHT} fill="#0a0a0a" />
              {points.map((point) => (
                <circle
                  key={point.id}
                  cx={point.x}
                  cy={point.y}
                  r={6}
                  fill={point.color}
                  opacity={0.9}
                >
                  <title>
                    {point.name
                      ? `${point.name} (${point.nationName})`
                      : "도시"}
                  </title>
                </circle>
              ))}
            </svg>
          </div>
        )}

        <div>
          <h3 className="mb-2 text-sm font-semibold">최근 동향</h3>
          {!data?.available || data.history.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              표시할 기록이 없습니다
            </p>
          ) : (
            <ul className="space-y-1">
              {data.history.map((item) => (
                <li
                  key={item.id}
                  className="text-sm leading-relaxed text-zinc-200"
                >
                  <span className="mr-2 text-xs text-muted-foreground">
                    {formatDateTime(item.sentAt)}
                  </span>
                  <span>{item.text}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
