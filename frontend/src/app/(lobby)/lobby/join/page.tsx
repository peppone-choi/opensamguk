"use client";

import { useEffect, useState, useMemo, useCallback } from "react";
import { useRouter } from "next/navigation";
import { UserPlus, ArrowLeft } from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { inheritanceApi } from "@/lib/gameApi";
import api from "@/lib/api";
import { PageHeader } from "@/components/game/page-header";
import { StatBar } from "@/components/game/stat-bar";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import type { InheritanceInfo, Nation } from "@/types";

const TOTAL_STAT_POINTS = 350;
const STAT_MIN = 10;
const STAT_MAX = 100;

const STAT_KEYS = [
  "leadership",
  "strength",
  "intel",
  "politics",
  "charm",
] as const;
type StatKey = (typeof STAT_KEYS)[number];

const STAT_LABELS: Record<StatKey, string> = {
  leadership: "통솔",
  strength: "무력",
  intel: "지력",
  politics: "정치",
  charm: "매력",
};

const STAT_COLORS: Record<StatKey, string> = {
  leadership: "bg-red-500",
  strength: "bg-orange-500",
  intel: "bg-blue-500",
  politics: "bg-green-500",
  charm: "bg-purple-500",
};

const CREW_TYPES: { code: number; label: string }[] = [
  { code: 0, label: "보병" },
  { code: 1, label: "궁병" },
  { code: 2, label: "기병" },
  { code: 3, label: "수군" },
];

// Personality list matching legacy character system
const PERSONALITIES: { key: string; name: string; info: string }[] = [
  { key: "Random", name: "랜덤", info: "성격을 랜덤으로 선택합니다." },
  { key: "Normal", name: "일반", info: "특별한 보정이 없습니다." },
  { key: "Brave", name: "호전", info: "전투에 적극적입니다." },
  { key: "Calm", name: "냉정", info: "냉정하게 판단합니다." },
  { key: "Loyal", name: "충성", info: "충성심이 높습니다." },
  { key: "Timid", name: "소심", info: "소극적으로 행동합니다." },
  { key: "Reckless", name: "저돌", info: "무모하게 돌진합니다." },
  { key: "Ambition", name: "야망", info: "큰 야망을 품고 있습니다." },
];

type StatPreset = "balanced" | "random" | "leadership" | "strength" | "intel";

export default function LobbyJoinPage() {
  const router = useRouter();
  const { currentWorld } = useWorldStore();
  const { fetchMyGeneral } = useGeneralStore();
  const { cities, nations, loadAll } = useGameStore();

  const [name, setName] = useState("");
  const [cityId, setCityId] = useState<number | "">("");
  const [nationId, setNationId] = useState<number>(0);
  const [crewType, setCrewType] = useState(0);
  const [personality, setPersonality] = useState("Random");
  const [stats, setStats] = useState<Record<StatKey, number>>({
    leadership: 70,
    strength: 70,
    intel: 70,
    politics: 70,
    charm: 70,
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Inheritance point system (유산 포인트) - legacy parity
  const [inheritInfo, setInheritInfo] = useState<InheritanceInfo | null>(null);
  const [inheritSpecial, setInheritSpecial] = useState("");
  const [inheritCity, setInheritCity] = useState<number | "">("");
  const [inheritBonusStat, setInheritBonusStat] = useState<
    [number, number, number]
  >([0, 0, 0]);

  // Nation scout messages for recruitment display
  const [scoutMessages, setScoutMessages] = useState<
    Record<number, string>
  >({});

  useEffect(() => {
    if (currentWorld) {
      loadAll(currentWorld.id);

      // Load inheritance info
      inheritanceApi
        .getInfo(currentWorld.id)
        .then(({ data }) => setInheritInfo(data))
        .catch(() => {});

      // Load scout messages from nations
      api
        .get<Nation[]>(`/worlds/${currentWorld.id}/nations`)
        .then(({ data: nationList }) => {
          const msgs: Record<number, string> = {};
          for (const n of nationList) {
            if (n.meta && typeof n.meta === "object" && "scoutMsg" in n.meta) {
              msgs[n.id] = String(n.meta.scoutMsg);
            }
          }
          setScoutMessages(msgs);
        })
        .catch(() => {});
    }
  }, [currentWorld, loadAll]);

  const totalUsed = useMemo(
    () => STAT_KEYS.reduce((sum, k) => sum + stats[k], 0),
    [stats],
  );
  const remaining = TOTAL_STAT_POINTS - totalUsed;

  const inheritBonusSum = inheritBonusStat.reduce((a, b) => a + b, 0);
  const inheritBonusValid =
    inheritBonusSum === 0 || (inheritBonusSum >= 3 && inheritBonusSum <= 5);

  const adjustStat = (key: StatKey, delta: number) => {
    setStats((prev) => {
      const next = Math.max(STAT_MIN, Math.min(STAT_MAX, prev[key] + delta));
      const newTotal = STAT_KEYS.reduce(
        (s, k) => s + (k === key ? next : prev[k]),
        0,
      );
      if (newTotal > TOTAL_STAT_POINTS) return prev;
      return { ...prev, [key]: next };
    });
  };

  const handleStatInput = (key: StatKey, value: number) => {
    const clamped = Math.max(STAT_MIN, Math.min(STAT_MAX, value));
    setStats((prev) => ({ ...prev, [key]: clamped }));
  };

  // Stat presets - legacy parity from core2026 JoinView
  const applyPreset = useCallback(
    (preset: StatPreset) => {
      const base = Math.floor(TOTAL_STAT_POINTS / 5);
      const r = (min: number, max: number) =>
        Math.floor(Math.random() * (max - min + 1)) + min;

      switch (preset) {
        case "balanced": {
          const remainder = TOTAL_STAT_POINTS - base * 5;
          setStats({
            leadership: base + (remainder > 0 ? 1 : 0),
            strength: base + (remainder > 1 ? 1 : 0),
            intel: base + (remainder > 2 ? 1 : 0),
            politics: base + (remainder > 3 ? 1 : 0),
            charm: base,
          });
          break;
        }
        case "random": {
          for (let attempt = 0; attempt < 100; attempt++) {
            const vals = STAT_KEYS.map(() => r(STAT_MIN, STAT_MAX));
            const sum = vals.reduce((a, b) => a + b, 0);
            if (sum === TOTAL_STAT_POINTS) {
              setStats({
                leadership: vals[0],
                strength: vals[1],
                intel: vals[2],
                politics: vals[3],
                charm: vals[4],
              });
              return;
            }
          }
          // Fallback: distribute evenly with random variation
          const vals = STAT_KEYS.map(() => base);
          let remain = TOTAL_STAT_POINTS - vals.reduce((a, b) => a + b, 0);
          while (remain > 0) {
            const idx = r(0, 4);
            if (vals[idx] < STAT_MAX) {
              vals[idx]++;
              remain--;
            }
          }
          setStats({
            leadership: vals[0],
            strength: vals[1],
            intel: vals[2],
            politics: vals[3],
            charm: vals[4],
          });
          break;
        }
        case "leadership":
        case "strength":
        case "intel": {
          const focusValue = Math.min(
            STAT_MAX,
            STAT_MIN + Math.floor(TOTAL_STAT_POINTS * 0.3),
          );
          const remain = TOTAL_STAT_POINTS - focusValue;
          const side = Math.floor(remain / 4);
          const last = remain - side * 3;
          const newStats: Record<StatKey, number> = {
            leadership: side,
            strength: side,
            intel: side,
            politics: side,
            charm: last,
          };
          newStats[preset] = focusValue;
          setStats(newStats);
          break;
        }
      }
    },
    [],
  );

  // Filter cities by selected nation
  const filteredCities = useMemo(() => {
    if (nationId === 0) return cities;
    return cities.filter((c) => c.nationId === nationId || c.nationId === 0);
  }, [cities, nationId]);

  // Nations with scout messages for recruitment display
  const nationsWithScout = useMemo(() => {
    return nations.filter(
      (n) =>
        scoutMessages[n.id] &&
        scoutMessages[n.id].trim().length > 0,
    );
  }, [nations, scoutMessages]);

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!currentWorld) return;
    if (!name.trim()) {
      setError("이름을 입력해주세요.");
      return;
    }
    if (cityId === "") {
      setError("도시를 선택해주세요.");
      return;
    }
    if (remaining !== 0) {
      setError(
        `능력치 합계가 ${TOTAL_STAT_POINTS}이어야 합니다. (현재: ${totalUsed})`,
      );
      return;
    }
    if (!inheritBonusValid) {
      setError("보너스 능력치는 합 0 또는 3~5 사이여야 합니다.");
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/worlds/${currentWorld.id}/generals`, {
        name: name.trim(),
        cityId,
        nationId: nationId || null,
        crewType,
        personality,
        ...stats,
        inheritSpecial: inheritSpecial || undefined,
        inheritCity: inheritCity || undefined,
        inheritBonusStat:
          inheritBonusSum > 0 ? inheritBonusStat : undefined,
      });
      await fetchMyGeneral(currentWorld.id);
      router.push("/");
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : "장수 생성에 실패했습니다.";
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );

  return (
    <div className="p-4 max-w-2xl mx-auto space-y-6">
      <Button
        variant="ghost"
        size="sm"
        onClick={() => router.push("/lobby")}
        className="mb-2"
      >
        <ArrowLeft className="size-4 mr-1" /> 로비로 돌아가기
      </Button>

      <PageHeader icon={UserPlus} title="장수 생성" />

      {error && (
        <div className="text-sm px-3 py-2 rounded bg-destructive/20 text-destructive">
          {error}
        </div>
      )}

      {/* Nation Recruitment Messages (임관 권유) - legacy parity from v_join.php & core2026 JoinView */}
      {nationsWithScout.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">국가 임관 권유</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {nationsWithScout.map((n) => (
              <div
                key={n.id}
                className="flex gap-3 items-start border border-input rounded p-2"
              >
                <div
                  className="px-2 py-1 text-xs font-bold text-black rounded shrink-0"
                  style={{ backgroundColor: n.color }}
                >
                  {n.name}
                </div>
                <p className="text-xs text-muted-foreground">
                  {scoutMessages[n.id]}
                </p>
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>장수 정보 입력</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-5">
            {/* Name */}
            <div className="space-y-1">
              <label className="block text-sm text-muted-foreground">
                장수명
              </label>
              <Input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={20}
                placeholder="장수 이름 입력"
              />
            </div>

            {/* Personality - legacy parity from core2026 JoinView */}
            <div className="space-y-1">
              <label className="block text-sm text-muted-foreground">
                성격
              </label>
              <select
                value={personality}
                onChange={(e) => setPersonality(e.target.value)}
                className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
              >
                {PERSONALITIES.map((p) => (
                  <option key={p.key} value={p.key}>
                    {p.name}
                  </option>
                ))}
              </select>
              <p className="text-xs text-muted-foreground">
                {PERSONALITIES.find((p) => p.key === personality)?.info}
              </p>
            </div>

            {/* Nation */}
            <div className="space-y-1">
              <label className="block text-sm text-muted-foreground">
                소속 국가
              </label>
              <select
                value={nationId}
                onChange={(e) => setNationId(Number(e.target.value))}
                className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
              >
                <option value={0}>재야 (무소속)</option>
                {nations.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.name}
                  </option>
                ))}
              </select>
            </div>

            {/* City */}
            <div className="space-y-1">
              <label className="block text-sm text-muted-foreground">
                시작 도시
              </label>
              <select
                value={cityId}
                onChange={(e) => setCityId(Number(e.target.value))}
                className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
              >
                <option value="">도시 선택</option>
                {filteredCities.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name} (Lv.{c.level})
                  </option>
                ))}
              </select>
            </div>

            {/* Crew Type */}
            <div className="space-y-1">
              <label className="block text-sm text-muted-foreground">
                병종
              </label>
              <select
                value={crewType}
                onChange={(e) => setCrewType(Number(e.target.value))}
                className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
              >
                {CREW_TYPES.map((ct) => (
                  <option key={ct.code} value={ct.code}>
                    {ct.label}
                  </option>
                ))}
              </select>
            </div>

            {/* Stat Presets - legacy parity from core2026 JoinView */}
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <label className="text-sm text-muted-foreground">
                  능력치 배분 (합계 {TOTAL_STAT_POINTS})
                </label>
                <Badge
                  variant={
                    remaining === 0
                      ? "default"
                      : remaining > 0
                        ? "secondary"
                        : "destructive"
                  }
                  className={
                    remaining === 0
                      ? "bg-green-600"
                      : remaining > 0
                        ? "bg-amber-600"
                        : ""
                  }
                >
                  남은: {remaining}
                </Badge>
              </div>

              <div className="flex flex-wrap gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => applyPreset("random")}
                >
                  랜덤형
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => applyPreset("leadership")}
                >
                  통솔형
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => applyPreset("strength")}
                >
                  무력형
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => applyPreset("intel")}
                >
                  지력형
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => applyPreset("balanced")}
                >
                  균형형
                </Button>
              </div>

              {STAT_KEYS.map((key) => (
                <div key={key} className="space-y-1">
                  <div className="flex items-center gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      size="icon-xs"
                      onClick={() => adjustStat(key, -5)}
                    >
                      -
                    </Button>
                    <div className="flex-1">
                      <StatBar
                        label={STAT_LABELS[key]}
                        value={stats[key]}
                        max={STAT_MAX}
                        color={STAT_COLORS[key]}
                      />
                    </div>
                    <Button
                      type="button"
                      variant="outline"
                      size="icon-xs"
                      onClick={() => adjustStat(key, 5)}
                    >
                      +
                    </Button>
                    <Input
                      type="number"
                      min={STAT_MIN}
                      max={STAT_MAX}
                      value={stats[key]}
                      onChange={(e) =>
                        handleStatInput(key, Number(e.target.value))
                      }
                      className="w-14 text-center"
                    />
                  </div>
                </div>
              ))}
            </div>

            {/* Inheritance Points (유산 포인트) - legacy parity from v_join.php & core2026 JoinView */}
            {inheritInfo && inheritInfo.points > 0 && (
              <Card className="border-amber-500/30">
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm">유산 포인트 옵션</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  <div className="flex justify-between text-xs">
                    <span>보유 포인트: {inheritInfo.points}</span>
                  </div>

                  <div className="space-y-2">
                    <label className="block text-xs text-muted-foreground">
                      전투 특기 선택
                    </label>
                    <select
                      value={inheritSpecial}
                      onChange={(e) => setInheritSpecial(e.target.value)}
                      className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
                    >
                      <option value="">선택 안함</option>
                    </select>
                  </div>

                  <div className="space-y-2">
                    <label className="block text-xs text-muted-foreground">
                      시작 도시 지정 (유산)
                    </label>
                    <select
                      value={inheritCity}
                      onChange={(e) =>
                        setInheritCity(
                          e.target.value ? Number(e.target.value) : "",
                        )
                      }
                      className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
                    >
                      <option value="">랜덤 배치</option>
                      {cities.map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name} (Lv.{c.level})
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="space-y-2">
                    <label className="block text-xs text-muted-foreground">
                      보너스 능력치 (합 0 또는 3~5)
                    </label>
                    <div className="grid grid-cols-3 gap-2">
                      <div>
                        <span className="text-xs">통솔</span>
                        <Input
                          type="number"
                          min={0}
                          max={5}
                          value={inheritBonusStat[0]}
                          onChange={(e) =>
                            setInheritBonusStat([
                              Number(e.target.value),
                              inheritBonusStat[1],
                              inheritBonusStat[2],
                            ])
                          }
                          className="text-center"
                        />
                      </div>
                      <div>
                        <span className="text-xs">무력</span>
                        <Input
                          type="number"
                          min={0}
                          max={5}
                          value={inheritBonusStat[1]}
                          onChange={(e) =>
                            setInheritBonusStat([
                              inheritBonusStat[0],
                              Number(e.target.value),
                              inheritBonusStat[2],
                            ])
                          }
                          className="text-center"
                        />
                      </div>
                      <div>
                        <span className="text-xs">지력</span>
                        <Input
                          type="number"
                          min={0}
                          max={5}
                          value={inheritBonusStat[2]}
                          onChange={(e) =>
                            setInheritBonusStat([
                              inheritBonusStat[0],
                              inheritBonusStat[1],
                              Number(e.target.value),
                            ])
                          }
                          className="text-center"
                        />
                      </div>
                    </div>
                    <p className="text-xs text-muted-foreground">
                      보너스 합: {inheritBonusSum}
                    </p>
                    {!inheritBonusValid && (
                      <p className="text-xs text-red-400">
                        보너스 능력치는 합 3~5 사이여야 합니다.
                      </p>
                    )}
                  </div>
                </CardContent>
              </Card>
            )}

            {/* Submit */}
            <div className="flex gap-2">
              <Button
                type="submit"
                disabled={submitting || remaining !== 0}
                className="flex-1"
              >
                {submitting ? "생성중..." : "장수 생성"}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={() => applyPreset("balanced")}
              >
                다시 입력
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
