"use client";

import type { CityFrontInfo } from "@/types";
import { SammoBar } from "@/components/game/sammo-bar";
import {
  isBrightColor,
  getNPCColor,
  REGION_NAMES,
  CITY_LEVEL_NAMES,
} from "@/lib/game-utils";

interface CityBasicCardProps {
  city: CityFrontInfo | null;
  region?: number;
}

export function CityBasicCard({ city, region }: CityBasicCardProps) {
  if (!city) return null;

  const nationColor = city.nationInfo.color ?? "#000000";
  const textColor = isBrightColor(nationColor) ? "black" : "white";
  const regionText = REGION_NAMES[region ?? 0] ?? "";
  const levelText = CITY_LEVEL_NAMES[city.level] ?? `Lv.${city.level}`;

  const tradeAltText = city.trade ? `${city.trade}%` : "상인 없음";
  const tradeBarPercent = city.trade ? (city.trade - 95) * 10 : 0;

  return (
    <div
      className="legacy-bg2 border-r border-b border-gray-600 text-sm"
      style={{
        display: "grid",
        gridTemplateColumns: "1fr 1fr 1fr 1fr",
      }}
    >
      {/* City name header */}
      <div
        className="border-t border-l border-gray-600 font-bold text-center"
        style={{
          gridColumn: "1 / 5",
          color: textColor,
          backgroundColor: nationColor,
          lineHeight: "1.8em",
        }}
      >
        【{regionText} | {levelText}】 {city.name}
      </div>

      {/* Nation name */}
      <div
        className="border-t border-l border-gray-600 font-bold text-center"
        style={{
          gridColumn: "1 / 5",
          color: textColor,
          backgroundColor: nationColor,
          lineHeight: "1.8em",
        }}
      >
        {city.nationInfo.id
          ? `지배 국가 【 ${city.nationInfo.name} 】`
          : "공 백 지"}
      </div>

      {/* Row 3: 주민 (spans 2 cols, 1fr 5fr head) + 태수 in col 4 */}
      <StatPanel label="주민" colSpan="1 / 3" headRatio="1fr 5fr">
        <SammoBar height={7} percent={(city.pop[0] / city.pop[1]) * 100} />
        <CellText>
          {city.pop[0].toLocaleString()} / {city.pop[1].toLocaleString()}
        </CellText>
      </StatPanel>
      <StatPanel label="민심" colSpan="3 / 4">
        <SammoBar height={7} percent={city.trust} />
        <CellText>
          {city.trust.toLocaleString(undefined, { maximumFractionDigits: 1 })}
        </CellText>
      </StatPanel>
      <OfficerCell
        label="태수"
        npc={city.officerList[4]?.npc ?? 0}
        name={city.officerList[4]?.name}
      />

      {/* Row 4: 농업 + 상업 + 군사 */}
      <StatPanel label="농업">
        <SammoBar height={7} percent={(city.agri[0] / city.agri[1]) * 100} />
        <CellText>
          {city.agri[0].toLocaleString()} / {city.agri[1].toLocaleString()}
        </CellText>
      </StatPanel>
      <StatPanel label="상업">
        <SammoBar height={7} percent={(city.comm[0] / city.comm[1]) * 100} />
        <CellText>
          {city.comm[0].toLocaleString()} / {city.comm[1].toLocaleString()}
        </CellText>
      </StatPanel>
      <StatPanel label="치안">
        <SammoBar height={7} percent={(city.secu[0] / city.secu[1]) * 100} />
        <CellText>
          {city.secu[0].toLocaleString()} / {city.secu[1].toLocaleString()}
        </CellText>
      </StatPanel>
      <OfficerCell
        label="군사"
        npc={city.officerList[3]?.npc ?? 0}
        name={city.officerList[3]?.name}
      />

      {/* Row 5: 수비 + 성벽 + 시세 + 종사 */}
      <StatPanel label="수비">
        <SammoBar height={7} percent={(city.def[0] / city.def[1]) * 100} />
        <CellText>
          {city.def[0].toLocaleString()} / {city.def[1].toLocaleString()}
        </CellText>
      </StatPanel>
      <StatPanel label="성벽">
        <SammoBar height={7} percent={(city.wall[0] / city.wall[1]) * 100} />
        <CellText>
          {city.wall[0].toLocaleString()} / {city.wall[1].toLocaleString()}
        </CellText>
      </StatPanel>
      <StatPanel label="시세">
        <SammoBar height={7} percent={tradeBarPercent} altText={tradeAltText} />
        <CellText>{tradeAltText}</CellText>
      </StatPanel>
      <OfficerCell
        label="종사"
        npc={city.officerList[2]?.npc ?? 0}
        name={city.officerList[2]?.name}
      />
    </div>
  );
}

function StatPanel({
  label,
  colSpan,
  headRatio,
  children,
}: {
  label: string;
  colSpan?: string;
  headRatio?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className="border-t border-l border-gray-600"
      style={{
        display: "grid",
        gridTemplateColumns: headRatio ?? "1fr 2fr",
        ...(colSpan ? { gridColumn: colSpan } : {}),
      }}
    >
      <div className="legacy-bg1 flex items-center justify-center">{label}</div>
      <div>{children}</div>
    </div>
  );
}

function OfficerCell({
  label,
  npc,
  name,
}: {
  label: string;
  npc: number;
  name?: string;
}) {
  return (
    <div
      className="border-t border-l border-gray-600"
      style={{ display: "grid", gridTemplateColumns: "1fr 2fr" }}
    >
      <div className="legacy-bg1 flex items-center justify-center">{label}</div>
      <div
        className="flex items-center justify-center"
        style={{ color: getNPCColor(npc) }}
      >
        {name ?? "-"}
      </div>
    </div>
  );
}

function CellText({ children }: { children: React.ReactNode }) {
  return (
    <div className="text-center" style={{ lineHeight: "1.2em" }}>
      {children}
    </div>
  );
}
