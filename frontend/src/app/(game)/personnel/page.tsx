"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { nationManagementApi } from "@/lib/gameApi";
import type { OfficerInfo } from "@/types";
import { Crown } from "lucide-react";
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
                    <TableCell>{g.officerLevel}</TableCell>
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
