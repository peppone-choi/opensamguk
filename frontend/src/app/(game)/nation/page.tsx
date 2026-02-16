"use client";

import { useEffect, useState } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { nationApi, generalApi, cityApi } from "@/lib/gameApi";
import type { Nation, General, City } from "@/types";
import { Flag } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
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
import { NationBadge } from "@/components/game/nation-badge";
import { GeneralPortrait } from "@/components/game/general-portrait";

const LEVEL_LABELS: Record<number, string> = {
  1: "주자사",
  2: "주목",
  3: "자사",
  4: "목",
  5: "공",
  6: "왕",
  7: "황제",
};

export default function NationPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const myGeneral = useGeneralStore((s) => s.myGeneral);
  const fetchMyGeneral = useGeneralStore((s) => s.fetchMyGeneral);
  const searchParams = useSearchParams();
  const router = useRouter();
  const [nation, setNation] = useState<Nation | null>(null);
  const [generals, setGenerals] = useState<General[]>([]);
  const [cities, setCities] = useState<City[]>([]);
  const [capitalName, setCapitalName] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const tabParam = searchParams.get("tab");
  const activeTab =
    tabParam === "generals" || tabParam === "cities" ? tabParam : "info";

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) {
      fetchMyGeneral(currentWorld.id).catch(() =>
        setError("장수 정보를 불러올 수 없습니다."),
      );
    }
  }, [currentWorld, myGeneral, fetchMyGeneral]);

  useEffect(() => {
    if (!myGeneral?.nationId) return;
    Promise.all([
      nationApi.get(myGeneral.nationId),
      generalApi.listByNation(myGeneral.nationId),
      cityApi.listByNation(myGeneral.nationId),
    ])
      .then(([natRes, gensRes, citiesRes]) => {
        setNation(natRes.data);
        setGenerals(gensRes.data);
        setCities(citiesRes.data);
        const cap = citiesRes.data.find(
          (c) => c.id === natRes.data.capitalCityId,
        );
        if (cap) setCapitalName(cap.name);
      })
      .catch(() => setError("국가 정보를 불러올 수 없습니다."))
      .finally(() => setLoading(false));
  }, [myGeneral?.nationId]);

  const handleTabChange = (value: string) => {
    if (value === "info") {
      router.replace("/nation", { scroll: false });
    } else {
      router.replace(`/nation?tab=${value}`, { scroll: false });
    }
  };

  if (!currentWorld) return <LoadingState message="월드를 선택해주세요." />;
  if (loading) return <LoadingState />;
  if (error) return <div className="p-4 text-red-400">{error}</div>;
  if (!myGeneral?.nationId)
    return (
      <div className="p-4 text-muted-foreground">소속 국가가 없습니다.</div>
    );
  if (!nation) return <LoadingState message="국가 정보가 없습니다." />;

  const totalCrew = generals.reduce((s, g) => s + g.crew, 0);
  const totalPop = cities.reduce((s, c) => s + c.pop, 0);

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
        </TabsList>

        {/* Info tab */}
        <TabsContent value="info">
          <Card>
            <CardContent className="pt-4">
              <div className="flex flex-wrap items-center gap-2 mb-3">
                <NationBadge name={nation.name} color={nation.color} />
                <Badge variant="secondary">
                  {LEVEL_LABELS[nation.level] ?? `Lv.${nation.level}`}
                </Badge>
                <Badge variant="outline">{nation.typeCode}</Badge>
                {capitalName && (
                  <Badge variant="outline" className="text-cyan-400">
                    수도: {capitalName}
                  </Badge>
                )}
              </div>

              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
                <div>
                  <span className="text-muted-foreground text-xs">금</span>
                  <p className="text-yellow-400 tabular-nums font-medium">
                    {nation.gold.toLocaleString()}
                  </p>
                </div>
                <div>
                  <span className="text-muted-foreground text-xs">쌀</span>
                  <p className="text-green-400 tabular-nums font-medium">
                    {nation.rice.toLocaleString()}
                  </p>
                </div>
                <div>
                  <span className="text-muted-foreground text-xs">국력</span>
                  <p className="tabular-nums font-medium">
                    {nation.power.toLocaleString()}
                  </p>
                </div>
                <div>
                  <span className="text-muted-foreground text-xs">기술</span>
                  <p className="tabular-nums font-medium">{nation.tech}</p>
                </div>
              </div>

              <div className="grid grid-cols-3 md:grid-cols-6 gap-2 mt-3 pt-3 border-t border-border text-xs text-muted-foreground">
                <span>
                  세율: <span className="text-foreground">{nation.bill}%</span>
                </span>
                <span>
                  교역률:{" "}
                  <span className="text-foreground">{nation.rate}%</span>
                </span>
                <span>
                  장수:{" "}
                  <span className="text-foreground">{generals.length}명</span>
                </span>
                <span>
                  도시:{" "}
                  <span className="text-foreground">{cities.length}개</span>
                </span>
                <span>
                  총병력:{" "}
                  <span className="text-foreground">
                    {totalCrew.toLocaleString()}
                  </span>
                </span>
                <span>
                  총인구:{" "}
                  <span className="text-foreground">
                    {totalPop.toLocaleString()}
                  </span>
                </span>
              </div>

              <div className="grid grid-cols-3 md:grid-cols-6 gap-2 mt-2 text-xs text-muted-foreground">
                <span>
                  전쟁:{" "}
                  <span className="text-foreground">{nation.warState}</span>
                </span>
                <span>
                  정찰:{" "}
                  <span className="text-foreground">{nation.scoutLevel}</span>
                </span>
                <span>
                  전략한도:{" "}
                  <span className="text-foreground">
                    {nation.strategicCmdLimit}
                  </span>
                </span>
                <span>
                  비밀한도:{" "}
                  <span className="text-foreground">{nation.secretLimit}</span>
                </span>
                <span>
                  항복한도:{" "}
                  <span className="text-foreground">
                    {nation.surrenderLimit}
                  </span>
                </span>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* Generals tab */}
        <TabsContent value="generals">
          <Card>
            <CardContent className="pt-4">
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
                        <TableHead className="text-center">통솔</TableHead>
                        <TableHead className="text-center">무력</TableHead>
                        <TableHead className="text-center">지력</TableHead>
                        <TableHead className="text-center">정치</TableHead>
                        <TableHead className="text-center">매력</TableHead>
                        <TableHead className="text-right">병력</TableHead>
                        <TableHead className="text-center">훈련</TableHead>
                        <TableHead className="text-center">사기</TableHead>
                        <TableHead className="text-right">금</TableHead>
                        <TableHead className="text-right">쌀</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {generals
                        .sort((a, b) => b.officerLevel - a.officerLevel)
                        .map((gen) => (
                          <TableRow key={gen.id}>
                            <TableCell>
                              <div className="flex items-center gap-1.5">
                                <GeneralPortrait
                                  picture={gen.picture}
                                  name={gen.name}
                                  size="sm"
                                />
                                <span className="font-medium whitespace-nowrap">
                                  {gen.name}
                                </span>
                              </div>
                            </TableCell>
                            <TableCell className="text-center tabular-nums">
                              {gen.officerLevel}
                            </TableCell>
                            <TableCell className="text-center tabular-nums">
                              {gen.leadership}
                            </TableCell>
                            <TableCell className="text-center tabular-nums">
                              {gen.strength}
                            </TableCell>
                            <TableCell className="text-center tabular-nums">
                              {gen.intel}
                            </TableCell>
                            <TableCell className="text-center tabular-nums">
                              {gen.politics}
                            </TableCell>
                            <TableCell className="text-center tabular-nums">
                              {gen.charm}
                            </TableCell>
                            <TableCell className="text-right tabular-nums">
                              {gen.crew.toLocaleString()}
                            </TableCell>
                            <TableCell className="text-center tabular-nums">
                              {gen.train}
                            </TableCell>
                            <TableCell className="text-center tabular-nums">
                              {gen.atmos}
                            </TableCell>
                            <TableCell className="text-right tabular-nums text-yellow-400">
                              {gen.gold.toLocaleString()}
                            </TableCell>
                            <TableCell className="text-right tabular-nums text-green-400">
                              {gen.rice.toLocaleString()}
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

        {/* Cities tab */}
        <TabsContent value="cities">
          <Card>
            <CardContent className="pt-4">
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
                        <TableHead className="text-right">인구</TableHead>
                        <TableHead className="text-center">민심</TableHead>
                        <TableHead className="text-right">농업</TableHead>
                        <TableHead className="text-right">상업</TableHead>
                        <TableHead className="text-center">치안</TableHead>
                        <TableHead className="text-right">수비</TableHead>
                        <TableHead className="text-right">성벽</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {cities.map((c) => (
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
                          <TableCell className="text-right tabular-nums">
                            {c.pop.toLocaleString()}
                          </TableCell>
                          <TableCell className="text-center tabular-nums">
                            {c.trust}
                          </TableCell>
                          <TableCell className="text-right tabular-nums">
                            {c.agri}
                          </TableCell>
                          <TableCell className="text-right tabular-nums">
                            {c.comm}
                          </TableCell>
                          <TableCell className="text-center tabular-nums">
                            {c.secu}
                          </TableCell>
                          <TableCell className="text-right tabular-nums">
                            {c.def}
                          </TableCell>
                          <TableCell className="text-right tabular-nums">
                            {c.wall}
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
      </Tabs>
    </div>
  );
}
