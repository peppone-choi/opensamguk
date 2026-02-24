"use client";

import { useEffect, useState, useCallback } from "react";
import { useRouter } from "next/navigation";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import {
  accountApi,
  historyApi,
  cityApi,
  nationApi,
  frontApi,
  itemApi,
  generalLogApi,
} from "@/lib/gameApi";
import type { City, Nation, Message, GeneralFrontInfo } from "@/types";
import { User, Settings, ScrollText, Trash2, Swords } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { ScrollArea } from "@/components/ui/scroll-area";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { SammoBar } from "@/components/game/sammo-bar";
import { toast } from "sonner";
import {
  formatInjury,
  formatOfficerLevelText,
  formatDefenceTrain,
  formatDexLevel,
  formatHonor,
  formatGeneralTypeCall,
  formatRefreshScore,
  getNPCColor,
  nextExpLevelRemain,
  CREW_TYPE_NAMES,
  numberWithCommas,
  ageColor,
  isValidObjKey,
} from "@/lib/game-utils";

const EQUIP_MAP: { key: string; label: string }[] = [
  { key: "weapon", label: "무기" },
  { key: "book", label: "서적" },
  { key: "horse", label: "군마" },
  { key: "item", label: "도구" },
];

const DEX_NAMES = ["보병", "궁병", "기병", "귀병", "차병"];

const DEFENCE_PRESETS = [
  { value: 90, label: "90 (☆)" },
  { value: 80, label: "80 (◎)" },
  { value: 60, label: "60 (○)" },
  { value: 40, label: "40 (△)" },
  { value: 999, label: "수비안함 (훈련+1,사기-1)" },
];

const POTION_OPTIONS = [
  { value: 10, label: "경상 이상 사용 (10+)" },
  { value: 21, label: "중상 이상 사용 (21+)" },
  { value: 41, label: "심각 이상 사용 (41+)" },
  { value: 61, label: "위독 이상 사용 (61+)" },
  { value: 100, label: "사용 안함" },
];

const TOURNAMENT_OPTIONS = [
  { value: 0, label: "수동 참여" },
  { value: 1, label: "자동 참여" },
];

export default function MyPage() {
  const router = useRouter();
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, loading, fetchMyGeneral } = useGeneralStore();
  const [frontInfo, setFrontInfo] = useState<GeneralFrontInfo | null>(null);
  const [city, setCity] = useState<City | null>(null);
  const [nation, setNation] = useState<Nation | null>(null);
  const [records, setRecords] = useState<Message[]>([]);
  const [battleRecords, setBattleRecords] = useState<Message[]>([]);
  const [historyRecords, setHistoryRecords] = useState<Message[]>([]);

  // Settings
  const [defenceTrain, setDefenceTrain] = useState(80);
  const [tournamentState, setTournamentState] = useState(0);
  const [potionThreshold, setPotionThreshold] = useState(999);
  const [preRiseDelete, setPreRiseDelete] = useState(false);
  const [preOpenDelete, setPreOpenDelete] = useState(false);
  const [borderReturn, setBorderReturn] = useState(false);
  const [customCss, setCustomCss] = useState("");
  const [cssPreview, setCssPreview] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!currentWorld) return;
    fetchMyGeneral(currentWorld.id).catch(() =>
      setError("장수 정보를 불러올 수 없습니다."),
    );
  }, [currentWorld, fetchMyGeneral]);

  useEffect(() => {
    if (!myGeneral || !currentWorld) return;
    // Initialize settings from general data
    setDefenceTrain(myGeneral.defenceTrain ?? 80);
    setTournamentState(myGeneral.tournamentState ?? 0);
    setPotionThreshold((myGeneral.meta?.potionThreshold as number) ?? 100);
    setPreRiseDelete((myGeneral.meta?.preRiseDelete as boolean) ?? false);
    setPreOpenDelete((myGeneral.meta?.preOpenDelete as boolean) ?? false);
    setBorderReturn((myGeneral.meta?.borderReturn as boolean) ?? false);
    setCustomCss((myGeneral.meta?.customCss as string) ?? "");

    // Fetch front info for dex/battle stats
    frontApi
      .getInfo(currentWorld.id)
      .then(({ data }) => setFrontInfo(data.general))
      .catch(() => {});

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
      .then(({ data }) => {
        setRecords(data);
        // Split records by type if payload has type info
        const battles = data.filter(
          (r) =>
            typeof r.payload?.content === "string" &&
            /전투|공격|방어|사살|패배|승리/.test(r.payload.content as string),
        );
        setBattleRecords(battles);
        const history = data.filter(
          (r) =>
            typeof r.payload?.content === "string" &&
            /열전|사망|등용|탈퇴|건국|멸망/.test(r.payload.content as string),
        );
        setHistoryRecords(history);
      })
      .catch(() => {});
  }, [myGeneral, currentWorld]);

  const handleSaveSettings = useCallback(async () => {
    setSaving(true);
    try {
      await accountApi.updateSettings({
        defenceTrain,
        tournamentState,
        potionThreshold,
        preRiseDelete,
        preOpenDelete,
        borderReturn,
        customCss,
      });
      toast.success("설정이 저장되었습니다.");
    } catch {
      toast.error("설정 저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  }, [defenceTrain, tournamentState, potionThreshold, preRiseDelete, preOpenDelete, borderReturn, customCss]);

  const handleVacation = useCallback(async () => {
    if (!confirm("휴가 상태를 전환하시겠습니까?")) return;
    try {
      await accountApi.toggleVacation();
      toast.success("휴가 상태가 전환되었습니다.");
      if (currentWorld) fetchMyGeneral(currentWorld.id);
    } catch {
      toast.error("휴가 전환에 실패했습니다.");
    }
  }, [currentWorld, fetchMyGeneral]);

  if (!currentWorld) return <LoadingState message="월드를 선택해주세요." />;
  if (loading) return <LoadingState />;
  if (error) return <div className="p-4 text-red-400">{error}</div>;
  if (!myGeneral) return <LoadingState message="장수 정보가 없습니다." />;

  const g = myGeneral;
  const nationLevel = nation?.level ?? 0;
  const injuryInfo = formatInjury(g.injury);
  const officerText = formatOfficerLevelText(g.officerLevel, nationLevel);
  const typeCall = formatGeneralTypeCall(g.leadership, g.strength, g.intel);
  const honorText = formatHonor(g.experience);
  const [expCur, expMax] = nextExpLevelRemain(g.experience, g.expLevel ?? 0);
  const npcColor = getNPCColor(g.npcState);
  const fi = frontInfo;
  const dexValues = [
    fi?.dex1 ?? 0,
    fi?.dex2 ?? 0,
    fi?.dex3 ?? 0,
    fi?.dex4 ?? 0,
    fi?.dex5 ?? 0,
  ];

  // Battle stats (from frontInfo DTO)
  const warnum = fi?.warnum ?? 0;
  const killnum = fi?.killnum ?? 0;
  const deathnum = fi?.deathnum ?? 0;
  const killcrew = fi?.killcrew ?? 0;
  const deathcrew = fi?.deathcrew ?? 0;
  const firenum = fi?.firenum ?? 0;
  const refreshScore = fi?.refreshScore ?? 0;

  const winRate = warnum > 0 ? ((killnum / warnum) * 100).toFixed(1) : "0.0";
  const killRate =
    deathcrew > 0
      ? ((killcrew / Math.max(deathcrew, 1)) * 100).toFixed(1)
      : "0.0";

  return (
    <div className="p-4 space-y-4 max-w-4xl mx-auto">
      <PageHeader icon={User} title="내 정보" />

      <Tabs defaultValue="info">
        <TabsList>
          <TabsTrigger value="info">장수 정보</TabsTrigger>
          <TabsTrigger value="battle">전투 통계</TabsTrigger>
          <TabsTrigger value="settings">설정</TabsTrigger>
          <TabsTrigger value="log">기록</TabsTrigger>
        </TabsList>

        {/* ===== TAB 1: 장수 정보 ===== */}
        <TabsContent value="info" className="space-y-4 mt-4">
          <div className="grid gap-4 lg:grid-cols-2">
            {/* Profile + Basic Info */}
            <Card>
              <CardContent className="pt-4 space-y-3">
                <div className="flex gap-4 items-start">
                  <GeneralPortrait
                    picture={g.picture}
                    name={g.name}
                    size="lg"
                  />
                  <div className="space-y-1 flex-1">
                    <p
                      className="text-lg font-bold"
                      style={{ color: npcColor }}
                    >
                      {g.name}
                    </p>
                    <p className="text-sm">
                      <span className="text-muted-foreground">관직:</span>{" "}
                      <span className="text-cyan-400">{officerText}</span>
                    </p>
                    <p className="text-sm">
                      <span className="text-muted-foreground">유형:</span>{" "}
                      <span className="text-yellow-400">{typeCall}</span>
                    </p>
                    <p className="text-sm">
                      <span className="text-muted-foreground">나이:</span>{" "}
                      <span
                        style={{
                          color: ageColor(g.age, g.deadYear - g.bornYear),
                        }}
                      >
                        {g.age}세
                      </span>
                    </p>
                    {nation && (
                      <div className="flex items-center gap-1">
                        <span className="text-sm text-muted-foreground">
                          세력:
                        </span>
                        <NationBadge name={nation.name} color={nation.color} />
                      </div>
                    )}
                    {city && (
                      <p className="text-sm">
                        <span className="text-muted-foreground">위치:</span>{" "}
                        {city.name}
                      </p>
                    )}
                    <p className="text-sm">
                      <span className="text-muted-foreground">상태:</span>{" "}
                      <span style={{ color: injuryInfo.color }}>
                        {injuryInfo.text}
                      </span>
                      {g.injury > 0 && (
                        <span className="text-red-400 ml-1">({g.injury}%)</span>
                      )}
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>

            {/* 5-Stat with Experience Bars */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">능력치</CardTitle>
              </CardHeader>
              <CardContent className="space-y-1.5">
                {[
                  {
                    label: "통솔",
                    value: g.leadership,
                    exp: g.leadershipExp,
                    color: "red",
                  },
                  {
                    label: "무력",
                    value: g.strength,
                    exp: g.strengthExp,
                    color: "orange",
                  },
                  {
                    label: "지력",
                    value: g.intel,
                    exp: g.intelExp,
                    color: "dodgerblue",
                  },
                  {
                    label: "정치",
                    value: g.politics,
                    exp: 0,
                    color: "limegreen",
                  },
                  {
                    label: "매력",
                    value: g.charm,
                    exp: 0,
                    color: "mediumpurple",
                  },
                ].map((s) => (
                  <div key={s.label} className="flex items-center gap-2">
                    <span
                      className="w-8 text-xs text-right"
                      style={{ color: s.color }}
                    >
                      {s.label}
                    </span>
                    <span className="w-6 text-xs text-right font-mono">
                      {s.value}
                    </span>
                    <div className="flex-1">
                      <SammoBar
                        height={7}
                        percent={s.value}
                        altText={`${s.value}/100`}
                      />
                    </div>
                    {s.exp > 0 && (
                      <span className="text-[10px] text-yellow-500 w-10 text-right">
                        +{s.exp}
                      </span>
                    )}
                  </div>
                ))}
              </CardContent>
            </Card>

            {/* Resources & Military */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">자원 / 군사</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
                  <div>
                    <span className="text-muted-foreground">자금:</span>{" "}
                    <span className="text-yellow-400">
                      {numberWithCommas(g.gold)}
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">군량:</span>{" "}
                    <span className="text-green-400">
                      {numberWithCommas(g.rice)}
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">병력:</span>{" "}
                    <span className="text-white">
                      {numberWithCommas(g.crew)}
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">병종:</span>{" "}
                    <span className="text-cyan-300">
                      {CREW_TYPE_NAMES[g.crewType] ?? "보병"}
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">훈련:</span>{" "}
                    <span
                      className={g.train >= 80 ? "text-cyan-400" : "text-white"}
                    >
                      {g.train}
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">사기:</span>{" "}
                    <span
                      className={g.atmos >= 80 ? "text-cyan-400" : "text-white"}
                    >
                      {g.atmos}
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">수비훈련:</span>{" "}
                    <span>{formatDefenceTrain(g.defenceTrain)}</span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">벌점:</span>{" "}
                    <span
                      className={
                        Object.keys(g.penalty || {}).length > 0
                          ? "text-red-400"
                          : ""
                      }
                    >
                      {Object.keys(g.penalty || {}).length > 0
                        ? JSON.stringify(g.penalty)
                        : "없음"}
                    </span>
                  </div>
                </div>
              </CardContent>
            </Card>

            {/* Experience / Dedication / Honor */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">명성 / 계급</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                <div className="flex items-center gap-2">
                  <span className="w-12 text-xs text-muted-foreground">
                    명성
                  </span>
                  <span className="w-12 text-xs text-yellow-400">
                    {honorText}
                  </span>
                  <div className="flex-1">
                    <SammoBar
                      height={7}
                      percent={expMax > 0 ? (expCur / expMax) * 100 : 0}
                      altText={`${expCur}/${expMax}`}
                    />
                  </div>
                  <span className="text-[10px] text-muted-foreground w-16 text-right">
                    {g.experience}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="w-12 text-xs text-muted-foreground">
                    계급
                  </span>
                  <span className="w-12 text-xs text-teal-400">
                    Lv.{g.dedLevel ?? 0}
                  </span>
                  <div className="flex-1">
                    <SammoBar
                      height={7}
                      percent={Math.min((g.dedication / 1000) * 100, 100)}
                      altText={`${g.dedication}/1000`}
                    />
                  </div>
                  <span className="text-[10px] text-muted-foreground w-16 text-right">
                    {g.dedication}
                  </span>
                </div>
                <div className="flex items-center gap-2 text-sm">
                  <span className="text-muted-foreground">귀속:</span>{" "}
                  <span>{g.belong}</span>
                  <span className="text-muted-foreground ml-3">배신:</span>{" "}
                  <span className={g.betray > 0 ? "text-red-400" : ""}>
                    {g.betray}
                  </span>
                </div>
              </CardContent>
            </Card>

            {/* Equipment */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">장비</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-2 gap-2 text-sm">
                  {EQUIP_MAP.map(({ key, label }) => {
                    const val = g[`${key}Code` as keyof typeof g] as string;
                    const hasItem = isValidObjKey(val);
                    return (
                      <div key={key} className="flex items-center gap-2">
                        <span className="text-muted-foreground w-8">
                          {label}:
                        </span>
                        <span
                          className={
                            hasItem ? "text-cyan-300" : "text-gray-500"
                          }
                        >
                          {hasItem ? val : "-"}
                        </span>
                      </div>
                    );
                  })}
                </div>
                <div className="mt-2 flex flex-wrap gap-1">
                  <Badge
                    variant={g.specialCode === "None" ? "outline" : "secondary"}
                  >
                    특기: {g.specialCode === "None" ? "없음" : g.specialCode}
                  </Badge>
                  <Badge
                    variant={
                      g.special2Code === "None" ? "outline" : "secondary"
                    }
                  >
                    특기2: {g.special2Code === "None" ? "없음" : g.special2Code}
                  </Badge>
                  <Badge variant="secondary">성격: {g.personalCode}</Badge>
                </div>
              </CardContent>
            </Card>

            {/* Proficiency (숙련도) */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">숙련도</CardTitle>
              </CardHeader>
              <CardContent className="space-y-1.5">
                {DEX_NAMES.map((name, i) => {
                  const dex = dexValues[i];
                  const info = formatDexLevel(dex);
                  return (
                    <div key={name} className="flex items-center gap-2">
                      <span className="w-8 text-xs text-muted-foreground">
                        {name}
                      </span>
                      <span
                        className="w-8 text-xs font-mono text-right"
                        style={{ color: info.color }}
                      >
                        {info.name}
                      </span>
                      <div className="flex-1">
                        <SammoBar
                          height={7}
                          percent={Math.min((info.level / 26) * 100, 100)}
                          altText={`${info.name} (${numberWithCommas(dex)})`}
                        />
                      </div>
                      <span className="text-[10px] text-muted-foreground w-14 text-right">
                        {numberWithCommas(dex)}
                      </span>
                    </div>
                  );
                })}
              </CardContent>
            </Card>
          </div>
        </TabsContent>

        {/* ===== TAB 2: 전투 통계 ===== */}
        <TabsContent value="battle" className="space-y-4 mt-4">
          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle className="text-sm flex items-center gap-2">
                  <Swords className="size-4" />
                  전투 기록
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
                  <div>
                    <span className="text-muted-foreground">전투 횟수:</span>{" "}
                    <span className="text-white font-mono">{warnum}</span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">승리:</span>{" "}
                    <span className="text-cyan-400 font-mono">{killnum}</span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">패배:</span>{" "}
                    <span className="text-red-400 font-mono">{deathnum}</span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">승률:</span>{" "}
                    <span
                      className={
                        Number(winRate) >= 50
                          ? "text-cyan-400"
                          : "text-orange-400"
                      }
                    >
                      {winRate}%
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">적 사살:</span>{" "}
                    <span className="text-yellow-400 font-mono">
                      {numberWithCommas(killcrew)}
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">아군 피해:</span>{" "}
                    <span className="text-red-300 font-mono">
                      {numberWithCommas(deathcrew)}
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">살상률:</span>{" "}
                    <span
                      className={
                        Number(killRate) >= 100
                          ? "text-cyan-400"
                          : "text-orange-400"
                      }
                    >
                      {killRate}%
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">계략 성공:</span>{" "}
                    <span className="text-green-400 font-mono">{firenum}</span>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle className="text-sm">접속 / 활동</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
                  <div>
                    <span className="text-muted-foreground">접속도:</span>{" "}
                    <span>{formatRefreshScore(refreshScore)}</span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">킬턴:</span>{" "}
                    <span>{g.killTurn ?? "-"}</span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">차단:</span>{" "}
                    <span className={g.blockState > 0 ? "text-red-400" : ""}>
                      {g.blockState > 0 ? "차단됨" : "정상"}
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">최근전투:</span>{" "}
                    <span className="text-xs">
                      {g.recentWarTime
                        ? new Date(g.recentWarTime).toLocaleDateString("ko-KR")
                        : "-"}
                    </span>
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>
        </TabsContent>

        {/* ===== TAB 3: 설정 ===== */}
        <TabsContent value="settings" className="space-y-4 mt-4">
          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle className="text-sm flex items-center gap-2">
                  <Settings className="size-4" />
                  장수 설정
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                {/* Defence Training */}
                <div className="space-y-1">
                  <label className="text-sm text-muted-foreground">
                    수비 훈련도
                  </label>
                  <select
                    value={defenceTrain}
                    onChange={(e) => setDefenceTrain(Number(e.target.value))}
                    className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
                  >
                    {DEFENCE_PRESETS.map((p) => (
                      <option key={p.value} value={p.value}>
                        {p.label}
                      </option>
                    ))}
                  </select>
                  <p className="text-xs text-muted-foreground">
                    999 선택 시 수비에 참가하지 않으며 훈련+1, 사기-1 보정
                  </p>
                </div>

                {/* Tournament */}
                <div className="space-y-1">
                  <label className="text-sm text-muted-foreground">
                    토너먼트 참가
                  </label>
                  <select
                    value={tournamentState}
                    onChange={(e) => setTournamentState(Number(e.target.value))}
                    className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
                  >
                    {TOURNAMENT_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </div>

                {/* Potion Threshold */}
                <div className="space-y-1">
                  <label className="text-sm text-muted-foreground">
                    약 사용 기준
                  </label>
                  <select
                    value={potionThreshold}
                    onChange={(e) => setPotionThreshold(Number(e.target.value))}
                    className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
                  >
                    {POTION_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                  <p className="text-xs text-muted-foreground">
                    부상이 설정 수치 이상일 때 자동으로 약을 사용합니다
                  </p>
                </div>

                {/* Pre-rise / Pre-open / Border Return toggles */}
                <div className="space-y-3 border-t border-gray-800 pt-3">
                  <p className="text-xs text-muted-foreground font-medium">거병/오픈/귀환 옵션</p>
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={preRiseDelete}
                      onChange={(e) => setPreRiseDelete(e.target.checked)}
                      className="rounded border-gray-600"
                    />
                    <span className="text-sm">사전거병 시 삭제</span>
                  </label>
                  <p className="text-xs text-muted-foreground ml-6">
                    거병 전에 장수를 삭제하고 새로 시작합니다.
                  </p>

                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={preOpenDelete}
                      onChange={(e) => setPreOpenDelete(e.target.checked)}
                      className="rounded border-gray-600"
                    />
                    <span className="text-sm">가오픈 삭제</span>
                  </label>
                  <p className="text-xs text-muted-foreground ml-6">
                    가오픈 시 장수를 삭제합니다.
                  </p>

                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={borderReturn}
                      onChange={(e) => setBorderReturn(e.target.checked)}
                      className="rounded border-gray-600"
                    />
                    <span className="text-sm">접경 귀환</span>
                  </label>
                  <p className="text-xs text-muted-foreground ml-6">
                    접경 도시에 도달하면 자동으로 귀환합니다.
                  </p>
                </div>

                {/* Custom CSS */}
                <div className="space-y-2 border-t border-gray-800 pt-3">
                  <div className="flex items-center justify-between">
                    <label className="text-sm text-muted-foreground">커스텀 CSS</label>
                    <Button
                      size="sm"
                      variant="ghost"
                      className="h-6 px-2 text-xs"
                      onClick={() => setCssPreview(!cssPreview)}
                    >
                      {cssPreview ? "미리보기 끄기" : "미리보기"}
                    </Button>
                  </div>
                  <textarea
                    value={customCss}
                    onChange={(e) => setCustomCss(e.target.value)}
                    placeholder={`/* 예: */\n.card { border-color: gold; }\n.text-cyan-400 { color: #ff6600; }`}
                    className="w-full h-32 px-3 py-2 bg-background border border-input rounded-md text-xs font-mono resize-y"
                  />
                  {cssPreview && customCss && (
                    <div className="border border-dashed border-yellow-600 rounded p-3">
                      <p className="text-[10px] text-yellow-500 mb-1">CSS 미리보기 (현재 페이지에 적용됨)</p>
                      <style dangerouslySetInnerHTML={{ __html: customCss }} />
                      <div className="text-xs text-muted-foreground">
                        위에 입력한 CSS가 현재 페이지에 실시간 적용되어 있습니다.
                      </div>
                    </div>
                  )}
                  <p className="text-xs text-muted-foreground">
                    게임 UI에 적용할 커스텀 CSS를 입력하세요. 저장 후 모든 페이지에 적용됩니다.
                  </p>
                </div>

                <Button
                  onClick={handleSaveSettings}
                  disabled={saving}
                  className="w-full"
                >
                  {saving ? "저장 중..." : "설정 저장"}
                </Button>
              </CardContent>
            </Card>

            <div className="space-y-4">
              {/* Actions */}
              <Card>
                <CardHeader>
                  <CardTitle className="text-sm">액션</CardTitle>
                </CardHeader>
                <CardContent className="space-y-2">
                  <Button
                    variant="outline"
                    onClick={handleVacation}
                    className="w-full"
                  >
                    휴가 전환
                  </Button>
                </CardContent>
              </Card>

              {/* Item Disposal */}
              <Card>
                <CardHeader>
                  <CardTitle className="text-sm flex items-center gap-2">
                    <Trash2 className="size-4" />
                    아이템 파기
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-2">
                  <p className="text-xs text-muted-foreground">
                    소지한 아이템을 파기합니다. 파기한 아이템은 복구할 수
                    없습니다.
                  </p>
                  <div className="grid grid-cols-2 gap-2">
                    {EQUIP_MAP.map(({ key, label }) => {
                      const val = g[`${key}Code` as keyof typeof g] as string;
                      const hasItem = isValidObjKey(val);
                      return (
                        <Button
                          key={key}
                          variant="outline"
                          size="sm"
                          disabled={!hasItem}
                          className="text-xs"
                          onClick={async () => {
                            if (
                              confirm(
                                `${label} [${val}]을(를) 정말 파기하시겠습니까?`,
                              )
                            ) {
                              try {
                                const res = await itemApi.discard(g.id, key);
                                if (res.data.success) {
                                  toast.success(
                                    res.data.logs?.[0] ??
                                      "아이템을 파기했습니다.",
                                  );
                                  router.refresh();
                                } else {
                                  toast.error(
                                    res.data.logs?.[0] ??
                                      "파기에 실패했습니다.",
                                  );
                                }
                              } catch {
                                toast.error("파기에 실패했습니다.");
                              }
                            }
                          }}
                        >
                          {label}: {hasItem ? val : "-"}
                        </Button>
                      );
                    })}
                  </div>
                </CardContent>
              </Card>
            </div>
          </div>
        </TabsContent>

        {/* ===== TAB 4: 기록 ===== */}
        <TabsContent value="log" className="mt-4">
          <LogTabContent
            generalId={g.id}
            records={records}
            battleRecords={battleRecords}
            historyRecords={historyRecords}
          />
        </TabsContent>
      </Tabs>
    </div>
  );
}

function LogTabContent({
  generalId,
  records,
  battleRecords,
  historyRecords,
}: {
  generalId: number;
  records: Message[];
  battleRecords: Message[];
  historyRecords: Message[];
}) {
  const [oldLogs, setOldLogs] = useState<{ id: number; message: string; date: string }[]>([]);
  const [oldLogType, setOldLogType] = useState<"generalHistory" | "generalAction" | "battleResult" | "battleDetail">("generalAction");
  const [oldLogsLoading, setOldLogsLoading] = useState(false);
  const [oldLogsEnd, setOldLogsEnd] = useState(false);

  const loadOldLogs = async (reset = false) => {
    setOldLogsLoading(true);
    try {
      const lastId = reset ? undefined : (oldLogs.length > 0 ? oldLogs[oldLogs.length - 1].id : undefined);
      const { data } = await generalLogApi.getOldLogs(generalId, generalId, oldLogType, lastId);
      if (data.result && data.logs) {
        if (reset) {
          setOldLogs(data.logs);
        } else {
          setOldLogs((prev) => [...prev, ...data.logs]);
        }
        if (data.logs.length === 0) setOldLogsEnd(true);
        else setOldLogsEnd(false);
      }
    } catch {
      // ignore
    } finally {
      setOldLogsLoading(false);
    }
  };

  return (
    <Tabs defaultValue="personal">
      <TabsList>
        <TabsTrigger value="personal">개인 기록</TabsTrigger>
        <TabsTrigger value="battle">전투 기록</TabsTrigger>
        <TabsTrigger value="history">장수 열전</TabsTrigger>
        <TabsTrigger value="old">이전 기록</TabsTrigger>
      </TabsList>

      <TabsContent value="personal" className="mt-3">
        <Card>
          <CardHeader>
            <CardTitle className="text-sm flex items-center gap-2">
              <ScrollText className="size-4" />
              개인 기록 ({records.length}건)
            </CardTitle>
          </CardHeader>
          <CardContent>
            <RecordList records={records} />
          </CardContent>
        </Card>
      </TabsContent>

      <TabsContent value="battle" className="mt-3">
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">
              전투 기록 ({battleRecords.length}건)
            </CardTitle>
          </CardHeader>
          <CardContent>
            <RecordList records={battleRecords} />
          </CardContent>
        </Card>
      </TabsContent>

      <TabsContent value="history" className="mt-3">
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">
              장수 열전 ({historyRecords.length}건)
            </CardTitle>
          </CardHeader>
          <CardContent>
            <RecordList records={historyRecords} />
          </CardContent>
        </Card>
      </TabsContent>

      <TabsContent value="old" className="mt-3">
        <Card>
          <CardHeader>
            <CardTitle className="text-sm flex items-center gap-2">
              <ScrollText className="size-4" />
              이전 기록
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground">유형:</span>
              <select
                value={oldLogType}
                onChange={(e) => {
                  setOldLogType(e.target.value as typeof oldLogType);
                  setOldLogs([]);
                  setOldLogsEnd(false);
                }}
                className="h-7 px-2 bg-background border border-input rounded text-xs"
              >
                <option value="generalAction">행동 기록</option>
                <option value="generalHistory">장수 열전</option>
                <option value="battleResult">전투 결과</option>
                <option value="battleDetail">전투 상세</option>
              </select>
              <Button
                size="sm"
                variant="outline"
                className="h-7 text-xs"
                disabled={oldLogsLoading}
                onClick={() => loadOldLogs(true)}
              >
                {oldLogsLoading ? "로딩..." : "불러오기"}
              </Button>
            </div>

            {oldLogs.length > 0 && (
              <ScrollArea className="max-h-[500px]">
                <div className="space-y-1">
                  {oldLogs.map((log) => (
                    <div
                      key={log.id}
                      className="text-sm py-1.5 px-2 rounded hover:bg-muted/30"
                    >
                      <span className="text-xs text-muted-foreground mr-2">
                        {log.date}
                      </span>
                      <span dangerouslySetInnerHTML={{ __html: log.message }} />
                    </div>
                  ))}
                </div>
              </ScrollArea>
            )}

            {oldLogs.length > 0 && !oldLogsEnd && (
              <Button
                size="sm"
                variant="outline"
                className="w-full text-xs"
                disabled={oldLogsLoading}
                onClick={() => loadOldLogs(false)}
              >
                {oldLogsLoading ? "로딩 중..." : "이전 기록 더 불러오기"}
              </Button>
            )}

            {oldLogsEnd && oldLogs.length > 0 && (
              <p className="text-xs text-muted-foreground text-center py-2">
                모든 기록을 불러왔습니다.
              </p>
            )}

            {oldLogs.length === 0 && !oldLogsLoading && (
              <p className="text-xs text-muted-foreground py-4">
                위에서 유형을 선택하고 &quot;불러오기&quot;를 클릭하세요.
              </p>
            )}
          </CardContent>
        </Card>
      </TabsContent>
    </Tabs>
  );
}

function RecordList({ records, pageSize = 20 }: { records: Message[]; pageSize?: number }) {
  const [page, setPage] = useState(0);

  if (records.length === 0) {
    return (
      <p className="text-sm text-muted-foreground py-4">기록이 없습니다.</p>
    );
  }

  const totalPages = Math.ceil(records.length / pageSize);
  const pagedRecords = records.slice(page * pageSize, (page + 1) * pageSize);

  return (
    <div className="space-y-2">
      {totalPages > 1 && (
        <div className="flex items-center gap-2 text-xs">
          <Button
            size="sm"
            variant="ghost"
            className="h-6 px-2 text-xs"
            disabled={page <= 0}
            onClick={() => setPage((p) => p - 1)}
          >
            ← 이전
          </Button>
          <span className="text-muted-foreground">
            {page + 1} / {totalPages} 페이지
          </span>
          <Button
            size="sm"
            variant="ghost"
            className="h-6 px-2 text-xs"
            disabled={page >= totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
          >
            다음 →
          </Button>
          {page > 0 && (
            <Button
              size="sm"
              variant="ghost"
              className="h-6 px-2 text-xs"
              onClick={() => setPage(0)}
            >
              처음으로
            </Button>
          )}
        </div>
      )}
      <ScrollArea className="max-h-[500px]">
        <div className="space-y-1">
          {pagedRecords.map((r) => (
            <div
              key={r.id}
              className="text-sm py-1.5 px-2 rounded hover:bg-muted/30"
            >
              <span className="text-xs text-muted-foreground mr-2">
                {new Date(r.sentAt).toLocaleString("ko-KR", {
                  month: "2-digit",
                  day: "2-digit",
                  hour: "2-digit",
                  minute: "2-digit",
                })}
              </span>
              <span
                dangerouslySetInnerHTML={{
                  __html:
                    (r.payload.content as string) ?? JSON.stringify(r.payload),
                }}
              />
            </div>
          ))}
        </div>
      </ScrollArea>
    </div>
  );
}
