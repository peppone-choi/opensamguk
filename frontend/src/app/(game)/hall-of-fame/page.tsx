"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { rankingApi } from "@/lib/gameApi";
import type { Message } from "@/types";
import { Trophy } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

export default function HallOfFamePage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!currentWorld) return;
    rankingApi
      .hallOfFame(currentWorld.id)
      .then(({ data }) => setMessages(data))
      .finally(() => setLoading(false));
  }, [currentWorld]);

  const hallEntries = useMemo(
    () =>
      messages
        .filter((m) => m.mailboxCode === "hall_of_fame")
        .sort(
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
      <PageHeader icon={Trophy} title="명예전당" />

      {hallEntries.length === 0 ? (
        <EmptyState icon={Trophy} title="명예전당 데이터가 없습니다." />
      ) : (
        <div className="space-y-3">
          {hallEntries.map((m, idx) => {
            const winner =
              (m.payload.winner as string) ??
              (m.payload.generalName as string) ??
              null;
            const nation =
              (m.payload.nation as string) ??
              (m.payload.nationName as string) ??
              null;
            const year = (m.payload.year as number) ?? null;
            const description =
              (m.payload.description as string) ??
              (m.payload.content as string) ??
              null;

            return (
              <Card key={m.id}>
                <CardContent className="flex items-center gap-3">
                  <Trophy className="size-5 text-amber-400 shrink-0" />
                  <Badge variant="default" className="shrink-0">
                    #{hallEntries.length - idx}
                  </Badge>
                  <div className="flex-1 min-w-0">
                    {winner && (
                      <p className="font-medium text-amber-300">{winner}</p>
                    )}
                    {nation && (
                      <p className="text-sm text-muted-foreground">{nation}</p>
                    )}
                    {description && (
                      <p className="text-sm text-muted-foreground">
                        {description}
                      </p>
                    )}
                    {!winner && !description && (
                      <p className="text-sm">{JSON.stringify(m.payload)}</p>
                    )}
                  </div>
                  {year != null && (
                    <Badge variant="secondary" className="shrink-0">
                      {year}년
                    </Badge>
                  )}
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
