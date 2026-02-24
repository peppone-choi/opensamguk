"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
  type DragEvent,
} from "react";
import { useRouter } from "next/navigation";
import { Clock3, Copy, Pencil, Trash2, GripVertical, ClipboardCopy } from "lucide-react";
import { useHotkeys } from "@/hooks/useHotkeys";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { commandApi, realtimeApi } from "@/lib/gameApi";
import { useWorldStore } from "@/stores/worldStore";
import { COMMAND_ARGS } from "@/components/game/command-arg-form";
import { CommandSelectForm } from "@/components/game/command-select-form";
import type { CommandTableEntry, GeneralTurn, RealtimeStatus } from "@/types";

const TURN_COUNT = 12;

interface CommandPanelProps {
  generalId: number;
  realtimeMode: boolean;
}

interface ClipboardItem {
  offset: number;
  actionCode: string;
  arg?: Record<string, unknown>;
  brief?: string | null;
}

interface StoredAction {
  name: string;
  items: ClipboardItem[];
}

interface FilledTurn {
  turnIdx: number;
  actionCode: string;
  arg: Record<string, unknown>;
  brief: string | null;
}

function formatCountdown(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

function turnTargetText(turn: FilledTurn): string {
  if (turn.brief && turn.brief !== turn.actionCode) {
    return turn.brief;
  }

  const arg = turn.arg ?? {};
  const values = [
    arg.destCityId,
    arg.cityId,
    arg.destGeneralID,
    arg.destNationId,
    arg.amount,
  ].filter((value) => value !== undefined && value !== null);

  return values.length > 0
    ? values.map((value) => String(value)).join(" / ")
    : "-";
}

export function CommandPanel({ generalId, realtimeMode }: CommandPanelProps) {
  const router = useRouter();
  const currentWorld = useWorldStore((s) => s.currentWorld);

  const [turns, setTurns] = useState<GeneralTurn[]>([]);
  const [commandTable, setCommandTable] = useState<
    Record<string, CommandTableEntry[]>
  >({});
  const [selectedTurns, setSelectedTurns] = useState<Set<number>>(new Set([0]));
  const [lastClickedTurn, setLastClickedTurn] = useState(0);
  const [showSelector, setShowSelector] = useState(false);
  const [realtimeStatus, setRealtimeStatus] = useState<RealtimeStatus | null>(
    null,
  );
  const [serverClock, setServerClock] = useState("");
  const [remaining, setRemaining] = useState(0);
  const [clipboard, setClipboard] = useState<ClipboardItem[] | null>(null);
  const [storedActions, setStoredActions] = useState<StoredAction[]>([]);
  const [selectedStoredAction, setSelectedStoredAction] = useState("");
  const [recentActions, setRecentActions] = useState<{ actionCode: string; arg?: Record<string, unknown>; brief?: string }[]>([]);

  const lastTurnRef = useRef(0);

  const filledTurns = useMemo<FilledTurn[]>(() => {
    const byIndex = new Map<number, GeneralTurn>();
    for (const turn of turns) {
      byIndex.set(turn.turnIdx, turn);
    }

    return Array.from({ length: TURN_COUNT }, (_, turnIdx) => {
      const existing = byIndex.get(turnIdx);
      return {
        turnIdx,
        actionCode: existing?.actionCode ?? "휴식",
        arg: existing?.arg ?? {},
        brief: existing?.brief ?? null,
      };
    });
  }, [turns]);

  const selectedTurnList = useMemo(
    () => Array.from(selectedTurns).sort((a, b) => a - b),
    [selectedTurns],
  );

  const selectedCount = selectedTurnList.length;

  const localStorageKey = `opensam:stored-actions:${generalId}`;
  const recentActionsKey = `opensam:recent-actions:${generalId}`;
  const turnSignature = `${currentWorld?.currentYear ?? 0}-${currentWorld?.currentMonth ?? 0}`;

  useEffect(() => {
    const updateClock = () => {
      setServerClock(
        new Date().toLocaleTimeString("ko-KR", {
          hour: "2-digit",
          minute: "2-digit",
          second: "2-digit",
          hour12: false,
        }),
      );
    };

    updateClock();
    const intervalId = window.setInterval(updateClock, 1000);
    return () => window.clearInterval(intervalId);
  }, []);

  useEffect(() => {
    void turnSignature;
    lastTurnRef.current = Date.now();
  }, [turnSignature]);

  useEffect(() => {
    if (!currentWorld?.tickSeconds || currentWorld.tickSeconds <= 0) {
      window.setTimeout(() => setRemaining(0), 0);
      return;
    }

    const tickMs = currentWorld.tickSeconds * 1000;
    const update = () => {
      const elapsed = Date.now() - lastTurnRef.current;
      const remainMs = Math.max(0, tickMs - elapsed);
      setRemaining(Math.ceil(remainMs / 1000));
    };

    update();
    const intervalId = window.setInterval(update, 1000);
    return () => window.clearInterval(intervalId);
  }, [currentWorld?.tickSeconds]);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const raw = window.localStorage.getItem(localStorageKey);
    if (!raw) {
      window.setTimeout(() => setStoredActions([]), 0);
      return;
    }

    try {
      const parsed = JSON.parse(raw) as StoredAction[];
      window.setTimeout(
        () => setStoredActions(Array.isArray(parsed) ? parsed : []),
        0,
      );
    } catch {
      window.setTimeout(() => setStoredActions([]), 0);
    }
  }, [localStorageKey]);

  // Load recent actions from localStorage
  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      const raw = window.localStorage.getItem(recentActionsKey);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) setRecentActions(parsed);
      }
    } catch { /* ignore */ }
  }, [recentActionsKey]);

  const pushRecentAction = useCallback(
    (actionCode: string, arg?: Record<string, unknown>, brief?: string) => {
      setRecentActions((prev) => {
        const key = JSON.stringify([actionCode, arg ?? {}]);
        const filtered = prev.filter((a) => JSON.stringify([a.actionCode, a.arg ?? {}]) !== key);
        const next = [{ actionCode, arg, brief }, ...filtered].slice(0, 10);
        if (typeof window !== "undefined") {
          window.localStorage.setItem(recentActionsKey, JSON.stringify(next));
        }
        return next;
      });
    },
    [recentActionsKey],
  );

  const persistStoredActions = useCallback(
    (items: StoredAction[]) => {
      setStoredActions(items);
      if (typeof window !== "undefined") {
        window.localStorage.setItem(localStorageKey, JSON.stringify(items));
      }
    },
    [localStorageKey],
  );

  const loadTurns = useCallback(async () => {
    const { data } = await commandApi.getReservedCommands(generalId);
    setTurns(data);
  }, [generalId]);

  const loadCommandTable = useCallback(async () => {
    const { data } = await commandApi.getCommandTable(generalId);
    setCommandTable(data);
  }, [generalId]);

  const loadRealtimeStatus = useCallback(async () => {
    if (!realtimeMode) {
      setRealtimeStatus(null);
      return;
    }
    const { data } = await realtimeApi.getStatus(generalId);
    setRealtimeStatus(data);
  }, [generalId, realtimeMode]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void Promise.all([loadTurns(), loadCommandTable(), loadRealtimeStatus()]);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [loadTurns, loadCommandTable, loadRealtimeStatus]);

  useEffect(() => {
    if (!realtimeMode) return;
    const intervalId = window.setInterval(() => {
      void loadRealtimeStatus();
    }, 1000);
    return () => window.clearInterval(intervalId);
  }, [realtimeMode, loadRealtimeStatus]);

  const applyToTurns = useCallback(
    async (
      turnList: number[],
      actionCode: string,
      arg?: Record<string, unknown>,
    ) => {
      await Promise.all(
        turnList.map((turn) =>
          commandApi.reserveCommand(generalId, {
            turn,
            command: actionCode,
            arg,
          }),
        ),
      );
      await loadTurns();
    },
    [generalId, loadTurns],
  );

  const clearTurns = useCallback(
    async (turnList: number[]) => {
      await Promise.all(
        turnList.map((turn) =>
          commandApi.deleteReservedCommand(generalId, turn),
        ),
      );
      await loadTurns();
    },
    [generalId, loadTurns],
  );

  // Erase selected turns and pull subsequent turns forward
  const eraseAndPull = useCallback(async () => {
    if (selectedTurnList.length === 0 || realtimeMode) return;
    const sortedSelected = [...selectedTurnList].sort((a, b) => a - b);
    const minTurn = sortedSelected[0];
    const selectedSet = new Set(sortedSelected);
    // Collect non-selected turns after minTurn, in order
    const remaining: FilledTurn[] = [];
    for (let i = 0; i < TURN_COUNT; i++) {
      if (i < minTurn || selectedSet.has(i)) continue;
      remaining.push(filledTurns[i]);
    }
    // Place remaining turns starting at minTurn, fill rest with empty
    const ops: Promise<unknown>[] = [];
    for (let i = minTurn; i < TURN_COUNT; i++) {
      const rIdx = i - minTurn;
      if (rIdx < remaining.length && remaining[rIdx].actionCode !== "휴식") {
        ops.push(commandApi.reserveCommand(generalId, {
          turn: i,
          command: remaining[rIdx].actionCode,
          arg: remaining[rIdx].arg,
        }));
      } else {
        ops.push(commandApi.deleteReservedCommand(generalId, i));
      }
    }
    await Promise.all(ops);
    await loadTurns();
  }, [filledTurns, generalId, loadTurns, realtimeMode, selectedTurnList]);

  // Repeat-fill: copy selected turn's command to all subsequent empty turns
  const repeatFillDown = useCallback(async () => {
    if (selectedTurnList.length !== 1 || realtimeMode) return;
    const srcIdx = selectedTurnList[0];
    const src = filledTurns[srcIdx];
    if (src.actionCode === "휴식") return;

    const ops: Promise<unknown>[] = [];
    for (let i = srcIdx + 1; i < TURN_COUNT; i++) {
      if (filledTurns[i].actionCode === "휴식") {
        ops.push(
          commandApi.reserveCommand(generalId, {
            turn: i,
            command: src.actionCode,
            arg: src.arg,
          }),
        );
      }
    }
    if (ops.length > 0) {
      await Promise.all(ops);
      await loadTurns();
    }
  }, [filledTurns, generalId, loadTurns, realtimeMode, selectedTurnList]);

  // Push empty slots at selected positions, shifting existing commands back
  const pushEmpty = useCallback(async () => {
    if (selectedTurnList.length === 0 || realtimeMode) return;
    const sortedSelected = [...selectedTurnList].sort((a, b) => a - b);
    const minTurn = sortedSelected[0];
    const insertCount = sortedSelected.length;
    // Collect all turns from minTurn that are not empty
    const existing: FilledTurn[] = [];
    for (let i = minTurn; i < TURN_COUNT; i++) {
      existing.push(filledTurns[i]);
    }
    // New layout: insertCount empty slots, then existing turns (truncated to fit)
    const ops: Promise<unknown>[] = [];
    for (let i = minTurn; i < TURN_COUNT; i++) {
      const offset = i - minTurn;
      if (offset < insertCount) {
        ops.push(commandApi.deleteReservedCommand(generalId, i));
      } else {
        const srcIdx = offset - insertCount;
        if (srcIdx < existing.length && existing[srcIdx].actionCode !== "휴식") {
          ops.push(commandApi.reserveCommand(generalId, {
            turn: i,
            command: existing[srcIdx].actionCode,
            arg: existing[srcIdx].arg,
          }));
        } else {
          ops.push(commandApi.deleteReservedCommand(generalId, i));
        }
      }
    }
    await Promise.all(ops);
    await loadTurns();
  }, [filledTurns, generalId, loadTurns, realtimeMode, selectedTurnList]);

  // --- Drag & Drop reorder ---
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const [dragOver, setDragOver] = useState<number | null>(null);

  const handleDragStart = useCallback((turnIdx: number, e: DragEvent<HTMLDivElement>) => {
    if (realtimeMode) { e.preventDefault(); return; }
    setDragFrom(turnIdx);
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", String(turnIdx));
  }, [realtimeMode]);

  const handleDragOver = useCallback((turnIdx: number, e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    setDragOver(turnIdx);
  }, []);

  const handleDragLeave = useCallback(() => {
    setDragOver(null);
  }, []);

  const handleDrop = useCallback(async (targetIdx: number, e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragOver(null);
    const fromIdx = dragFrom;
    setDragFrom(null);
    if (fromIdx === null || fromIdx === targetIdx) return;

    // Reorder: remove fromIdx, insert at targetIdx
    const ordered = [...filledTurns];
    const [moved] = ordered.splice(fromIdx, 1);
    ordered.splice(targetIdx, 0, moved);

    // Save new order
    const ops: Promise<unknown>[] = [];
    const minIdx = Math.min(fromIdx, targetIdx);
    const maxIdx = Math.max(fromIdx, targetIdx);
    for (let i = minIdx; i <= maxIdx; i++) {
      if (ordered[i].actionCode === "휴식") {
        ops.push(commandApi.deleteReservedCommand(generalId, i));
      } else {
        ops.push(commandApi.reserveCommand(generalId, {
          turn: i,
          command: ordered[i].actionCode,
          arg: ordered[i].arg,
        }));
      }
    }
    await Promise.all(ops);
    await loadTurns();
    setSelectedTurns(new Set([targetIdx]));
    setLastClickedTurn(targetIdx);
  }, [dragFrom, filledTurns, generalId, loadTurns]);

  const handleDragEnd = useCallback(() => {
    setDragFrom(null);
    setDragOver(null);
  }, []);

  // --- System clipboard text copy ---
  const copySelectedAsText = useCallback(() => {
    if (selectedTurnList.length === 0) return;
    const lines = selectedTurnList.map((idx) => {
      const turn = filledTurns[idx];
      const brief = turn.brief?.replace(/<[^>]*>/g, "") ?? turn.actionCode;
      return `${idx + 1}턴 ${brief}`;
    });
    void navigator.clipboard.writeText(lines.join("\n"));
  }, [filledTurns, selectedTurnList]);

  const handleTurnClick = (
    turnIdx: number,
    event: ReactMouseEvent<HTMLButtonElement>,
  ) => {
    if (event.shiftKey) {
      const min = Math.min(lastClickedTurn, turnIdx);
      const max = Math.max(lastClickedTurn, turnIdx);
      const next = new Set<number>();
      for (let idx = min; idx <= max; idx += 1) {
        next.add(idx);
      }
      setSelectedTurns(next);
      setLastClickedTurn(turnIdx);
      return;
    }

    if (event.ctrlKey || event.metaKey) {
      const next = new Set(selectedTurns);
      if (next.has(turnIdx)) {
        next.delete(turnIdx);
      } else {
        next.add(turnIdx);
      }

      if (next.size === 0) {
        next.add(turnIdx);
      }

      setSelectedTurns(next);
      setLastClickedTurn(turnIdx);
      return;
    }

    setSelectedTurns(new Set([turnIdx]));
    setLastClickedTurn(turnIdx);

    const clicked = filledTurns[turnIdx];
    if (clicked.actionCode === "휴식" && !realtimeMode) {
      setShowSelector(true);
    }
  };

  const copySelected = useCallback(() => {
    if (selectedTurnList.length === 0) return;
    const min = selectedTurnList[0];
    const items = selectedTurnList.map((turnIdx) => {
      const turn = filledTurns[turnIdx];
      return {
        offset: turnIdx - min,
        actionCode: turn.actionCode,
        arg: turn.arg,
        brief: turn.brief,
      };
    });
    setClipboard(items);
  }, [filledTurns, selectedTurnList]);

  const pasteClipboard = useCallback(async () => {
    if (!clipboard || selectedTurnList.length === 0 || realtimeMode) return;

    const anchor = selectedTurnList[0];
    const validItems = clipboard
      .map((item) => ({ ...item, target: anchor + item.offset }))
      .filter((item) => item.target >= 0 && item.target < TURN_COUNT);

    if (validItems.length === 0) return;

    await Promise.all(
      validItems.map((item) =>
        item.actionCode === "휴식"
          ? commandApi.deleteReservedCommand(generalId, item.target)
          : commandApi.reserveCommand(generalId, {
              turn: item.target,
              command: item.actionCode,
              arg: item.arg,
            }),
      ),
    );
    await loadTurns();
  }, [clipboard, generalId, loadTurns, realtimeMode, selectedTurnList]);

  const saveStoredAction = () => {
    if (selectedTurnList.length === 0) return;
    const min = selectedTurnList[0];
    const items = selectedTurnList.map((turnIdx) => {
      const turn = filledTurns[turnIdx];
      return {
        offset: turnIdx - min,
        actionCode: turn.actionCode,
        arg: turn.arg,
        brief: turn.brief,
      };
    });

    const defaultName = items
      .map((item) =>
        item.actionCode === "휴식" ? "휴" : item.actionCode.charAt(0),
      )
      .join("");
    const input = window.prompt("저장 액션 이름", defaultName);
    if (!input) return;

    const trimmedName = input.trim();
    if (!trimmedName) return;

    const deduped = storedActions.filter(
      (action) => action.name !== trimmedName,
    );
    persistStoredActions([...deduped, { name: trimmedName, items }]);
    setSelectedStoredAction(trimmedName);
  };

  const loadStoredAction = async () => {
    if (!selectedStoredAction || selectedTurnList.length === 0 || realtimeMode)
      return;
    const stored = storedActions.find(
      (entry) => entry.name === selectedStoredAction,
    );
    if (!stored) return;

    const anchor = selectedTurnList[0];
    const validItems = stored.items
      .map((item) => ({ ...item, target: anchor + item.offset }))
      .filter((item) => item.target >= 0 && item.target < TURN_COUNT);

    await Promise.all(
      validItems.map((item) =>
        item.actionCode === "휴식"
          ? commandApi.deleteReservedCommand(generalId, item.target)
          : commandApi.reserveCommand(generalId, {
              turn: item.target,
              command: item.actionCode,
              arg: item.arg,
            }),
      ),
    );
    await loadTurns();
  };

  const deleteStoredAction = () => {
    if (!selectedStoredAction) return;
    const next = storedActions.filter(
      (item) => item.name !== selectedStoredAction,
    );
    persistStoredActions(next);
    setSelectedStoredAction("");
  };

  const handleCommandSelect = async (
    actionCode: string,
    arg?: Record<string, unknown>,
  ) => {
    const targets = selectedTurnList.length > 0 ? selectedTurnList : [0];
    if (COMMAND_ARGS[actionCode]) {
      router.push(
        `/processing?command=${encodeURIComponent(actionCode)}&turnList=${targets.join(",")}`,
      );
      setShowSelector(false);
      return;
    }

    pushRecentAction(actionCode, arg);
    await applyToTurns(targets, actionCode, arg);
    setShowSelector(false);
  };

  // Keyboard shortcuts: 1-9 to select turns, Escape to close, Delete to clear
  useHotkeys([
    ...Array.from({ length: 9 }, (_, i) => ({
      key: String(i + 1),
      handler: () => {
        if (i < TURN_COUNT) {
          setSelectedTurns(new Set([i]));
          setLastClickedTurn(i);
        }
      },
      description: `Select turn ${i + 1}`,
    })),
    { key: "0", handler: () => { setSelectedTurns(new Set([9])); setLastClickedTurn(9); }, description: "Select turn 10" },
    { key: "-", handler: () => { setSelectedTurns(new Set([10])); setLastClickedTurn(10); }, description: "Select turn 11" },
    { key: "=", handler: () => { setSelectedTurns(new Set([11])); setLastClickedTurn(11); }, description: "Select turn 12" },
    { key: "Escape", handler: () => { if (showSelector) setShowSelector(false); }, description: "Close command selector" },
    { key: "Enter", handler: () => { if (!showSelector && !realtimeMode) setShowSelector(true); }, description: "Open command selector" },
    { key: "Delete", handler: () => { if (selectedTurnList.length > 0 && !realtimeMode) clearTurns(selectedTurnList); }, description: "Clear selected turns" },
    { key: "c", ctrl: true, handler: copySelected, description: "Copy selected turns" },
    { key: "v", ctrl: true, handler: () => { void pasteClipboard(); }, description: "Paste turns", preventDefault: true },
  ]);

  return (
    <Card className="border-gray-700">
      <CardHeader className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle className="text-base">12턴 예약 편집</CardTitle>
          <div className="flex items-center gap-2 text-xs text-gray-300">
            <Clock3 className="size-3.5 text-amber-300" />
            <span>{serverClock}</span>
            {currentWorld?.tickSeconds ? (
              <Badge variant="secondary" className="text-[11px]">
                다음 턴 {formatCountdown(remaining)}
              </Badge>
            ) : null}
            {realtimeMode && realtimeStatus ? (
              <Badge variant="outline" className="text-[11px] text-cyan-300">
                CP {realtimeStatus.commandPoints} / 대기{" "}
                {realtimeStatus.remainingSeconds}s
              </Badge>
            ) : null}
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          <Button
            size="sm"
            variant="outline"
            disabled={realtimeMode}
            onClick={() => setShowSelector(true)}
          >
            선택 채우기
          </Button>
          <Button size="sm" variant="outline" onClick={copySelected}>
            복사
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={!clipboard || realtimeMode}
            onClick={() => void pasteClipboard()}
          >
            붙여넣기
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="text-red-300"
            disabled={realtimeMode}
            onClick={() => void clearTurns(selectedTurnList)}
          >
            선택 비우기
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={realtimeMode || selectedTurnList.length === 0}
            onClick={() => void eraseAndPull()}
          >
            지우고 당기기
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={realtimeMode || selectedTurnList.length === 0}
            onClick={() => void pushEmpty()}
          >
            뒤로 밀기
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={realtimeMode || selectedTurnList.length !== 1}
            title="선택한 턴의 명령을 이후 빈 턴에 반복 채우기"
            onClick={() => void repeatFillDown()}
          >
            반복채우기
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={selectedTurnList.length === 0}
            onClick={copySelectedAsText}
            title="선택한 턴을 텍스트로 클립보드에 복사"
          >
            <ClipboardCopy className="size-3 mr-1" />
            텍스트 복사
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={realtimeMode}
            onClick={saveStoredAction}
          >
            보관
          </Button>

          <select
            value={selectedStoredAction}
            onChange={(event) => setSelectedStoredAction(event.target.value)}
            className="h-8 min-w-[140px] rounded-md border border-input bg-background px-2 text-xs"
          >
            <option value="">저장 액션 선택</option>
            {storedActions.map((item) => (
              <option key={item.name} value={item.name}>
                {item.name}
              </option>
            ))}
          </select>
          <Button
            size="sm"
            variant="outline"
            disabled={!selectedStoredAction || realtimeMode}
            onClick={() => void loadStoredAction()}
          >
            불러오기
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="text-red-300"
            disabled={!selectedStoredAction}
            onClick={deleteStoredAction}
          >
            삭제
          </Button>

          {/* Range selection helpers */}
          <span className="text-[10px] text-muted-foreground">|</span>
          <Button
            size="sm"
            variant="ghost"
            className="h-6 text-[10px] px-1.5"
            onClick={() => {
              const s = new Set<number>();
              for (let i = 0; i < TURN_COUNT; i += 2) s.add(i);
              setSelectedTurns(s);
            }}
          >
            홀수턴
          </Button>
          <Button
            size="sm"
            variant="ghost"
            className="h-6 text-[10px] px-1.5"
            onClick={() => {
              const s = new Set<number>();
              for (let i = 1; i < TURN_COUNT; i += 2) s.add(i);
              setSelectedTurns(s);
            }}
          >
            짝수턴
          </Button>
          <Button
            size="sm"
            variant="ghost"
            className="h-6 text-[10px] px-1.5"
            onClick={() => {
              const s = new Set<number>();
              for (let i = 0; i < TURN_COUNT; i++) s.add(i);
              setSelectedTurns(s);
            }}
          >
            전체
          </Button>

          <div className="ml-auto text-[11px] text-gray-400">
            {selectedCount}개 선택 · Shift 범위 · Ctrl 다중 · 드래그 순서변경
          </div>
        </div>
      </CardHeader>

      <CardContent>
        <div className="overflow-hidden rounded-md border border-gray-700">
          <div className="grid grid-cols-[24px_68px_120px_1fr_136px] bg-[#1a1a1a] px-2 py-1.5 text-[11px] text-gray-400">
            <div />
            <div>턴</div>
            <div>명령</div>
            <div>대상/상세</div>
            <div className="text-right">작업</div>
          </div>

          {filledTurns.map((turn) => {
            const isSelected = selectedTurns.has(turn.turnIdx);
            const isEmpty = turn.actionCode === "휴식";
            const isDragTarget = dragOver === turn.turnIdx && dragFrom !== null && dragFrom !== turn.turnIdx;

            return (
              <div
                key={turn.turnIdx}
                className={`grid grid-cols-[24px_68px_120px_1fr_136px] items-center border-t border-gray-800 text-xs transition-colors ${
                  isDragTarget
                    ? "bg-[#1a3a2a] border-t-2 border-t-emerald-500"
                    : isSelected
                      ? "bg-[#18224b]"
                      : "bg-[#101010] hover:bg-[#171717]"
                } ${dragFrom === turn.turnIdx ? "opacity-40" : ""}`}
                onDragOver={(e) => handleDragOver(turn.turnIdx, e)}
                onDragLeave={handleDragLeave}
                onDrop={(e) => void handleDrop(turn.turnIdx, e)}
              >
                <div
                  draggable={!realtimeMode}
                  onDragStart={(e) => handleDragStart(turn.turnIdx, e)}
                  onDragEnd={handleDragEnd}
                  className="flex items-center justify-center cursor-grab active:cursor-grabbing h-full text-gray-500 hover:text-gray-300"
                  title="드래그하여 순서 변경"
                >
                  <GripVertical className="size-3.5" />
                </div>
                <button
                  type="button"
                  onClick={(event) => handleTurnClick(turn.turnIdx, event)}
                  className="contents cursor-pointer"
                >
                <div className="font-mono text-gray-300 px-2 py-2">턴 {turn.turnIdx}</div>
                <div>
                  {isEmpty ? (
                    <Badge
                      variant="outline"
                      className="border-gray-600 text-gray-400"
                    >
                      빈 턴
                    </Badge>
                  ) : (
                    <Badge variant="secondary" className="text-cyan-200">
                      {turn.actionCode}
                    </Badge>
                  )}
                </div>
                <div className="truncate text-gray-300">
                  {isEmpty ? "명령 없음" : turnTargetText(turn)}
                </div>
                <div className="flex items-center justify-end gap-1">
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-7 w-7 p-0"
                    onClick={(event) => {
                      event.stopPropagation();
                      if (realtimeMode) return;
                      setSelectedTurns(new Set([turn.turnIdx]));
                      setLastClickedTurn(turn.turnIdx);
                      setShowSelector(true);
                    }}
                  >
                    <Pencil className="size-3.5" />
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-7 w-7 p-0 text-red-300"
                    onClick={(event) => {
                      event.stopPropagation();
                      if (realtimeMode) return;
                      void clearTurns([turn.turnIdx]);
                    }}
                  >
                    <Trash2 className="size-3.5" />
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-7 w-7 p-0"
                    onClick={(event) => {
                      event.stopPropagation();
                      setClipboard([
                        {
                          offset: 0,
                          actionCode: turn.actionCode,
                          arg: turn.arg,
                          brief: turn.brief,
                        },
                      ]);
                    }}
                  >
                    <Copy className="size-3.5" />
                  </Button>
                </div>
                </button>
              </div>
            );
          })}
        </div>

        {/* Recent actions quick-apply bar */}
        {recentActions.length > 0 && !realtimeMode && (
          <div className="space-y-1">
            <p className="text-[10px] text-muted-foreground font-medium">최근 사용 명령</p>
            <div className="flex flex-wrap gap-1">
              {recentActions.map((ra, idx) => (
                <Button
                  key={idx}
                  size="sm"
                  variant="secondary"
                  className="h-6 text-[10px] px-2"
                  disabled={selectedTurnList.length === 0}
                  onClick={() => {
                    const targets = selectedTurnList.length > 0 ? selectedTurnList : [0];
                    if (COMMAND_ARGS[ra.actionCode]) {
                      router.push(
                        `/processing?command=${encodeURIComponent(ra.actionCode)}&turnList=${targets.join(",")}`,
                      );
                    } else {
                      void applyToTurns(targets, ra.actionCode, ra.arg);
                    }
                  }}
                  title={ra.brief ?? ra.actionCode}
                >
                  {ra.actionCode}
                  {ra.brief && ra.brief !== ra.actionCode ? ` (${ra.brief})` : ""}
                </Button>
              ))}
            </div>
          </div>
        )}

        {showSelector && (
          <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/60 p-4">
            <div className="w-full max-w-2xl rounded-md border border-gray-700 bg-background shadow-xl">
              <div className="flex items-center justify-between border-b border-gray-700 px-4 py-2">
                <p className="text-sm font-semibold text-gray-100">
                  명령 선택 ({selectedTurnList.join(", ")})
                </p>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setShowSelector(false)}
                >
                  닫기
                </Button>
              </div>
              <div className="p-3">
                <CommandSelectForm
                  commandTable={commandTable}
                  onSelect={(actionCode, arg) => {
                    void handleCommandSelect(actionCode, arg);
                  }}
                  onCancel={() => setShowSelector(false)}
                  realtimeMode={realtimeMode}
                  generalId={generalId}
                />
              </div>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
