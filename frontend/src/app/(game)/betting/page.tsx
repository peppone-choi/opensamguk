"use client";

import { useEffect, useState, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { Dice5 } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { bettingApi } from "@/lib/gameApi";
import type { BettingInfo } from "@/types";

export default function BettingPage() {
  const { currentWorld } = useWorldStore();
  const { myGeneral } = useGeneralStore();
  const { generals, loadAll } = useGameStore();
  const [info, setInfo] = useState<BettingInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [targetId, setTargetId] = useState("");
  const [betAmount, setBetAmount] = useState("");

  const load = useCallback(async () => {
    if (!currentWorld) return;
    try {
      loadAll(currentWorld.id);
      const { data } = await bettingApi.getInfo(currentWorld.id);
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

  const handleBet = async () => {
    if (!currentWorld || !myGeneral || !targetId || !betAmount) return;
    try {
      await bettingApi.placeBet(
        currentWorld.id,
        myGeneral.id,
        parseInt(targetId),
        parseInt(betAmount),
      );
      setBetAmount("");
      setTargetId("");
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

  return (
    <div className="max-w-2xl mx-auto space-y-4">
      <PageHeader icon={Dice5} title="베팅" />

      {/* Bet form */}
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">베팅하기</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div>
            <label className="text-xs text-muted-foreground">대상 선택</label>
            <select
              value={targetId}
              onChange={(e) => setTargetId(e.target.value)}
              className="h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            >
              <option value="">선택...</option>
              {generals.map((g) => (
                <option key={g.id} value={g.id}>
                  {g.name}
                  {info?.odds[g.id.toString()]
                    ? ` (배당: ${info.odds[g.id.toString()]}x)`
                    : ""}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="text-xs text-muted-foreground">베팅 금액</label>
            <Input
              type="number"
              value={betAmount}
              onChange={(e) => setBetAmount(e.target.value)}
              placeholder="금액"
            />
          </div>
          <Button
            size="sm"
            onClick={handleBet}
            disabled={!targetId || !betAmount}
          >
            베팅
          </Button>
        </CardContent>
      </Card>

      {/* Bet history */}
      {info && info.bets.length > 0 ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">베팅 내역</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {info.bets.map((bet, i) => {
                const bettor = generalMap.get(bet.generalId);
                const target = generalMap.get(bet.targetId);
                return (
                  <div
                    key={i}
                    className="flex items-center gap-2 text-xs border border-border rounded p-2"
                  >
                    <GeneralPortrait
                      picture={bettor?.picture}
                      name={bettor?.name ?? ""}
                      size="sm"
                    />
                    <span>{bettor?.name ?? `#${bet.generalId}`}</span>
                    <span className="text-muted-foreground">→</span>
                    <span className="text-amber-400">
                      {target?.name ?? `#${bet.targetId}`}
                    </span>
                    <Badge variant="secondary" className="ml-auto">
                      {bet.amount}금
                    </Badge>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>
      ) : (
        <EmptyState icon={Dice5} title="베팅 내역이 없습니다." />
      )}
    </div>
  );
}
