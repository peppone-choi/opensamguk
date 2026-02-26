"use client";

import {
  useEffect,
  useState,
  useCallback,
  useMemo,
  Suspense,
  type DragEvent,
  type MouseEvent,
} from "react";
import { useSearchParams } from "next/navigation";
import {
  Swords,
  Crown,
  Clock,
  Copy,
  ClipboardPaste,
  Save,
  FolderOpen,
  Trash2,
} from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { CommandPanel } from "@/components/game/command-panel";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
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

interface NationPreset {
  name: string;
  items: {
    offset: number;
    actionCode: string;
    arg: Record<string, unknown>;
    brief: string | null;
  }[];
}

/** Nation Command Panel with drag/drop + clipboard + presets */
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
  const [commandTable, setCommandTable] = useState<
    Record<string, CommandTableEntry[]>
  >({});
  const [loading, setLoading] = useState(true);
  const [selectedTurn, setSelectedTurn] = useState<number>(0);
  const [showSelector, setShowSelector] = useState(false);
  const [selectedSlots, setSelectedSlots] = useState<Set<number>>(new Set([0]));
  const [lastClickedSlot, setLastClickedSlot] = useState<number | null>(0);
  const [clipboard, setClipboard] = useState<NationPreset["items"] | null>(
    null,
  );
  const [presets, setPresets] = useState<NationPreset[]>([]);
  const [selectedPreset, setSelectedPreset] = useState("");
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const [dragOver, setDragOver] = useState<number | null>(null);
  const [presetName, setPresetName] = useState("");

  const presetKey = `opensam:commands:nation-presets:${nationId}`;

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

  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      const raw = window.localStorage.getItem(presetKey);
      if (!raw) return;
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) setPresets(parsed);
    } catch {
      // ignore
    }
  }, [presetKey]);

  const persistPresets = useCallback(
    (next: NationPreset[]) => {
      setPresets(next);
      if (typeof window !== "undefined") {
        window.localStorage.setItem(presetKey, JSON.stringify(next));
      }
    },
    [presetKey],
  );

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

  const handleReserve = async (
    actionCode: string,
    arg?: Record<string, unknown>,
  ) => {
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

  const handleSlotClick = (idx: number, e: MouseEvent) => {
    setSelectedTurn(idx);
    if (e.shiftKey && lastClickedSlot !== null) {
      const min = Math.min(lastClickedSlot, idx);
      const max = Math.max(lastClickedSlot, idx);
      const range = new Set(selectedSlots);
      for (let i = min; i <= max; i++) range.add(i);
      setSelectedSlots(range);
      return;
    }

    if (e.metaKey || e.ctrlKey) {
      const next = new Set(selectedSlots);
      if (next.has(idx)) next.delete(idx);
      else next.add(idx);
      if (next.size === 0) next.add(idx);
      setSelectedSlots(next);
      setLastClickedSlot(idx);
      return;
    }

    setSelectedSlots(new Set([idx]));
    setLastClickedSlot(idx);
  };

  const copySelected = useCallback(() => {
    const slots = [...selectedSlots].sort((a, b) => a - b);
    if (slots.length === 0) return;
    const anchor = slots[0];
    setClipboard(
      slots.map((idx) => {
        const t = filledTurns[idx];
        return {
          offset: idx - anchor,
          actionCode: t.actionCode,
          arg: t.arg,
          brief: t.brief,
        };
      }),
    );
  }, [filledTurns, selectedSlots]);

  const pasteClipboard = useCallback(async () => {
    if (!clipboard) return;
    const slots = [...selectedSlots].sort((a, b) => a - b);
    const anchor = slots.length > 0 ? slots[0] : 0;
    const items = clipboard
      .map((item) => ({ ...item, target: anchor + item.offset }))
      .filter((item) => item.target >= 0 && item.target < TURN_COUNT);
    if (items.length === 0) return;
    await commandApi.reserveNation(
      nationId,
      generalId,
      items.map((item) => ({
        turnIdx: item.target,
        actionCode: item.actionCode,
        arg: item.arg,
      })),
    );
    await loadData();
  }, [clipboard, selectedSlots, nationId, generalId, loadData]);

  const savePreset = useCallback(() => {
    const slots = [...selectedSlots].sort((a, b) => a - b);
    if (slots.length === 0) return;
    const anchor = slots[0];
    const name = presetName.trim();
    if (!name) return;
    const items = slots.map((idx) => {
      const t = filledTurns[idx];
      return {
        offset: idx - anchor,
        actionCode: t.actionCode,
        arg: t.arg,
        brief: t.brief,
      };
    });
    const deduped = presets.filter((p) => p.name !== name);
    persistPresets([...deduped, { name, items }]);
    setSelectedPreset(name);
    setPresetName("");
  }, [selectedSlots, presetName, filledTurns, presets, persistPresets]);

  const loadPreset = useCallback(async () => {
    if (!selectedPreset) return;
    const preset = presets.find((p) => p.name === selectedPreset);
    if (!preset) return;
    const slots = [...selectedSlots].sort((a, b) => a - b);
    const anchor = slots.length > 0 ? slots[0] : 0;
    const items = preset.items
      .map((item) => ({ ...item, target: anchor + item.offset }))
      .filter((item) => item.target >= 0 && item.target < TURN_COUNT);
    if (items.length === 0) return;

    await commandApi.reserveNation(
      nationId,
      generalId,
      items.map((item) => ({
        turnIdx: item.target,
        actionCode: item.actionCode,
        arg: item.arg,
      })),
    );
    await loadData();
  }, [selectedPreset, presets, selectedSlots, nationId, generalId, loadData]);

  const deletePreset = useCallback(() => {
    if (!selectedPreset) return;
    persistPresets(presets.filter((p) => p.name !== selectedPreset));
    setSelectedPreset("");
  }, [selectedPreset, presets, persistPresets]);

  const handleDragStart = useCallback(
    (idx: number, e: DragEvent<HTMLButtonElement>) => {
      setDragFrom(idx);
      e.dataTransfer.effectAllowed = "move";
      e.dataTransfer.setData("text/plain", String(idx));
    },
    [],
  );

  const handleDrop = useCallback(
    async (targetIdx: number, e: DragEvent<HTMLButtonElement>) => {
      e.preventDefault();
      setDragOver(null);
      const fromIdx = dragFrom;
      setDragFrom(null);
      if (fromIdx === null || fromIdx === targetIdx) return;

      const reordered = [...filledTurns];
      const [moved] = reordered.splice(fromIdx, 1);
      reordered.splice(targetIdx, 0, moved);

      const minIdx = Math.min(fromIdx, targetIdx);
      const maxIdx = Math.max(fromIdx, targetIdx);

      await commandApi.reserveNation(
        nationId,
        generalId,
        Array.from({ length: maxIdx - minIdx + 1 }, (_, i) => {
          const idx = minIdx + i;
          return {
            turnIdx: idx,
            actionCode: reordered[idx].actionCode,
            arg: reordered[idx].arg,
          };
        }),
      );

      await loadData();
      setSelectedSlots(new Set([targetIdx]));
      setLastClickedSlot(targetIdx);
    },
    [dragFrom, filledTurns, nationId, generalId, loadData],
  );

  if (loading) return <LoadingState message="국가 명령 불러오는 중..." />;

  return (
    <div className="space-y-3">
      <Card>
        <CardContent className="pt-4 space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={copySelected}
            >
              <Copy className="size-3.5 mr-1" /> 복사
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={pasteClipboard}
              disabled={!clipboard}
            >
              <ClipboardPaste className="size-3.5 mr-1" /> 붙여넣기
            </Button>
            <div className="h-6 w-px bg-border mx-1" />
            <Input
              className="h-8 w-36"
              value={presetName}
              onChange={(e) => setPresetName(e.target.value)}
              placeholder="프리셋 이름"
            />
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={savePreset}
            >
              <Save className="size-3.5 mr-1" /> 저장
            </Button>
            <select
              className="h-8 rounded-md border border-input bg-background px-2 text-sm"
              value={selectedPreset}
              onChange={(e) => setSelectedPreset(e.target.value)}
            >
              <option value="">프리셋 선택</option>
              {presets.map((preset) => (
                <option key={preset.name} value={preset.name}>
                  {preset.name}
                </option>
              ))}
            </select>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={loadPreset}
              disabled={!selectedPreset}
            >
              <FolderOpen className="size-3.5 mr-1" /> 불러오기
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={deletePreset}
              disabled={!selectedPreset}
            >
              <Trash2 className="size-3.5 mr-1" /> 삭제
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">
            Shift+클릭 범위선택 · Ctrl/Cmd+클릭 다중선택 · 드래그로 순서변경
          </p>
        </CardContent>
      </Card>

      {/* Turn grid */}
      <div className="grid grid-cols-4 sm:grid-cols-6 gap-1">
        {filledTurns.map((t) => (
          <button
            key={t.turnIdx}
            type="button"
            draggable
            onDragStart={(e) => handleDragStart(t.turnIdx, e)}
            onDragOver={(e) => {
              e.preventDefault();
              setDragOver(t.turnIdx);
            }}
            onDragLeave={() => setDragOver(null)}
            onDrop={(e) => void handleDrop(t.turnIdx, e)}
            onDragEnd={() => {
              setDragFrom(null);
              setDragOver(null);
            }}
            onClick={(e) => {
              handleSlotClick(t.turnIdx, e);
              setShowSelector(true);
            }}
            className={`relative rounded border px-2 py-2 text-left text-[11px] leading-snug transition-colors ${
              selectedSlots.has(t.turnIdx)
                ? "border-blue-500 bg-blue-500/10"
                : dragOver === t.turnIdx
                  ? "border-emerald-500 bg-emerald-500/10"
                  : "border-gray-700 bg-[#111] hover:bg-gray-900"
            }`}
            title="드래그하여 순서 변경"
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
                    void handleClear(t.turnIdx);
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
              <div className="truncate text-muted-foreground text-[10px]">
                {t.brief}
              </div>
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
                <p className="text-[10px] text-muted-foreground font-bold mb-1">
                  {cat}
                </p>
                <div className="flex flex-wrap gap-1">
                  {commandTable[cat].map((cmd) => (
                    <Button
                      key={cmd.actionCode}
                      size="sm"
                      variant="outline"
                      className="h-7 text-[11px]"
                      disabled={!cmd.enabled}
                      onClick={() => void handleReserve(cmd.actionCode)}
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
  return (
    <Suspense fallback={<LoadingState message="명령 정보를 불러오는 중..." />}>
      <CommandsPageInner />
    </Suspense>
  );
}

function CommandsPageInner() {
  const searchParams = useSearchParams();
  const initialMode =
    searchParams.get("mode") === "nation" ? "nation" : "general";
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const [mode, setMode] = useState<"general" | "nation">(initialMode);

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) {
      fetchMyGeneral(currentWorld.id).catch(() => {});
    }
  }, [currentWorld, myGeneral, fetchMyGeneral]);

  const isChief = (myGeneral?.officerLevel ?? 0) >= 5;

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
