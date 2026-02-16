"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { historyApi } from "@/lib/gameApi";
import type { Message } from "@/types";
import { ScrollText, Clock } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

export default function HistoryPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!currentWorld) return;
    historyApi
      .getWorldHistory(currentWorld.id)
      .then(({ data }) => setMessages(data))
      .finally(() => setLoading(false));
  }, [currentWorld]);

  const sorted = useMemo(
    () =>
      [...messages].sort(
        (a, b) => new Date(b.sentAt).getTime() - new Date(a.sentAt).getTime(),
      ),
    [messages],
  );

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <PageHeader icon={ScrollText} title="역사" />

      {sorted.length === 0 ? (
        <EmptyState icon={Clock} title="기록된 역사가 없습니다." />
      ) : (
        <div className="space-y-2">
          {sorted.map((m) => {
            const year = (m.payload.year as number) ?? null;
            const month = (m.payload.month as number) ?? null;
            const description =
              (m.payload.description as string) ??
              (m.payload.content as string) ??
              (m.payload.message as string) ??
              JSON.stringify(m.payload);

            return (
              <Card key={m.id}>
                <CardContent className="flex gap-3 items-start">
                  <div className="shrink-0 pt-0.5">
                    {year != null && month != null ? (
                      <Badge variant="secondary">
                        {year}년 {month}월
                      </Badge>
                    ) : (
                      <Badge variant="outline">
                        {new Date(m.sentAt).toLocaleDateString("ko-KR")}
                      </Badge>
                    )}
                  </div>
                  <p className="text-sm flex-1">{description}</p>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
