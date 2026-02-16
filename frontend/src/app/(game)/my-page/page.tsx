"use client";

import { useEffect, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { accountApi, historyApi, cityApi, nationApi } from "@/lib/gameApi";
import type { City, Nation, Message } from "@/types";
import { User, Settings, ScrollText } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { ScrollArea } from "@/components/ui/scroll-area";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { StatBar } from "@/components/game/stat-bar";
import { ResourceDisplay } from "@/components/game/resource-display";
import { NationBadge } from "@/components/game/nation-badge";

const EQUIP_LABELS: Record<string, string> = {
  weaponCode: "무기",
  bookCode: "서적",
  horseCode: "군마",
  itemCode: "도구",
};

export default function MyPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, loading, fetchMyGeneral } = useGeneralStore();
  const [city, setCity] = useState<City | null>(null);
  const [nation, setNation] = useState<Nation | null>(null);
  const [records, setRecords] = useState<Message[]>([]);
  const [defenceTrain, setDefenceTrain] = useState(0);
  const [tournamentState, setTournamentState] = useState(0);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!currentWorld) return;
    fetchMyGeneral(currentWorld.id).catch(() =>
      setError("장수 정보를 불러올 수 없습니다."),
    );
  }, [currentWorld, fetchMyGeneral]);

  useEffect(() => {
    if (!myGeneral) return;
    if (myGeneral.cityId) {
      cityApi
        .get(myGeneral.cityId)
        .then(({ data }) => setCity(data))
        .catch(() => {});
    }
    if (myGeneral.nationId) {
      nationApi
        .get(myGeneral.nationId)
        .then(({ data }) => setNation(data))
        .catch(() => {});
    }
    historyApi
      .getGeneralRecords(myGeneral.id)
      .then(({ data }) => setRecords(data))
      .catch(() => {});
  }, [myGeneral]);

  const handleSaveSettings = async () => {
    setSaving(true);
    try {
      await accountApi.updateSettings({ defenceTrain, tournamentState });
    } finally {
      setSaving(false);
    }
  };

  const handleVacation = async () => {
    if (!confirm("휴가 상태를 전환하시겠습니까?")) return;
    await accountApi.toggleVacation();
    if (currentWorld) fetchMyGeneral(currentWorld.id);
  };

  if (!currentWorld) return <LoadingState message="월드를 선택해주세요." />;
  if (loading) return <LoadingState />;
  if (error) return <div className="p-4 text-red-400">{error}</div>;
  if (!myGeneral) return <LoadingState message="장수 정보가 없습니다." />;

  const g = myGeneral;
  const equipKeys = [
    "weaponCode",
    "bookCode",
    "horseCode",
    "itemCode",
  ] as const;

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={User} title="마이페이지" />

      <Tabs defaultValue="info">
        <TabsList>
          <TabsTrigger value="info">장수 정보</TabsTrigger>
          <TabsTrigger value="settings">설정</TabsTrigger>
          <TabsTrigger value="log">활동 기록</TabsTrigger>
        </TabsList>

        <TabsContent value="info" className="space-y-4 mt-4">
          {/* Profile */}
          <Card>
            <CardHeader>
              <CardTitle>프로필</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex gap-4 items-start">
                <GeneralPortrait picture={g.picture} name={g.name} size="lg" />
                <div className="space-y-1">
                  <p className="text-lg font-semibold">{g.name}</p>
                  <p className="text-sm text-muted-foreground">
                    나이 {g.age}세
                  </p>
                  {nation && (
                    <NationBadge name={nation.name} color={nation.color} />
                  )}
                  {city && (
                    <p className="text-sm text-muted-foreground">
                      위치: {city.name}
                    </p>
                  )}
                  <p className="text-sm text-muted-foreground">
                    관직: {g.officerLevel}
                  </p>
                  {g.injury > 0 && (
                    <Badge variant="destructive">부상: {g.injury}</Badge>
                  )}
                </div>
              </div>
            </CardContent>
          </Card>

          {/* 5-stat */}
          <Card>
            <CardHeader>
              <CardTitle>능력치</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <StatBar label="통솔" value={g.leadership} color="bg-red-500" />
              <StatBar label="무력" value={g.strength} color="bg-orange-500" />
              <StatBar label="지력" value={g.intel} color="bg-blue-500" />
              <StatBar label="정치" value={g.politics} color="bg-green-500" />
              <StatBar label="매력" value={g.charm} color="bg-purple-500" />
            </CardContent>
          </Card>

          {/* Resources */}
          <Card>
            <CardHeader>
              <CardTitle>자원</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <ResourceDisplay gold={g.gold} rice={g.rice} crew={g.crew} />
              <div className="flex gap-4 text-sm text-muted-foreground">
                <span>훈련: {g.train}</span>
                <span>사기: {g.atmos}</span>
              </div>
            </CardContent>
          </Card>

          {/* Experience / Dedication */}
          <Card>
            <CardHeader>
              <CardTitle>경험 / 공헌</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <StatBar
                label="경험"
                value={g.experience}
                max={1000}
                color="bg-yellow-500"
              />
              <StatBar
                label="공헌"
                value={g.dedication}
                max={1000}
                color="bg-teal-500"
              />
            </CardContent>
          </Card>

          {/* Equipment */}
          <Card>
            <CardHeader>
              <CardTitle>장비</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex flex-wrap gap-2">
                {equipKeys.map((key) => (
                  <Badge
                    key={key}
                    variant={g[key] === "None" ? "outline" : "secondary"}
                  >
                    {EQUIP_LABELS[key]}: {g[key] === "None" ? "없음" : g[key]}
                  </Badge>
                ))}
              </div>
            </CardContent>
          </Card>

          {/* Special / Personality */}
          <Card>
            <CardHeader>
              <CardTitle>특기 / 성격</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex flex-wrap gap-2">
                <Badge
                  variant={g.specialCode === "None" ? "outline" : "secondary"}
                >
                  특기1: {g.specialCode === "None" ? "없음" : g.specialCode}
                </Badge>
                <Badge
                  variant={g.special2Code === "None" ? "outline" : "secondary"}
                >
                  특기2: {g.special2Code === "None" ? "없음" : g.special2Code}
                </Badge>
                <Badge variant="secondary">성격: {g.personalCode}</Badge>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="settings" className="space-y-4 mt-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Settings className="size-4" />
                장수 설정
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <label className="text-sm text-muted-foreground">
                  수비 훈련도 (0-100)
                </label>
                <Input
                  type="number"
                  min={0}
                  max={100}
                  value={defenceTrain}
                  onChange={(e) => setDefenceTrain(Number(e.target.value))}
                />
              </div>
              <div className="flex items-center gap-3">
                <label className="text-sm text-muted-foreground">
                  토너먼트 참가
                </label>
                <input
                  type="checkbox"
                  checked={tournamentState === 1}
                  onChange={(e) => setTournamentState(e.target.checked ? 1 : 0)}
                  className="rounded border-input"
                />
              </div>
              <Button onClick={handleSaveSettings} disabled={saving}>
                {saving ? "저장 중..." : "설정 저장"}
              </Button>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>액션</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <Button variant="outline" onClick={handleVacation}>
                휴가 전환
              </Button>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="log" className="space-y-4 mt-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <ScrollText className="size-4" />
                활동 기록
              </CardTitle>
            </CardHeader>
            <CardContent>
              {records.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  기록이 없습니다.
                </p>
              ) : (
                <ScrollArea className="max-h-96">
                  <div className="space-y-2">
                    {records.map((r) => (
                      <div
                        key={r.id}
                        className="text-sm p-2 rounded bg-muted/50"
                      >
                        <span className="text-xs text-muted-foreground">
                          {new Date(r.sentAt).toLocaleString("ko-KR")}
                        </span>
                        <p>
                          {(r.payload.content as string) ??
                            JSON.stringify(r.payload)}
                        </p>
                      </div>
                    ))}
                  </div>
                </ScrollArea>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
