package opensamguk.engine.v2

import opensamguk.logic.event.EventActionFactory

/**
 * OPENSAM-151 (v2 R2) — v2 event-action leaf 등록기.
 *
 * v1의 `opensamguk.logic.event.WorldActions`와 **분리된 파일**이다. WorldActions는 T1 동결 대상이라
 * v2 leaf를 거기에 끼워 넣을 수 없고, 끼워 넣어서도 안 된다 — v2 leaf가 v1 월드의 팩토리 이름공간에
 * 섞이는 것과 별개로, v1 파일 하나를 v2가 계속 넓히기 시작하면 격리 경계가 파일 단위로 사라진다.
 *
 * 등록은 **이름만** 추가한다. v2 leaf는 시나리오의 `event` 행이 이름으로 호출하지 않는 한 절대 돌지
 * 않으므로, 등록 자체는 v1 월드의 동작을 바꾸지 않는다(v1 시나리오에는 그 이름을 쓰는 행이 없다).
 */
object V2WorldActions {
    fun register(factory: EventActionFactory): EventActionFactory =
        V2ProcessCityIncomeAction.register(factory)
}
