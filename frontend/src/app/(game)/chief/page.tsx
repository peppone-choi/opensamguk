"use client";

import { useEffect, useState } from "react";
import { Crown } from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { generalApi, commandApi, nationApi } from "@/lib/gameApi";
import type { General, Nation, NationTurn, CommandResult, CommandTableEntry } from "@/types";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { ResourceDisplay } from "@/components/game/resource-display";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

export default function ChiefPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const [nation, setNation] = useState<Nation | null>(null);
  const [nationGenerals, setNationGenerals] = useState<General[]>([]);
  const [nationTurns, setNationTurns] = useState<NationTurn[]>([]);
  const [selectedCmd, setSelectedCmd] = useState<CommandTableEntry | null>(null);
  const [nationCommandTable, setNationCommandTable] = useState<Record<string, CommandTableEntry[]>>({});
  const [executing, setExecuting] = useState(false);
  const [cmdResult, setCmdResult] = useState<CommandResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!currentWorld) return;
    fetchMyGeneral(currentWorld.id);
  }, [currentWorld, fetchMyGeneral]);

  useEffect(() => {
    if (!myGeneral || myGeneral.officerLevel < 5) return;
    setLoading(true);
    Promise.all([
      nationApi.get(myGeneral.nationId),
      generalApi.listByNation(myGeneral.nationId),
      commandApi.getNationReserved(myGeneral.nationId, myGeneral.officerLevel),
      commandApi.getNationCommandTable(myGeneral.id),
    ])
      .then(([natRes, gRes, tRes, cmdRes]) => {
        setNation(natRes.data);
        setNationGenerals(gRes.data);
        setNationTurns(tRes.data);
        setNationCommandTable(cmdRes.data);
      })
      .catch(() => setError("데이터를 불러올 수 없습니다."))
      .finally(() => setLoading(false));
  }, [myGeneral]);

  const nationCommandCategories = Object.keys(nationCommandTable);

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (!myGeneral) return <LoadingState />;
  if (myGeneral.officerLevel < 5)
    return (
      <div className="p-4 text-muted-foreground">관직 Lv.5 이상만 사용 가능합니다.</div>
    );
  if (loading) return <LoadingState />;
  if (error) return <div className="p-4 text-destructive">{error}</div>;

  const npcGenerals = nationGenerals.filter((g) => g.npcState > 0);
  const playerGenerals = nationGenerals.filter((g) => g.npcState === 0);

  return (
    <div className="p-4 space-y-6 max-w-3xl mx-auto">
      <PageHeader icon={Crown} title="군주 센터" />

      {/* Nation Resources */}
      {nation && (
        <Card>
          <CardHeader>
            <CardTitle>{nation.name} 국가 자원</CardTitle>
          </CardHeader>
          <CardContent>
            <ResourceDisplay gold={nation.gold} rice={nation.rice} crew={0} />
          </CardContent>
        </Card>
      )}

      {/* Nation Turn Queue */}
      <Card>
        <CardHeader>
          <CardTitle>국가 턴 예약</CardTitle>
        </CardHeader>
        <CardContent>
          {nationTurns.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              예약된 턴이 없습니다.
            </p>
          ) : (
            <div className="space-y-1">
              {nationTurns.map((t) => (
                <div
                  key={t.id}
                  className="flex items-center gap-3 rounded px-3 py-2 text-sm bg-muted"
                >
                  <span className="text-muted-foreground">#{t.turnIdx}</span>
                  <span className="font-medium">{t.actionCode}</span>
                  {t.brief && (
                    <span className="text-muted-foreground text-xs">
                      {t.brief}
                    </span>
                  )}
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Nation Commands */}
      <Card>
        <CardHeader>
          <CardTitle>국가 명령</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap gap-2">
            {nationCommandCategories.map((category) => (
              <div key={category} className="w-full">
                <div className="mb-1 text-xs text-gray-400">{category}</div>
                <div className="flex flex-wrap gap-2">
                  {(nationCommandTable[category] ?? []).map((cmd) => (
                    <Button
                      key={cmd.actionCode}
                      variant={selectedCmd?.actionCode === cmd.actionCode ? "default" : "outline"}
                      size="sm"
                      disabled={!cmd.enabled}
                      title={cmd.reason}
                      onClick={() => {
                        if (!cmd.enabled) return;
                        setSelectedCmd((prev) =>
                          prev?.actionCode === cmd.actionCode ? null : cmd,
                        );
                      }}
                    >
                      {cmd.name}
                    </Button>
                  ))}
                </div>
              </div>
            ))}
          </div>
          {selectedCmd && (
            <div className="rounded border p-4 text-sm space-y-3">
              <p className="text-muted-foreground">
                선택된 명령: <Badge>{selectedCmd.name}</Badge>
              </p>
              {currentWorld.realtimeMode && (
                <p className="text-xs text-gray-400">
                  소모: {selectedCmd.commandPointCost}CP / 실행 지연: {selectedCmd.durationSeconds}초
                </p>
              )}
              <Button
                onClick={async () => {
                  if (!myGeneral) return;
                  setExecuting(true);
                  setCmdResult(null);
                  try {
                    const { data } = await commandApi.executeNation(
                      myGeneral.id,
                      selectedCmd.actionCode,
                    );
                    setCmdResult(data);
                  } catch {
                    setCmdResult({
                      success: false,
                      logs: ["실행 중 오류가 발생했습니다."],
                    });
                  } finally {
                    setExecuting(false);
                  }
                }}
                disabled={executing}
                size="sm"
              >
                {executing ? "실행중..." : "명령 실행"}
              </Button>
              {cmdResult && (
                <div
                  className={`p-3 rounded text-sm ${cmdResult.success ? "bg-green-900/50 text-green-300" : "bg-red-900/50 text-red-300"}`}
                >
                  <Badge
                    variant={cmdResult.success ? "secondary" : "destructive"}
                    className="mb-2"
                  >
                    {cmdResult.success ? "성공" : "실패"}
                  </Badge>
                  {cmdResult.logs.map((log, i) => (
                    <p key={i}>{log}</p>
                  ))}
                </div>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Player / NPC Generals */}
      <Card>
        <CardHeader>
          <CardTitle>
            소속 장수 (플레이어 {playerGenerals.length}명 / NPC{" "}
            {npcGenerals.length}명)
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-1">
            {nationGenerals.map((g) => (
              <div
                key={g.id}
                className="flex items-center gap-3 rounded bg-muted px-3 py-2 text-sm"
              >
                <GeneralPortrait picture={g.picture} name={g.name} size="sm" />
                <span className="font-medium w-20 truncate">{g.name}</span>
                <span className="text-xs text-muted-foreground">
                  관직 Lv.{g.officerLevel}
                </span>
                <span className="text-xs text-muted-foreground">
                  병력 {g.crew.toLocaleString()}
                </span>
                {g.npcState > 0 && (
                  <Badge variant="outline" className="ml-auto">
                    NPC
                  </Badge>
                )}
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
