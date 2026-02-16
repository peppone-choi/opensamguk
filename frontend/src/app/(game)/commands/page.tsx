"use client";

import { useEffect, useMemo, useState, useCallback } from "react";
import { Terminal } from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { commandApi, realtimeApi } from "@/lib/gameApi";
import type { GeneralTurn, CommandResult, CommandTableEntry } from "@/types";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";

const TURN_COUNT = 12;

export default function CommandsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const fetchMyGeneral = useGeneralStore((s) => s.fetchMyGeneral);

  const [turns, setTurns] = useState<GeneralTurn[]>([]);
  const [selectedSlot, setSelectedSlot] = useState<number>(0);
  const [selectedCmd, setSelectedCmd] = useState<string>("");
  const [argText, setArgText] = useState("");
  const [results, setResults] = useState<CommandResult[]>([]);
  const [commandTable, setCommandTable] = useState<Record<string, CommandTableEntry[]>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const categories = useMemo(() => Object.keys(commandTable), [commandTable]);

  const loadData = useCallback(async () => {
    if (!myGeneral) return;
    try {
      const [turnRes, tableRes] = await Promise.all([
        commandApi.getReserved(myGeneral.id),
        commandApi.getCommandTable(myGeneral.id),
      ]);
      setTurns(turnRes.data);
      setCommandTable(tableRes.data);
    } finally {
      setLoading(false);
    }
  }, [myGeneral]);

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) {
      fetchMyGeneral(currentWorld.id).catch(() => {});
    }
  }, [currentWorld, myGeneral, fetchMyGeneral]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const getTurn = (idx: number) => turns.find((t) => t.turnIdx === idx);

  const findCommand = useCallback(
    (actionCode: string): CommandTableEntry | null => {
      for (const category of categories) {
        const found = commandTable[category]?.find((cmd) => cmd.actionCode === actionCode);
        if (found) return found;
      }
      return null;
    },
    [categories, commandTable],
  );

  const selectedCommand = selectedCmd ? findCommand(selectedCmd) : null;

  const parseArg = (): Record<string, unknown> | undefined => {
    if (!argText.trim()) return undefined;
    return JSON.parse(argText) as Record<string, unknown>;
  };

  const handleReserve = async () => {
    if (!myGeneral || !selectedCmd || currentWorld?.realtimeMode) return;
    setSaving(true);
    try {
      let parsedArg: Record<string, unknown> | undefined;
      try {
        parsedArg = parseArg();
      } catch {
        setResults((prev) => [
          { success: false, logs: ["추가 인자 JSON 형식이 올바르지 않습니다."] },
          ...prev,
        ].slice(0, 20));
        return;
      }

      await commandApi.reserve(myGeneral.id, [
        { turnIdx: selectedSlot, actionCode: selectedCmd, arg: parsedArg },
      ]);
      await loadData();
    } finally {
      setSaving(false);
    }
  };

  const handleExecute = async () => {
    if (!myGeneral || !selectedCmd) return;
    setSaving(true);
    try {
      let parsedArg: Record<string, unknown> | undefined;
      try {
        parsedArg = parseArg();
      } catch {
        setResults((prev) => [
          { success: false, logs: ["추가 인자 JSON 형식이 올바르지 않습니다."] },
          ...prev,
        ].slice(0, 20));
        return;
      }

      const { data } = currentWorld.realtimeMode
        ? await realtimeApi.execute(myGeneral.id, selectedCmd, parsedArg)
        : await commandApi.execute(myGeneral.id, selectedCmd, parsedArg);
      setResults((prev) => [data, ...prev].slice(0, 20));
      await loadData();
    } finally {
      setSaving(false);
    }
  };

  if (!currentWorld)
    return <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>;
  if (loading) return <LoadingState />;
  if (!myGeneral)
    return <div className="p-4 text-muted-foreground">장수 정보가 없습니다.</div>;

  return (
    <div className="legacy-page-wrap p-2 space-y-3">
      <PageHeader icon={Terminal} title="커맨드" />

      <Card>
        <CardHeader>
          <CardTitle>턴 예약 ({TURN_COUNT}턴)</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-4 gap-[1px] bg-gray-600">
            {Array.from({ length: TURN_COUNT }, (_, i) => {
              const turn = getTurn(i);
              return (
                <Button
                  key={i}
                  variant={selectedSlot === i ? "secondary" : "outline"}
                  className="h-auto flex-col items-start px-2 py-2 text-left"
                  onClick={() => setSelectedSlot(i)}
                  disabled={Boolean(currentWorld.realtimeMode)}
                >
                  <span className="flex w-full items-center gap-1">
                    <span className="text-xs text-gray-400">#{i + 1}</span>
                    <Badge variant="secondary" className="text-xs">
                      {turn?.actionCode ?? "휴식"}
                    </Badge>
                  </span>
                  {turn?.brief && <span className="w-full truncate text-xs text-gray-300">{turn.brief}</span>}
                </Button>
              );
            })}
          </div>
          {currentWorld.realtimeMode && (
            <p className="mt-2 text-xs text-gray-400">
              실시간 모드에서는 예턴 예약이 비활성화됩니다.
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-sm">
            {currentWorld.realtimeMode ? "실시간 명령 요청" : `슬롯 #${selectedSlot + 1} 명령 설정`}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <select
            value={selectedCmd}
            onChange={(e) => setSelectedCmd(e.target.value)}
            className="w-full border border-gray-600 bg-[#111] px-2 py-1.5 text-sm"
          >
            <option value="">-- 명령 선택 --</option>
            {categories.map((category) => (
              <optgroup key={category} label={category}>
                {(commandTable[category] ?? []).map((cmd) => (
                  <option key={cmd.actionCode} value={cmd.actionCode} disabled={!cmd.enabled}>
                    {cmd.name}
                    {currentWorld.realtimeMode
                      ? ` (${cmd.commandPointCost}CP / ${cmd.durationSeconds}s)`
                      : ""}
                    {cmd.enabled ? "" : ` - ${cmd.reason ?? "불가"}`}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>

          {selectedCommand && currentWorld.realtimeMode && (
            <div className="border border-gray-600 bg-[#111] px-2 py-1 text-xs text-gray-300">
              소모: {selectedCommand.commandPointCost}CP / 실행 지연: {selectedCommand.durationSeconds}초
            </div>
          )}

          <div className="space-y-1">
            <label className="text-xs text-muted-foreground">추가 인자 (JSON, 선택사항)</label>
            <Input
              type="text"
              value={argText}
              onChange={(e) => setArgText(e.target.value)}
              placeholder='예: {"cityId": 1}'
            />
          </div>

          <div className="flex gap-2">
            {!currentWorld.realtimeMode && (
              <Button onClick={handleReserve} disabled={saving || !selectedCmd}>
                예약 (#{selectedSlot + 1})
              </Button>
            )}
            <Button
              variant="secondary"
              onClick={handleExecute}
              disabled={saving || !selectedCmd}
            >
              {currentWorld.realtimeMode ? "실시간 실행 요청" : "즉시실행"}
            </Button>
          </div>
        </CardContent>
      </Card>

      {results.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>실행 결과</CardTitle>
          </CardHeader>
          <CardContent>
            <ScrollArea className="max-h-60">
              <div className="space-y-2">
                {results.map((r, i) => (
                  <div
                    key={i}
                    className={`p-2 text-sm ${r.success ? "bg-green-900/30" : "bg-red-900/30"}`}
                  >
                    {r.logs.map((log, j) => (
                      <p key={j}>{log}</p>
                    ))}
                    {r.message && <p className="text-muted-foreground">{r.message}</p>}
                  </div>
                ))}
              </div>
            </ScrollArea>
          </CardContent>
        </Card>
      )}

      <details className="text-sm">
        <summary className="cursor-pointer text-muted-foreground hover:text-foreground">
          전체 커맨드 목록
        </summary>
        <div className="mt-2 space-y-2">
          {categories.map((category) => (
            <div key={category}>
              <span className="text-xs text-muted-foreground">{category}:</span>
              <div className="mt-1 flex flex-wrap gap-1">
                {(commandTable[category] ?? []).map((cmd) => (
                  <Badge
                    key={cmd.actionCode}
                    variant={cmd.enabled ? "secondary" : "outline"}
                    className="cursor-pointer"
                    onClick={() => cmd.enabled && setSelectedCmd(cmd.actionCode)}
                    title={cmd.reason}
                  >
                    {cmd.name}
                  </Badge>
                ))}
              </div>
            </div>
          ))}
        </div>
      </details>
    </div>
  );
}
