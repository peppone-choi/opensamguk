"use client";

import { useEffect, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import {
  cityApi,
  diplomacyApi,
  generalApi,
  nationApi,
  nationManagementApi,
  nationPolicyApi,
} from "@/lib/gameApi";
import type { City, Diplomacy, General, Nation } from "@/types";
import { Building2, Crown, Search } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { NationBadge } from "@/components/game/nation-badge";
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

function getBill(dedication: number): number {
  return Math.min(Math.ceil(Math.sqrt(dedication) / 10), 30) * 200 + 400;
}

type SortKey =
  | "name"
  | "level"
  | "pop"
  | "agri"
  | "comm"
  | "secu"
  | "def"
  | "wall"
  | "trust"
  | "trade"
  | "region"
  | "supplyState";

const OFFICER_TITLES: Record<number, string> = {
  4: "태수",
  3: "군사",
  2: "종사",
};

const REGION_NAMES: Record<number, string> = {
  0: "중원",
  1: "하북",
  2: "서북",
  3: "서남",
  4: "강남",
  5: "형초",
  6: "산동",
  7: "요동",
  8: "강동",
};

export default function NationCitiesPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const fetchMyGeneral = useGeneralStore((s) => s.fetchMyGeneral);

  const [cities, setCities] = useState<City[] | null>(null);
  const [allNations, setAllNations] = useState<Nation[]>([]);
  const [myNation, setMyNation] = useState<Nation | null>(null);
  const [nationGenerals, setNationGenerals] = useState<General[]>([]);
  const [diplomacyList, setDiplomacyList] = useState<Diplomacy[]>([]);
  const [rate, setRate] = useState(20);
  const [bill, setBill] = useState(100);
  const [savingPolicy, setSavingPolicy] = useState(false);
  const [saveMsg, setSaveMsg] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");
  const [cityFilter, setCityFilter] = useState("");
  const [appointMode, setAppointMode] = useState(false);
  const [appointCity, setAppointCity] = useState<number | null>(null);
  const [appointLevel, setAppointLevel] = useState<number>(4);
  const [appointGeneralId, setAppointGeneralId] = useState<string>("");
  const [appointSaving, setAppointSaving] = useState(false);

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) {
      fetchMyGeneral(currentWorld.id).catch(() =>
        setError("장수 정보를 불러올 수 없습니다."),
      );
    }
  }, [currentWorld, myGeneral, fetchMyGeneral]);

  useEffect(() => {
    if (!myGeneral?.nationId || !currentWorld) return;
    Promise.all([
      cityApi.listByNation(myGeneral.nationId),
      nationApi.listByWorld(currentWorld.id),
      nationApi.get(myGeneral.nationId),
      generalApi.listByNation(myGeneral.nationId),
      diplomacyApi.listByNation(currentWorld.id, myGeneral.nationId),
      nationPolicyApi.getPolicy(myGeneral.nationId),
    ])
      .then(
        ([
          cityRes,
          allNationsRes,
          myNationRes,
          nationGeneralsRes,
          diplomacyRes,
          policyRes,
        ]) => {
          setCities(cityRes.data);
          setAllNations(allNationsRes.data);
          setMyNation(myNationRes.data);
          setNationGenerals(nationGeneralsRes.data);
          setDiplomacyList(diplomacyRes.data);
          setRate(Number(policyRes.data.rate) || 20);
          setBill(Number(policyRes.data.bill) || 100);
        },
      )
      .catch(() => setError("국가 도시 정보를 불러올 수 없습니다."));
  }, [myGeneral?.nationId, currentWorld]);

  const handleSavePolicy = async () => {
    if (!myGeneral?.nationId) return;
    setSavingPolicy(true);
    setSaveMsg("");
    try {
      await nationPolicyApi.updatePolicy(myGeneral.nationId, { rate, bill });
      setSaveMsg("정책이 저장되었습니다.");
    } catch {
      setSaveMsg("정책 저장에 실패했습니다.");
    } finally {
      setSavingPolicy(false);
    }
  };

  const handleAppoint = async () => {
    if (!myGeneral?.nationId || !appointCity || !appointGeneralId) return;
    setAppointSaving(true);
    try {
      await nationManagementApi.appointOfficer(myGeneral.nationId, {
        generalId: Number(appointGeneralId),
        officerLevel: appointLevel,
        officerCity: appointCity,
      });
      setSaveMsg("관직 임명 완료");
      setAppointCity(null);
      setAppointGeneralId("");
      // Reload generals
      const res = await generalApi.listByNation(myGeneral.nationId);
      setNationGenerals(res.data);
    } catch {
      setSaveMsg("관직 임명 실패");
    } finally {
      setAppointSaving(false);
    }
  };

  const getCityOfficers = (cityId: number) => {
    return nationGenerals
      .filter((g) => g.cityId === cityId && g.officerLevel >= 2 && g.officerLevel <= 4)
      .sort((a, b) => b.officerLevel - a.officerLevel);
  };

  const getUnassignedGenerals = () => {
    return nationGenerals.filter((g) => g.officerLevel < 2);
  };

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("desc");
    }
  };

  const arrow = (key: SortKey) =>
    sortKey === key ? (sortDir === "asc" ? " ▲" : " ▼") : "";

  const filtered = (cities ?? []).filter((c) =>
    cityFilter ? c.name.toLowerCase().includes(cityFilter.toLowerCase()) : true
  );

  const sorted = [...filtered].sort((a, b) => {
    let cmp = 0;
    if (sortKey === "name") {
      cmp = a.name.localeCompare(b.name);
    } else {
      cmp = (a[sortKey] as number) - (b[sortKey] as number);
    }
    return sortDir === "asc" ? cmp : -cmp;
  });

  const nationMap = new Map(allNations.map((n) => [n.id, n]));

  const diplomacyRows = diplomacyList
    .filter((d) => !d.isDead)
    .map((d) => {
      const otherNationId =
        d.srcNationId === myGeneral?.nationId ? d.destNationId : d.srcNationId;
      return {
        id: d.id,
        nation: nationMap.get(otherNationId),
        relation: d.stateCode,
        term: d.term,
      };
    });

  const budgetRows = sorted.map((c) => {
    const officerCnt = nationGenerals.filter((g) => g.cityId === c.id).length;
    const goldCity = calcCityGoldIncome(
      c,
      officerCnt,
      myNation?.capitalCityId === c.id,
      myNation?.level ?? 0,
    );
    const goldWar = Math.round((goldCity * rate) / 100);
    const riceCity = calcCityRiceIncome(
      c,
      officerCnt,
      myNation?.capitalCityId === c.id,
      myNation?.level ?? 0,
    );
    const riceWall = calcCityWallRiceIncome(
      c,
      officerCnt,
      myNation?.capitalCityId === c.id,
      myNation?.level ?? 0,
    );
    const expense = nationGenerals
      .filter((g) => g.cityId === c.id)
      .reduce((sum, g) => sum + getBill(g.dedication), 0);

    return {
      city: c,
      goldIncome: goldCity + goldWar,
      riceIncome: riceCity + riceWall,
      expense,
      goldNet: goldCity + goldWar - expense,
    };
  });

  const totalGoldIncome = budgetRows.reduce(
    (sum, row) => sum + row.goldIncome,
    0,
  );
  const totalRiceIncome = budgetRows.reduce(
    (sum, row) => sum + row.riceIncome,
    0,
  );
  const totalExpense = budgetRows.reduce((sum, row) => sum + row.expense, 0);

  if (!currentWorld) return <LoadingState message="월드를 선택해주세요." />;
  if (myGeneral?.nationId && cities === null) return <LoadingState />;
  if (error) return <div className="p-4 text-red-400">{error}</div>;
  if (!myGeneral?.nationId)
    return (
      <div className="p-4">
        <EmptyState
          icon={Building2}
          title="소속 국가가 없습니다."
          description="국가에 가입한 후 이용할 수 있습니다."
        />
      </div>
    );

  const columns: { key: SortKey; label: string }[] = [
    { key: "name", label: "이름" },
    { key: "region", label: "지역" },
    { key: "level", label: "레벨" },
    { key: "pop", label: "인구" },
    { key: "trust", label: "민심" },
    { key: "trade", label: "시세" },
    { key: "agri", label: "농업" },
    { key: "comm", label: "상업" },
    { key: "secu", label: "치안" },
    { key: "def", label: "수비" },
    { key: "wall", label: "성벽" },
    { key: "supplyState", label: "보급" },
  ];

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Building2} title="세력 도시" />

      <Card>
        <CardHeader>
          <CardTitle>국가 정책 편집</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <label
                htmlFor="nation-policy-rate"
                className="text-xs text-muted-foreground"
              >
                교역율 (5-30)
              </label>
              <Input
                id="nation-policy-rate"
                type="number"
                min={5}
                max={30}
                value={rate}
                onChange={(e) => setRate(Number(e.target.value))}
              />
            </div>
            <div className="space-y-1">
              <label
                htmlFor="nation-policy-bill"
                className="text-xs text-muted-foreground"
              >
                세율 (20-200)
              </label>
              <Input
                id="nation-policy-bill"
                type="number"
                min={20}
                max={200}
                value={bill}
                onChange={(e) => setBill(Number(e.target.value))}
              />
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Button onClick={handleSavePolicy} disabled={savingPolicy}>
              {savingPolicy ? "저장 중..." : "정책 저장"}
            </Button>
            {saveMsg && (
              <span className="text-xs text-muted-foreground">{saveMsg}</span>
            )}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>외교 관계 개요</CardTitle>
        </CardHeader>
        <CardContent>
          {diplomacyRows.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              외교 관계가 없습니다.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>국가</TableHead>
                  <TableHead>관계</TableHead>
                  <TableHead className="text-right">잔여 턴</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {diplomacyRows.map((row) => (
                  <TableRow key={row.id}>
                    <TableCell>
                      <NationBadge
                        name={row.nation?.name}
                        color={row.nation?.color}
                      />
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={
                          row.relation === "alliance"
                            ? "default"
                            : row.relation === "war"
                              ? "destructive"
                              : row.relation === "nonaggression"
                                ? "secondary"
                                : "outline"
                        }
                      >
                        {row.relation === "alliance"
                          ? "동맹"
                          : row.relation === "war"
                            ? "적대"
                            : row.relation === "nonaggression"
                              ? "불가침"
                              : row.relation}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {row.term}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>도시별 예산 계산</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="text-sm grid grid-cols-2 gap-2">
            <div>금 총수입: +{totalGoldIncome.toLocaleString()}</div>
            <div>쌀 총수입: +{totalRiceIncome.toLocaleString()}</div>
            <div>총지출(녹봉): -{totalExpense.toLocaleString()}</div>
            <div
              className={
                totalGoldIncome - totalExpense >= 0
                  ? "text-green-400"
                  : "text-red-400"
              }
            >
              금 순수익: {(totalGoldIncome - totalExpense).toLocaleString()}
            </div>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>도시</TableHead>
                <TableHead className="text-right">금수입</TableHead>
                <TableHead className="text-right">쌀수입</TableHead>
                <TableHead className="text-right">지출</TableHead>
                <TableHead className="text-right">금순익</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {budgetRows.map((row) => (
                <TableRow key={row.city.id}>
                  <TableCell>{row.city.name}</TableCell>
                  <TableCell className="text-right tabular-nums">
                    {row.goldIncome.toLocaleString()}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {row.riceIncome.toLocaleString()}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {row.expense.toLocaleString()}
                  </TableCell>
                  <TableCell
                    className={`text-right tabular-nums ${row.goldNet >= 0 ? "text-green-400" : "text-red-400"}`}
                  >
                    {row.goldNet.toLocaleString()}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* City filter + appointment mode toggle */}
      <div className="flex items-center gap-3 flex-wrap">
        <div className="relative w-48">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
          <Input
            placeholder="도시 검색..."
            value={cityFilter}
            onChange={(e) => setCityFilter(e.target.value)}
            className="pl-8"
          />
        </div>
        <Button
          variant={appointMode ? "default" : "outline"}
          size="sm"
          onClick={() => setAppointMode(!appointMode)}
        >
          {appointMode ? "관직 임명 모드 ON" : "관직 임명 모드"}
        </Button>
      </div>

      {(cities ?? []).length === 0 ? (
        <EmptyState icon={Building2} title="보유 도시가 없습니다." />
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              {columns.map((col) => (
                <TableHead
                  key={col.key}
                  className="cursor-pointer hover:text-foreground"
                  onClick={() => toggleSort(col.key)}
                >
                  {col.label}
                  {arrow(col.key)}
                </TableHead>
              ))}
              <TableHead>관직</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {sorted.map((c) => {
              const officers = getCityOfficers(c.id);
              const isCapital = myNation?.capitalCityId === c.id;
              return (
                <TableRow key={c.id}>
                  <TableCell className="font-medium">
                    <span className="flex items-center gap-1">
                      {isCapital && <Crown className="size-4 text-amber-400" />}
                      {c.name}
                    </span>
                  </TableCell>
                  <TableCell>
                    <span className="text-xs text-muted-foreground">
                      {REGION_NAMES[c.region] ?? `지역${c.region}`}
                    </span>
                  </TableCell>
                  <TableCell>{c.level}</TableCell>
                  <TableCell>
                    {c.pop.toLocaleString()}/{c.popMax.toLocaleString()}
                  </TableCell>
                  <TableCell>{c.trust}</TableCell>
                  <TableCell>{c.trade}%</TableCell>
                  <TableCell>
                    {c.agri.toLocaleString()}/{c.agriMax.toLocaleString()}
                  </TableCell>
                  <TableCell>
                    {c.comm.toLocaleString()}/{c.commMax.toLocaleString()}
                  </TableCell>
                  <TableCell>
                    {c.secu.toLocaleString()}/{c.secuMax.toLocaleString()}
                  </TableCell>
                  <TableCell>
                    {c.def.toLocaleString()}/{c.defMax.toLocaleString()}
                  </TableCell>
                  <TableCell>
                    {c.wall.toLocaleString()}/{c.wallMax.toLocaleString()}
                  </TableCell>
                  <TableCell>
                    <Badge
                      variant={c.supplyState === 1 ? "default" : "destructive"}
                    >
                      {c.supplyState === 1 ? "보급" : "단절"}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <div className="space-y-1">
                      {officers.length > 0 ? (
                        officers.map((o) => (
                          <div key={o.id} className="text-xs flex items-center gap-1">
                            <Badge variant="outline" className="text-[10px] px-1">
                              {OFFICER_TITLES[o.officerLevel] ?? `Lv${o.officerLevel}`}
                            </Badge>
                            <span>{o.name}</span>
                          </div>
                        ))
                      ) : (
                        <span className="text-xs text-muted-foreground">-</span>
                      )}
                      {appointMode && (
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-xs h-6 px-2"
                          onClick={() => setAppointCity(appointCity === c.id ? null : c.id)}
                        >
                          {appointCity === c.id ? "취소" : "+ 임명"}
                        </Button>
                      )}
                      {appointMode && appointCity === c.id && (
                        <div className="mt-1 space-y-1 p-2 border rounded bg-background">
                          <Select
                            value={String(appointLevel)}
                            onValueChange={(v) => setAppointLevel(Number(v))}
                          >
                            <SelectTrigger className="h-7 text-xs">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="4">태수</SelectItem>
                              <SelectItem value="3">군사</SelectItem>
                              <SelectItem value="2">종사</SelectItem>
                            </SelectContent>
                          </Select>
                          <Select
                            value={appointGeneralId}
                            onValueChange={setAppointGeneralId}
                          >
                            <SelectTrigger className="h-7 text-xs">
                              <SelectValue placeholder="장수 선택" />
                            </SelectTrigger>
                            <SelectContent>
                              {getUnassignedGenerals().map((g) => (
                                <SelectItem key={g.id} value={String(g.id)}>
                                  {g.name} (통{g.leadership}/무{g.strength}/지{g.intel})
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                          <Button
                            size="sm"
                            className="h-6 text-xs"
                            disabled={!appointGeneralId || appointSaving}
                            onClick={handleAppoint}
                          >
                            {appointSaving ? "임명 중..." : "임명"}
                          </Button>
                        </div>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
