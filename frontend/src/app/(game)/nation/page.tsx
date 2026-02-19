"use client";

import { useEffect, useState, useMemo, useCallback } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import {
  nationApi,
  generalApi,
  cityApi,
  nationPolicyApi,
  diplomacyApi,
} from "@/lib/gameApi";
import type {
  Nation,
  General,
  City,
  NationPolicyInfo,
  Diplomacy,
} from "@/types";
import { Flag } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { NationBadge } from "@/components/game/nation-badge";
import { GeneralPortrait } from "@/components/game/general-portrait";
import {
  formatOfficerLevelText,
  getNPCColor,
  REGION_NAMES,
  CREW_TYPE_NAMES,
} from "@/lib/game-utils";

// ── Constants ──────────────────────────────────────────────────────

const LEVEL_LABELS: Record<number, string> = {
  0: "두목",
  1: "영주",
  2: "군벌",
  3: "주자사",
  4: "주목",
  5: "공",
  6: "왕",
  7: "황제",
};

const MAX_DED_LEVEL = 30;

const DIP_STATE_LABELS: Record<string, { label: string; color: string }> = {
  normal: { label: "통상", color: "gray" },
  nowar: { label: "불가침", color: "cyan" },
  alliance: { label: "동맹", color: "limegreen" },
  war: { label: "교전", color: "red" },
};

// ── Income / expense helpers (legacy parity) ────────────────────────

function calcCityGoldIncome(
  city: City,
  officerCnt: number,
  isCapital: boolean,
  nationLevel: number,
): number {
  if (city.commMax <= 0) return 0;
  const trustRatio = city.trust / 200 + 0.5;
  let v = (city.pop * (city.comm / city.commMax) * trustRatio) / 30;
  v *= 1 + (city.secuMax > 0 ? city.secu / city.secuMax / 10 : 0);
  v *= Math.pow(1.05, officerCnt);
  if (isCapital && nationLevel > 0) v *= 1 + 1 / (3 * nationLevel);
  return Math.round(v);
}

function calcCityRiceIncome(
  city: City,
  officerCnt: number,
  isCapital: boolean,
  nationLevel: number,
): number {
  if (city.agriMax <= 0) return 0;
  const trustRatio = city.trust / 200 + 0.5;
  let v = (city.pop * (city.agri / city.agriMax) * trustRatio) / 30;
  v *= 1 + (city.secuMax > 0 ? city.secu / city.secuMax / 10 : 0);
  v *= Math.pow(1.05, officerCnt);
  if (isCapital && nationLevel > 0) v *= 1 + 1 / (3 * nationLevel);
  return Math.round(v);
}

function calcCityWallRiceIncome(
  city: City,
  officerCnt: number,
  isCapital: boolean,
  nationLevel: number,
): number {
  if (city.wallMax <= 0) return 0;
  let v = (city.def * city.wall) / city.wallMax / 3;
  v *= 1 + (city.secuMax > 0 ? city.secu / city.secuMax / 10 : 0);
  v *= Math.pow(1.05, officerCnt);
  if (isCapital && nationLevel > 0) v *= 1 + 1 / (3 * nationLevel);
  return Math.round(v);
}

function getDedLevel(dedication: number): number {
  return Math.min(Math.ceil(Math.sqrt(dedication) / 10), MAX_DED_LEVEL);
}

function getBill(dedication: number): number {
  return getDedLevel(dedication) * 200 + 400;
}

// ── Sort options ────────────────────────────────────────────────────

type SortOpt<T> = { label: string; key: string; fn: (a: T, b: T) => number };

const GEN_SORTS: SortOpt<General>[] = [
  {
    label: "관직순",
    key: "officer",
    fn: (a, b) => b.officerLevel - a.officerLevel,
  },
  { label: "헌신순", key: "ded", fn: (a, b) => b.dedication - a.dedication },
  { label: "경험순", key: "exp", fn: (a, b) => b.experience - a.experience },
  { label: "통솔순", key: "lead", fn: (a, b) => b.leadership - a.leadership },
  { label: "무력순", key: "str", fn: (a, b) => b.strength - a.strength },
  { label: "지력순", key: "int", fn: (a, b) => b.intel - a.intel },
  { label: "정치순", key: "pol", fn: (a, b) => b.politics - a.politics },
  { label: "매력순", key: "cha", fn: (a, b) => b.charm - a.charm },
  { label: "금순", key: "gold", fn: (a, b) => b.gold - a.gold },
  { label: "쌀순", key: "rice", fn: (a, b) => b.rice - a.rice },
  { label: "병력순", key: "crew", fn: (a, b) => b.crew - a.crew },
  { label: "훈련순", key: "train", fn: (a, b) => b.train - a.train },
  { label: "사기순", key: "atmos", fn: (a, b) => b.atmos - a.atmos },
  { label: "NPC순", key: "npc", fn: (a, b) => a.npcState - b.npcState },
  { label: "이름순", key: "name", fn: (a, b) => a.name.localeCompare(b.name) },
];

const CITY_SORTS: SortOpt<City>[] = [
  { label: "이름순", key: "name", fn: (a, b) => a.name.localeCompare(b.name) },
  { label: "레벨순", key: "level", fn: (a, b) => b.level - a.level },
  { label: "인구순", key: "pop", fn: (a, b) => b.pop - a.pop },
  { label: "민심순", key: "trust", fn: (a, b) => b.trust - a.trust },
  { label: "농업순", key: "agri", fn: (a, b) => b.agri - a.agri },
  { label: "상업순", key: "comm", fn: (a, b) => b.comm - a.comm },
  { label: "치안순", key: "secu", fn: (a, b) => b.secu - a.secu },
  { label: "수비순", key: "def", fn: (a, b) => b.def - a.def },
  { label: "성벽순", key: "wall", fn: (a, b) => b.wall - a.wall },
  { label: "지역순", key: "region", fn: (a, b) => a.region - b.region },
  { label: "인구최대순", key: "popMax", fn: (a, b) => b.popMax - a.popMax },
  { label: "수비최대순", key: "defMax", fn: (a, b) => b.defMax - a.defMax },
];

// ── Main component ──────────────────────────────────────────────────

export default function NationPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const fetchMyGeneral = useGeneralStore((s) => s.fetchMyGeneral);
  const searchParams = useSearchParams();
  const router = useRouter();

  const [nation, setNation] = useState<Nation | null>(null);
  const [generals, setGenerals] = useState<General[]>([]);
  const [cities, setCities] = useState<City[]>([]);
  const [allNations, setAllNations] = useState<Nation[]>([]);
  const [diplomacyList, setDiplomacyList] = useState<Diplomacy[]>([]);
  const [, setPolicy] = useState<NationPolicyInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [generalSort, setGeneralSort] = useState("officer");
  const [citySort, setCitySort] = useState("name");

  // 내무부 edit state
  const [editNotice, setEditNotice] = useState("");
  const [editRate, setEditRate] = useState(20);
  const [editBill, setEditBill] = useState(100);
  const [editSecretLimit, setEditSecretLimit] = useState(12);
  const [saving, setSaving] = useState(false);

  const tabParam = searchParams.get("tab");
  const validTabs = ["info", "generals", "cities", "admin"];
  const activeTab = validTabs.includes(tabParam ?? "") ? tabParam! : "info";
  const isOfficer = (myGeneral?.officerLevel ?? 0) >= 5;

  useEffect(() => {
    if (!currentWorld || myGeneral) return;
    fetchMyGeneral(currentWorld.id).catch(() =>
      setError("장수 정보를 불러올 수 없습니다."),
    );
  }, [currentWorld, myGeneral, fetchMyGeneral]);

  useEffect(() => {
    if (!myGeneral?.nationId || !currentWorld) return;
    const nId = myGeneral.nationId;
    const wId = currentWorld.id;
    const off = myGeneral.officerLevel >= 5;

    const base = [
      nationApi.get(nId),
      generalApi.listByNation(nId),
      cityApi.listByNation(nId),
    ];
    const extra = off
      ? [
          nationPolicyApi.getPolicy(nId),
          diplomacyApi.listByNation(wId, nId),
          nationApi.listByWorld(wId),
        ]
      : [];

    Promise.all([...base, ...extra])
      .then((res) => {
        setNation((res[0] as { data: Nation }).data);
        setGenerals((res[1] as { data: General[] }).data);
        setCities((res[2] as { data: City[] }).data);
        if (off) {
          const pol = (res[3] as { data: NationPolicyInfo }).data;
          setPolicy(pol);
          setEditNotice(pol.notice ?? "");
          setEditRate(pol.rate ?? 20);
          setEditBill(pol.bill ?? 100);
          setEditSecretLimit(pol.secretLimit ?? 12);
          setDiplomacyList((res[4] as { data: Diplomacy[] }).data);
          setAllNations((res[5] as { data: Nation[] }).data);
        }
      })
      .catch(() => setError("국가 정보를 불러올 수 없습니다."))
      .finally(() => setLoading(false));
  }, [myGeneral?.nationId, myGeneral?.officerLevel, currentWorld]);

  const handleTabChange = (value: string) => {
    router.replace(value === "info" ? "/nation" : `/nation?tab=${value}`, {
      scroll: false,
    });
  };

  // ── Income calculations ──────────────────────────────────────────

  const officerCntByCity = useMemo(() => {
    const m = new Map<number, number>();
    generals.forEach((g) => {
      if (g.officerLevel >= 2 && g.officerLevel <= 4 && g.officerCity > 0) {
        m.set(g.officerCity, (m.get(g.officerCity) || 0) + 1);
      }
    });
    return m;
  }, [generals]);

  const income = useMemo(() => {
    if (!nation || !cities.length) {
      return { goldCity: 0, goldWar: 0, riceCity: 0, riceWall: 0, outcome: 0 };
    }
    let gcBase = 0,
      rcBase = 0,
      rwBase = 0,
      gw = 0;
    cities.forEach((c) => {
      const cnt = officerCntByCity.get(c.id) || 0;
      const cap = c.id === nation.capitalCityId;
      gcBase += calcCityGoldIncome(c, cnt, cap, nation.level);
      rcBase += calcCityRiceIncome(c, cnt, cap, nation.level);
      rwBase += calcCityWallRiceIncome(c, cnt, cap, nation.level);
      gw += c.supplyState ? Math.round(c.dead / 10) : 0;
    });
    const goldCity = Math.round((gcBase * nation.rate) / 20);
    const riceCity = Math.round((rcBase * nation.rate) / 20);
    const riceWall = Math.round((rwBase * nation.rate) / 20);
    const baseOut = generals
      .filter((g) => g.npcState !== 5)
      .reduce((s, g) => s + getBill(g.dedication), 0);
    const outcome = Math.round((baseOut * nation.bill) / 100);
    return { goldCity, goldWar: gw, riceCity, riceWall, outcome };
  }, [nation, cities, generals, officerCntByCity]);

  // ── Policy save ──────────────────────────────────────────────────

  const saveNotice = useCallback(async () => {
    if (!nation) return;
    setSaving(true);
    try {
      await nationPolicyApi.updateNotice(nation.id, editNotice);
    } finally {
      setSaving(false);
    }
  }, [nation, editNotice]);

  const savePolicy = useCallback(
    async (field: string, value: unknown) => {
      if (!nation) return;
      setSaving(true);
      try {
        await nationPolicyApi.updatePolicy(nation.id, { [field]: value });
      } finally {
        setSaving(false);
      }
    },
    [nation],
  );

  // ── Sorted data ──────────────────────────────────────────────────

  const sortedGenerals = useMemo(() => {
    const opt = GEN_SORTS.find((o) => o.key === generalSort);
    return opt ? [...generals].sort(opt.fn) : generals;
  }, [generals, generalSort]);

  const sortedCities = useMemo(() => {
    const opt = CITY_SORTS.find((o) => o.key === citySort);
    return opt ? [...cities].sort(opt.fn) : cities;
  }, [cities, citySort]);

  const cityOfficers = useMemo(() => {
    const m = new Map<
      number,
      { taesu?: General; gunsa?: General; jongsa?: General }
    >();
    generals.forEach((g) => {
      if (g.officerCity > 0 && g.officerLevel >= 2 && g.officerLevel <= 4) {
        const e = m.get(g.officerCity) || {};
        if (g.officerLevel === 4) e.taesu = g;
        else if (g.officerLevel === 3) e.gunsa = g;
        else if (g.officerLevel === 2) e.jongsa = g;
        m.set(g.officerCity, e);
      }
    });
    return m;
  }, [generals]);

  // ── Render guards ────────────────────────────────────────────────

  if (!currentWorld) return <LoadingState message="월드를 선택해주세요." />;
  if (loading) return <LoadingState />;
  if (error) return <div className="p-4 text-red-400">{error}</div>;
  if (!myGeneral?.nationId)
    return (
      <div className="p-4 text-muted-foreground">소속 국가가 없습니다.</div>
    );
  if (!nation) return <LoadingState message="국가 정보가 없습니다." />;

  // ── Derived ──────────────────────────────────────────────────────

  const capitalCity = cities.find((c) => c.id === nation.capitalCityId);
  const totalCrew = generals.reduce((s, g) => s + g.crew, 0);
  const maxCrew = generals
    .filter((g) => g.npcState !== 5)
    .reduce((s, g) => s + g.leadership * 100, 0);
  const totalPop = cities.reduce((s, c) => s + c.pop, 0);
  const maxPop = cities.reduce((s, c) => s + c.popMax, 0);
  const { goldCity, goldWar, riceCity, riceWall, outcome } = income;
  const totalGold = goldCity + goldWar;
  const totalRice = riceCity + riceWall;
  const goldDiff = totalGold - outcome;
  const riceDiff = totalRice - outcome;
  const fmtDiff = (v: number) =>
    v >= 0 ? `+${v.toLocaleString()}` : v.toLocaleString();

  return (
    <div className="space-y-3 max-w-5xl">
      <PageHeader icon={Flag} title={nation.name} />

      <Tabs value={activeTab} onValueChange={handleTabChange}>
        <TabsList>
          <TabsTrigger value="info">세력정보</TabsTrigger>
          <TabsTrigger value="generals">
            세력장수 ({generals.length})
          </TabsTrigger>
          <TabsTrigger value="cities">세력도시 ({cities.length})</TabsTrigger>
          {isOfficer && <TabsTrigger value="admin">내무부</TabsTrigger>}
        </TabsList>

        {/* ── Tab 1: 세력정보 ── */}
        <TabsContent value="info">
          <Card>
            <CardContent>
              <div className="flex flex-wrap items-center gap-2 mb-3">
                <NationBadge name={nation.name} color={nation.color} />
                <Badge variant="secondary">
                  {LEVEL_LABELS[nation.level] ?? `Lv.${nation.level}`}
                </Badge>
                <Badge variant="outline">{nation.typeCode}</Badge>
                {capitalCity && (
                  <Badge variant="outline" className="text-cyan-400">
                    수도: {capitalCity.name}
                  </Badge>
                )}
              </div>

              {/* Legacy-style 4-col info grid */}
              <div
                className="grid text-xs border border-gray-600"
                style={{ gridTemplateColumns: "6rem 1fr 6rem 1fr" }}
              >
                <LCell>총주민</LCell>
                <VCell>
                  {totalPop.toLocaleString()} / {maxPop.toLocaleString()}
                </VCell>
                <LCell>총병사</LCell>
                <VCell>
                  {totalCrew.toLocaleString()} / {maxCrew.toLocaleString()}
                </VCell>

                <LCell>국 고</LCell>
                <VCell className="text-yellow-400">
                  {nation.gold.toLocaleString()}
                </VCell>
                <LCell>병 량</LCell>
                <VCell className="text-green-400">
                  {nation.rice.toLocaleString()}
                </VCell>

                <LCell>세금/단기</LCell>
                <VCell>
                  +{goldCity.toLocaleString()} / +{goldWar.toLocaleString()}
                </VCell>
                <LCell>세곡/둔전</LCell>
                <VCell>
                  +{riceCity.toLocaleString()} / +{riceWall.toLocaleString()}
                </VCell>

                <LCell>수입/지출</LCell>
                <VCell>
                  <span className="text-green-400">
                    +{totalGold.toLocaleString()}
                  </span>
                  {" / "}
                  <span className="text-red-400">
                    -{outcome.toLocaleString()}
                  </span>
                </VCell>
                <LCell>수입/지출</LCell>
                <VCell>
                  <span className="text-green-400">
                    +{totalRice.toLocaleString()}
                  </span>
                  {" / "}
                  <span className="text-red-400">
                    -{outcome.toLocaleString()}
                  </span>
                </VCell>

                <LCell>국고 예산</LCell>
                <VCell>
                  {(nation.gold + goldDiff).toLocaleString()}{" "}
                  <span
                    className={
                      goldDiff >= 0 ? "text-green-400" : "text-red-400"
                    }
                  >
                    ({fmtDiff(goldDiff)})
                  </span>
                </VCell>
                <LCell>병량 예산</LCell>
                <VCell>
                  {(nation.rice + riceDiff).toLocaleString()}{" "}
                  <span
                    className={
                      riceDiff >= 0 ? "text-green-400" : "text-red-400"
                    }
                  >
                    ({fmtDiff(riceDiff)})
                  </span>
                </VCell>

                <LCell>세 율</LCell>
                <VCell>{nation.rate}%</VCell>
                <LCell>지급률</LCell>
                <VCell>{nation.bill}%</VCell>

                <LCell>국 력</LCell>
                <VCell>{nation.power.toLocaleString()}</VCell>
                <LCell>기술력</LCell>
                <VCell>{Math.floor(nation.tech).toLocaleString()}</VCell>

                <LCell>속 령</LCell>
                <VCell>{cities.length}개</VCell>
                <LCell>장 수</LCell>
                <VCell>{generals.length}명</VCell>

                <LCell>작 위</LCell>
                <VCell>
                  {LEVEL_LABELS[nation.level] ?? `Lv.${nation.level}`}
                </VCell>
                <LCell>전 쟁</LCell>
                <VCell>
                  {nation.warState ? (
                    <span className="text-red-400">교전중</span>
                  ) : (
                    <span className="text-green-400">평화</span>
                  )}
                </VCell>
              </div>

              {/* City list */}
              <div className="mt-2 text-xs">
                <span className="text-muted-foreground">속령일람: </span>
                {cities.map((c, i) => (
                  <span key={c.id}>
                    {i > 0 && ", "}
                    <span
                      className={
                        c.id === nation.capitalCityId ? "text-cyan-400" : ""
                      }
                    >
                      {c.name}
                    </span>
                  </span>
                ))}
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Tab 2: 세력장수 ── */}
        <TabsContent value="generals">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center justify-between">
                <span>세력장수 ({generals.length}명)</span>
                <Select value={generalSort} onValueChange={setGeneralSort}>
                  <SelectTrigger size="sm" className="w-28">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {GEN_SORTS.map((o) => (
                      <SelectItem key={o.key} value={o.key}>
                        {o.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </CardTitle>
            </CardHeader>
            <CardContent>
              {generals.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  장수가 없습니다.
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>이름</TableHead>
                        <TableHead className="text-center">관직</TableHead>
                        <TableHead className="text-center">NPC</TableHead>
                        <TableHead className="text-center">통</TableHead>
                        <TableHead className="text-center">무</TableHead>
                        <TableHead className="text-center">지</TableHead>
                        <TableHead className="text-center">정</TableHead>
                        <TableHead className="text-center">매</TableHead>
                        <TableHead className="text-right">병력</TableHead>
                        <TableHead className="text-center">병종</TableHead>
                        <TableHead className="text-center">훈련</TableHead>
                        <TableHead className="text-center">사기</TableHead>
                        <TableHead className="text-right">금</TableHead>
                        <TableHead className="text-right">쌀</TableHead>
                        <TableHead className="text-right">헌신</TableHead>
                        <TableHead className="text-right">경험</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {sortedGenerals.map((g) => (
                        <TableRow key={g.id}>
                          <TableCell>
                            <div className="flex items-center gap-1.5">
                              <GeneralPortrait
                                picture={g.picture}
                                name={g.name}
                                size="sm"
                              />
                              <span
                                className="font-medium whitespace-nowrap"
                                style={{ color: getNPCColor(g.npcState) }}
                              >
                                {g.name}
                              </span>
                            </div>
                          </TableCell>
                          <TableCell className="text-center text-xs whitespace-nowrap">
                            {formatOfficerLevelText(
                              g.officerLevel,
                              nation.level,
                            )}
                          </TableCell>
                          <TableCell className="text-center text-xs">
                            {g.npcState > 0 ? (
                              <span style={{ color: getNPCColor(g.npcState) }}>
                                NPC
                              </span>
                            ) : (
                              "유저"
                            )}
                          </TableCell>
                          <TableCell className="text-center tabular-nums">
                            {g.leadership}
                          </TableCell>
                          <TableCell className="text-center tabular-nums">
                            {g.strength}
                          </TableCell>
                          <TableCell className="text-center tabular-nums">
                            {g.intel}
                          </TableCell>
                          <TableCell className="text-center tabular-nums">
                            {g.politics}
                          </TableCell>
                          <TableCell className="text-center tabular-nums">
                            {g.charm}
                          </TableCell>
                          <TableCell className="text-right tabular-nums">
                            {g.crew.toLocaleString()}
                          </TableCell>
                          <TableCell className="text-center text-xs">
                            {CREW_TYPE_NAMES[g.crewType] ?? "?"}
                          </TableCell>
                          <TableCell className="text-center tabular-nums">
                            {g.train}
                          </TableCell>
                          <TableCell className="text-center tabular-nums">
                            {g.atmos}
                          </TableCell>
                          <TableCell className="text-right tabular-nums text-yellow-400">
                            {g.gold.toLocaleString()}
                          </TableCell>
                          <TableCell className="text-right tabular-nums text-green-400">
                            {g.rice.toLocaleString()}
                          </TableCell>
                          <TableCell className="text-right tabular-nums">
                            {g.dedication.toLocaleString()}
                          </TableCell>
                          <TableCell className="text-right tabular-nums">
                            {g.experience.toLocaleString()}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Tab 3: 세력도시 ── */}
        <TabsContent value="cities">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center justify-between">
                <span>세력도시 ({cities.length}개)</span>
                <Select value={citySort} onValueChange={setCitySort}>
                  <SelectTrigger size="sm" className="w-32">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {CITY_SORTS.map((o) => (
                      <SelectItem key={o.key} value={o.key}>
                        {o.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </CardTitle>
            </CardHeader>
            <CardContent>
              {cities.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  도시가 없습니다.
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>이름</TableHead>
                        <TableHead className="text-center">레벨</TableHead>
                        <TableHead className="text-center">지역</TableHead>
                        <TableHead className="text-right">인구</TableHead>
                        <TableHead className="text-center">민심</TableHead>
                        <TableHead className="text-right">농업</TableHead>
                        <TableHead className="text-right">상업</TableHead>
                        <TableHead className="text-center">치안</TableHead>
                        <TableHead className="text-right">수비</TableHead>
                        <TableHead className="text-right">성벽</TableHead>
                        <TableHead className="text-center">태수</TableHead>
                        <TableHead className="text-center">군사</TableHead>
                        <TableHead className="text-center">종사</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {sortedCities.map((c) => {
                        const off = cityOfficers.get(c.id);
                        return (
                          <TableRow
                            key={c.id}
                            className={
                              c.id === nation.capitalCityId
                                ? "border-l-2 border-l-cyan-400"
                                : ""
                            }
                          >
                            <TableCell className="font-medium whitespace-nowrap">
                              {c.name}
                              {c.id === nation.capitalCityId && (
                                <span className="text-cyan-400 text-xs ml-1">
                                  (수도)
                                </span>
                              )}
                            </TableCell>
                            <TableCell className="text-center">
                              {c.level}
                            </TableCell>
                            <TableCell className="text-center text-xs">
                              {REGION_NAMES[c.region] ?? c.region}
                            </TableCell>
                            <TableCell className="text-right tabular-nums">
                              {c.pop.toLocaleString()}
                              <span className="text-muted-foreground">
                                /{c.popMax.toLocaleString()}
                              </span>
                            </TableCell>
                            <TableCell className="text-center tabular-nums">
                              {c.trust}
                            </TableCell>
                            <TableCell className="text-right tabular-nums">
                              {c.agri}
                              <span className="text-muted-foreground">
                                /{c.agriMax}
                              </span>
                            </TableCell>
                            <TableCell className="text-right tabular-nums">
                              {c.comm}
                              <span className="text-muted-foreground">
                                /{c.commMax}
                              </span>
                            </TableCell>
                            <TableCell className="text-center tabular-nums">
                              {c.secu}
                              <span className="text-muted-foreground">
                                /{c.secuMax}
                              </span>
                            </TableCell>
                            <TableCell className="text-right tabular-nums">
                              {c.def}
                              <span className="text-muted-foreground">
                                /{c.defMax}
                              </span>
                            </TableCell>
                            <TableCell className="text-right tabular-nums">
                              {c.wall}
                              <span className="text-muted-foreground">
                                /{c.wallMax}
                              </span>
                            </TableCell>
                            <TableCell className="text-center text-xs whitespace-nowrap">
                              {off?.taesu ? (
                                <span
                                  style={{
                                    color: getNPCColor(off.taesu.npcState),
                                  }}
                                >
                                  {off.taesu.name}
                                </span>
                              ) : (
                                "-"
                              )}
                            </TableCell>
                            <TableCell className="text-center text-xs whitespace-nowrap">
                              {off?.gunsa ? (
                                <span
                                  style={{
                                    color: getNPCColor(off.gunsa.npcState),
                                  }}
                                >
                                  {off.gunsa.name}
                                </span>
                              ) : (
                                "-"
                              )}
                            </TableCell>
                            <TableCell className="text-center text-xs whitespace-nowrap">
                              {off?.jongsa ? (
                                <span
                                  style={{
                                    color: getNPCColor(off.jongsa.npcState),
                                  }}
                                >
                                  {off.jongsa.name}
                                </span>
                              ) : (
                                "-"
                              )}
                            </TableCell>
                          </TableRow>
                        );
                      })}
                    </TableBody>
                  </Table>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── Tab 4: 내무부 ── */}
        {isOfficer && (
          <TabsContent value="admin">
            <div className="space-y-3">
              {/* Nation Notice */}
              <Card>
                <CardHeader>
                  <CardTitle>국가 공지</CardTitle>
                </CardHeader>
                <CardContent>
                  <Textarea
                    value={editNotice}
                    onChange={(e) => setEditNotice(e.target.value)}
                    rows={3}
                    placeholder="국가 공지사항을 입력하세요..."
                    disabled={myGeneral.officerLevel < 5}
                  />
                  {myGeneral.officerLevel >= 5 && (
                    <Button
                      size="sm"
                      className="mt-2"
                      onClick={saveNotice}
                      disabled={saving}
                    >
                      공지 저장
                    </Button>
                  )}
                </CardContent>
              </Card>

              {/* Policy Controls */}
              <Card>
                <CardHeader>
                  <CardTitle>정책 설정</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    <PolicyField
                      label="세율 (5~30%)"
                      value={editRate}
                      min={5}
                      max={30}
                      unit="%"
                      editable={myGeneral.officerLevel >= 5}
                      saving={saving}
                      onChange={setEditRate}
                      onSave={() => savePolicy("rate", editRate)}
                    />
                    <PolicyField
                      label="지급률 (20~200%)"
                      value={editBill}
                      min={20}
                      max={200}
                      unit="%"
                      editable={myGeneral.officerLevel >= 5}
                      saving={saving}
                      onChange={setEditBill}
                      onSave={() => savePolicy("bill", editBill)}
                    />
                    <PolicyField
                      label="기밀 권한 (사관년도)"
                      value={editSecretLimit}
                      min={1}
                      max={99}
                      unit="년"
                      editable={myGeneral.officerLevel >= 5}
                      saving={saving}
                      onChange={setEditSecretLimit}
                      onSave={() => savePolicy("secretLimit", editSecretLimit)}
                    />
                  </div>

                  {myGeneral.officerLevel >= 5 && (
                    <div className="flex gap-3 mt-4 pt-3 border-t border-gray-600">
                      <Button
                        size="sm"
                        variant={nation.scoutLevel ? "destructive" : "outline"}
                        onClick={() =>
                          savePolicy("blockScout", !nation.scoutLevel)
                        }
                        disabled={saving}
                      >
                        임관{" "}
                        {nation.scoutLevel ? "금지중 → 허가" : "허가중 → 금지"}
                      </Button>
                      <Button
                        size="sm"
                        variant={nation.warState ? "destructive" : "outline"}
                        onClick={() => savePolicy("blockWar", !nation.warState)}
                        disabled={saving}
                      >
                        전쟁{" "}
                        {nation.warState ? "금지중 → 허가" : "허가중 → 금지"}
                      </Button>
                    </div>
                  )}
                </CardContent>
              </Card>

              {/* Income/Expense Detail */}
              <Card>
                <CardHeader>
                  <CardTitle>수입/지출 상세</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="grid grid-cols-2 gap-4 text-sm">
                    <div className="space-y-1">
                      <h4 className="font-medium text-yellow-400">금 수입</h4>
                      <IncomeRow label="세금 (도시)" value={goldCity} />
                      <IncomeRow label="단기 (전쟁)" value={goldWar} />
                      <div className="flex justify-between font-medium border-t border-gray-600 pt-1">
                        <span>합계</span>
                        <span>+{totalGold.toLocaleString()}</span>
                      </div>
                    </div>
                    <div className="space-y-1">
                      <h4 className="font-medium text-green-400">쌀 수입</h4>
                      <IncomeRow label="세곡 (농업)" value={riceCity} />
                      <IncomeRow label="둔전 (성벽)" value={riceWall} />
                      <div className="flex justify-between font-medium border-t border-gray-600 pt-1">
                        <span>합계</span>
                        <span>+{totalRice.toLocaleString()}</span>
                      </div>
                    </div>
                  </div>
                  <div className="mt-3 pt-3 border-t border-gray-600 text-sm space-y-1">
                    <div className="flex justify-between">
                      <span className="text-muted-foreground">지출 (녹봉)</span>
                      <span className="text-red-400">
                        -{outcome.toLocaleString()}
                      </span>
                    </div>
                    <div className="flex justify-between font-medium">
                      <span>금 순수익</span>
                      <span
                        className={
                          goldDiff >= 0 ? "text-green-400" : "text-red-400"
                        }
                      >
                        {fmtDiff(goldDiff)}
                      </span>
                    </div>
                    <div className="flex justify-between font-medium">
                      <span>쌀 순수익</span>
                      <span
                        className={
                          riceDiff >= 0 ? "text-green-400" : "text-red-400"
                        }
                      >
                        {fmtDiff(riceDiff)}
                      </span>
                    </div>
                  </div>
                </CardContent>
              </Card>

              {/* Diplomatic Relations */}
              {diplomacyList.length > 0 && (
                <Card>
                  <CardHeader>
                    <CardTitle>외교 관계</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="overflow-x-auto">
                      <Table>
                        <TableHeader>
                          <TableRow>
                            <TableHead>국가</TableHead>
                            <TableHead className="text-center">관계</TableHead>
                            <TableHead className="text-center">
                              잔여기한
                            </TableHead>
                          </TableRow>
                        </TableHeader>
                        <TableBody>
                          {diplomacyList
                            .filter(
                              (d) => d.destNationId !== nation.id && !d.isDead,
                            )
                            .map((d) => {
                              const dest = allNations.find(
                                (n) => n.id === d.destNationId,
                              );
                              const info = DIP_STATE_LABELS[d.stateCode] ?? {
                                label: d.stateCode,
                                color: "gray",
                              };
                              return (
                                <TableRow key={d.id}>
                                  <TableCell>
                                    <NationBadge
                                      name={dest?.name}
                                      color={dest?.color}
                                    />
                                  </TableCell>
                                  <TableCell className="text-center">
                                    <span style={{ color: info.color }}>
                                      {info.label}
                                    </span>
                                  </TableCell>
                                  <TableCell className="text-center tabular-nums">
                                    {d.term > 0 ? `${d.term}개월` : "-"}
                                  </TableCell>
                                </TableRow>
                              );
                            })}
                        </TableBody>
                      </Table>
                    </div>
                  </CardContent>
                </Card>
              )}
            </div>
          </TabsContent>
        )}
      </Tabs>
    </div>
  );
}

// ── Sub-components ──────────────────────────────────────────────────

function LCell({ children }: { children: React.ReactNode }) {
  return (
    <div className="legacy-bg1 px-2 py-1 text-center border-t border-r border-gray-600 whitespace-nowrap">
      {children}
    </div>
  );
}

function VCell({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={`px-2 py-1 text-center tabular-nums border-t border-r border-gray-600 ${className ?? ""}`}
    >
      {children}
    </div>
  );
}

function IncomeRow({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex justify-between">
      <span className="text-muted-foreground">{label}</span>
      <span>+{value.toLocaleString()}</span>
    </div>
  );
}

function PolicyField({
  label,
  value,
  min,
  max,
  unit,
  editable,
  saving,
  onChange,
  onSave,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  unit: string;
  editable: boolean;
  saving: boolean;
  onChange: (v: number) => void;
  onSave: () => void;
}) {
  return (
    <div className="space-y-1">
      <label className="text-xs text-muted-foreground">{label}</label>
      <div className="flex items-center gap-2">
        <Input
          type="number"
          min={min}
          max={max}
          value={value}
          onChange={(e) => onChange(Number(e.target.value))}
          className="w-20"
          disabled={!editable}
        />
        <span className="text-sm">{unit}</span>
        {editable && (
          <Button
            size="sm"
            variant="outline"
            onClick={onSave}
            disabled={saving}
          >
            적용
          </Button>
        )}
      </div>
    </div>
  );
}
