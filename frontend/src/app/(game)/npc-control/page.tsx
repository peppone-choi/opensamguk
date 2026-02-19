"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { useGeneralStore } from "@/stores/generalStore";
import { npcPolicyApi } from "@/lib/gameApi";
import { Bot, Settings, ListOrdered, Users, Crosshair } from "lucide-react";
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
import { CREW_TYPE_NAMES } from "@/lib/game-utils";

// Policy fields organized by category
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

const PRIORITY_ITEMS = [
  "전쟁",
  "모병",
  "훈련",
  "농업",
  "상업",
  "치안",
  "수비",
  "성벽",
  "기술",
  "외교",
];

const NPC_MODE_LABELS: Record<number, string> = {
  0: "전투형 (공격 우선)",
  1: "균형형 (기본)",
  2: "내정형 (개발 우선)",
};

export default function NpcPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, cities, loading, loadAll } = useGameStore();

  const [policy, setPolicy] = useState<Record<string, number>>({});
  const [priority, setPriority] = useState<string[]>([...PRIORITY_ITEMS]);
  const [npcMode, setNpcMode] = useState<string>("1");
  const [policyLoading, setPolicyLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState("");

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

  useEffect(() => {
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
        if (Array.isArray(data.priority)) {
          setPriority(data.priority as string[]);
        }
        if (data.npcMode != null) {
          setNpcMode(String(data.npcMode));
        }
      })
      .catch(() => {})
      .finally(() => setPolicyLoading(false));
  }, [myGeneral?.nationId]);

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
    setMsg("");
    try {
      await npcPolicyApi.updatePolicy(myGeneral.nationId, {
        ...policy,
        npcMode: Number(npcMode),
      });
      setMsg("NPC 정책이 저장되었습니다.");
    } catch {
      setMsg("저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const handleSavePriority = async () => {
    if (!myGeneral?.nationId) return;
    setSaving(true);
    setMsg("");
    try {
      await npcPolicyApi.updatePriority(myGeneral.nationId, { priority });
      setMsg("우선순위가 저장되었습니다.");
    } catch {
      setMsg("저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const handleSaveGeneralOverride = async () => {
    if (!myGeneral?.nationId || !selectedNpcId) return;
    setSaving(true);
    setMsg("");
    try {
      await npcPolicyApi.updatePolicy(myGeneral.nationId, {
        generalOverrides: {
          [selectedNpcId]: generalOverride,
        },
      });
      setMsg("장수별 설정이 저장되었습니다.");
    } catch {
      setMsg("저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const movePriority = (idx: number, dir: -1 | 1) => {
    const next = idx + dir;
    if (next < 0 || next >= priority.length) return;
    const arr = [...priority];
    [arr[idx], arr[next]] = [arr[next], arr[idx]];
    setPriority(arr);
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

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <PageHeader icon={Bot} title="임시 NPC 정책" />

      {msg && <p className="text-sm text-green-400">{msg}</p>}

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

              <Button onClick={handleSavePolicy} disabled={saving}>
                {saving ? "저장 중..." : "정책 저장"}
              </Button>
            </>
          )}
        </TabsContent>

        {/* Tab 3: Priority */}
        <TabsContent value="priority" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <ListOrdered className="size-4" />
                AI 우선순위
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <p className="text-xs text-muted-foreground mb-2">
                NPC 장수가 행동할 때의 우선순위를 설정합니다. 위에 있는 항목이
                먼저 수행됩니다.
              </p>
              {priority.map((item, idx) => (
                <div
                  key={item}
                  className="flex items-center gap-2 rounded border border-gray-700 p-2"
                >
                  <span className="w-6 text-center text-xs text-muted-foreground tabular-nums">
                    {idx + 1}
                  </span>
                  <span className="flex-1 text-sm">{item}</span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={idx === 0}
                    onClick={() => movePriority(idx, -1)}
                  >
                    ▲
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={idx === priority.length - 1}
                    onClick={() => movePriority(idx, 1)}
                  >
                    ▼
                  </Button>
                </div>
              ))}
              <Button onClick={handleSavePriority} disabled={saving}>
                {saving ? "저장 중..." : "우선순위 저장"}
              </Button>
            </CardContent>
          </Card>
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
