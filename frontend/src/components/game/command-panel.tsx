"use client";

import { useState, useCallback, useEffect, useRef } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { commandApi, realtimeApi } from "@/lib/gameApi";
import { useWorldStore } from "@/stores/worldStore";
import { CommandSelectForm } from "@/components/game/command-select-form";
import type { GeneralTurn, CommandTableEntry, RealtimeStatus } from "@/types";

const TURN_COUNT = 12;

interface CommandPanelProps {
  generalId: number;
  realtimeMode: boolean;
}

export function CommandPanel({ generalId, realtimeMode }: CommandPanelProps) {
  const [turns, setTurns] = useState<GeneralTurn[]>([]);
  const [selectedSlots, setSelectedSlots] = useState<Set<number>>(new Set([0]));
  const [lastClickedSlot, setLastClickedSlot] = useState(0);
  const [commandTable, setCommandTable] = useState<
    Record<string, CommandTableEntry[]>
  >({});
  const [showForm, setShowForm] = useState(false);
  const [serverTime, setServerTime] = useState<string>("");
  const [realtimeStatus, setRealtimeStatus] = useState<RealtimeStatus | null>(
    null,
  );
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const timerRef = useRef<ReturnType<typeof setInterval>>(undefined);

  // Server clock
  useEffect(() => {
    const updateClock = () => {
      setServerTime(
        new Date().toLocaleTimeString("ko-KR", {
          hour: "2-digit",
          minute: "2-digit",
          second: "2-digit",
          hour12: false,
        }),
      );
    };
    updateClock();
    timerRef.current = setInterval(updateClock, 1000);
    return () => clearInterval(timerRef.current);
  }, []);

  const loadTurns = useCallback(async () => {
    try {
      const { data } = await commandApi.getReserved(generalId);
      setTurns(data);
    } catch {
      /* ignore */
    }
  }, [generalId]);

  const loadRealtimeStatus = useCallback(async () => {
    if (!realtimeMode) {
      setRealtimeStatus(null);
      return;
    }

    try {
      const { data } = await realtimeApi.getStatus(generalId);
      setRealtimeStatus(data);
    } catch {
      /* ignore */
    }
  }, [generalId, realtimeMode]);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const [turnsRes, tableRes] = await Promise.all([
          commandApi.getReserved(generalId),
          commandApi.getCommandTable(generalId),
        ]);
        if (active) {
          setTurns(turnsRes.data);
          setCommandTable(tableRes.data);
        }
        if (active && realtimeMode) {
          const statusRes = await realtimeApi.getStatus(generalId);
          setRealtimeStatus(statusRes.data);
        }
      } catch {
        /* ignore */
      }
    })();
    return () => {
      active = false;
    };
  }, [generalId, realtimeMode]);

  useEffect(() => {
    if (!realtimeMode) return;
    const id = setInterval(() => {
      void loadRealtimeStatus();
    }, 1000);
    return () => clearInterval(id);
  }, [realtimeMode, loadRealtimeStatus]);

  const getTurn = (idx: number) => turns.find((t) => t.turnIdx === idx);

  const handleSlotClick = (idx: number, e: React.MouseEvent) => {
    if (e.shiftKey && lastClickedSlot !== idx) {
      // Shift+click: range select
      const start = Math.min(lastClickedSlot, idx);
      const end = Math.max(lastClickedSlot, idx);
      const newSet = new Set<number>();
      for (let i = start; i <= end; i++) newSet.add(i);
      setSelectedSlots(newSet);
    } else if (e.ctrlKey || e.metaKey) {
      // Ctrl/Cmd+click: toggle individual
      const newSet = new Set(selectedSlots);
      if (newSet.has(idx)) {
        newSet.delete(idx);
        if (newSet.size === 0) newSet.add(idx);
      } else {
        newSet.add(idx);
      }
      setSelectedSlots(newSet);
    } else {
      // Normal click: single select
      setSelectedSlots(new Set([idx]));
      setShowForm(true);
    }
    setLastClickedSlot(idx);
  };

  const handleSelectCommand = async (
    actionCode: string,
    arg?: Record<string, unknown>,
  ) => {
    try {
      // Apply command to all selected slots
      const reservations = [...selectedSlots]
        .sort((a, b) => a - b)
        .map((slot) => ({ turnIdx: slot, actionCode, arg }));
      await commandApi.reserve(generalId, reservations);
      await loadTurns();
      setShowForm(false);
      // Auto-advance to next slot after last selected
      const maxSlot = Math.max(...selectedSlots);
      if (maxSlot < TURN_COUNT - 1) {
        setSelectedSlots(new Set([maxSlot + 1]));
        setLastClickedSlot(maxSlot + 1);
      }
    } catch {
      /* ignore */
    }
  };

  const handleRepeat = async () => {
    if (realtimeMode) return;
    try {
      await commandApi.repeatTurns(generalId, 1);
      await loadTurns();
    } catch {
      /* ignore */
    }
  };

  const handlePush = async (amount: number) => {
    if (realtimeMode) return;
    try {
      await commandApi.pushTurns(generalId, amount);
      await loadTurns();
    } catch {
      /* ignore */
    }
  };

  const handleClearSelected = async () => {
    if (realtimeMode) return;
    // Clear selected slots (set to 휴식) then pull remaining forward
    try {
      const reservations = [...selectedSlots]
        .sort((a, b) => a - b)
        .map((slot) => ({ turnIdx: slot, actionCode: "휴식" }));
      await commandApi.reserve(generalId, reservations);
      await loadTurns();
    } catch {
      /* ignore */
    }
  };

  const yearMonth = currentWorld
    ? `${currentWorld.currentYear}년 ${currentWorld.currentMonth}월`
    : "";

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle>명령 예약 ({TURN_COUNT}턴)</CardTitle>
          <div className="flex items-center gap-2">
            <span className="text-[11px] text-gray-300">{serverTime}</span>
            {yearMonth && (
              <span className="text-[11px] text-gray-300">{yearMonth}</span>
            )}
            {realtimeMode && realtimeStatus && (
              <span className="text-[11px] text-cyan-300">
                CP {realtimeStatus.commandPoints} / 대기{" "}
                {realtimeStatus.remainingSeconds}s
              </span>
            )}
          </div>
        </div>
        {/* Action bar */}
        <div className="mt-1 flex items-center gap-1">
          {!realtimeMode && (
            <>
              <Button
                variant="outline"
                size="sm"
                onClick={handleRepeat}
                title="반복"
              >
                반복
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => handlePush(1)}
                title="밀기"
              >
                밀기
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => handlePush(-1)}
                title="당기기"
              >
                당기기
              </Button>
              {selectedSlots.size > 1 && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleClearSelected}
                  title="선택 비우기"
                  className="text-red-400"
                >
                  선택 비움
                </Button>
              )}
            </>
          )}
          <span className="ml-auto text-[10px] text-gray-400">
            {realtimeMode
              ? "실시간 모드: 예턴 사용 불가"
              : selectedSlots.size > 1
                ? `${selectedSlots.size}개 선택`
                : "Shift+클릭: 범위선택"}
          </span>
        </div>
      </CardHeader>
      <CardContent className="space-y-2">
        {/* Turn slot vertical list */}
        <div className="space-y-[1px] bg-gray-600">
          {Array.from({ length: TURN_COUNT }, (_, i) => {
            const turn = getTurn(i);
            const isSelected = selectedSlots.has(i);
            const actionCode = turn?.actionCode ?? "휴식";
            const brief = turn?.brief;
            const isRest = actionCode === "휴식";
            return (
              <button
                key={i}
                onClick={(e) => handleSlotClick(i, e)}
                className={`flex w-full items-center gap-2 px-2 py-1.5 text-left text-xs transition-colors ${
                  isSelected
                    ? "bg-[#141c65] text-white"
                    : "bg-[#111] hover:bg-[#191919]"
                }`}
              >
                <span className="w-6 shrink-0 tabular-nums text-gray-400">
                  #{i + 1}
                </span>
                <span
                  className={`shrink-0 border px-1 py-0 text-[10px] ${
                    isRest
                      ? "border-gray-600 text-gray-400"
                      : "border-cyan-700 text-cyan-300"
                  }`}
                >
                  {actionCode}
                </span>
                {brief && (
                  <span className="flex-1 truncate text-gray-300">{brief}</span>
                )}
              </button>
            );
          })}
        </div>

        {/* Command selection form */}
        {showForm && (
          <CommandSelectForm
            commandTable={commandTable}
            onSelect={handleSelectCommand}
            onCancel={() => setShowForm(false)}
            realtimeMode={realtimeMode}
            generalId={generalId}
          />
        )}
      </CardContent>
    </Card>
  );
}
