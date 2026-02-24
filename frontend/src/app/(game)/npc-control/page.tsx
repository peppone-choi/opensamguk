"use client";

import { useEffect, useMemo, useState, useRef, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { useGeneralStore } from "@/stores/generalStore";
import { npcPolicyApi } from "@/lib/gameApi";
import {
  Bot,
  Settings,
  ListOrdered,
  Users,
  Crosshair,
  GripVertical,
  RotateCcw,
  Undo2,
  HelpCircle,
  Clock,
  DollarSign,
  Wheat,
} from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { CREW_TYPE_NAMES } from "@/lib/game-utils";
import { toast } from "sonner";

/* ── Full NPC policy field definitions (legacy parity) ── */

interface PolicyField {
  key: string;
  label: string;
  hint?: string;
  step?: number;
  min?: number;
  max?: number;
  isPercent?: boolean;
  zeroHintFn?: (zeroPolicy: Record<string, number>, computed: Record<string, number>) => string;
}

const POLICY_CATEGORIES: {
  label: string;
  icon: typeof Settings;
  fields: PolicyField[];
}[] = [
  {
    label: "국가 자원 기준",
    icon: DollarSign,
    fields: [
      {
        key: "reqNationGold",
        label: "국가 권장 금",
        step: 100,
        hint: "이보다 많으면 포상, 적으면 몰수/헌납합니다. (긴급포상 제외)",
      },
      {
        key: "reqNationRice",
        label: "국가 권장 쌀",
        step: 100,
        hint: "이보다 많으면 포상, 적으면 몰수/헌납합니다. (긴급포상 제외)",
      },
    ],
  },
  {
    label: "유저장 자원",
    icon: Users,
    fields: [
      {
        key: "reqHumanWarUrgentGold",
        label: "유저전투장 긴급포상 금",
        step: 100,
        zeroHintFn: (zp) =>
          `0이면 보병 6회 징병 가능한 금 기준 (현재 ${zp.reqHumanWarUrgentGold?.toLocaleString() ?? "?"})`,
      },
      {
        key: "reqHumanWarUrgentRice",
        label: "유저전투장 긴급포상 쌀",
        step: 100,
        zeroHintFn: (zp) =>
          `0이면 기본 병종 6회 사살 가능한 쌀 기준 (현재 ${zp.reqHumanWarUrgentRice?.toLocaleString() ?? "?"})`,
      },
      {
        key: "reqHumanWarRecommandGold",
        label: "유저전투장 권장 금",
        step: 100,
        zeroHintFn: (_zp, calc) =>
          `0이면 유저전투장 긴급포상 금의 2배 (현재 ${((calc.reqHumanWarUrgentGold ?? 0) * 2).toLocaleString()})`,
      },
      {
        key: "reqHumanWarRecommandRice",
        label: "유저전투장 권장 쌀",
        step: 100,
        zeroHintFn: (_zp, calc) =>
          `0이면 유저전투장 긴급포상 쌀의 2배 (현재 ${((calc.reqHumanWarUrgentRice ?? 0) * 2).toLocaleString()})`,
      },
      {
        key: "reqHumanDevelGold",
        label: "유저내정장 권장 금",
        step: 100,
        hint: "유저내정장에게 주는 금. 이보다 적으면 포상합니다.",
      },
      {
        key: "reqHumanDevelRice",
        label: "유저내정장 권장 쌀",
        step: 100,
        hint: "유저내정장에게 주는 쌀. 이보다 적으면 포상합니다.",
      },
    ],
  },
  {
    label: "NPC장 자원",
    icon: Bot,
    fields: [
      {
        key: "reqNPCWarGold",
        label: "NPC전투장 권장 금",
        step: 100,
        zeroHintFn: (zp) =>
          `0이면 기본 병종 4회 징병비 기준 (현재 ${zp.reqNPCWarGold?.toLocaleString() ?? "?"})`,
      },
      {
        key: "reqNPCWarRice",
        label: "NPC전투장 권장 쌀",
        step: 100,
        zeroHintFn: (zp) =>
          `0이면 기본 병종 4회 사살 가능한 쌀 기준 (현재 ${zp.reqNPCWarRice?.toLocaleString() ?? "?"})`,
      },
      {
        key: "reqNPCDevelGold",
        label: "NPC내정장 권장 금",
        step: 100,
        zeroHintFn: (zp) =>
          `0이면 30턴 내정 가능한 금 기준 (현재 ${zp.reqNPCDevelGold?.toLocaleString() ?? "?"})`,
      },
      {
        key: "reqNPCDevelRice",
        label: "NPC내정장 권장 쌀",
        step: 100,
        hint: "NPC내정장에게 주는 쌀. 이보다 5배 더 많다면 헌납합니다.",
      },
    ],
  },
  {
    label: "자원 단위",
    icon: Settings,
    fields: [
      {
        key: "minimumResourceActionAmount",
        label: "포상/몰수/헌납 최소 단위",
        step: 100,
        min: 100,
        hint: "연산결과가 이 단위보다 적다면 수행하지 않습니다.",
      },
      {
        key: "maximumResourceActionAmount",
        label: "포상/몰수/헌납 최대 단위",
        step: 100,
        min: 100,
        hint: "연산결과가 이 단위보다 크다면 이 값에 맞춥니다.",
      },
    ],
  },
  {
    label: "전쟁 기준",
    icon: Crosshair,
    fields: [
      {
        key: "minWarCrew",
        label: "최소 전투 가능 병력 수",
        step: 50,
        hint: "이보다 적을 때에는 징병을 시도합니다.",
      },
      {
        key: "minNPCRecruitCityPopulation",
        label: "NPC 최소 징병 가능 인구 수",
        step: 100,
        hint: "도시의 인구가 이보다 낮으면 NPC는 도시에서 징병하지 않고 후방 워프합니다.",
      },
      {
        key: "safeRecruitCityPopulationRatio",
        label: "제자리 징병 허용 인구율(%)",
        isPercent: true,
        step: 0.5,
        min: 0,
        max: 100,
        hint: "전쟁 시 후방 발령/워프의 기준 인구. 이보다 많다면 '충분하다'고 판단합니다.",
      },
      {
        key: "minNPCWarLeadership",
        label: "NPC 전투 참여 통솔 기준",
        step: 5,
        hint: "이 수치보다 같거나 높으면 NPC전투장으로 분류됩니다.",
      },
      {
        key: "properWarTrainAtmos",
        label: "훈련/사기진작 목표치",
        step: 5,
        min: 20,
        max: 100,
        hint: "훈련/사기진작 기준치. 이보다 같거나 높으면 출병합니다.",
      },
      {
        key: "cureThreshold",
        label: "요양 기준",
        step: 5,
        min: 10,
        max: 100,
        hint: "요양 기준 %. 이보다 많이 부상을 입으면 요양합니다.",
      },
    ],
  },
];

const ALL_POLICY_KEYS = POLICY_CATEGORIES.flatMap((c) =>
  c.fields.map((f) => f.key),
);

/* ── Priority items (will be overridden by server if available) ── */

const DEFAULT_NATION_PRIORITY_ITEMS = [
  { key: "불가침제의", help: "불가침/동맹 외교 제의" },
  { key: "선전포고", help: "타국에 선전포고" },
  { key: "천도", help: "수도 이전 검토" },
  { key: "유저장긴급포상", help: "유저 전투장에게 긴급 포상" },
  { key: "부대전방발령", help: "부대 단위 전방 발령" },
  { key: "유저장구출발령", help: "유저장 구출 발령" },
  { key: "유저장후방발령", help: "유저장 후방 발령" },
  { key: "부대유저장후방발령", help: "부대 단위 유저장 후방 발령" },
  { key: "유저장전방발령", help: "유저장 전방 발령" },
  { key: "유저장포상", help: "유저장 포상" },
  { key: "부대구출발령", help: "부대 구출 발령" },
  { key: "부대후방발령", help: "부대 후방 발령" },
  { key: "NPC긴급포상", help: "NPC장 긴급 포상" },
  { key: "NPC구출발령", help: "NPC장 구출 발령" },
  { key: "NPC후방발령", help: "NPC장 후방 발령" },
  { key: "NPC포상", help: "NPC장 포상" },
  { key: "NPC전방발령", help: "NPC장 전방 발령" },
  { key: "유저장내정발령", help: "유저장 내정 발령" },
  { key: "NPC내정발령", help: "NPC장 내정 발령" },
  { key: "NPC몰수", help: "NPC장 자원 몰수" },
];

const DEFAULT_GENERAL_PRIORITY_ITEMS = [
  { key: "NPC사망대비", help: "NPC 사망 대비 행동" },
  { key: "귀환", help: "귀환" },
  { key: "금쌀구매", help: "금/쌀 구매" },
  { key: "출병", help: "출전하여 전투 수행" },
  { key: "긴급내정", help: "긴급 내정 수행" },
  { key: "전투준비", help: "전투 준비 (징병/훈련)" },
  { key: "전방워프", help: "전방으로 이동" },
  { key: "NPC헌납", help: "NPC 자원 헌납" },
  { key: "징병", help: "병사 충원" },
  { key: "후방워프", help: "후방으로 이동" },
  { key: "전쟁내정", help: "전쟁 중 내정 수행" },
  { key: "소집해제", help: "소집 해제" },
  { key: "일반내정", help: "일반 내정 수행" },
  { key: "내정워프", help: "내정 도시로 이동" },
];

const NPC_MODE_LABELS: Record<number, string> = {
  0: "전투형 (공격 우선)",
  1: "균형형 (기본)",
  2: "내정형 (개발 우선)",
};

/* ── Drag-and-Drop Priority List Component ── */

interface DragItem {
  key: string;
  help: string;
}

function DraggablePriorityList({
  title,
  items,
  activeKeys,
  onReorder,
  onToggleActive,
}: {
  title: string;
  items: DragItem[];
  activeKeys: string[];
  onReorder: (newActive: string[]) => void;
  onToggleActive: (key: string, active: boolean) => void;
}) {
  const dragItemRef = useRef<number | null>(null);
  const dragOverRef = useRef<number | null>(null);

  const activeItems = activeKeys
    .map((k) => items.find((i) => i.key === k))
    .filter(Boolean) as DragItem[];
  const inactiveItems = items.filter((i) => !activeKeys.includes(i.key));

  const handleDragStart = (idx: number) => {
    dragItemRef.current = idx;
  };

  const handleDragOver = (e: React.DragEvent, idx: number) => {
    e.preventDefault();
    dragOverRef.current = idx;
  };

  const handleDrop = () => {
    if (dragItemRef.current == null || dragOverRef.current == null) return;
    const newArr = [...activeKeys];
    const [removed] = newArr.splice(dragItemRef.current, 1);
    newArr.splice(dragOverRef.current, 0, removed);
    onReorder(newArr);
    dragItemRef.current = null;
    dragOverRef.current = null;
  };

  const movePriority = (idx: number, dir: -1 | 1) => {
    const next = idx + dir;
    if (next < 0 || next >= activeKeys.length) return;
    const arr = [...activeKeys];
    [arr[idx], arr[next]] = [arr[next], arr[idx]];
    onReorder(arr);
  };

  return (
    <TooltipProvider delayDuration={200}>
      <div className="space-y-3">
        <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
          {title}
        </h4>

        {/* Active items */}
        <div className="space-y-1">
          <span className="text-[10px] text-green-400">
            활성 (위 → 높은 우선순위)
          </span>
          {activeItems.map((item, idx) => (
            <div
              key={item.key}
              draggable
              onDragStart={() => handleDragStart(idx)}
              onDragOver={(e) => handleDragOver(e, idx)}
              onDrop={handleDrop}
              className="flex items-center gap-2 rounded border border-gray-700 p-2 cursor-grab active:cursor-grabbing hover:border-gray-500 transition-colors bg-background"
            >
              <GripVertical className="size-3 text-muted-foreground/50 shrink-0" />
              <span className="w-5 text-center text-xs text-muted-foreground tabular-nums">
                {idx + 1}
              </span>
              <span className="flex-1 text-sm">{item.key}</span>
              <Tooltip>
                <TooltipTrigger asChild>
                  <HelpCircle className="size-3 text-muted-foreground/50 shrink-0" />
                </TooltipTrigger>
                <TooltipContent side="left">
                  <p className="text-xs">{item.help}</p>
                </TooltipContent>
              </Tooltip>
              <Button
                size="sm"
                variant="ghost"
                className="h-5 w-5 p-0"
                disabled={idx === 0}
                onClick={() => movePriority(idx, -1)}
              >
                ▲
              </Button>
              <Button
                size="sm"
                variant="ghost"
                className="h-5 w-5 p-0"
                disabled={idx === activeItems.length - 1}
                onClick={() => movePriority(idx, 1)}
              >
                ▼
              </Button>
              <Button
                size="sm"
                variant="ghost"
                className="h-5 text-red-400 px-1 text-[10px]"
                onClick={() => onToggleActive(item.key, false)}
              >
                비활성
              </Button>
            </div>
          ))}
          {activeItems.length === 0 && (
            <div className="text-xs text-muted-foreground/60 py-2 text-center">
              활성 항목 없음
            </div>
          )}
        </div>

        {/* Inactive items */}
        {inactiveItems.length > 0 && (
          <div className="space-y-1">
            <span className="text-[10px] text-muted-foreground/60">
              비활성
            </span>
            {inactiveItems.map((item) => (
              <div
                key={item.key}
                className="flex items-center gap-2 rounded border border-dashed border-gray-800 p-2 opacity-60"
              >
                <span className="w-5" />
                <span className="flex-1 text-sm text-muted-foreground">
                  {item.key}
                </span>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <HelpCircle className="size-3 text-muted-foreground/50 shrink-0" />
                  </TooltipTrigger>
                  <TooltipContent side="left">
                    <p className="text-xs">{item.help}</p>
                  </TooltipContent>
                </Tooltip>
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-5 text-green-400 px-1 text-[10px]"
                  onClick={() => onToggleActive(item.key, true)}
                >
                  활성
                </Button>
              </div>
            ))}
          </div>
        )}
      </div>
    </TooltipProvider>
  );
}

/* ── Page Component ── */

export default function NpcPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, cities, loading, loadAll } = useGameStore();

  const [policy, setPolicy] = useState<Record<string, number>>({});
  const [zeroPolicy, setZeroPolicy] = useState<Record<string, number>>({});
  const [nationPriority, setNationPriority] = useState<string[]>([]);
  const [generalPriority, setGeneralPriority] = useState<string[]>([]);
  const [npcMode, setNpcMode] = useState<string>("1");
  const [policyLoading, setPolicyLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // Server-provided priority items
  const [serverNationPriorityItems, setServerNationPriorityItems] = useState<
    DragItem[] | null
  >(null);
  const [serverGeneralPriorityItems, setServerGeneralPriorityItems] = useState<
    DragItem[] | null
  >(null);

  // Force config (hidden JSON fields)
  const [combatForce, setCombatForce] = useState<Record<number, number[]>>({});
  const [supportForce, setSupportForce] = useState<number[]>([]);
  const [developForce, setDevelopForce] = useState<number[]>([]);

  // Derived stat values for zero-policy computation
  const [defaultStatMax, setDefaultStatMax] = useState(70);
  const [defaultStatNPCMax, setDefaultStatNPCMax] = useState(60);

  // Audit trail
  const [lastSetters, setLastSetters] = useState<{
    nation?: { setter: string; date: string };
    general?: { setter: string; date: string };
    policy?: { setter: string; date: string };
  }>({});

  // Settings history log
  const [settingsHistory, setSettingsHistory] = useState<
    { setter: string; date: string; action: string; details: string }[]
  >([]);

  // Snapshots for rollback
  const [prevPolicy, setPrevPolicy] = useState<Record<string, number>>({});
  const [prevNationPriority, setPrevNationPriority] = useState<string[]>([]);
  const [prevGeneralPriority, setPrevGeneralPriority] = useState<string[]>([]);
  const [defaultNationPriority, setDefaultNationPriority] = useState<string[]>(
    [],
  );
  const [defaultGeneralPriority, setDefaultGeneralPriority] = useState<
    string[]
  >([]);

  // General-level override state
  const [selectedNpcId, setSelectedNpcId] = useState<string>("");
  const [generalOverride, setGeneralOverride] = useState<
    Record<string, number>
  >({});

  useEffect(() => {
    if (!currentWorld) return;
    fetchMyGeneral(currentWorld.id);
    loadAll(currentWorld.id);
  }, [currentWorld, fetchMyGeneral, loadAll]);

  const loadPolicy = useCallback(() => {
    if (!myGeneral?.nationId) return;
    setPolicyLoading(true);
    npcPolicyApi
      .getPolicy(myGeneral.nationId)
      .then(({ data }) => {
        const p: Record<string, number> = {};
        for (const key of ALL_POLICY_KEYS) {
          if (key === "safeRecruitCityPopulationRatio") {
            // Stored as ratio 0~1, display as percent
            p[key] = ((data[key] as number) ?? 0) * 100;
          } else {
            p[key] = (data[key] as number) ?? 0;
          }
        }
        setPolicy(p);
        setPrevPolicy({ ...p });

        // Zero policy defaults
        if (data.zeroPolicy) {
          setZeroPolicy(data.zeroPolicy as Record<string, number>);
        }

        // Stat max values for zero-policy display
        if (data.defaultStatMax != null) {
          setDefaultStatMax(data.defaultStatMax as number);
        }
        if (data.defaultStatNPCMax != null) {
          setDefaultStatNPCMax(data.defaultStatNPCMax as number);
        }

        // Nation priority
        if (Array.isArray(data.nationPriority)) {
          setNationPriority(data.nationPriority as string[]);
          setPrevNationPriority([...(data.nationPriority as string[])]);
        } else if (Array.isArray(data.currentNationPriority)) {
          setNationPriority(data.currentNationPriority as string[]);
          setPrevNationPriority([...(data.currentNationPriority as string[])]);
        }

        // General priority
        if (Array.isArray(data.generalPriority)) {
          setGeneralPriority(data.generalPriority as string[]);
          setPrevGeneralPriority([...(data.generalPriority as string[])]);
        } else if (Array.isArray(data.currentGeneralActionPriority)) {
          setGeneralPriority(data.currentGeneralActionPriority as string[]);
          setPrevGeneralPriority([
            ...(data.currentGeneralActionPriority as string[]),
          ]);
        } else if (Array.isArray(data.priority)) {
          setGeneralPriority(data.priority as string[]);
          setPrevGeneralPriority([...(data.priority as string[])]);
        }

        // Server-provided available priority items
        if (Array.isArray(data.availableNationPriorityItems)) {
          setServerNationPriorityItems(
            (data.availableNationPriorityItems as string[]).map((k) => ({
              key: k,
              help:
                DEFAULT_NATION_PRIORITY_ITEMS.find((d) => d.key === k)?.help ??
                k,
            })),
          );
        }
        if (Array.isArray(data.availableGeneralActionPriorityItems)) {
          setServerGeneralPriorityItems(
            (data.availableGeneralActionPriorityItems as string[]).map((k) => ({
              key: k,
              help:
                DEFAULT_GENERAL_PRIORITY_ITEMS.find((d) => d.key === k)
                  ?.help ?? k,
            })),
          );
        }

        // Default priorities (for reset)
        if (Array.isArray(data.defaultNationPriority)) {
          setDefaultNationPriority(data.defaultNationPriority as string[]);
        }
        if (Array.isArray(data.defaultGeneralActionPriority)) {
          setDefaultGeneralPriority(
            data.defaultGeneralActionPriority as string[],
          );
        }

        if (data.npcMode != null) {
          setNpcMode(String(data.npcMode));
        }
        if (data.lastSetters) {
          setLastSetters(
            data.lastSetters as {
              nation?: { setter: string; date: string };
              general?: { setter: string; date: string };
              policy?: { setter: string; date: string };
            },
          );
        }
        if (Array.isArray(data.history)) {
          setSettingsHistory(
            data.history as {
              setter: string;
              date: string;
              action: string;
              details: string;
            }[],
          );
        }

        // Force config (hidden JSON)
        if (data.CombatForce) {
          setCombatForce(data.CombatForce as Record<number, number[]>);
        }
        if (Array.isArray(data.SupportForce)) {
          setSupportForce(data.SupportForce as number[]);
        }
        if (Array.isArray(data.DevelopForce)) {
          setDevelopForce(data.DevelopForce as number[]);
        }
      })
      .catch(() => {})
      .finally(() => setPolicyLoading(false));
  }, [myGeneral?.nationId]);

  useEffect(() => {
    loadPolicy();
  }, [loadPolicy]);

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  const npcGenerals = useMemo(() => {
    if (!myGeneral) return [];
    return generals.filter(
      (g) => g.npcState > 0 && g.nationId === myGeneral.nationId,
    );
  }, [generals, myGeneral]);

  // Compute calcPolicyValue for zero-policy display
  const calcPolicyValue = useCallback(
    (key: string): number => {
      const val = policy[key] ?? 0;
      if (val === 0 && zeroPolicy[key] != null) {
        return zeroPolicy[key];
      }
      return val;
    },
    [policy, zeroPolicy],
  );

  // Computed values for zeroHintFn
  const computedValues = useMemo(() => {
    const computed: Record<string, number> = {};
    for (const key of ALL_POLICY_KEYS) {
      computed[key] = calcPolicyValue(key);
    }
    return computed;
  }, [calcPolicyValue]);

  // Use server-provided items or fallback
  const nationPriorityItems =
    serverNationPriorityItems ?? DEFAULT_NATION_PRIORITY_ITEMS;
  const generalPriorityItems =
    serverGeneralPriorityItems ?? DEFAULT_GENERAL_PRIORITY_ITEMS;

  const handlePolicyChange = (key: string, value: number) => {
    setPolicy((prev) => ({ ...prev, [key]: value }));
  };

  const handleSavePolicy = async () => {
    if (!myGeneral?.nationId) return;
    setSaving(true);
    try {
      // Convert percent back to ratio for safeRecruitCityPopulationRatio
      const policyToSend = { ...policy };
      if (policyToSend.safeRecruitCityPopulationRatio != null) {
        policyToSend.safeRecruitCityPopulationRatio =
          policyToSend.safeRecruitCityPopulationRatio / 100;
      }
      await npcPolicyApi.updatePolicy(myGeneral.nationId, {
        ...policyToSend,
        npcMode: Number(npcMode),
        CombatForce: combatForce,
        SupportForce: supportForce,
        DevelopForce: developForce,
      });
      setPrevPolicy({ ...policy });
      toast.success("NPC 정책이 저장되었습니다.");
      loadPolicy();
    } catch {
      toast.error("저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const handleSavePriority = async () => {
    if (!myGeneral?.nationId) return;
    setSaving(true);
    try {
      await npcPolicyApi.updatePriority(myGeneral.nationId, {
        nationPriority,
        generalPriority,
      });
      setPrevNationPriority([...nationPriority]);
      setPrevGeneralPriority([...generalPriority]);
      toast.success("우선순위가 저장되었습니다.");
      loadPolicy();
    } catch {
      toast.error("저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const handleSaveGeneralOverride = async () => {
    if (!myGeneral?.nationId || !selectedNpcId) return;
    setSaving(true);
    try {
      await npcPolicyApi.updatePolicy(myGeneral.nationId, {
        generalOverrides: {
          [selectedNpcId]: generalOverride,
        },
      });
      toast.success("장수별 설정이 저장되었습니다.");
    } catch {
      toast.error("저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  // Reset & Rollback handlers
  const handleResetNationPriority = () => {
    if (defaultNationPriority.length > 0) {
      setNationPriority([...defaultNationPriority]);
    } else {
      setNationPriority(nationPriorityItems.map((i) => i.key));
    }
    toast.info(
      "국가턴 우선순위를 초기값으로 되돌렸습니다. 저장을 눌러주세요.",
    );
  };
  const handleResetGeneralPriority = () => {
    if (defaultGeneralPriority.length > 0) {
      setGeneralPriority([...defaultGeneralPriority]);
    } else {
      setGeneralPriority(generalPriorityItems.map((i) => i.key));
    }
    toast.info(
      "장수턴 우선순위를 초기값으로 되돌렸습니다. 저장을 눌러주세요.",
    );
  };
  const handleRollbackNationPriority = () => {
    setNationPriority([...prevNationPriority]);
    toast.info("국가턴 우선순위를 이전값으로 되돌렸습니다.");
  };
  const handleRollbackGeneralPriority = () => {
    setGeneralPriority([...prevGeneralPriority]);
    toast.info("장수턴 우선순위를 이전값으로 되돌렸습니다.");
  };
  const handleRollbackPolicy = () => {
    setPolicy({ ...prevPolicy });
    toast.info("정책을 이전값으로 되돌렸습니다.");
  };

  const handleNationToggle = (key: string, active: boolean) => {
    if (active) {
      setNationPriority((prev) => [...prev, key]);
    } else {
      setNationPriority((prev) => prev.filter((k) => k !== key));
    }
  };

  const handleGeneralToggle = (key: string, active: boolean) => {
    if (active) {
      setGeneralPriority((prev) => [...prev, key]);
    } else {
      setGeneralPriority((prev) => prev.filter((k) => k !== key));
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading)
    return (
      <div className="p-4">
        <LoadingState />
      </div>
    );

  function LastSetterInfo({
    setter,
  }: {
    setter?: { setter: string; date: string };
  }) {
    if (!setter) return null;
    return (
      <div className="flex items-center gap-1 text-[10px] text-muted-foreground/60">
        <Clock className="size-3" />
        <span>
          마지막 설정: {setter.setter} ({setter.date})
        </span>
      </div>
    );
  }

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <PageHeader icon={Bot} title="NPC 정책" />

      <Tabs defaultValue="list">
        <TabsList>
          <TabsTrigger value="list">NPC 장수</TabsTrigger>
          <TabsTrigger value="policy">국가 정책</TabsTrigger>
          <TabsTrigger value="priority">우선순위</TabsTrigger>
          <TabsTrigger value="override">장수별 설정</TabsTrigger>
          <TabsTrigger value="history">설정 이력</TabsTrigger>
        </TabsList>

        {/* Tab 1: NPC generals list */}
        <TabsContent value="list" className="mt-4">
          {npcGenerals.length === 0 ? (
            <EmptyState icon={Bot} title="관리 가능한 NPC 장수가 없습니다." />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>도시</TableHead>
                  <TableHead className="text-right">병력</TableHead>
                  <TableHead>병종</TableHead>
                  <TableHead className="text-right">훈련</TableHead>
                  <TableHead className="text-right">사기</TableHead>
                  <TableHead>NPC</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {npcGenerals.map((g) => {
                  const city = cityMap.get(g.cityId);
                  return (
                    <TableRow key={g.id}>
                      <TableCell className="font-medium">
                        <div className="flex items-center gap-2">
                          <GeneralPortrait
                            picture={g.picture}
                            name={g.name}
                            size="sm"
                          />
                          {g.name}
                        </div>
                      </TableCell>
                      <TableCell>{city?.name ?? "-"}</TableCell>
                      <TableCell className="text-right tabular-nums">
                        {g.crew.toLocaleString()}
                      </TableCell>
                      <TableCell>
                        {CREW_TYPE_NAMES[g.crewType] ?? g.crewType}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {g.train}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {g.atmos}
                      </TableCell>
                      <TableCell>
                        <Badge variant="secondary">{g.npcState}</Badge>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </TabsContent>

        {/* Tab 2: Full NPC policy with ~20 fields */}
        <TabsContent value="policy" className="mt-4 space-y-4">
          {policyLoading ? (
            <LoadingState />
          ) : (
            <>
              {/* Audit info */}
              <LastSetterInfo setter={lastSetters.policy} />

              {/* NPC Mode Selector */}
              <Card>
                <CardHeader>
                  <CardTitle className="text-sm">NPC 모드</CardTitle>
                </CardHeader>
                <CardContent>
                  <Select value={npcMode} onValueChange={setNpcMode}>
                    <SelectTrigger size="sm" className="w-full max-w-[300px]">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {Object.entries(NPC_MODE_LABELS).map(([k, label]) => (
                        <SelectItem key={k} value={k}>
                          {label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </CardContent>
              </Card>

              {/* Policy categories with all ~20 fields */}
              {POLICY_CATEGORIES.map((cat) => (
                <Card key={cat.label}>
                  <CardHeader>
                    <CardTitle className="flex items-center gap-2 text-sm">
                      <cat.icon className="size-4" />
                      {cat.label}
                    </CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="grid grid-cols-2 gap-3">
                      {cat.fields.map((f) => {
                        const displayValue = policy[f.key] ?? 0;
                        const zeroHint = f.zeroHintFn
                          ? f.zeroHintFn(zeroPolicy, computedValues)
                          : null;
                        return (
                          <div key={f.key} className="space-y-1">
                            <label className="text-xs text-muted-foreground">
                              {f.label}
                            </label>
                            <Input
                              type="number"
                              step={f.step ?? 1}
                              min={f.min}
                              max={f.max}
                              value={displayValue}
                              onChange={(e) =>
                                handlePolicyChange(
                                  f.key,
                                  Number(e.target.value),
                                )
                              }
                            />
                            {f.hint && (
                              <p className="text-[10px] text-muted-foreground/70">
                                {f.hint}
                              </p>
                            )}
                            {zeroHint && displayValue === 0 && (
                              <p className="text-[10px] text-yellow-500/80">
                                {zeroHint}
                              </p>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  </CardContent>
                </Card>
              ))}

              {/* Force config (hidden JSON fields) */}
              <Card>
                <CardHeader>
                  <CardTitle className="text-sm flex items-center gap-2">
                    <Settings className="size-4" />
                    부대 배치 설정
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  <p className="text-[10px] text-muted-foreground">
                    전투 부대, 후방 징병 부대, 내정 부대의 JSON 설정입니다.
                    고급 설정이므로 주의하세요.
                  </p>
                  <div className="space-y-2">
                    <div className="space-y-1">
                      <label className="text-xs text-muted-foreground">
                        전투 부대 (CombatForce)
                      </label>
                      <Input
                        value={JSON.stringify(combatForce)}
                        onChange={(e) => {
                          try {
                            setCombatForce(JSON.parse(e.target.value));
                          } catch {
                            /* ignore parse errors while typing */
                          }
                        }}
                        className="font-mono text-xs"
                        placeholder='{"부대번호":[시작도시,도착도시],...}'
                      />
                      <p className="text-[10px] text-muted-foreground/60">
                        JSON: {"{"}부대번호:[시작도시번호(아국),도착도시번호(적군)],...{"}"}
                      </p>
                    </div>
                    <div className="space-y-1">
                      <label className="text-xs text-muted-foreground">
                        후방 징병 부대 (SupportForce)
                      </label>
                      <Input
                        value={JSON.stringify(supportForce)}
                        onChange={(e) => {
                          try {
                            setSupportForce(JSON.parse(e.target.value));
                          } catch {
                            /* ignore */
                          }
                        }}
                        className="font-mono text-xs"
                        placeholder="[부대번호,...]"
                      />
                    </div>
                    <div className="space-y-1">
                      <label className="text-xs text-muted-foreground">
                        내정 부대 (DevelopForce)
                      </label>
                      <Input
                        value={JSON.stringify(developForce)}
                        onChange={(e) => {
                          try {
                            setDevelopForce(JSON.parse(e.target.value));
                          } catch {
                            /* ignore */
                          }
                        }}
                        className="font-mono text-xs"
                        placeholder="[부대번호,...]"
                      />
                    </div>
                  </div>
                </CardContent>
              </Card>

              <div className="flex items-center gap-2">
                <Button onClick={handleSavePolicy} disabled={saving}>
                  {saving ? "저장 중..." : "정책 저장"}
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleRollbackPolicy}
                >
                  <Undo2 className="size-3 mr-1" />
                  이전값으로
                </Button>
              </div>
            </>
          )}
        </TabsContent>

        {/* Tab 3: Priority — Split into Nation Turn + General Turn */}
        <TabsContent value="priority" className="mt-4 space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <ListOrdered className="size-4" />
                NPC 사령턴 우선순위
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <LastSetterInfo setter={lastSetters.nation} />
              <p className="text-xs text-muted-foreground">
                예턴이 없거나 지정되어 있더라도 실패하면 아래 순위에 따라
                사령턴을 시도합니다. 드래그하거나 ▲▼ 버튼으로 순서를
                변경하세요.
              </p>
              <DraggablePriorityList
                title="국가턴"
                items={nationPriorityItems}
                activeKeys={nationPriority}
                onReorder={setNationPriority}
                onToggleActive={handleNationToggle}
              />
              <div className="flex items-center gap-2 pt-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={handleResetNationPriority}
                >
                  <RotateCcw className="size-3 mr-1" />
                  초깃값으로
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={handleRollbackNationPriority}
                >
                  <Undo2 className="size-3 mr-1" />
                  이전값으로
                </Button>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <ListOrdered className="size-4" />
                NPC 일반턴 우선순위
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <LastSetterInfo setter={lastSetters.general} />
              <p className="text-xs text-muted-foreground">
                순위가 높은 것부터 시도합니다. 아무것도 실행할 수 없으면
                물자조달이나 인재탐색을 합니다.
              </p>
              <DraggablePriorityList
                title="장수턴"
                items={generalPriorityItems}
                activeKeys={generalPriority}
                onReorder={setGeneralPriority}
                onToggleActive={handleGeneralToggle}
              />
              <div className="flex items-center gap-2 pt-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={handleResetGeneralPriority}
                >
                  <RotateCcw className="size-3 mr-1" />
                  초깃값으로
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={handleRollbackGeneralPriority}
                >
                  <Undo2 className="size-3 mr-1" />
                  이전값으로
                </Button>
              </div>
            </CardContent>
          </Card>

          <Button onClick={handleSavePriority} disabled={saving}>
            {saving ? "저장 중..." : "우선순위 저장"}
          </Button>
        </TabsContent>

        {/* Tab 4: General-level override */}
        <TabsContent value="override" className="mt-4 space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">장수별 정책 설정</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <p className="text-xs text-muted-foreground">
                특정 NPC 장수에 대해 국가 기본 정책을 덮어쓸 수 있습니다.
                설정하지 않은 항목은 국가 기본 정책을 따릅니다.
              </p>
              <Select value={selectedNpcId} onValueChange={setSelectedNpcId}>
                <SelectTrigger size="sm" className="w-full max-w-[300px]">
                  <SelectValue placeholder="NPC 장수 선택..." />
                </SelectTrigger>
                <SelectContent>
                  {npcGenerals.map((g) => (
                    <SelectItem key={g.id} value={String(g.id)}>
                      {g.name} ({cityMap.get(g.cityId)?.name ?? "-"})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              {selectedNpcId && (
                <>
                  <div className="grid grid-cols-2 gap-3">
                    {POLICY_CATEGORIES.flatMap((cat) => cat.fields).map((f) => (
                      <div key={f.key} className="space-y-1">
                        <label className="text-xs text-muted-foreground">
                          {f.label}
                        </label>
                        <Input
                          type="number"
                          placeholder={String(policy[f.key] ?? 0)}
                          value={generalOverride[f.key] ?? ""}
                          onChange={(e) =>
                            setGeneralOverride((prev) => ({
                              ...prev,
                              [f.key]: Number(e.target.value),
                            }))
                          }
                        />
                      </div>
                    ))}
                  </div>
                  <Button onClick={handleSaveGeneralOverride} disabled={saving}>
                    {saving ? "저장 중..." : "장수별 설정 저장"}
                  </Button>
                </>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* Settings History Tab */}
        <TabsContent value="history" className="space-y-3">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm flex items-center gap-2">
                <Clock className="size-4" />
                설정자 이력
              </CardTitle>
            </CardHeader>
            <CardContent>
              {settingsHistory.length > 0 ? (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="text-xs">일시</TableHead>
                      <TableHead className="text-xs">설정자</TableHead>
                      <TableHead className="text-xs">항목</TableHead>
                      <TableHead className="text-xs">내용</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {settingsHistory.map((entry, idx) => (
                      <TableRow key={idx}>
                        <TableCell className="text-xs text-muted-foreground">
                          {entry.date}
                        </TableCell>
                        <TableCell className="text-xs font-medium">
                          {entry.setter}
                        </TableCell>
                        <TableCell className="text-xs">
                          {entry.action}
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground max-w-[200px] truncate">
                          {entry.details}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              ) : (
                <div className="space-y-3">
                  <p className="text-xs text-muted-foreground text-center py-4">
                    설정 이력이 없습니다.
                  </p>
                  {lastSetters.policy && (
                    <div className="text-xs text-muted-foreground">
                      정책: {lastSetters.policy.setter} (
                      {lastSetters.policy.date})
                    </div>
                  )}
                  {lastSetters.nation && (
                    <div className="text-xs text-muted-foreground">
                      국가 우선순위: {lastSetters.nation.setter} (
                      {lastSetters.nation.date})
                    </div>
                  )}
                  {lastSetters.general && (
                    <div className="text-xs text-muted-foreground">
                      장수 우선순위: {lastSetters.general.setter} (
                      {lastSetters.general.date})
                    </div>
                  )}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
