"use client";

import type { GeneralFrontInfo, NationFrontInfo } from "@/types";
import { SammoBar } from "@/components/game/sammo-bar";
import {
  isBrightColor,
  calcInjury,
  formatInjury,
  formatGeneralTypeCall,
  formatRefreshScore,
  nextExpLevelRemain,
  ageColor,
  isValidObjKey,
} from "@/lib/game-utils";
import { getPortraitUrl, getCrewTypeIconUrl } from "@/lib/image";

interface GeneralBasicCardProps {
  general: GeneralFrontInfo | null;
  nation: NationFrontInfo | null;
  turnTerm?: number;
  lastExecuted?: string | null;
}

export function GeneralBasicCard({
  general,
  nation,
  turnTerm,
  lastExecuted,
}: GeneralBasicCardProps) {
  if (!general) return null;

  const injuryInfo = formatInjury(general.injury);
  const typeCall = formatGeneralTypeCall(
    general.leadership,
    general.strength,
    general.intel,
  );
  const nationColor = nation?.color ?? "#000000";
  const nationTextColor = isBrightColor(nationColor) ? "#000" : "#fff";

  const leadershipEff = calcInjury(general.leadership, general.injury);
  const strengthEff = calcInjury(general.strength, general.injury);
  const intelEff = calcInjury(general.intel, general.injury);

  const [expCur, expMax] = nextExpLevelRemain(
    general.experience,
    general.explevel,
  );
  const expPercent = expMax > 0 ? (expCur / expMax) * 100 : 0;

  // Stat exp bar (upgradeLimit default 100)
  const statUpThreshold = 100;

  // Next execute time
  let nextExecText = "-";
  if (lastExecuted && turnTerm) {
    const turnTime = new Date(general.turntime).getTime();
    const lastExecTime = new Date(lastExecuted).getTime();
    let effective = turnTime;
    if (effective < lastExecTime) {
      effective = effective + turnTerm * 60000;
    }
    const minutes = Math.max(
      0,
      Math.min(999, Math.floor((effective - lastExecTime) / 60000)),
    );
    nextExecText = `${minutes}분 남음`;
  }

  return (
    <div
      className="border border-gray-600 text-sm"
      style={{
        width: 500,
        maxWidth: "100%",
        display: "grid",
        gridTemplateColumns: "64px repeat(3, 2fr 5fr)",
        gridTemplateRows: "repeat(9, calc(64px / 3))",
        textAlign: "center",
      }}
    >
      {/* Portrait - spans 3 rows */}
      <div
        className="border-l border-t border-gray-600"
        style={{
          gridRow: "1 / 4",
          width: 64,
          height: 64,
          backgroundImage: `url('${getPortraitUrl(general.picture)}')`,
          backgroundSize: "contain",
          backgroundRepeat: "no-repeat",
        }}
      />

      {/* Name bar - spans columns 2-7 */}
      <div
        className="border-t border-gray-600 font-bold truncate px-1"
        style={{
          gridRow: "1 / 2",
          gridColumn: "2 / 8",
          color: nationTextColor,
          backgroundColor: nationColor,
          lineHeight: "calc(64px / 3)",
        }}
      >
        {general.name} 【
        {general.officerCity > 0 &&
          general.officerLevel >= 2 &&
          general.officerLevel <= 4 && <>{general.officerCity} </>}
        {general.officerLevelText} | {typeCall} |{" "}
        <span style={{ color: injuryInfo.color }}>{injuryInfo.text}</span>】{" "}
        {general.turntime.substring(11, 19)}
      </div>

      {/* Row 2: 통솔 */}
      <Cell head>통솔</Cell>
      <Cell>
        <div className="flex items-center gap-1">
          <span style={{ color: injuryInfo.color }}>{leadershipEff}</span>
          {general.lbonus > 0 && (
            <span style={{ color: "cyan" }}>+{general.lbonus}</span>
          )}
          <div className="flex-1">
            <SammoBar
              height={10}
              percent={(general.leadershipExp / statUpThreshold) * 100}
            />
          </div>
        </div>
      </Cell>

      {/* 무력 */}
      <Cell head>무력</Cell>
      <Cell>
        <div className="flex items-center gap-1">
          <span style={{ color: injuryInfo.color }}>{strengthEff}</span>
          <div className="flex-1">
            <SammoBar
              height={10}
              percent={(general.strengthExp / statUpThreshold) * 100}
            />
          </div>
        </div>
      </Cell>

      {/* 지력 */}
      <Cell head>지력</Cell>
      <Cell>
        <div className="flex items-center gap-1">
          <span style={{ color: injuryInfo.color }}>{intelEff}</span>
          <div className="flex-1">
            <SammoBar
              height={10}
              percent={(general.intelExp / statUpThreshold) * 100}
            />
          </div>
        </div>
      </Cell>

      {/* Row 3: 명마/무기/서적 */}
      <Cell head>명마</Cell>
      <Cell>{isValidObjKey(general.horse) ? general.horse : "-"}</Cell>
      <Cell head>무기</Cell>
      <Cell>{isValidObjKey(general.weapon) ? general.weapon : "-"}</Cell>
      <Cell head>서적</Cell>
      <Cell>{isValidObjKey(general.book) ? general.book : "-"}</Cell>

      {/* Row 4: 자금/군량/도구 */}
      <Cell head>자금</Cell>
      <Cell>{general.gold.toLocaleString()}</Cell>
      <Cell head>군량</Cell>
      <Cell>{general.rice.toLocaleString()}</Cell>
      <Cell head>도구</Cell>
      <Cell>{isValidObjKey(general.item) ? general.item : "-"}</Cell>

      {/* Crew type icon - spans 3 rows */}
      <div
        className="border-l border-t border-gray-600"
        style={{
          gridRow: "4 / 7",
          width: 64,
          height: 64,
          backgroundImage: `url('${getCrewTypeIconUrl(parseInt(general.crewtype.replace("che_", "")) || 0)}')`,
          backgroundSize: "contain",
          backgroundRepeat: "no-repeat",
        }}
      />

      {/* Row 5: 병종/병사/성격 */}
      <Cell head>병종</Cell>
      <Cell>{general.crewtype}</Cell>
      <Cell head>병사</Cell>
      <Cell>{general.crew.toLocaleString()}</Cell>
      <Cell head>성격</Cell>
      <Cell>{isValidObjKey(general.personal) ? general.personal : "-"}</Cell>

      {/* Row 6: 훈련/사기/특기 */}
      <Cell head>훈련</Cell>
      <Cell>{general.train}</Cell>
      <Cell head>사기</Cell>
      <Cell>{general.atmos}</Cell>
      <Cell head>특기</Cell>
      <Cell>
        {isValidObjKey(general.specialDomestic)
          ? general.specialDomestic
          : `${Math.max(general.age + 1, general.specage)}세`}
        {" / "}
        {isValidObjKey(general.specialWar)
          ? general.specialWar
          : `${Math.max(general.age + 1, general.specage2)}세`}
      </Cell>

      {/* Row 7: Lv + exp bar + 연령 */}
      <Cell head>Lv</Cell>
      <Cell>{general.explevel}</Cell>
      <div
        className="border-t border-gray-600 flex items-center px-1"
        style={{ gridColumn: "3 / 6" }}
      >
        <SammoBar height={10} percent={expPercent} />
      </div>
      <Cell head>연령</Cell>
      <Cell>
        <span style={{ color: ageColor(general.age) }}>{general.age}세</span>
      </Cell>

      {/* Row 8: 수비 + 삭턴 + 실행 */}
      <Cell head>수비</Cell>
      <Cell wide={2}>
        {general.defenceTrain === 999 ? (
          <span style={{ color: "red" }}>수비 안함</span>
        ) : (
          <span style={{ color: "limegreen" }}>
            수비 함(훈사{general.defenceTrain})
          </span>
        )}
      </Cell>
      <Cell head>삭턴</Cell>
      <Cell>{general.killturn ?? "-"} 턴</Cell>
      <Cell head>실행</Cell>
      <Cell>{nextExecText}</Cell>

      {/* Row 9: 부대 + 벌점 */}
      <Cell head>부대</Cell>
      <Cell wide={2}>{general.troopInfo ? general.troopInfo.name : "-"}</Cell>
      <Cell head>벌점</Cell>
      <Cell wide={3}>
        {formatRefreshScore(general.refreshScoreTotal ?? 0)}{" "}
        {(general.refreshScoreTotal ?? 0).toLocaleString()}점(
        {general.refreshScore ?? 0})
      </Cell>
    </div>
  );
}

function Cell({
  head,
  wide,
  children,
}: {
  head?: boolean;
  wide?: number;
  children?: React.ReactNode;
}) {
  return (
    <div
      className={`border-t border-gray-600 ${head ? "border-l legacy-bg1" : ""}`}
      style={wide ? { gridColumn: `span ${wide}` } : undefined}
    >
      {children}
    </div>
  );
}
