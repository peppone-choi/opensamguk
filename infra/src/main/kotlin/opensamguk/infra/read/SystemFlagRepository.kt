package opensamguk.infra.read

import opensamguk.infra.entity.SystemFlagEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * 전역 시스템 플래그 저장소 — legacy `system WHERE NO = 1` 단일 행.
 *
 * 가입/로그인 경로는 [SINGLETON_ID] 행을 읽어 allow_join/allow_login 게이트를 적용한다.
 * 어드민(B2b)은 같은 행을 갱신한다. 행이 없으면(이론상) [findSingleton]이 null을 반환하므로
 * 호출부는 기본값(미허용)으로 폴백하거나 시드해야 한다. V11 마이그레이션이 id=1 행을 시드한다.
 *
 * `findSingleton`은 Kotlin 인터페이스 default 메서드의 `DefaultImpls` 컴파일 리스크를 피해 `@Query`로 구현한다.
 */
@Repository
interface SystemFlagRepository : JpaRepository<SystemFlagEntity, Long> {

    companion object {
        /** legacy `WHERE NO = 1` — 시스템 플래그 단일 행 식별자. */
        const val SINGLETON_ID: Long = 1
    }

    /** 단일 시스템 플래그 행(id=1). 미존재 시 null. */
    @Query("SELECT s FROM SystemFlagEntity s WHERE s.id = 1")
    fun findSingleton(): SystemFlagEntity?
}
