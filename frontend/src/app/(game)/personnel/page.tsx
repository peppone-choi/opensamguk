"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { nationManagementApi } from "@/lib/gameApi";
import type { OfficerInfo } from "@/types";
import { Crown, Filter, Handshake } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { ErrorState } from "@/components/game/error-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { formatOfficerLevelText } from "@/lib/game-utils";

export default function PersonnelPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, cities, loadAll } = useGameStore();
  const [officers, setOfficers] = useState<OfficerInfo[]>([]);
  const [loading, setLoading] = useState(true);

  // Appointment form
  const [selGeneralId, setSelGeneralId] = useState("");
  const [selOfficerLevel, setSelOfficerLevel] = useState("");
  const [selCity, setSelCity] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(false);

  // Candidate filters
  const [filterStat, setFilterStat] = useState<
    "all" | "leadership" | "strength" | "intel" | "politics"
  >("all");
  const [filterMinStat, setFilterMinStat] = useState(0);
  const [filterCity, setFilterCity] = useState<string>("");
  const [filterSearch, setFilterSearch] = useState("");

  // Diplomat appointment
  const [diplomatGeneralId, setDiplomatGeneralId] = useState("");

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
    loadAll(currentWorld.id);
  }, [currentWorld, myGeneral, fetchMyGeneral, loadAll]);

  useEffect(() => {
    if (!myGeneral?.nationId) return;
    setError(false);
    nationManagementApi
      .getOfficers(myGeneral.nationId)
      .then(({ data }) => setOfficers(data))
      .catch(() => setError(true))
      .finally(() => setLoading(false));
  }, [myGeneral?.nationId]);

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  const nationGenerals = useMemo(
    () => generals.filter((g) => g.nationId === myGeneral?.nationId),
    [generals, myGeneral],
  );

  const sortedOfficers = useMemo(
    () => [...officers].sort((a, b) => b.officerLevel - a.officerLevel),
    [officers],
  );

  const filteredCandidates = useMemo(() => {
    let list = nationGenerals;
    if (filterSearch) {
      const q = filterSearch.toLowerCase();
      list = list.filter((g) => g.name.toLowerCase().includes(q));
    }
    if (filterCity) {
      list = list.filter((g) => g.cityId === Number(filterCity));
    }
    if (filterStat !== "all" && filterMinStat > 0) {
      list = list.filter((g) => {
        const val =
          filterStat === "leadership"
            ? g.leadership
            : filterStat === "strength"
              ? g.strength
              : filterStat === "intel"
                ? g.intel
                : g.politics;
        return val >= filterMinStat;
      });
    }
    return list.sort((a, b) => {
      if (filterStat === "leadership") return b.leadership - a.leadership;
      if (filterStat === "strength") return b.strength - a.strength;
      if (filterStat === "intel") return b.intel - a.intel;
      if (filterStat === "politics") return b.politics - a.politics;
      return (
        b.leadership +
        b.strength +
        b.intel -
        (a.leadership + a.strength + a.intel)
      );
    });
  }, [nationGenerals, filterSearch, filterCity, filterStat, filterMinStat]);

  const handleAppointDiplomat = async () => {
    if (!myGeneral?.nationId || !diplomatGeneralId) return;
    setSaving(true);
    try {
      await nationManagementApi.appointOfficer(myGeneral.nationId, {
        generalId: Number(diplomatGeneralId),
        officerLevel: 7, // diplomat level
      });
      const { data } = await nationManagementApi.getOfficers(
        myGeneral.nationId,
      );
      setOfficers(data);
      setDiplomatGeneralId("");
    } finally {
      setSaving(false);
    }
  };

  const handleAppoint = async () => {
    if (!myGeneral?.nationId || !selGeneralId || !selOfficerLevel) return;
    setSaving(true);
    try {
      await nationManagementApi.appointOfficer(myGeneral.nationId, {
        generalId: Number(selGeneralId),
        officerLevel: Number(selOfficerLevel),
        officerCity: selCity ? Number(selCity) : undefined,
      });
      const { data } = await nationManagementApi.getOfficers(
        myGeneral.nationId,
      );
      setOfficers(data);
      setSelGeneralId("");
      setSelOfficerLevel("");
      setSelCity("");
    } finally {
      setSaving(false);
    }
  };

  const handleExpel = async (generalId: number) => {
    if (!myGeneral?.nationId) return;
    if (!confirm("정말 추방하시겠습니까?")) return;
    await nationManagementApi.expel(myGeneral.nationId, generalId);
    const { data } = await nationManagementApi.getOfficers(myGeneral.nationId);
    setOfficers(data);
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;
  if (error) return <ErrorState title="인사 정보를 불러오지 못했습니다." />;
  if (!myGeneral?.nationId)
    return (
      <div className="p-4 text-muted-foreground">소속 국가가 없습니다.</div>
    );

  return (
    <div className="p-4 space-y-6 max-w-3xl mx-auto">
      <PageHeader icon={Crown} title="인사 관리" />

      {/* Officers Table */}
      <Card>
        <CardHeader>
          <CardTitle>관직 현황 ({sortedOfficers.length})</CardTitle>
        </CardHeader>
        <CardContent>
          {sortedOfficers.length === 0 ? (
            <EmptyState icon={Crown} title="임명된 관직이 없습니다." />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>장수</TableHead>
                  <TableHead>관직</TableHead>
                  <TableHead>도시</TableHead>
                  <TableHead></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {sortedOfficers.map((g) => (
                  <TableRow key={g.id}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <GeneralPortrait
                          picture={g.picture}
                          name={g.name}
                          size="sm"
                        />
                        {g.name}
                      </div>
                    </TableCell>
                    <TableCell>
                      <span title={`레벨 ${g.officerLevel}`}>
                        {formatOfficerLevelText(g.officerLevel)}
                      </span>
                    </TableCell>
                    <TableCell>{cityMap.get(g.cityId)?.name ?? "-"}</TableCell>
                    <TableCell>
                      <Button
                        variant="destructive"
                        size="sm"
                        onClick={() => handleExpel(g.id)}
                      >
                        추방
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Candidate Filter */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Filter className="size-4" />
            후보 필터
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              placeholder="이름 검색..."
              value={filterSearch}
              onChange={(e) => setFilterSearch(e.target.value)}
              className="w-40 h-8 text-xs"
            />
            <select
              value={filterCity}
              onChange={(e) => setFilterCity(e.target.value)}
              className="h-8 border border-gray-600 bg-[#111] px-2 text-xs text-white rounded"
            >
              <option value="">전체 도시</option>
              {cities
                .filter((c) => c.nationId === myGeneral.nationId)
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
            </select>
            <select
              value={filterStat}
              onChange={(e) =>
                setFilterStat(e.target.value as typeof filterStat)
              }
              className="h-8 border border-gray-600 bg-[#111] px-2 text-xs text-white rounded"
            >
              <option value="all">전체 능력치</option>
              <option value="leadership">통솔</option>
              <option value="strength">무력</option>
              <option value="intel">지력</option>
              <option value="politics">정치</option>
            </select>
            {filterStat !== "all" && (
              <Input
                type="number"
                placeholder="최소값"
                value={filterMinStat || ""}
                onChange={(e) => setFilterMinStat(Number(e.target.value))}
                className="w-20 h-8 text-xs"
                min={0}
              />
            )}
          </div>
          <div className="text-xs text-muted-foreground">
            {filteredCandidates.length}명 표시
          </div>
          <div className="max-h-48 overflow-y-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="text-xs">장수</TableHead>
                  <TableHead className="text-xs">통솔</TableHead>
                  <TableHead className="text-xs">무력</TableHead>
                  <TableHead className="text-xs">지력</TableHead>
                  <TableHead className="text-xs">정치</TableHead>
                  <TableHead className="text-xs">도시</TableHead>
                  <TableHead className="text-xs">관직</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filteredCandidates.slice(0, 30).map((g) => (
                  <TableRow
                    key={g.id}
                    className="cursor-pointer hover:bg-muted/30"
                    onClick={() => setSelGeneralId(String(g.id))}
                  >
                    <TableCell className="text-xs">
                      <div className="flex items-center gap-1">
                        <GeneralPortrait
                          picture={g.picture}
                          name={g.name}
                          size="sm"
                        />
                        {g.name}
                        {String(g.id) === selGeneralId && (
                          <Badge className="text-[8px] ml-1">선택됨</Badge>
                        )}
                      </div>
                    </TableCell>
                    <TableCell className="text-xs tabular-nums">
                      {g.leadership}
                    </TableCell>
                    <TableCell className="text-xs tabular-nums">
                      {g.strength}
                    </TableCell>
                    <TableCell className="text-xs tabular-nums">
                      {g.intel}
                    </TableCell>
                    <TableCell className="text-xs tabular-nums">
                      {g.politics}
                    </TableCell>
                    <TableCell className="text-xs">
                      {cityMap.get(g.cityId)?.name ?? "-"}
                    </TableCell>
                    <TableCell className="text-xs">
                      {g.officerLevel > 0
                        ? formatOfficerLevelText(g.officerLevel)
                        : "-"}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </CardContent>
      </Card>

      {/* Diplomat Special Appointment */}
      {myGeneral.officerLevel >= 12 && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Handshake className="size-4" />
              외교권자 특수 임명
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <p className="text-xs text-muted-foreground">
              외교 권한을 가진 특수 관직을 임명합니다. 외교 업무(동맹, 휴전
              등)를 수행할 수 있습니다.
            </p>
            <div className="flex items-center gap-2">
              <select
                value={diplomatGeneralId}
                onChange={(e) => setDiplomatGeneralId(e.target.value)}
                className="h-9 flex-1 rounded-md border border-input bg-transparent px-3 py-1 text-sm"
              >
                <option value="">장수 선택...</option>
                {nationGenerals
                  .filter((g) => g.officerLevel < 7)
                  .map((g) => (
                    <option key={g.id} value={g.id}>
                      {g.name} (통{g.leadership}/무{g.strength}/지{g.intel})
                    </option>
                  ))}
              </select>
              <Button
                onClick={handleAppointDiplomat}
                disabled={saving || !diplomatGeneralId}
                size="sm"
              >
                {saving ? "임명 중..." : "외교권자 임명"}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Appointment Form */}
      <Card>
        <CardHeader>
          <CardTitle>관직 임명</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {myGeneral.officerLevel < 12 && (
            <div className="p-3 bg-destructive/10 text-destructive text-sm rounded-md">
              군주만 관직을 임명할 수 있습니다.
            </div>
          )}
          <div>
            <label className="block text-xs text-muted-foreground mb-1">
              장수
            </label>
            <select
              value={selGeneralId}
              onChange={(e) => setSelGeneralId(e.target.value)}
              disabled={myGeneral.officerLevel < 12}
              className="h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] md:text-sm disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <option value="">선택...</option>
              {nationGenerals.map((g) => (
                <option key={g.id} value={g.id}>
                  {g.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-xs text-muted-foreground mb-1">
              관직 레벨 (1-{Math.max(1, myGeneral.officerLevel - 1)})
            </label>
            <Input
              type="number"
              min={1}
              max={Math.max(1, myGeneral.officerLevel - 1)}
              value={selOfficerLevel}
              onChange={(e) => setSelOfficerLevel(e.target.value)}
              disabled={myGeneral.officerLevel < 12}
              placeholder={`1-${Math.max(1, myGeneral.officerLevel - 1)}`}
              className="disabled:opacity-50 disabled:cursor-not-allowed"
            />
          </div>
          <div>
            <label className="block text-xs text-muted-foreground mb-1">
              도시 (선택)
            </label>
            <select
              value={selCity}
              onChange={(e) => setSelCity(e.target.value)}
              disabled={myGeneral.officerLevel < 12}
              className="h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] md:text-sm disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <option value="">선택...</option>
              {cities
                .filter((c) => c.nationId === myGeneral.nationId)
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
            </select>
          </div>
          <Button
            onClick={handleAppoint}
            disabled={
              saving ||
              !selGeneralId ||
              !selOfficerLevel ||
              myGeneral.officerLevel < 12
            }
          >
            {saving ? "임명 중..." : "임명"}
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
