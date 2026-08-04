package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.infra.entity.BoardPostEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * `board_post` 테이블용 JPA read 리포지토리.
 *
 * 데몬 게시판 댓글 인테이크 핸들러는 충실한 게시물 존재 여부 read에 [findByIdAndNationId]를 사용하며
 * (PHP `SELECT board WHERE no AND nation_no` → `'게시물이 없습니다.'`), 게시물의 `is_secret`을 읽어
 * 댓글 권한을 게이팅한다. write 경로(게시판 게시물/댓글 INSERT)는
 * [opensamguk.infra.persistence.JdbcFlushExecutor]를 거치며 — 엔진에서 `save()`/`delete()`를 하지 않는다.
 */
interface BoardPostRepository {
    fun findByIdAndNationId(id: Int, nationId: Int): BoardPostEntity?
}

internal interface BoardPostRawRepository : SpringDataRepository<BoardPostEntity, Int> {
    @Query(
        value = """
            SELECT * FROM board_post
            WHERE world_id = :worldId AND id = :id AND nation_id = :nationId
        """,
        nativeQuery = true,
    )
    fun findByWorldIdAndIdAndNationId(
        @Param("worldId") worldId: Int,
        @Param("id") id: Int,
        @Param("nationId") nationId: Int,
    ): BoardPostEntity?
}

internal class WorldScopedBoardPostRepository(
    private val raw: BoardPostRawRepository,
    private val worldId: WorldId,
) : BoardPostRepository {
    override fun findByIdAndNationId(id: Int, nationId: Int): BoardPostEntity? =
        raw.findByWorldIdAndIdAndNationId(worldId.value, id, nationId)
}
