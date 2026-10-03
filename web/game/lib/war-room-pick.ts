// 작전실 선택 카드(P-W01, 보드 V31K4WarRoom sel_card · V31K4MWarRoom 선택 알약)의 보기 모델. React 없음.
// 고른 城은 새 지도의 고르기(WarRoomMap onPick — 미리보기 행 · 세력 목록을 함께 싣는다) 또는 「내 위치」 알약(내 城, 미리보기 없이 front-info)에서 온다.
import { UNOWNED_NATION_NAME } from '@opensamguk/ui';
import type { Corps, CorpsList } from './campaign-reads';
import { countyHead } from './county-view';
import type { FrontCityInfo, MapPreviewCity, MapPreviewNation } from './types';

/** 고른 城. city 는 지도에서 골랐을 때만(미리보기 행). 「내 위치」 알약으로 고르면 null — 내 城의 front-info 로 그린다. */
export interface WarRoomPickTarget {
    readonly cityId: number;
    readonly city: MapPreviewCity | null;
    readonly nations: readonly MapPreviewNation[];
    /** 그 城이 든 구역의 서버 id(군단 자리와 같은 id). 모르면 null. */
    readonly provinceRecordId: string | null;
}

export interface PickView {
    readonly cityId: number;
    readonly name: string;
    readonly commanderyName: string | null;
    readonly ownerName: string;
    readonly ownerColor: string | null;
    /** 우리 세력 현 — 보급 · 특산 실제 몫(D40) · 첩보 단추의 바탕. */
    readonly mine: boolean;
    readonly isSeat: boolean;
    readonly isCapital: boolean;
    /** 내 장수가 선 城. */
    readonly here: boolean;
    /** 보급 — 우리 현만 안다(남의 현 · 모름은 null). */
    readonly supplied: boolean | null;
}

/**
 * 고른 城 → 카드 머리 · 칸. 미리보기 행이 있으면 현 상세와 같은 머리(countyHead), 없으면 내 城의 front-info.
 * 내 城이 아닌데 미리보기 행도 없으면 그릴 것이 없다(null).
 */
export function pickView(target: WarRoomPickTarget, home: FrontCityInfo | null | undefined, myNationId: number | null): PickView | null {
    if (target.city) {
        const head = countyHead(target.city, target.nations, { nationId: myNationId, cityId: home?.id ?? null });
        return {
            cityId: target.cityId, name: head.name, commanderyName: head.commanderyName, ownerName: head.ownerName, ownerColor: head.ownerColor,
            mine: head.mine, isSeat: head.isSeat, isCapital: head.isCapital, here: head.here, supplied: head.mine ? target.city.supply : null,
        };
    }
    if (!home || home.id !== target.cityId) return null;
    const mine = myNationId != null && myNationId > 0 && home.nationId === myNationId;
    return {
        cityId: home.id, name: home.name, commanderyName: null,
        ownerName: home.nationId > 0 ? home.nationName ?? '어느 세력' : UNOWNED_NATION_NAME, ownerColor: home.nationId > 0 ? home.nationColor ?? null : null,
        mine, isSeat: false, isCapital: false, here: true, supplied: null,
    };
}

/** 이 城 구역에 선 군단 — 군단 읽기가 READY 이고 구역 id 를 알 때만 목록(빈 목록 = 없음). 안 보이는(FOG) 군단은 세지 않는다. */
export type Stationed = { readonly kind: 'unknown' } | { readonly kind: 'list'; readonly names: readonly string[] };

export function stationedCorps(corps: CorpsList | null | undefined, provinceRecordId: string | null): Stationed {
    if (corps?.status !== 'READY' || !corps.corps || provinceRecordId == null) return { kind: 'unknown' };
    const here = corps.corps.filter((c: Corps) => c.provinceId === provinceRecordId && c.visibility !== 'FOG');
    return { kind: 'list', names: here.map((c) => `${c.commanderName ?? c.ownerName ?? '이름 모를'} 군단`) };
}

/** 주둔 칸 글자 — 둘까지 이름, 넘으면 「외 n」. */
export function stationedText(s: Stationed): string {
    if (s.kind === 'unknown') return '?';
    if (s.names.length === 0) return '없음';
    return s.names.length <= 2 ? s.names.join(' · ') : `${s.names.slice(0, 2).join(' · ')} 외 ${s.names.length - 2}`;
}

/** 모바일 선택 알약 아래 글 — 「영천군 치소」 · 「영천군」. 군을 모르면 빈 글. */
export function pickSubline(view: PickView): string {
    if (!view.commanderyName) return '';
    return view.isSeat ? `${view.commanderyName} 치소` : view.commanderyName;
}
