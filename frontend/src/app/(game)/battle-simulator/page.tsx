"use client";

import { useState, useEffect, useMemo, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { battleSimApi, simulatorExportApi } from "@/lib/gameApi";
import type { BattleSimUnit, BattleSimCity, BattleSimResult, BattleSimRepeatResult, General } from "@/types";
import { Swords, Play, RotateCcw, Download, Upload, UserPlus, Plus, Minus, CloudRain, Mountain, Building } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";

const CREW_TYPES: Record<number, string> = {
  0: "보병", 1: "궁병", 2: "기병", 3: "귀병", 4: "차병", 5: "노병",
  6: "연노병", 7: "근위기병", 8: "무당병", 9: "서량기병", 10: "등갑병", 11: "수군",
};

// Known item/weapon/horse/book codes from legacy — these are used as dropdown options.
// In production these would come from game const store; we provide common ones here.
const WEAPON_CODES = [
  "", "nothing", "dagger", "short_sword", "sword", "spear", "halberd",
  "iron_mace", "double_sword", "glaive", "trident", "great_sword",
  "sky_piercer", "green_dragon", "serpent_spear",
];
const BOOK_CODES = [
  "", "nothing", "tactics_basic", "tactics_mid", "tactics_adv",
  "art_of_war", "mencius", "taigong", "36_strategies",
];
const HORSE_CODES = [
  "", "nothing", "horse_basic", "horse_mid", "horse_adv",
  "red_hare", "hex_mark", "dilu", "shadow_runner",
];
const ITEM_CODES = [
  "", "nothing", "jade_seal", "imperial_edict", "ancient_wine",
  "seven_star_sword", "bronze_sparrow",
];
const SPECIAL_CODES = [
  "", "none", "fire_attack", "ambush", "siege", "naval",
  "divine", "charge", "rapid_advance", "fortify",
];

interface InheritBuff {
  warAvoidRatio: number;
  warCriticalRatio: number;
  warMagicTrialProb: number;
  warAvoidRatioOppose: number;
  warCriticalRatioOppose: number;
  warMagicTrialProbOppose: number;
}

const defaultInheritBuff = (): InheritBuff => ({
  warAvoidRatio: 0,
  warCriticalRatio: 0,
  warMagicTrialProb: 0,
  warAvoidRatioOppose: 0,
  warCriticalRatioOppose: 0,
  warMagicTrialProbOppose: 0,
});

interface UnitFormState {
  name: string;
  leadership: number;
  strength: number;
  intel: number;
  crew: number;
  crewType: number;
  train: number;
  atmos: number;
  weaponCode: string;
  bookCode: string;
  horseCode: string;
  itemCode: string;
  specialCode: string;
  injury: number;
  rice: number;
  dex1: number;
  dex2: number;
  dex3: number;
  dex4: number;
  dex5: number;
  defenceTrain: number;
  officerLevel: number;
  expLevel: number;
  warnum: number;
  killnum: number;
  killcrew: number;
  personalCode: string;
  inheritBuff: InheritBuff;
}

const defaultUnit = (name: string): UnitFormState => ({
  name,
  leadership: 50,
  strength: 50,
  intel: 50,
  crew: 7000,
  crewType: 0,
  train: 100,
  atmos: 100,
  weaponCode: "",
  bookCode: "",
  horseCode: "",
  itemCode: "",
  specialCode: "",
  injury: 0,
  rice: 10000,
  dex1: 0,
  dex2: 0,
  dex3: 0,
  dex4: 0,
  dex5: 0,
  defenceTrain: 80,
  officerLevel: 1,
  expLevel: 0,
  warnum: 0,
  killnum: 0,
  killcrew: 0,
  personalCode: "",
  inheritBuff: defaultInheritBuff(),
});

interface CityDefenseState {
  def: number;
  wall: number;
  level: number;
  terrain: number; // 0=평지, 1=산지, 2=습지, 3=수상
  weather: number; // 0=맑음, 1=비, 2=눈, 3=안개
}

const TERRAIN_LABELS: Record<number, string> = { 0: "평지", 1: "산지", 2: "습지", 3: "수상" };
const WEATHER_LABELS: Record<number, string> = { 0: "맑음", 1: "비", 2: "눈", 3: "안개" };

const defaultCityDefense: CityDefenseState = { def: 1000, wall: 1000, level: 5, terrain: 0, weather: 0 };

function NumberField({
  label,
  value,
  onChange,
  min,
  max,
  step,
  className: cls,
}: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  min?: number;
  max?: number;
  step?: number;
  className?: string;
}) {
  return (
    <div className={`flex items-center gap-1 ${cls ?? ""}`}>
      <span className="text-xs text-muted-foreground whitespace-nowrap w-10 text-right">{label}</span>
      <Input
        type="number"
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        min={min}
        max={max}
        step={step}
        className="w-20 h-7 text-xs tabular-nums"
      />
    </div>
  );
}

function SelectField({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  options: string[];
}) {
  return (
    <div className="flex items-center gap-1">
      <span className="text-xs text-muted-foreground whitespace-nowrap w-10 text-right">{label}</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="h-7 border border-gray-600 bg-[#111] px-1 text-xs text-white min-w-[80px]"
      >
        {options.map((o) => (
          <option key={o} value={o}>{o || "(없음)"}</option>
        ))}
      </select>
    </div>
  );
}

function UnitBuilder({
  title,
  unit,
  onChange,
  generals,
  myGeneralId,
}: {
  title: string;
  unit: UnitFormState;
  onChange: (u: UnitFormState) => void;
  generals: General[];
  myGeneralId: number | null;
}) {
  const set = <K extends keyof UnitFormState>(key: K, val: UnitFormState[K]) =>
    onChange({ ...unit, [key]: val });

  const [showInherit, setShowInherit] = useState(false);
  const [loadingGeneral, setLoadingGeneral] = useState(false);

  const handleLoadGeneral = async (targetId: number) => {
    if (!myGeneralId) return;
    setLoadingGeneral(true);
    try {
      const { data } = await simulatorExportApi.exportGeneral(myGeneralId, targetId);
      if (data.result && data.data) {
        const d = data.data;
        onChange({
          ...unit,
          name: (d.name as string) ?? unit.name,
          leadership: (d.leadership as number) ?? unit.leadership,
          strength: (d.strength as number) ?? unit.strength,
          intel: (d.intel as number) ?? unit.intel,
          crew: (d.crew as number) ?? unit.crew,
          crewType: (d.crewtype as number) ?? unit.crewType,
          train: (d.train as number) ?? unit.train,
          atmos: (d.atmos as number) ?? unit.atmos,
          weaponCode: (d.weapon as string) ?? "",
          bookCode: (d.book as string) ?? "",
          horseCode: (d.horse as string) ?? "",
          itemCode: (d.item as string) ?? "",
          specialCode: (d.specialWar as string) ?? "",
          personalCode: (d.personal as string) ?? "",
          injury: (d.injury as number) ?? 0,
          rice: (d.rice as number) ?? 10000,
          defenceTrain: (d.defenceTrain as number) ?? 80,
          officerLevel: (d.officerLevel as number) ?? 1,
          dex1: ((d.dex as Record<string, number> | undefined)?.["1"]) ?? 0,
          dex2: ((d.dex as Record<string, number> | undefined)?.["2"]) ?? 0,
          dex3: ((d.dex as Record<string, number> | undefined)?.["3"]) ?? 0,
          dex4: ((d.dex as Record<string, number> | undefined)?.["4"]) ?? 0,
          dex5: ((d.dex as Record<string, number> | undefined)?.["5"]) ?? 0,
          warnum: 0,
          killnum: 0,
          killcrew: 0,
          expLevel: 0,
          inheritBuff: defaultInheritBuff(),
        });
      }
    } catch {
      // ignore
    } finally {
      setLoadingGeneral(false);
    }
  };

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm flex items-center gap-2">
          {title}
          {/* General picker */}
          {generals.length > 0 && myGeneralId && (
            <select
              onChange={(e) => {
                const id = Number(e.target.value);
                if (id > 0) handleLoadGeneral(id);
              }}
              className="h-6 border border-gray-600 bg-[#111] px-1 text-[10px] text-white ml-auto"
              disabled={loadingGeneral}
              defaultValue=""
            >
              <option value="" disabled>{loadingGeneral ? "로딩..." : "서버에서 불러오기"}</option>
              {generals.map((g) => (
                <option key={g.id} value={g.id} style={g.npcState > 0 ? { color: "#999" } : undefined}>
                  {g.name}
                </option>
              ))}
            </select>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {/* Name */}
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground w-10 text-right">이름</span>
          <Input value={unit.name} onChange={(e) => set("name", e.target.value)} className="w-24 h-7 text-xs" />
        </div>

        {/* Core stats */}
        <div className="flex flex-wrap gap-2">
          <NumberField label="통솔" value={unit.leadership} onChange={(v) => set("leadership", v)} min={1} max={100} />
          <NumberField label="무력" value={unit.strength} onChange={(v) => set("strength", v)} min={1} max={100} />
          <NumberField label="지력" value={unit.intel} onChange={(v) => set("intel", v)} min={1} max={100} />
        </div>

        {/* Crew */}
        <div className="flex flex-wrap gap-2">
          <div className="flex items-center gap-1">
            <span className="text-xs text-muted-foreground w-10 text-right">병종</span>
            <select
              value={unit.crewType}
              onChange={(e) => set("crewType", Number(e.target.value))}
              className="h-7 border border-gray-600 bg-[#111] px-1 text-xs text-white"
            >
              {Object.entries(CREW_TYPES).map(([k, v]) => (
                <option key={k} value={k}>{v}</option>
              ))}
            </select>
          </div>
          <NumberField label="병사" value={unit.crew} onChange={(v) => set("crew", v)} min={100} step={100} />
        </div>

        {/* Train / Atmos */}
        <div className="flex flex-wrap gap-2">
          <NumberField label="훈련" value={unit.train} onChange={(v) => set("train", v)} min={40} max={100} />
          <NumberField label="사기" value={unit.atmos} onChange={(v) => set("atmos", v)} min={40} max={100} />
          <NumberField label="수비훈" value={unit.defenceTrain} onChange={(v) => set("defenceTrain", v)} min={0} max={100} />
        </div>

        {/* Injury / Rice */}
        <div className="flex flex-wrap gap-2">
          <NumberField label="부상" value={unit.injury} onChange={(v) => set("injury", v)} min={0} max={80} />
          <NumberField label="군량" value={unit.rice} onChange={(v) => set("rice", v)} min={0} step={1000} />
        </div>

        {/* Equipment dropdowns */}
        <div className="flex flex-wrap gap-2">
          <SelectField label="무기" value={unit.weaponCode} onChange={(v) => set("weaponCode", v)} options={WEAPON_CODES} />
          <SelectField label="서적" value={unit.bookCode} onChange={(v) => set("bookCode", v)} options={BOOK_CODES} />
        </div>
        <div className="flex flex-wrap gap-2">
          <SelectField label="명마" value={unit.horseCode} onChange={(v) => set("horseCode", v)} options={HORSE_CODES} />
          <SelectField label="아이템" value={unit.itemCode} onChange={(v) => set("itemCode", v)} options={ITEM_CODES} />
        </div>
        <div className="flex flex-wrap gap-2">
          <SelectField label="특기" value={unit.specialCode} onChange={(v) => set("specialCode", v)} options={SPECIAL_CODES} />
          <SelectField label="성격" value={unit.personalCode} onChange={(v) => set("personalCode", v)} options={["", "의리", "냉정", "호탈"]} />
        </div>

        {/* Dex values */}
        <div className="flex flex-wrap gap-2">
          {["창", "궁", "기", "귀차", "노"].map((label, i) => {
            const key = `dex${i + 1}` as keyof UnitFormState;
            return (
              <NumberField
                key={label}
                label={label}
                value={unit[key] as number}
                onChange={(v) => set(key, v as never)}
                min={0}
              />
            );
          })}
        </div>

        {/* Officer / Exp level */}
        <div className="flex flex-wrap gap-2">
          <NumberField label="관직" value={unit.officerLevel} onChange={(v) => set("officerLevel", v)} min={0} max={12} />
          <NumberField label="레벨" value={unit.expLevel} onChange={(v) => set("expLevel", v)} min={0} />
        </div>

        {/* Inherit buffs (collapsible) */}
        <div>
          <button
            type="button"
            onClick={() => setShowInherit(!showInherit)}
            className="text-[10px] text-blue-400 underline"
          >
            {showInherit ? "▲ 유산 버프 숨기기" : "▼ 유산 버프 보기"}
          </button>
          {showInherit && (
            <div className="mt-1 grid grid-cols-2 gap-1">
              <NumberField label="회피" value={unit.inheritBuff.warAvoidRatio} onChange={(v) => set("inheritBuff", { ...unit.inheritBuff, warAvoidRatio: v })} />
              <NumberField label="회피저" value={unit.inheritBuff.warAvoidRatioOppose} onChange={(v) => set("inheritBuff", { ...unit.inheritBuff, warAvoidRatioOppose: v })} />
              <NumberField label="필살" value={unit.inheritBuff.warCriticalRatio} onChange={(v) => set("inheritBuff", { ...unit.inheritBuff, warCriticalRatio: v })} />
              <NumberField label="필살저" value={unit.inheritBuff.warCriticalRatioOppose} onChange={(v) => set("inheritBuff", { ...unit.inheritBuff, warCriticalRatioOppose: v })} />
              <NumberField label="계략" value={unit.inheritBuff.warMagicTrialProb} onChange={(v) => set("inheritBuff", { ...unit.inheritBuff, warMagicTrialProb: v })} />
              <NumberField label="계략저" value={unit.inheritBuff.warMagicTrialProbOppose} onChange={(v) => set("inheritBuff", { ...unit.inheritBuff, warMagicTrialProbOppose: v })} />
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

export default function BattleSimulatorPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, nations, loadAll } = useGameStore();

  const [year, setYear] = useState(currentWorld?.currentYear ?? 200);
  const [month, setMonth] = useState(currentWorld?.currentMonth ?? 1);
  const [seed, setSeed] = useState("");
  const [repeatCount, setRepeatCount] = useState<1 | 1000>(1);

  const [attacker, setAttacker] = useState<UnitFormState>(defaultUnit("공격측"));
  const [defenders, setDefenders] = useState<UnitFormState[]>([defaultUnit("방어측")]);
  const [cityDef, setCityDef] = useState<CityDefenseState>({ ...defaultCityDefense });
  const [nationContext, setNationContext] = useState<number | null>(null);

  // Backward compat alias
  const defender = defenders[0];
  const setDefender = (u: UnitFormState) => setDefenders((prev) => [u, ...prev.slice(1)]);

  const [result, setResult] = useState<(BattleSimResult & { repeatSummary?: BattleSimRepeatResult }) | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (currentWorld) {
      loadAll(currentWorld.id);
      if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
    }
  }, [currentWorld, loadAll, myGeneral, fetchMyGeneral]);

  const handleSimulate = async () => {
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const toUnit = (u: UnitFormState): BattleSimUnit => ({
        name: u.name,
        leadership: u.leadership,
        strength: u.strength,
        intel: u.intel,
        crew: u.crew,
        crewType: u.crewType,
        train: u.train,
        atmos: u.atmos,
        weaponCode: u.weaponCode || undefined,
        bookCode: u.bookCode || undefined,
        horseCode: u.horseCode || undefined,
        itemCode: u.itemCode || undefined,
        specialCode: u.specialCode || undefined,
        personalCode: u.personalCode || undefined,
        injury: u.injury,
        rice: u.rice,
        dex1: u.dex1,
        dex2: u.dex2,
        dex3: u.dex3,
        dex4: u.dex4,
        dex5: u.dex5,
        defenceTrain: u.defenceTrain,
        officerLevel: u.officerLevel,
        expLevel: u.expLevel,
        inheritBuff: u.inheritBuff,
      });
      const attackerUnit = toUnit(attacker);
      const defenderUnit = toUnit(defender);
      const defCity: BattleSimCity = {
        def: cityDef.def,
        wall: cityDef.wall,
        level: cityDef.level,
      };
      const { data } = await battleSimApi.simulate(attackerUnit, defenderUnit, defCity, {
        year,
        month,
        seed: seed || undefined,
        repeatCount,
      });
      setResult(data);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "시뮬레이션 오류");
    } finally {
      setRunning(false);
    }
  };

  const handleReset = () => {
    setAttacker(defaultUnit("공격측"));
    setDefender(defaultUnit("방어측"));
    setCityDef({ ...defaultCityDefense });
    setResult(null);
    setError(null);
  };

  const handleDownload = () => {
    if (!result) return;
    const dateStr = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
    const content = JSON.stringify(
      {
        attacker: { name: attacker.name, crew: attacker.crew },
        defender: { name: defender.name, crew: defender.crew },
        result,
      },
      null,
      2,
    );
    const blob = new Blob([content], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `battle_${dateStr}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleExportConfig = () => {
    const config = { attacker, defenders, cityDef, year, month, seed, repeatCount, nationContext };
    const blob = new Blob([JSON.stringify(config, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `sim_config_${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleImportConfig = () => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = ".json";
    input.onchange = async (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;
      try {
        const text = await file.text();
        const config = JSON.parse(text);
        if (config.attacker) setAttacker(config.attacker);
        if (config.defenders) setDefenders(config.defenders);
        else if (config.defender) setDefenders([config.defender]);
        if (config.cityDef) setCityDef(config.cityDef);
        if (config.year) setYear(config.year);
        if (config.month) setMonth(config.month);
        if (config.seed) setSeed(config.seed);
        if (config.repeatCount) setRepeatCount(config.repeatCount);
        if (config.nationContext !== undefined) setNationContext(config.nationContext);
      } catch {
        setError("설정 파일을 읽을 수 없습니다.");
      }
    };
    input.click();
  };

  const addDefender = () => {
    setDefenders((prev) => [...prev, defaultUnit(`방어측 ${prev.length + 1}`)]);
  };

  const removeDefender = (idx: number) => {
    if (defenders.length <= 1) return;
    setDefenders((prev) => prev.filter((_, i) => i !== idx));
  };

  if (!currentWorld)
    return <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>;

  return (
    <div className="space-y-4 max-w-4xl mx-auto">
      <PageHeader icon={Swords} title="전투 시뮬레이터" description="장수 간 전투를 시뮬레이션합니다." />

      {/* Global Config */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">전역 설정</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-1">
              <span className="text-xs text-muted-foreground">년</span>
              <Input type="number" value={year} onChange={(e) => setYear(Number(e.target.value))} min={1} className="w-20 h-7 text-xs tabular-nums" />
            </div>
            <div className="flex items-center gap-1">
              <span className="text-xs text-muted-foreground">월</span>
              <Input type="number" value={month} onChange={(e) => setMonth(Number(e.target.value))} min={1} max={12} className="w-16 h-7 text-xs tabular-nums" />
            </div>
            <div className="flex items-center gap-1">
              <span className="text-xs text-muted-foreground">시드</span>
              <Input value={seed} onChange={(e) => setSeed(e.target.value)} placeholder="랜덤" className="w-32 h-7 text-xs" />
            </div>
            <div className="flex items-center gap-1">
              <span className="text-xs text-muted-foreground">반복</span>
              <select
                value={repeatCount}
                onChange={(e) => setRepeatCount(Number(e.target.value) as 1 | 1000)}
                className="h-7 border border-gray-600 bg-[#111] px-1 text-xs text-white"
              >
                <option value={1}>1회 (로그)</option>
                <option value={1000}>1000회 (요약)</option>
              </select>
            </div>
          </div>
          {/* Nation Context Selector */}
          <div className="flex items-center gap-2">
            <span className="text-xs text-muted-foreground">국가 컨텍스트:</span>
            <select
              value={nationContext ?? ""}
              onChange={(e) => setNationContext(e.target.value ? Number(e.target.value) : null)}
              className="h-7 border border-gray-600 bg-[#111] px-1 text-xs text-white min-w-[120px]"
            >
              <option value="">없음</option>
              {nations.filter((n) => n.id > 0).map((n) => (
                <option key={n.id} value={n.id}>{n.name}</option>
              ))}
            </select>
            <span className="text-[10px] text-muted-foreground">국가 기술 등 컨텍스트 반영</span>
          </div>
          {/* Import / Export */}
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" className="h-7 text-xs" onClick={handleExportConfig}>
              <Download className="size-3 mr-1" />
              설정 내보내기
            </Button>
            <Button size="sm" variant="outline" className="h-7 text-xs" onClick={handleImportConfig}>
              <Upload className="size-3 mr-1" />
              설정 가져오기
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Attacker / Defenders */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <UnitBuilder
          title="공격측 장수"
          unit={attacker}
          onChange={setAttacker}
          generals={generals}
          myGeneralId={myGeneral?.id ?? null}
        />
        <div className="space-y-4">
          {defenders.map((def, idx) => (
            <div key={idx} className="relative">
              <UnitBuilder
                title={defenders.length > 1 ? `방어측 장수 ${idx + 1}` : "방어측 장수"}
                unit={def}
                onChange={(u) => setDefenders((prev) => prev.map((d, i) => i === idx ? u : d))}
                generals={generals}
                myGeneralId={myGeneral?.id ?? null}
              />
              {defenders.length > 1 && (
                <Button
                  size="sm"
                  variant="ghost"
                  className="absolute top-2 right-2 h-6 w-6 p-0 text-red-400"
                  onClick={() => removeDefender(idx)}
                >
                  <Minus className="size-3" />
                </Button>
              )}
            </div>
          ))}
          <Button size="sm" variant="outline" className="w-full" onClick={addDefender}>
            <Plus className="size-3 mr-1" />
            방어측 추가 (다중 방어자)
          </Button>

          {/* City Defense with Terrain/Weather */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm flex items-center gap-2">
                <Building className="size-4" />
                방어측 도시
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <div className="flex flex-wrap gap-2">
                <NumberField label="수비" value={cityDef.def} onChange={(v) => setCityDef({ ...cityDef, def: v })} min={0} step={10} />
                <NumberField label="성벽" value={cityDef.wall} onChange={(v) => setCityDef({ ...cityDef, wall: v })} min={0} step={10} />
                <div className="flex items-center gap-1">
                  <span className="text-xs text-muted-foreground w-10 text-right">규모</span>
                  <select
                    value={cityDef.level}
                    onChange={(e) => setCityDef({ ...cityDef, level: Number(e.target.value) })}
                    className="h-7 border border-gray-600 bg-[#111] px-1 text-xs text-white"
                  >
                    {[1, 2, 3, 4, 5, 6, 7].map((lv) => (
                      <option key={lv} value={lv}>Lv.{lv}</option>
                    ))}
                  </select>
                </div>
              </div>
              <div className="flex flex-wrap gap-2">
                <div className="flex items-center gap-1">
                  <Mountain className="size-3 text-muted-foreground" />
                  <span className="text-xs text-muted-foreground w-10 text-right">지형</span>
                  <select
                    value={cityDef.terrain}
                    onChange={(e) => setCityDef({ ...cityDef, terrain: Number(e.target.value) })}
                    className="h-7 border border-gray-600 bg-[#111] px-1 text-xs text-white"
                  >
                    {Object.entries(TERRAIN_LABELS).map(([k, v]) => (
                      <option key={k} value={k}>{v}</option>
                    ))}
                  </select>
                </div>
                <div className="flex items-center gap-1">
                  <CloudRain className="size-3 text-muted-foreground" />
                  <span className="text-xs text-muted-foreground w-10 text-right">날씨</span>
                  <select
                    value={cityDef.weather}
                    onChange={(e) => setCityDef({ ...cityDef, weather: Number(e.target.value) })}
                    className="h-7 border border-gray-600 bg-[#111] px-1 text-xs text-white"
                  >
                    {Object.entries(WEATHER_LABELS).map(([k, v]) => (
                      <option key={k} value={k}>{v}</option>
                    ))}
                  </select>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>

      {/* Action buttons */}
      <div className="flex gap-2">
        <Button onClick={handleSimulate} disabled={running}>
          <Play className="size-4" />
          {running ? "시뮬레이션 중..." : "전투 시작"}
        </Button>
        <Button variant="outline" onClick={handleReset}>
          <RotateCcw className="size-4" />
          초기화
        </Button>
        {result && (
          <Button variant="outline" onClick={handleDownload}>
            <Download className="size-4" />
            결과 저장
          </Button>
        )}
      </div>

      {/* Error */}
      {error && (
        <div className="text-sm text-destructive border border-red-900 bg-red-950/30 p-3 rounded">
          {error}
        </div>
      )}

      {/* Result */}
      {result && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm">
              전투 결과
              <Badge variant={result.winner === "attacker" ? "default" : "destructive"}>
                {result.winner === "attacker" ? "공격측 승리" : result.winner === "defender" ? "방어측 승리" : "무승부"}
              </Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="grid grid-cols-3 gap-4 text-sm">
              <div className="space-y-1">
                <div className="text-xs text-muted-foreground">공격측 잔여 병력</div>
                <div className="text-lg font-bold tabular-nums">{result.attackerRemaining.toLocaleString()}</div>
              </div>
              <div className="space-y-1">
                <div className="text-xs text-muted-foreground">방어측 잔여 병력</div>
                <div className="text-lg font-bold tabular-nums">{result.defenderRemaining.toLocaleString()}</div>
              </div>
              <div className="space-y-1">
                <div className="text-xs text-muted-foreground">전투 라운드</div>
                <div className="text-lg font-bold tabular-nums">{result.rounds}회</div>
              </div>
            </div>

            {/* 1000-Repeat Summary */}
            {result.repeatSummary && (
              <div className="space-y-2">
                <div className="text-xs text-muted-foreground font-medium">1000회 반복 요약</div>
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                  <div className="border border-gray-700 rounded p-2 text-center">
                    <div className="text-[10px] text-muted-foreground">공격측 승률</div>
                    <div className="text-lg font-bold text-blue-400 tabular-nums">
                      {(result.repeatSummary.attackerWinRate * 100).toFixed(1)}%
                    </div>
                    <div className="text-[10px] text-muted-foreground">{result.repeatSummary.attackerWins}승</div>
                  </div>
                  <div className="border border-gray-700 rounded p-2 text-center">
                    <div className="text-[10px] text-muted-foreground">방어측 승률</div>
                    <div className="text-lg font-bold text-red-400 tabular-nums">
                      {((1 - result.repeatSummary.attackerWinRate - (result.repeatSummary.draws / result.repeatSummary.totalRuns)) * 100).toFixed(1)}%
                    </div>
                    <div className="text-[10px] text-muted-foreground">{result.repeatSummary.defenderWins}승</div>
                  </div>
                  <div className="border border-gray-700 rounded p-2 text-center">
                    <div className="text-[10px] text-muted-foreground">무승부</div>
                    <div className="text-lg font-bold text-gray-400 tabular-nums">{result.repeatSummary.draws}</div>
                  </div>
                  <div className="border border-gray-700 rounded p-2 text-center">
                    <div className="text-[10px] text-muted-foreground">평균 라운드</div>
                    <div className="text-lg font-bold tabular-nums">{result.repeatSummary.avgRounds.toFixed(1)}</div>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div className="border border-gray-700 rounded p-2 text-center">
                    <div className="text-[10px] text-muted-foreground">공격측 평균 잔여 병력</div>
                    <div className="text-sm font-bold tabular-nums">{Math.round(result.repeatSummary.avgAttackerRemaining).toLocaleString()}</div>
                  </div>
                  <div className="border border-gray-700 rounded p-2 text-center">
                    <div className="text-[10px] text-muted-foreground">방어측 평균 잔여 병력</div>
                    <div className="text-sm font-bold tabular-nums">{Math.round(result.repeatSummary.avgDefenderRemaining).toLocaleString()}</div>
                  </div>
                </div>
                {/* Win rate bar */}
                <div className="h-4 rounded-full overflow-hidden flex bg-gray-800">
                  <div
                    className="bg-blue-500 h-full"
                    style={{ width: `${result.repeatSummary.attackerWinRate * 100}%` }}
                    title={`공격측 ${result.repeatSummary.attackerWins}승`}
                  />
                  <div
                    className="bg-gray-600 h-full"
                    style={{ width: `${(result.repeatSummary.draws / result.repeatSummary.totalRuns) * 100}%` }}
                    title={`무승부 ${result.repeatSummary.draws}`}
                  />
                  <div
                    className="bg-red-500 h-full flex-1"
                    title={`방어측 ${result.repeatSummary.defenderWins}승`}
                  />
                </div>
                <div className="flex justify-between text-[10px] text-muted-foreground">
                  <span>공격측 {result.repeatSummary.attackerWins}승</span>
                  <span>총 {result.repeatSummary.totalRuns}회</span>
                  <span>방어측 {result.repeatSummary.defenderWins}승</span>
                </div>
              </div>
            )}

            {/* Battle Log */}
            {result.logs && result.logs.length > 0 && (
              <div className="space-y-1">
                <div className="text-xs text-muted-foreground font-medium">전투 로그</div>
                <div className="max-h-80 overflow-y-auto border border-gray-800 bg-[#0a0a0a] p-2 rounded text-xs space-y-0.5">
                  {result.logs.map((log, i) => (
                    <div key={i} className="text-gray-300" dangerouslySetInnerHTML={{ __html: log }} />
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
