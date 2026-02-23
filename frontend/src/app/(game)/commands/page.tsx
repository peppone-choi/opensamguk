"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import { Swords, Crown, Clock } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { CommandPanel } from "@/components/game/command-panel";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { commandApi } from "@/lib/gameApi";
import type { NationTurn, CommandTableEntry } from "@/types";

/** Server clock display */
function ServerClock() {
  const [time, setTime] = useState("");
  useEffect(() => {
    const update = () => {
      setTime(
        new Date().toLocaleTimeString("ko-KR", {
          hour: "2-digit",
          minute: "2-digit",
          second: "2-digit",
          hour12: false,
        }),
      );
    };
    update();
    const id = setInterval(update, 1000);
    return () => clearInterval(id);
  }, []);
  return (
    <span className="flex items-center gap-1 text-xs text-muted-foreground">
      <Clock className="size-3" />
      {time}
    </span>
  );
}

const TURN_COUNT = 12;

interface NationFilledTurn {
  turnIdx: number;
  actionCode: string;
  arg: Record<string, unknown>;
  brief: string | null;
}

/** Simplified Nation Command Panel for chief-level commands */
function NationCommandPanel({
  nationId,
  generalId,
  officerLevel,
}: {
  nationId: number;
  generalId: number;
  officerLevel: number;
}) {
  const [turns, setTurns] = useState<NationTurn[]>([]);
  const [commandTable, setCommandTable] = useState<Record<string, CommandTableEntry[]>>({});
  const [loading, setLoading] = useState(true);
  const [selectedTurn, setSelectedTurn] = useState<number>(0);
  const [showSelector, setShowSelector] = useState(false);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [turnsRes, tableRes] = await Promise.all([
        commandApi.getNationReserved(nationId, officerLevel),
        commandApi.getNationCommandTable(generalId),
      ]);
      setTurns(turnsRes.data);
      setCommandTable(tableRes.data);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [nationId, generalId, officerLevel]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const filledTurns = useMemo<NationFilledTurn[]>(() => {
    const byIdx = new Map<number, NationTurn>();
    for (const t of turns) byIdx.set(t.turnIdx, t);
    return Array.from({ length: TURN_COUNT }, (_, i) => {
      const existing = byIdx.get(i);
      return {
        turnIdx: i,
        actionCode: existing?.actionCode ?? "없음",
        arg: existing?.arg ?? {},
        brief: existing?.brief ?? null,
      };
    });
  }, [turns]);

  const categories = useMemo(() => Object.keys(commandTable), [commandTable]);
  const allCommands = useMemo(
    () => Object.values(commandTable).flat(),
    [commandTable],
  );

  const handleReserve = async (actionCode: string, arg?: Record<string, unknown>) => {
    try {
      await commandApi.reserveNation(nationId, generalId, [
        { turnIdx: selectedTurn, actionCode, arg },
      ]);
      await loadData();
      setShowSelector(false);
    } catch {
      // ignore
    }
  };

  const handleClear = async (turnIdx: number) => {
    try {
      await commandApi.reserveNation(nationId, generalId, [
        { turnIdx, actionCode: "없음" },
      ]);
      await loadData();
    } catch {
      // ignore
    }
  };

  if (loading) return <LoadingState message="국가 명령 불러오는 중..." />;

  return (
    <div className="space-y-3">
      {/* Turn grid */}
      <div className="grid grid-cols-4 sm:grid-cols-6 gap-1">
        {filledTurns.map((t) => (
          <button
            key={t.turnIdx}
            type="button"
            onClick={() => {
              setSelectedTurn(t.turnIdx);
              setShowSelector(true);
            }}
            className={`relative rounded border px-2 py-2 text-left text-[11px] leading-snug transition-colors ${
              selectedTurn === t.turnIdx
                ? "border-blue-500 bg-blue-500/10"
                : "border-gray-700 bg-[#111] hover:bg-gray-900"
            }`}
          >
            <div className="flex items-center justify-between">
              <Badge variant="outline" className="text-[10px] px-1 py-0">
                {t.turnIdx + 1}턴
              </Badge>
              {t.actionCode !== "없음" && (
                <button
                  type="button"
                  className="text-gray-500 hover:text-red-400 text-[10px]"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleClear(t.turnIdx);
                  }}
                >
                  ✕
                </button>
              )}
            </div>
            <div className="mt-1 truncate font-medium">
              {t.actionCode === "없음" ? (
                <span className="text-muted-foreground">비어있음</span>
              ) : (
                t.actionCode
              )}
            </div>
            {t.brief && t.brief !== t.actionCode && (
              <div className="truncate text-muted-foreground text-[10px]">{t.brief}</div>
            )}
          </button>
        ))}
      </div>

      {/* Command selector */}
      {showSelector && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">
              {selectedTurn + 1}턴 국가 명령 선택
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {categories.map((cat) => (
              <div key={cat}>
                <p className="text-[10px] text-muted-foreground font-bold mb-1">{cat}</p>
                <div className="flex flex-wrap gap-1">
                  {commandTable[cat].map((cmd) => (
                    <Button
                      key={cmd.actionCode}
                      size="sm"
                      variant="outline"
                      className="h-7 text-[11px]"
                      disabled={!cmd.enabled}
                      onClick={() => handleReserve(cmd.actionCode)}
                    >
                      {cmd.name ?? cmd.actionCode}
                    </Button>
                  ))}
                </div>
              </div>
            ))}
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setShowSelector(false)}
            >
              닫기
            </Button>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

export default function CommandsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { nations } = useGameStore();
  const [mode, setMode] = useState<"general" | "nation">("general");

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) {
      fetchMyGeneral(currentWorld.id).catch(() => {});
    }
  }, [currentWorld, myGeneral, fetchMyGeneral]);

  const isChief = (myGeneral?.officerLevel ?? 0) >= 5;
  const myNation = nations.find((n) => n.id === myGeneral?.nationId);

  if (!currentWorld) {
    return (
      <div className="p-4">
        <EmptyState
          title="월드를 선택해주세요"
          description="명령 예약은 월드 진입 후 이용할 수 있습니다."
        />
      </div>
    );
  }

  if (!myGeneral) {
    return <LoadingState message="명령 정보를 불러오는 중..." />;
  }

  return (
    <div className="p-4 space-y-4 max-w-4xl mx-auto">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <PageHeader
          icon={mode === "nation" ? Crown : Swords}
          title={mode === "nation" ? "국가 명령 예약" : "명령 예약"}
          description={
            mode === "nation"
              ? "국가 턴 명령을 예약합니다."
              : "12턴 예약, 다중 선택, 저장 액션을 이용해 명령을 빠르게 편성합니다."
          }
        />
        <div className="flex items-center gap-2">
          <ServerClock />
          {isChief && (
            <Button
              size="sm"
              variant={mode === "nation" ? "default" : "outline"}
              onClick={() => setMode(mode === "nation" ? "general" : "nation")}
            >
              <Crown className="size-3.5 mr-1" />
              {mode === "nation" ? "장수 명령" : "국가 명령"}
            </Button>
          )}
        </div>
      </div>

      {mode === "general" ? (
        <CommandPanel
          generalId={myGeneral.id}
          realtimeMode={currentWorld.realtimeMode}
        />
      ) : (
        myGeneral.nationId > 0 && (
          <NationCommandPanel
            nationId={myGeneral.nationId}
            generalId={myGeneral.id}
            officerLevel={myGeneral.officerLevel}
          />
        )
      )}
    </div>
  );
}
