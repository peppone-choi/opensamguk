"use client";

import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { useGameStore } from "@/stores/gameStore";
import { useGeneralStore } from "@/stores/generalStore";
import type { City, General, Nation } from "@/types";

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
  // Espionage
  첩보: [{ type: "city", key: "destCityId", label: "목표 도시" }],
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

  if (!fields) {
    // No args needed - auto-submit
    return null;
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
        const list: City[] = field.key === "destCityId" ? cities : myCities;
        return (
          <select
            key={field.key}
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            className="w-full bg-background border border-input rounded-md px-2 py-1.5 text-xs"
          >
            <option value="">{field.label}...</option>
            {list.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} (Lv.{c.level})
              </option>
            ))}
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
            {list.map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
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
      <p className="text-xs text-muted-foreground">명령 인자</p>
      {fields.map(renderField)}
      <Button size="sm" onClick={handleSubmit} className="w-full">
        확인
      </Button>
    </div>
  );
}

export { COMMAND_ARGS };
