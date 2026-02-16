"use client";

import { useEffect, useState, useCallback } from "react";
import { Vote } from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import type { Message } from "@/types";
import { voteApi } from "@/lib/gameApi";

export default function VotePage() {
  const { currentWorld } = useWorldStore();
  const { myGeneral } = useGeneralStore();
  const [votes, setVotes] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const { data } = await voteApi.list(currentWorld.id);
      setVotes(data);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld]);

  useEffect(() => {
    load();
  }, [load]);

  const handleVote = async (voteId: number, optionIndex: number) => {
    if (!myGeneral) return;
    try {
      await voteApi.cast(voteId, myGeneral.id, optionIndex);
      await load();
    } catch {
      /* ignore */
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={Vote} title="투표" />

      {votes.length === 0 ? (
        <EmptyState icon={Vote} title="진행 중인 투표가 없습니다." />
      ) : (
        <div className="space-y-4">
          {votes.map((vote) => {
            const title = vote.payload.title as string;
            const options = (vote.payload.options as string[]) ?? [];
            const ballots =
              (vote.payload.ballots as Record<string, number>) ?? {};
            const state = vote.payload.state as string;
            const myVoteIdx = myGeneral
              ? ballots[myGeneral.id.toString()]
              : undefined;

            // Count votes per option
            const counts = new Array(options.length).fill(0);
            Object.values(ballots).forEach((idx) => {
              if (typeof idx === "number" && idx < counts.length) counts[idx]++;
            });
            const total = counts.reduce((a, b) => a + b, 0);

            return (
              <Card key={vote.id}>
                <CardContent className="space-y-3">
                  <div className="flex items-center justify-between">
                    <h2 className="font-semibold text-sm">{title}</h2>
                    <Badge variant={state === "open" ? "default" : "outline"}>
                      {state === "open" ? "진행중" : "종료"}
                    </Badge>
                  </div>
                  <div className="space-y-2">
                    {options.map((opt, i) => {
                      const pct = total > 0 ? (counts[i] / total) * 100 : 0;
                      const isMyVote = myVoteIdx === i;
                      return (
                        <div key={i} className="space-y-1">
                          <div className="flex justify-between text-sm">
                            <span className={isMyVote ? "text-amber-400" : ""}>
                              {opt}
                              {isMyVote && (
                                <Badge
                                  variant="outline"
                                  className="ml-2 text-xs"
                                >
                                  내 투표
                                </Badge>
                              )}
                            </span>
                            <span className="text-muted-foreground">
                              {counts[i]}표 ({pct.toFixed(0)}%)
                            </span>
                          </div>
                          <Progress
                            value={pct}
                            className="h-2"
                            indicatorColor={
                              isMyVote ? "bg-amber-400" : "bg-primary"
                            }
                          />
                        </div>
                      );
                    })}
                  </div>
                  {state === "open" && myVoteIdx === undefined && (
                    <div className="flex gap-2 pt-1">
                      {options.map((opt, i) => (
                        <Button
                          key={i}
                          variant="outline"
                          size="sm"
                          onClick={() => handleVote(vote.id, i)}
                        >
                          {opt}
                        </Button>
                      ))}
                    </div>
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
