// 같은 郡 안에서 한글 표시명이 겹치는 縣에 붙이는 작은 漢字 병기(이슈 #838).
//
// 영천군 안에 「양성현」이 둘이다 — 陽城(철 산지)과 襄城. 독음은 둘 다 맞아서 표시명은 바꾸지 않고
// (2026-09-18 결정), 화면에서만 「양성현 陽城」처럼 작고 흐린 글자를 뒤에 단다. 대상은 생성기
// tools/map/build_county_display_name_collisions.py 가 han-tiles 전수에서 뽑은 목록뿐이다 —
// 그 밖의 縣에는 병기가 붙지 않는다(郡이 다르면 「뭐뭐군 뭐뭐현」에서 이미 갈린다).
import type { ReactNode } from 'react';
import {
  COUNTY_GLOSS_BY_JURISDICTION_ID,
  COUNTY_GLOSS_BY_SIMPLIFIED_STEM,
} from './countyNameGloss.generated';

/** 목록에 오른 관할이면 繁體 병기(「陽城」), 아니면 undefined. */
export function countyGlossForJurisdiction(jurisdictionId: string | null | undefined): string | undefined {
  if (jurisdictionId == null) return undefined;
  return Object.prototype.hasOwnProperty.call(COUNTY_GLOSS_BY_JURISDICTION_ID, jurisdictionId)
    ? COUNTY_GLOSS_BY_JURISDICTION_ID[jurisdictionId]
    : undefined;
}

// 서버 표시명(han-world-v3 meta.displayName)은 같은 충돌을 「영천군 양성현(阳城)」처럼 簡體 꼬리로 가른다.
const TRAILING_HAN_QUALIFIER = /\(([^()]+)\)$/;

/**
 * 화면 이름을 본문과 병기로 나눈다. 꼬리 「(阳城)」가 목록의 簡體 어간이면 떼어 繁體 병기로 돌려준다.
 * 목록 밖 꼬리(「와구(渦口)」 같은 거점)는 건드리지 않는다.
 */
export function splitCountyGloss(displayName: string): { name: string; gloss?: string } {
  const match = TRAILING_HAN_QUALIFIER.exec(displayName);
  if (!match) return { name: displayName };
  const stem = match[1];
  if (!Object.prototype.hasOwnProperty.call(COUNTY_GLOSS_BY_SIMPLIFIED_STEM, stem)) return { name: displayName };
  return { name: displayName.slice(0, match.index), gloss: COUNTY_GLOSS_BY_SIMPLIFIED_STEM[stem] };
}

export interface PlaceNameWithGlossProps {
  name: ReactNode;
  gloss?: string;
}

/** 이름 뒤에 작고 흐린 漢字 병기. 병기가 없으면 이름만 낸다. 스타일: tokens.css `.os-place-gloss`. */
export function PlaceNameWithGloss({ name, gloss }: PlaceNameWithGlossProps) {
  if (!gloss) return <>{name}</>;
  return (
    <>
      {name}
      <span className="os-place-gloss" lang="zh-Hant">{gloss}</span>
    </>
  );
}
