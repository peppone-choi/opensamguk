"use client";

import type { GeneralFrontInfo, NationFrontInfo } from "@/types";
import {
  isBrightColor,
  calcInjury,
  formatInjury,
  formatGeneralTypeCall,
  formatRefreshScore,
  ageColor,
  isValidObjKey,
} from "@/lib/game-utils";
import { getPortraitUrl } from "@/lib/image";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";

interface GeneralLiteCardProps {
  general: GeneralFrontInfo;
  nation: NationFrontInfo;
  gameConst?: {
    retirementYear: number;
    personalityList?: Record<string, { name: string; info?: string }>;
    specialDomesticList?: Record<string, { name: string; info?: string }>;
    specialWarList?: Record<string, { name: string; info?: string }>;
  };
}

/**
 * Lightweight general card showing portrait, name, stats, resources, and traits.
 * Ported from legacy GeneralLiteCard.vue.
 */
export function GeneralLiteCard({
  general,
  nation,
  gameConst,
}: GeneralLiteCardProps) {
  const iconPath = getPortraitUrl(general.picture);
  const { text: injuryText, color: injuryColor } = formatInjury(general.injury);
  const typeCall = formatGeneralTypeCall(
    general.leadership,
    general.strength,
    general.intel,
  );
  const retYear = gameConst?.retirementYear ?? 80;
  const ageFontColor = ageColor(general.age, retYear);

  const getActionInfo = (
    key: string,
    list?: Record<string, { name: string; info?: string }>,
  ) => {
    if (!key || !list || !list[key]) return { name: "-", info: "" };
    return list[key];
  };

  const personal = getActionInfo(
    general.personal,
    gameConst?.personalityList,
  );
  const specialDomestic = getActionInfo(
    general.specialDomestic,
    gameConst?.specialDomesticList,
  );
  const specialWar = getActionInfo(
    general.specialWar,
    gameConst?.specialWarList,
  );

  const refreshScoreText = formatRefreshScore(general.refreshScoreTotal ?? 0);

  const nameStyle = {
    color: nation.color && isBrightColor(nation.color) ? "#000" : "#fff",
    backgroundColor: nation.color || "#333",
  };

  return (
    <div className="grid text-center text-sm border border-border bg-muted/30"
      style={{
        gridTemplateColumns: "64px repeat(3, 2fr 5fr)",
        gridTemplateRows: "repeat(5, calc(64px / 3))",
      }}
    >
      {/* Portrait */}
      <div
        className="row-span-3 bg-contain bg-no-repeat bg-center"
        style={{
          width: 64,
          height: 64,
          backgroundImage: `url('${iconPath}')`,
        }}
      />

      {/* Name row */}
      <div
        className="col-span-6 font-bold py-0.5"
        style={nameStyle}
      >
        {general.name} 【{general.officerLevelText} | {typeCall} |{" "}
        <span style={{ color: injuryColor }}>{injuryText}</span>】
      </div>

      {/* Row 2: stats */}
      <div className="bg-muted/50 border-b border-border">통솔</div>
      <div className="border-b border-border">
        <span style={{ color: injuryColor }}>
          {calcInjury(general.leadership, general.injury)}
        </span>
        {general.lbonus > 0 && (
          <span className="text-cyan-400">+{general.lbonus}</span>
        )}
      </div>
      <div className="bg-muted/50 border-b border-border">무력</div>
      <div className="border-b border-border" style={{ color: injuryColor }}>
        {calcInjury(general.strength, general.injury)}
      </div>
      <div className="bg-muted/50 border-b border-border">지력</div>
      <div className="border-b border-border" style={{ color: injuryColor }}>
        {calcInjury(general.intel, general.injury)}
      </div>

      {/* Row 3: resources */}
      <div className="bg-muted/50 border-b border-border">자금</div>
      <div className="border-b border-border">
        {general.gold.toLocaleString()}
      </div>
      <div className="bg-muted/50 border-b border-border">군량</div>
      <div className="border-b border-border">
        {general.rice.toLocaleString()}
      </div>
      <div className="bg-muted/50 border-b border-border">성격</div>
      <div className="border-b border-border">
        <MaybeTooltip text={personal.name} info={personal.info} />
      </div>

      {/* Row 4: filler + level, age, specials */}
      <div className="bg-muted/50 border-l border-b border-border" />
      <div className="bg-muted/50 border-b border-border">Lv</div>
      <div className="border-b border-border">{general.explevel}</div>
      <div className="bg-muted/50 border-b border-border">연령</div>
      <div className="border-b border-border" style={{ color: ageFontColor }}>
        {general.age}세
      </div>
      <div className="bg-muted/50 border-b border-border">특기</div>
      <div className="border-b border-border">
        <MaybeTooltip text={specialDomestic.name} info={specialDomestic.info} />
        {" / "}
        <MaybeTooltip text={specialWar.name} info={specialWar.info} />
      </div>

      {/* Row 5: filler + killturn, penalty */}
      <div className="bg-muted/50 border-l border-b border-border" />
      <div className="bg-muted/50 border-b border-border">삭턴</div>
      <div className="border-b border-border">
        {general.killturn ?? "-"} 턴
      </div>
      <div className="bg-muted/50 border-b border-border">벌점</div>
      <div className="border-b border-border col-span-3">
        {refreshScoreText} {(general.refreshScoreTotal ?? 0).toLocaleString()}점
      </div>
    </div>
  );
}

function MaybeTooltip({
  text,
  info,
}: {
  text: string;
  info?: string;
}) {
  if (!info) return <span>{text}</span>;
  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <span className="cursor-help underline decoration-dotted">
            {text}
          </span>
        </TooltipTrigger>
        <TooltipContent>{info}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
