"use client";

import { useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { commandApi, realtimeApi } from "@/lib/gameApi";
import {
  CommandArgForm,
  COMMAND_ARGS,
} from "@/components/game/command-arg-form";
import type { CommandTableEntry, CommandResult } from "@/types";

interface CommandSelectFormProps {
  commandTable: Record<string, CommandTableEntry[]>;
  onSelect: (actionCode: string, arg?: Record<string, unknown>) => void;
  onCancel: () => void;
  realtimeMode: boolean;
  generalId: number;
}

export function CommandSelectForm({
  commandTable,
  onSelect,
  onCancel,
  realtimeMode,
  generalId,
}: CommandSelectFormProps) {
  const [selectedCmd, setSelectedCmd] = useState("");
  const [pendingArg, setPendingArg] = useState<
    Record<string, unknown> | undefined
  >();
  const [result, setResult] = useState<CommandResult | null>(null);
  const [executing, setExecuting] = useState(false);

  const categories = Object.keys(commandTable);
  const hasArgForm = !!(selectedCmd && COMMAND_ARGS[selectedCmd]);

  const handleReserve = (arg?: Record<string, unknown>) => {
    if (!selectedCmd) return;
    onSelect(selectedCmd, arg);
  };

  const handleExecute = async (arg?: Record<string, unknown>) => {
    if (!selectedCmd) return;
    setExecuting(true);
    try {
      const { data } = realtimeMode
        ? await realtimeApi.execute(generalId, selectedCmd, arg)
        : await commandApi.execute(generalId, selectedCmd, arg);
      setResult(data);
    } catch {
      /* ignore */
    } finally {
      setExecuting(false);
    }
  };

  const handleArgSubmit = (arg: Record<string, unknown>) => {
    setPendingArg(arg);
  };

  const handleSelectCmd = (actionCode: string) => {
    setSelectedCmd(actionCode);
    setPendingArg(undefined);
    setResult(null);
  };

  return (
    <Card className="border-amber-400/30">
      <CardContent className="space-y-3 pt-3">
        <Tabs defaultValue={categories[0] ?? ""}>
          <TabsList className="flex-wrap h-auto">
            {categories.map((cat) => (
              <TabsTrigger key={cat} value={cat} className="text-xs">
                {cat}
              </TabsTrigger>
            ))}
          </TabsList>
          {categories.map((cat) => (
            <TabsContent key={cat} value={cat}>
              <div className="flex flex-wrap gap-1">
                {commandTable[cat].map((cmd) => (
                  <Badge
                    key={cmd.actionCode}
                    variant={
                      selectedCmd === cmd.actionCode ? "default" : "secondary"
                    }
                    className={`cursor-pointer text-xs ${
                      !cmd.enabled ? "opacity-40 cursor-not-allowed" : ""
                    }`}
                    onClick={() => {
                      if (cmd.enabled) handleSelectCmd(cmd.actionCode);
                    }}
                    title={cmd.reason ?? undefined}
                  >
                    {cmd.name}
                    {realtimeMode && (
                      <span className="ml-1 text-[10px] text-gray-300">
                        ({cmd.commandPointCost}CP/{cmd.durationSeconds}s)
                      </span>
                    )}
                  </Badge>
                ))}
              </div>
            </TabsContent>
          ))}
        </Tabs>

        {selectedCmd && (
          <>
            {hasArgForm && (
              <CommandArgForm
                actionCode={selectedCmd}
                onSubmit={handleArgSubmit}
              />
            )}

            <div className="flex gap-2">
              {!realtimeMode && (
                <Button
                  size="sm"
                  onClick={() => handleReserve(pendingArg)}
                  disabled={hasArgForm && !pendingArg}
                >
                  예약
                </Button>
              )}
              <Button
                size="sm"
                variant="secondary"
                onClick={() => handleExecute(pendingArg)}
                disabled={executing || (hasArgForm && !pendingArg)}
              >
                {executing
                  ? "요청중..."
                  : realtimeMode
                    ? "실시간 실행 요청"
                    : "즉시실행"}
              </Button>
              <Button size="sm" variant="ghost" onClick={onCancel}>
                취소
              </Button>
            </div>
            {realtimeMode && (
              <p className="text-[11px] text-gray-400">
                실시간 모드에서는 예턴 예약이 비활성화되며, 커맨드 포인트를
                소모해 실행 요청 후 지연시간이 지나면 자동 실행됩니다.
              </p>
            )}
          </>
        )}

        {result && (
          <div
            className={`text-xs p-2 rounded ${
              result.success ? "bg-green-900/30" : "bg-red-900/30"
            }`}
          >
            {result.logs.map((log, i) => (
              <p key={i}>{log}</p>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
