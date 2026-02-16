"use client";

import { useEffect, useState, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { Trophy } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { tournamentApi } from "@/lib/gameApi";
import type { TournamentInfo } from "@/types";

const STATE_LABELS: Record<number, string> = {
  0: "대기",
  1: "모집중",
  2: "진행중",
  3: "종료",
};

export default function TournamentPage() {
  const { currentWorld } = useWorldStore();
  const { myGeneral } = useGeneralStore();
  const { generals, loadAll } = useGameStore();
  const [info, setInfo] = useState<TournamentInfo | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!currentWorld) return;
    try {
      loadAll(currentWorld.id);
      const { data } = await tournamentApi.getInfo(currentWorld.id);
      setInfo(data);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld, loadAll]);

  useEffect(() => {
    load();
  }, [load]);

  const handleRegister = async () => {
    if (!currentWorld || !myGeneral) return;
    try {
      await tournamentApi.register(currentWorld.id, myGeneral.id);
      await load();
    } catch {
      /* ignore */
    }
  };

  const generalMap = new Map(generals.map((g) => [g.id, g]));

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  const isRegistered = myGeneral && info?.participants.includes(myGeneral.id);

  return (
    <div className="max-w-3xl mx-auto space-y-4">
      <PageHeader icon={Trophy} title="토너먼트" />

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-sm">
            상태
            <Badge variant="secondary">
              {STATE_LABELS[info?.state ?? 0] ?? "알 수 없음"}
            </Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-xs text-muted-foreground">
            참가자: {info?.participants.length ?? 0}명
          </p>
          {info?.state === 1 && myGeneral && !isRegistered && (
            <Button size="sm" onClick={handleRegister}>
              참가 등록
            </Button>
          )}
          {isRegistered && (
            <Badge variant="outline" className="text-xs">
              등록 완료
            </Badge>
          )}
        </CardContent>
      </Card>

      {/* Participants */}
      {info && info.participants.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">참가자</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {info.participants.map((pid) => {
                const gen = generalMap.get(pid);
                return (
                  <div key={pid} className="flex items-center gap-1.5 text-xs">
                    <GeneralPortrait
                      picture={gen?.picture}
                      name={gen?.name ?? `#${pid}`}
                      size="sm"
                    />
                    <span>{gen?.name ?? `#${pid}`}</span>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Bracket */}
      {info && info.bracket.length > 0 ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">대진표</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {info.bracket.map((match, i) => {
                const p1 = generalMap.get(match.p1);
                const p2 = generalMap.get(match.p2);
                return (
                  <div
                    key={i}
                    className="flex items-center gap-3 text-xs border border-border rounded p-2"
                  >
                    <Badge variant="outline">R{match.round}</Badge>
                    <span
                      className={
                        match.winner === match.p1
                          ? "text-amber-400 font-bold"
                          : ""
                      }
                    >
                      {p1?.name ?? `#${match.p1}`}
                    </span>
                    <span className="text-muted-foreground">vs</span>
                    <span
                      className={
                        match.winner === match.p2
                          ? "text-amber-400 font-bold"
                          : ""
                      }
                    >
                      {p2?.name ?? `#${match.p2}`}
                    </span>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>
      ) : (
        <EmptyState icon={Trophy} title="대진표가 아직 생성되지 않았습니다." />
      )}
    </div>
  );
}
