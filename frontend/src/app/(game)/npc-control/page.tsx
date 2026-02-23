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

/* ── Policy field definitions ── */

const POLICY_CATEGORIES: {
  label: string;
  icon: typeof Settings;
  fields: { key: string; label: string; hint?: string }[];
}[] = [
  {
    label: "전쟁",
    icon: Crosshair,
    fields: [
      {
        key: "warPolicy",
        label: "전쟁 정책",
        hint: "0=비전쟁, 1=방어, 2=공격",
      },
      { key: "minWarCrew", label: "최소 전쟁 병력" },
      { key: "minDefenceCrew", label: "최소 수비 병력" },
    ],
  },
  {
    label: "군사",
    icon: Users,
    fields: [
      { key: "recruitPolicy", label: "모병 정책", hint: "0=안함, 1=자동" },
      { key: "trainPolicy", label: "훈련 정책", hint: "0=안함, 1=자동" },
      { key: "maxRecruitCrew", label: "최대 모병 병력" },
      { key: "trainTarget", label: "훈련 목표" },
      { key: "atmosTarget", label: "사기 목표" },
    ],
  },
  {
    label: "내정",
    icon: Settings,
    fields: [
      { key: "developPolicy", label: "개발 정책", hint: "0=안함, 1=자동" },
      { key: "agriTarget", label: "농업 목표" },
      { key: "commTarget", label: "상업 목표" },
      { key: "secuTarget", label: "치안 목표" },
      { key: "defTarget", label: "수비 목표" },
      { key: "wallTarget", label: "성벽 목표" },
    ],
  },
  {
    label: "외교/정찰/기술",
    icon: Settings,
    fields: [
      { key: "diplomacyPolicy", label: "외교 정책" },
      { key: "scoutPolicy", label: "정찰 정책" },
      { key: "defencePolicy", label: "수비 정책" },
      { key: "techPolicy", label: "기술 정책" },
    ],
  },
  {
    label: "자원",
    icon: Settings,
    fields: [
      { key: "goldReserve", label: "금 비축" },
      { key: "riceReserve", label: "쌀 비축" },
    ],
  },
];

const ALL_POLICY_KEYS = POLICY_CATEGORIES.flatMap((c) =>
  c.fields.map((f) => f.key),
);

const NATION_PRIORITY_ITEMS = [
  { key: "전쟁", help: "타국 도시 공격" },
  { key: "수비배치", help: "수비 장수 배치" },
  { key: "외교", help: "동맹/불가침 등 외교 활동" },
  { key: "기술투자", help: "국가 기술 개발 투자" },
  { key: "천도", help: "수도 이전 검토" },
  { key: "징병", help: "국가 차원 징병" },
  { key: "국가세율", help: "세율 조정" },
];

const GENERAL_PRIORITY_ITEMS = [
  { key: "전쟁", help: "출전하여 전투 수행" },
  { key: "모병", help: "병사 충원" },
  { key: "훈련", help: "병사 훈련도 향상" },
  { key: "농업", help: "도시 농업 수치 개발" },
  { key: "상업", help: "도시 상업 수치 개발" },
  { key: "치안", help: "도시 치안 유지" },
  { key: "수비", help: "도시 수비 강화" },
  { key: "성벽", help: "도시 성벽 보수" },
  { key: "기술", help: "기술 연구" },
  { key: "외교", help: "외교 임무 수행" },
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
          <span className="text-[10px] text-green-400">활성 (위 → 높은 우선순위)</span>
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
  const [nationPriority, setNationPriority] = useState<string[]>(
    NATION_PRIORITY_ITEMS.map((i) => i.key),
  );
  const [generalPriority, setGeneralPriority] = useState<string[]>(
    GENERAL_PRIORITY_ITEMS.map((i) => i.key),
  );
  const [npcMode, setNpcMode] = useState<string>("1");
  const [policyLoading, setPolicyLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // Audit trail (last setter info)
  const [lastSetters, setLastSetters] = useState<{
    nation?: { setter: string; date: string };
    general?: { setter: string; date: string };
    policy?: { setter: string; date: string };
  }>({});

  // Snapshots for rollback
  const [prevPolicy, setPrevPolicy] = useState<Record<string, number>>({});
  const [prevNationPriority, setPrevNationPriority] = useState<string[]>([]);
  const [prevGeneralPriority, setPrevGeneralPriority] = useState<string[]>([]);
  const [defaultNationPriority] = useState<string[]>(
    NATION_PRIORITY_ITEMS.map((i) => i.key),
  );
  const [defaultGeneralPriority] = useState<string[]>(
    GENERAL_PRIORITY_ITEMS.map((i) => i.key),
  );

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
          p[key] = (data[key] as number) ?? 0;
        }
        setPolicy(p);
        setPrevPolicy({ ...p });

        if (Array.isArray(data.nationPriority)) {
          setNationPriority(data.nationPriority as string[]);
          setPrevNationPriority([...(data.nationPriority as string[])]);
        } else if (Array.isArray(data.priority)) {
          // Fallback: legacy single priority list — split into nation/general
          setGeneralPriority(data.priority as string[]);
          setPrevGeneralPriority([...(data.priority as string[])]);
        }
        if (Array.isArray(data.generalPriority)) {
          setGeneralPriority(data.generalPriority as string[]);
          setPrevGeneralPriority([...(data.generalPriority as string[])]);
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

  const handlePolicyChange = (key: string, value: number) => {
    setPolicy((prev) => ({ ...prev, [key]: value }));
  };

  const handleSavePolicy = async () => {
    if (!myGeneral?.nationId) return;
    setSaving(true);
    try {
      await npcPolicyApi.updatePolicy(myGeneral.nationId, {
        ...policy,
        npcMode: Number(npcMode),
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
    setNationPriority([...defaultNationPriority]);
    toast.info("국가턴 우선순위를 초기값으로 되돌렸습니다. 저장을 눌러주세요.");
  };
  const handleResetGeneralPriority = () => {
    setGeneralPriority([...defaultGeneralPriority]);
    toast.info("장수턴 우선순위를 초기값으로 되돌렸습니다. 저장을 눌러주세요.");
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
      <PageHeader icon={Bot} title="임시 NPC 정책" />

      <Tabs defaultValue="list">
        <TabsList>
          <TabsTrigger value="list">NPC 장수</TabsTrigger>
          <TabsTrigger value="policy">NPC 정책</TabsTrigger>
          <TabsTrigger value="priority">우선순위</TabsTrigger>
          <TabsTrigger value="override">장수별 설정</TabsTrigger>
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

        {/* Tab 2: NPC policy */}
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

              {/* Policy categories */}
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
                      {cat.fields.map((f) => (
                        <div key={f.key} className="space-y-1">
                          <label className="text-xs text-muted-foreground">
                            {f.label}
                          </label>
                          <Input
                            type="number"
                            value={policy[f.key] ?? 0}
                            onChange={(e) =>
                              handlePolicyChange(f.key, Number(e.target.value))
                            }
                          />
                          {f.hint && (
                            <p className="text-[10px] text-muted-foreground/70">
                              {f.hint}
                            </p>
                          )}
                        </div>
                      ))}
                    </div>
                  </CardContent>
                </Card>
              ))}

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
                NPC 국가턴 우선순위
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <LastSetterInfo setter={lastSetters.nation} />
              <p className="text-xs text-muted-foreground">
                NPC 국가가 턴을 수행할 때의 우선순위입니다. 드래그하거나
                ▲▼ 버튼으로 순서를 변경하세요.
              </p>
              <DraggablePriorityList
                title="국가턴"
                items={NATION_PRIORITY_ITEMS}
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
                NPC 장수턴 우선순위
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <LastSetterInfo setter={lastSetters.general} />
              <p className="text-xs text-muted-foreground">
                NPC 장수가 개인 턴을 수행할 때의 우선순위입니다.
              </p>
              <DraggablePriorityList
                title="장수턴"
                items={GENERAL_PRIORITY_ITEMS}
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
      </Tabs>
    </div>
  );
}
