package opensamguk.infra.read

import opensamguk.infra.entity.BoardPostEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * `board_post` 테이블용 JPA read 리포지토리.
 *
 * 데몬 게시판 댓글 인테이크 핸들러는 충실한 게시물 존재 여부 read에 [findByIdAndNationId]를 사용하며
 * (PHP `SELECT board WHERE no AND nation_no` → `'게시물이 없습니다.'`), 게시물의 `is_secret`을 읽어
 * 댓글 권한을 게이팅한다. write 경로(게시판 게시물/댓글 INSERT)는
 * [opensamguk.infra.persistence.JdbcFlushExecutor]를 거치며 — 엔진에서 `save()`/`delete()`를 하지 않는다.
 */
@Repository
interface BoardPostRepository : JpaRepository<BoardPostEntity, Int> {

    /** 소속 국가로 스코프된 게시물 (PHP `WHERE no = %i AND nation_no = %i`); null = 없음. */
    fun findByIdAndNationId(id: Int, nationId: Int): BoardPostEntity?
}
