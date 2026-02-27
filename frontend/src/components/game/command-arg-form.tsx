"use client";

import { useState, useMemo } from "react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useGameStore } from "@/stores/gameStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useWorldStore } from "@/stores/worldStore";
import type { City, General, Nation } from "@/types";
import { CrewTypeBrowser } from "./crew-type-browser";

/** Arg schema for each command that requires user input */
type ArgField =
  | { type: "city"; key: string; label: string }
  | { type: "nation"; key: string; label: string }
  | { type: "general"; key: string; label: string }
  | { type: "number"; key: string; label: string; placeholder?: string }
  | { type: "boolean"; key: string; label: string }
  | { type: "text"; key: string; label: string; placeholder?: string }
  | {
      type: "select";
      key: string;
      label: string;
      options: { value: string; label: string }[];
    };

const CREW_TYPE_OPTIONS = [
  { value: "0", label: "보병" },
  { value: "1", label: "궁병" },
  { value: "2", label: "기병" },
  { value: "3", label: "수군" },
];

const ITEM_TYPE_OPTIONS = [
  { value: "weapon", label: "무기" },
  { value: "book", label: "서적" },
  { value: "horse", label: "군마" },
  { value: "item", label: "도구" },
];

const NATION_TYPE_OPTIONS = [
  { value: "군벌", label: "군벌" },
  { value: "문치", label: "문치" },
  { value: "무치", label: "무치" },
  { value: "상업", label: "상업" },
  { value: "농업", label: "농업" },
];

const COLOR_TYPE_OPTIONS = [
  { value: "0", label: "기본" },
  { value: "1", label: "적색" },
  { value: "2", label: "청색" },
  { value: "3", label: "녹색" },
  { value: "4", label: "황색" },
  { value: "5", label: "자색" },
  { value: "6", label: "백색" },
  { value: "7", label: "흑색" },
];

const FLAG_COLOR_OPTIONS = [
  { value: "red", label: "적색" },
  { value: "blue", label: "청색" },
  { value: "green", label: "녹색" },
  { value: "yellow", label: "황색" },
  { value: "purple", label: "자색" },
  { value: "black", label: "흑색" },
  { value: "white", label: "백색" },
];

const NPC_OPTION_OPTIONS = [{ value: "순간이동", label: "순간이동" }];

const PIJANG_OPTION_OPTIONS = [
  { value: "전략", label: "전략" },
  { value: "급습", label: "급습" },
  { value: "수몰", label: "수몰" },
  { value: "초토화", label: "초토화" },
  { value: "허보", label: "허보" },
  { value: "의병모집", label: "의병모집" },
];

const COMMAND_ARGS: Record<string, ArgField[]> = {
  // Military - recruitment
  모병: [
    {
      type: "select",
      key: "crewType",
      label: "병종",
      options: CREW_TYPE_OPTIONS,
    },
    { type: "number", key: "amount", label: "수량", placeholder: "최대" },
  ],
  징병: [
    {
      type: "select",
      key: "crewType",
      label: "병종",
      options: CREW_TYPE_OPTIONS,
    },
    { type: "number", key: "amount", label: "수량", placeholder: "최대" },
  ],
  // Movement
  출병: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  이동: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  강행: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  // Espionage & tactics
  첩보: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  화계: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  탈취: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  파괴: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  선동: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  // Personnel
  등용: [{ type: "general", key: "destGeneralID", label: "대상 장수" }],
  장수대상임관: [{ type: "general", key: "destGeneralID", label: "대상 장수" }],
  // Economy
  증여: [
    { type: "general", key: "destGeneralID", label: "대상 장수" },
    { type: "boolean", key: "isGold", label: "금화 여부 (off=쌀)" },
    { type: "number", key: "amount", label: "수량" },
  ],
  헌납: [
    { type: "boolean", key: "isGold", label: "금화 여부 (off=쌀)" },
    { type: "number", key: "amount", label: "수량" },
  ],
  군량매매: [
    {
      type: "boolean",
      key: "buyRice",
      label: "쌀 구매 여부 (off=쌀 판매)",
    },
    { type: "number", key: "amount", label: "매매 수량" },
  ],
  // Equipment
  장비매매: [
    {
      type: "select",
      key: "itemType",
      label: "장비 종류",
      options: ITEM_TYPE_OPTIONS,
    },
    {
      type: "text",
      key: "itemCode",
      label: "아이템 코드",
      placeholder: "예: S_sword",
    },
  ],
  건국: [
    {
      type: "text",
      key: "nationName",
      label: "국가명",
      placeholder: "신생국",
    },
    {
      type: "select",
      key: "nationType",
      label: "국가 성향",
      options: NATION_TYPE_OPTIONS,
    },
    {
      type: "select",
      key: "colorType",
      label: "국가 색상",
      options: COLOR_TYPE_OPTIONS,
    },
  ],
  CR건국: [
    {
      type: "text",
      key: "nationName",
      label: "국가명",
      placeholder: "신생국",
    },
    {
      type: "select",
      key: "nationType",
      label: "국가 성향",
      options: NATION_TYPE_OPTIONS,
    },
    {
      type: "select",
      key: "colorType",
      label: "국가 색상",
      options: COLOR_TYPE_OPTIONS,
    },
  ],
  무작위건국: [
    {
      type: "text",
      key: "nationName",
      label: "국가명",
      placeholder: "신생국",
    },
    {
      type: "select",
      key: "nationType",
      label: "국가 성향",
      options: NATION_TYPE_OPTIONS,
    },
    {
      type: "select",
      key: "colorType",
      label: "국가 색상",
      options: COLOR_TYPE_OPTIONS,
    },
  ],
  숙련전환: [
    {
      type: "select",
      key: "srcArmType",
      label: "감소 대상 숙련",
      options: CREW_TYPE_OPTIONS,
    },
    {
      type: "select",
      key: "destArmType",
      label: "전환 대상 숙련",
      options: CREW_TYPE_OPTIONS,
    },
  ],
  선양: [{ type: "general", key: "destGeneralID", label: "선양 대상 장수" }],
  임관: [{ type: "nation", key: "destNationId", label: "임관할 국가" }],
  랜덤임관: [],
  NPC능동: [
    {
      type: "select",
      key: "optionText",
      label: "NPC 동작",
      options: NPC_OPTION_OPTIONS,
    },
    { type: "city", key: "destCityID", label: "목표 도시" },
  ],
  // Nation commands
  포상: [
    { type: "general", key: "destGeneralID", label: "대상 장수" },
    { type: "boolean", key: "isGold", label: "금화 여부 (off=쌀)" },
    { type: "number", key: "amount", label: "수량" },
  ],
  몰수: [
    { type: "general", key: "destGeneralID", label: "대상 장수" },
    { type: "boolean", key: "isGold", label: "금화 여부 (off=쌀)" },
    { type: "number", key: "amount", label: "수량" },
  ],
  발령: [
    { type: "general", key: "destGeneralID", label: "대상 장수" },
    { type: "city", key: "cityId", label: "이동 도시" },
  ],
  천도: [{ type: "city", key: "destCityId", label: "새 수도" }],
  백성동원: [{ type: "city", key: "cityId", label: "대상 도시" }],
  수몰: [{ type: "city", key: "cityId", label: "대상 도시" }],
  초토화: [{ type: "city", key: "cityId", label: "대상 도시" }],
  허보: [{ type: "city", key: "cityId", label: "대상 도시" }],
  물자원조: [
    { type: "nation", key: "destNationId", label: "대상 국가" },
    { type: "number", key: "goldAmount", label: "지원 금" },
    { type: "number", key: "riceAmount", label: "지원 쌀" },
  ],
  국호변경: [
    {
      type: "text",
      key: "nationName",
      label: "새 국호",
      placeholder: "국가명",
    },
  ],
  국기변경: [
    {
      type: "select",
      key: "colorType",
      label: "국기 색상",
      options: FLAG_COLOR_OPTIONS,
    },
  ],
  // Diplomacy
  선전포고: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  종전제의: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  종전수락: [
    { type: "nation", key: "destNationId", label: "대상 국가" },
    { type: "general", key: "destGeneralID", label: "대상 장수" },
  ],
  불가침제의: [
    { type: "nation", key: "destNationId", label: "대상 국가" },
    { type: "number", key: "year", label: "유효 연도" },
    { type: "number", key: "month", label: "유효 월" },
  ],
  불가침수락: [
    { type: "nation", key: "destNationId", label: "대상 국가" },
    { type: "general", key: "destGeneralID", label: "대상 장수" },
    { type: "number", key: "year", label: "유효 연도" },
    { type: "number", key: "month", label: "유효 월" },
  ],
  불가침파기제의: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  불가침파기수락: [
    { type: "nation", key: "destNationId", label: "대상 국가" },
    { type: "general", key: "destGeneralID", label: "대상 장수" },
  ],
  // Strategic
  급습: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  이호경식: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  피장파장: [
    { type: "nation", key: "destNationId", label: "대상 국가" },
    {
      type: "select",
      key: "commandType",
      label: "대응 전략",
      options: PIJANG_OPTION_OPTIONS,
    },
  ],
  부대탈퇴지시: [{ type: "general", key: "destGeneralID", label: "대상 장수" }],
  인구이동: [
    { type: "city", key: "destCityId", label: "도착 도시" },
    { type: "number", key: "amount", label: "이동 인구" },
  ],
};

/** Contextual help text for commands, matching legacy processing pages */
const COMMAND_HELP: Record<string, string> = {
  // City-targeting commands
  강행: "선택된 도시로 강행합니다. 최대 3칸내 도시로만 강행이 가능합니다.",
  이동: "선택된 도시로 이동합니다. 인접 도시로만 이동이 가능합니다.",
  출병: "선택된 도시를 향해 침공을 합니다. 침공 경로에 적군의 도시가 있다면 전투를 벌입니다.",
  첩보: "선택된 도시에 첩보를 실행합니다. 인접도시일 경우 많은 정보를 얻을 수 있습니다.",
  화계: "선택된 도시에 화계를 실행합니다.",
  탈취: "선택된 도시에 탈취를 실행합니다.",
  파괴: "선택된 도시에 파괴를 실행합니다.",
  선동: "선택된 도시에 선동을 실행합니다.",
  수몰: "선택된 도시에 수몰을 발동합니다. 전쟁중인 상대국 도시만 가능합니다.",
  백성동원:
    "선택된 도시에 백성을 동원해 성벽을 쌓습니다. 아국 도시만 가능합니다.",
  천도: "선택된 도시로 천도합니다. 현재 수도에서 연결된 도시만 가능하며, 1+2×거리만큼의 턴이 필요합니다.",
  허보: "선택된 도시에 허보를 발동합니다. 선포, 전쟁중인 상대국 도시만 가능합니다.",
  초토화:
    "선택된 도시를 초토화 시킵니다. 도시가 공백지가 되며, 국고가 확보됩니다. 수뇌들은 명성을 잃고, 모든 장수들은 배신 수치가 1 증가합니다.",
  // General-targeting commands
  등용: "다른 세력 장수를 등용합니다.",
  장수대상임관: "특정 장수의 세력에 임관합니다.",
  증여: "자신의 자금이나 군량을 다른 장수에게 증여합니다.",
  포상: "국고로 장수에게 자금이나 군량을 지급합니다.",
  몰수: "장수의 자금이나 군량을 몰수합니다. 몰수한것은 국가재산으로 귀속됩니다.",
  부대탈퇴지시: "지정한 장수에게 부대 탈퇴를 지시합니다. 부대원만 가능합니다.",
  발령: "장수를 지정한 도시로 발령합니다.",
  헌납: "자신의 자금이나 군량을 국고에 헌납합니다.",
  // Economy
  모병: "병종을 선택하고 모병합니다. 비용은 자금에서 차감됩니다.",
  징병: "병종을 선택하고 징병합니다. 도시 인구가 감소합니다.",
  군량매매: "금과 쌀을 교환합니다.",
  장비매매: "무기, 서적, 군마, 도구를 구매합니다.",
  // Nation
  건국: "새 국가를 건국합니다.",
  CR건국: "새 국가를 건국합니다. (특수)",
  무작위건국: "무작위 위치에 건국합니다.",
  선전포고:
    "타국에게 선전 포고합니다. 고립되지 않은 아국 도시에서 인접한 국가에 선포 가능합니다.",
  종전제의: "전쟁중인 국가에 종전을 제의합니다.",
  불가침제의: "타국에 불가침 조약을 제의합니다.",
  불가침파기제의: "불가침중인 국가에 조약 파기를 제의합니다.",
  급습: "선택된 국가에 급습을 발동합니다. 선포, 전쟁중인 상대국에만 가능합니다.",
  이호경식:
    "선택된 국가에 이호경식을 발동합니다. 선포, 전쟁중인 상대국에만 가능합니다.",
  물자원조: "동맹국에 금과 쌀을 원조합니다.",
  국호변경: "국가명을 변경합니다.",
  국기변경: "국기 색상을 변경합니다.",
  피장파장: "선택된 국가에 대한 대응 전략을 설정합니다.",
  인구이동: "도시 간 인구를 이동시킵니다.",
  숙련전환: "병종 숙련도를 전환합니다.",
  선양: "장수에게 왕위를 선양합니다.",
  임관: "국가에 임관합니다.",
  NPC능동: "NPC를 수동 조작합니다.",
};

/** Commands that target cities (shown with distance sorting) */
const CITY_TARGET_COMMANDS = new Set([
  "출병",
  "이동",
  "강행",
  "첩보",
  "화계",
  "탈취",
  "파괴",
  "선동",
  "수몰",
  "백성동원",
  "천도",
  "허보",
  "초토화",
  "NPC능동",
  "발령",
  "인구이동",
]);

interface CommandArgFormProps {
  actionCode: string;
  onSubmit: (arg: Record<string, unknown>) => void;
}

export function CommandArgForm({ actionCode, onSubmit }: CommandArgFormProps) {
  const { cities, nations, generals } = useGameStore();
  const { myGeneral } = useGeneralStore();
  const [valuesByCommand, setValuesByCommand] = useState<
    Record<string, Record<string, string>>
  >({});

  const fields = COMMAND_ARGS[actionCode];
  const values = valuesByCommand[actionCode] ?? {};
  const helpText = COMMAND_HELP[actionCode];

  // Sort cities by nation ownership for better UX
  const sortedCities = useMemo(() => {
    if (!myGeneral) return cities;
    const myCities: City[] = [];
    const otherCities: City[] = [];
    for (const c of cities) {
      if (c.nationId === myGeneral.nationId) myCities.push(c);
      else otherCities.push(c);
    }
    return [...myCities, ...otherCities];
  }, [cities, myGeneral]);

  if (!fields) {
    // No args needed - auto-submit
    return null;
  }

  // Use rich crew type browser for recruitment commands
  if (actionCode === "징병" || actionCode === "모병") {
    return (
      <CrewTypeBrowser
        commandName={actionCode}
        onSubmit={(crewTypeCode, amount) => {
          onSubmit({ crewType: crewTypeCode, amount });
        }}
      />
    );
  }

  const setValue = (key: string, val: string) => {
    setValuesByCommand((prev) => ({
      ...prev,
      [actionCode]: {
        ...(prev[actionCode] ?? {}),
        [key]: val,
      },
    }));
  };

  const handleSubmit = () => {
    const arg: Record<string, unknown> = {};
    for (const field of fields) {
      const raw = values[field.key];
      if (!raw && raw !== "0") continue;
      if (
        field.type === "number" ||
        field.type === "city" ||
        field.type === "nation" ||
        field.type === "general"
      ) {
        arg[field.key] = Number(raw);
      } else if (field.type === "boolean") {
        arg[field.key] = raw === "true";
      } else if (field.type === "select" && !isNaN(Number(raw))) {
        arg[field.key] = Number(raw);
      } else {
        arg[field.key] = raw;
      }
    }
    onSubmit(arg);
  };

  // Filter cities to own nation for some commands
  const myCities = myGeneral
    ? cities.filter((c) => c.nationId === myGeneral.nationId)
    : cities;

  const renderField = (field: ArgField) => {
    switch (field.type) {
      case "city": {
        const isCityTarget = CITY_TARGET_COMMANDS.has(actionCode);
        const showAllCities =
          field.key === "destCityId" ||
          field.key === "destCityID" ||
          isCityTarget;
        const list: City[] = showAllCities ? sortedCities : myCities;
        return (
          <select
            key={field.key}
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            className="w-full bg-background border border-input rounded-md px-2 py-1.5 text-xs"
          >
            <option value="">{field.label}...</option>
            {list.map((c) => {
              const nation = nations.find((n) => n.id === c.nationId);
              const nationTag = nation
                ? ` [${nation.name}]`
                : c.nationId === 0
                  ? " [공백]"
                  : "";
              const isMyCity = myGeneral && c.nationId === myGeneral.nationId;
              return (
                <option
                  key={c.id}
                  value={c.id}
                  style={isMyCity ? { fontWeight: "bold" } : undefined}
                >
                  {c.name} (Lv.{c.level}){nationTag}
                </option>
              );
            })}
          </select>
        );
      }
      case "nation": {
        const list: Nation[] = nations.filter(
          (n) => !myGeneral || n.id !== myGeneral.nationId,
        );
        return (
          <select
            key={field.key}
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            className="w-full bg-background border border-input rounded-md px-2 py-1.5 text-xs"
          >
            <option value="">{field.label}...</option>
            {list.map((n) => (
              <option key={n.id} value={n.id}>
                {n.name}
              </option>
            ))}
          </select>
        );
      }
      case "general": {
        const list: General[] = generals.filter(
          (g) => !myGeneral || g.id !== myGeneral.id,
        );
        return (
          <select
            key={field.key}
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            className="w-full bg-background border border-input rounded-md px-2 py-1.5 text-xs"
          >
            <option value="">{field.label}...</option>
            {list.map((g) => {
              const nation = nations.find((n) => n.id === g.nationId);
              const nationTag = nation
                ? ` [${nation.name}]`
                : g.nationId === 0
                  ? " [재야]"
                  : "";
              const city = cities.find((c) => c.id === g.cityId);
              const cityTag = city ? ` ${city.name}` : "";
              const stats = `${g.leadership ?? "?"}/${g.strength ?? "?"}/${g.intel ?? "?"}`;
              return (
                <option key={g.id} value={g.id}>
                  {g.name}
                  {nationTag}
                  {cityTag} ({stats})
                </option>
              );
            })}
          </select>
        );
      }
      case "select":
        return (
          <select
            key={field.key}
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            className="w-full bg-background border border-input rounded-md px-2 py-1.5 text-xs"
          >
            <option value="">{field.label}...</option>
            {field.options.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        );
      case "boolean":
        return (
          <label
            key={field.key}
            className="flex items-center gap-2 text-xs text-muted-foreground"
          >
            <input
              type="checkbox"
              checked={values[field.key] === "true"}
              onChange={(e) =>
                setValue(field.key, e.target.checked ? "true" : "false")
              }
              className="accent-amber-400"
            />
            {field.label}
          </label>
        );
      case "number":
        return (
          <Input
            key={field.key}
            type="number"
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            placeholder={field.placeholder ?? field.label}
            className="text-xs h-8"
          />
        );
      case "text":
        return (
          <Input
            key={field.key}
            type="text"
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            placeholder={field.placeholder ?? field.label}
            className="text-xs h-8"
          />
        );
    }
  };

  return (
    <div className="space-y-2">
      {helpText && (
        <div className="rounded-md bg-amber-900/20 border border-amber-800/40 px-3 py-2 text-xs text-amber-200/90">
          {helpText}
        </div>
      )}
      <p className="text-xs text-muted-foreground">명령 인자</p>
      {fields.map((field) => (
        <div key={field.key} className="space-y-0.5">
          <label className="text-[10px] text-muted-foreground font-medium">
            {field.label}
          </label>
          {renderField(field)}
        </div>
      ))}
      <Button size="sm" onClick={handleSubmit} className="w-full">
        확인
      </Button>
    </div>
  );
}

export { COMMAND_ARGS };
