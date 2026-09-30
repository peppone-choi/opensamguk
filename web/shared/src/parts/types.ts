/**
 * v3.1 공용 부품의 계약(ADR-LITE-049 2026-09-30 「v3.1 전체 승인」, 보드 `V31SystemParts` · `MParts` · `States` ·
 * `MapPick` · `People` · `TimeBar`). 화면 레인(K4–K8)은 이 타입으로 코딩한다.
 *
 * 규칙(v31system RULES): 누를 영역 44 · 호버 · title 전용 정보 금지 · 비활성은 aria-disabled(네이티브 disabled 금지) ·
 * 단추 안 단추 금지 · 입력 상태는 서버가 준 값으로만 · 빈 ≠ 실패 · 설계에서 안 정한 수치는 [미정].
 */
import type { ReactNode } from 'react';

// ---------------------------------------------------------------- 입력 4상태(InputAction)

/** 계약판 K6-01 `GET /api/inputs/availability` 의 `status`. 원장 PLANNED 는 서버가 NOT_DELIVERED 로 준다. */
export type InputStatus = 'AVAILABLE' | 'BLOCKED' | 'NOT_DELIVERED';

/** 계약판 K6-01 한 행 그대로. 행이 없으면(원장에 없는 입력) 화면은 그 조작을 그리지 않는다 — `null` 로 넘긴다. */
export interface InputAvailability {
  readonly inputId: string;
  readonly status: InputStatus;
  /** 원장 failureReasons 코드(K7-08). 화면 글자로 쓰지 않고 `data-reason-code` 와 도움말 찾기에만 쓴다. */
  readonly code?: string;
  /** 서버가 준 사유 문장. BLOCKED 에서 보이는 꼬리표와 사유 시트 본문이 된다. */
  readonly reason?: string;
}

// ---------------------------------------------------------------- 사유 시트(ReasonSheet = 보드의 ReasonTooltip)

/** 도움말 서랍으로 가는 고리(K7). `id` 는 `?help=<id>` 로 연다. */
export interface HelpTopicRef {
  readonly id: string;
  /** 「도움말 — {title} →」 */
  readonly title: string;
}

/** 사유 시트에 담는 것. 데스크톱은 말풍선(폭 320), 모바일(< 768)은 하단 시트. 누르면 열리고 호버는 미리 보기뿐이다. */
export interface ReasonContent {
  /** 본문. 서버 reason 그대로. */
  readonly reason: string;
  /** 머리 한 줄(예: 「발령은 주공만 할 수 있습니다.」). 없으면 reason 만 보인다. */
  readonly title?: string;
  readonly code?: string;
  readonly inputId?: string;
  /** 「이렇게 하면 됩니다」 칸. K7 `useReasonHelp(code, inputId)` 가 채운다. */
  readonly recovery?: string;
  /** recovery 가 승인 전 초안이면 참 — 「초안」 칩을 붙인다(K7 recoveryDraft). */
  readonly recoveryDraft?: boolean;
  readonly helpTopic?: HelpTopicRef;
}

// ---------------------------------------------------------------- 상태(StatusView, P-X01)

/**
 * 영역(또는 화면 전체)을 대신하는 상태.
 * - `loading` 0.3초 넘을 때만 뼈대 · `empty` 무엇이 없는지 + 채우는 법 · `error` 다시 시도 + 오류 번호(빈 것과 다른 모양)
 * - `denied` 이유 + 이렇게 하면 됩니다 + 도움말 · `waiting` 서버 대기 A(읽기 없음, 영역 전체)
 * - `stale` 연결 끊김(마지막 자료 시각 + 다시 잇기) · `not-found` 없는 화면 · `maintenance` 점검
 * 서버 대기 B(읽기는 있고 입력만 없음)는 StatusView 가 아니다 — 내용을 그대로 두고 InputAction 이 NOT_DELIVERED 로 그린다.
 */
export type StatusKind = 'loading' | 'empty' | 'error' | 'denied' | 'waiting' | 'stale' | 'not-found' | 'maintenance';

// ---------------------------------------------------------------- 지도 대상 고르기(MapTargetPicker)

/**
 * 고르는 대상 종류(보드 MapPick · MapModes).
 * - `place` 가는 곳 한 칸 — 성 · 현 · 구역 목적지(이동 · 출병 · 수송 …)
 * - `jurisdiction` 관할 경계 — 주 · 군국(지방 관직 임명, 군 첩보 …)
 * - `corps` 군단 · `multi-county` 여러 현(고른 순서 번호)
 */
export type TargetKind = 'place' | 'jurisdiction' | 'corps' | 'multi-county';

/**
 * 후보 하나 — 계약판 U-01 옵션 항목 그대로(`{targetKind, targetId, provinceId, cityId?, cell, available, reasonCode, reason,
 * distanceCells, distanceTurns}`)에 화면 이름을 더한 것. 가능 · 불가를 같이 보이고, 지도 표지와 목록이 같은 상태 · 같은 사유를 쓴다.
 */
export interface TargetCandidate {
  readonly targetKind: TargetKind;
  readonly targetId: string;
  readonly provinceId?: string;
  readonly cityId?: string;
  /** 지도 칸. 없으면(U-01 보강 전 옵션) 지도 표지 없이 목록에만 나온다. */
  readonly cell?: { readonly col: number; readonly row: number };
  readonly available: boolean;
  readonly reasonCode?: string;
  readonly reason?: string;
  readonly distanceCells?: number;
  readonly distanceTurns?: number;
  /** 화면 이름(예: 「허현」). */
  readonly name: string;
  /** 둘째 줄(예: 「영천군 · 이웃」). */
  readonly sub?: string;
  /** 목록 묶음 탭(「묶음 내 영지」 · 「이웃」 …). 없으면 「전체」 에만 든다. */
  readonly groups?: readonly string[];
  /** 지금 내 자리 — 거리 대신 「지금 자리」 칩. 고를 수 있는지는 `available` 이 정한다. */
  readonly here?: boolean;
}

/** 지도 표지 · 목록 행이 그릴 상태. K2 지도 층은 `markerStateOf` 로 받아 그린다. */
export type TargetMarkerState = 'ok' | 'no' | 'selected' | 'here';

// ---------------------------------------------------------------- 사람 고르기(PeoplePicker, NPC 포함)

/** 묶음 탭 4개. 보드 PGROUPS. */
export type PeopleGroup = 'mine' | 'nation' | 'rulers' | 'all';

export interface PersonOption {
  readonly generalId: number;
  readonly name: string;
  /** 사람 장수면 「사람」 칩. false · 없음(서버가 안 줌) = 칩 없음. */
  readonly isHuman?: boolean;
  /** 초상 — 공용 Portrait(`picture` · `imageServer`)로 그린다. 없으면 첫 글자 판. */
  readonly picture?: string | null;
  readonly imageServer?: number | null;
  /**
   * 소속. null = 재야(「재야」 로 그린다). 없음(undefined) = 서버가 안 줌 → 소속 조각을 그리지 않는다.
   * null 과 undefined 를 섞지 않는다 — 모르는 것을 「재야」 라고 말하게 된다.
   */
  readonly nation?: { readonly id: number; readonly name: string; readonly color: string } | null;
  /** 자리(예: 「양적현」). null = 「자리 모름(시야 밖)」. 없음(undefined) = 서버가 안 줌 → 자리 조각을 그리지 않는다. */
  readonly location?: string | null;
  /** 이 사람이 드는 묶음('all' 은 넣지 않아도 된다). 모르면 [] 로 두고 PeoplePicker `groups` 에서 그 탭을 끈다. */
  readonly groups: readonly Exclude<PeopleGroup, 'all'>[];
  /** 고를 수 없으면 사유(행 전체가 사유를 여는 단추가 된다). */
  readonly blockedReason?: string;
}

// ---------------------------------------------------------------- 시간 막대(TimeBar)

export type TimeBarMode = 'replay' | 'live';

export interface TimeBarEvent {
  readonly id: string;
  /** 0 … duration(ms). */
  readonly at: number;
  /** 누르면 그 순간으로. 「{label} — n% 지점으로」 로 읽힌다. */
  readonly label: string;
  readonly tone?: 'moss' | 'info' | 'bronze' | 'rust';
}

export type TimeBarSpeed = 0.5 | 1 | 2;

/** 보이는 짧은 글자 조각(빈 글자 금지)을 받는 곳에 쓴다. */
export type Label = NonNullable<ReactNode>;
