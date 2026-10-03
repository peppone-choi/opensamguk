package opensamguk.boardapi.board

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import opensamguk.boardapi.security.BoardUserDetails
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Converter
class GatewayBoardCategoryConverter : AttributeConverter<GatewayBoardCategory, String> {
    override fun convertToDatabaseColumn(value: GatewayBoardCategory?): String? = value?.name
    override fun convertToEntityAttribute(value: String?): GatewayBoardCategory? = value?.let(::GatewayBoardCategory)
}

@Entity
@Table(name = "gateway_board_definition")
open class GatewayBoardDefinitionEntity(
    @Column(name = "board_key", nullable = false, unique = true, length = 32) open var key: String,
    @Column(nullable = false, length = 80) open var name: String,
    @Column(name = "sort_order", nullable = false) open var sortOrder: Int = 0,
    @Column(nullable = false) open var writable: Boolean = true,
    @Column(name = "created_at", nullable = false) open var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) open var id: Long? = null,
) {
    protected constructor() : this(key = "FREE", name = "")
}

interface GatewayBoardDefinitionRepository : JpaRepository<GatewayBoardDefinitionEntity, Long> {
    fun findAllByOrderBySortOrderAscIdAsc(): List<GatewayBoardDefinitionEntity>
    fun findByKey(key: String): GatewayBoardDefinitionEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from GatewayBoardDefinitionEntity b where b.id = :id")
    fun lockById(@Param("id") id: Long): GatewayBoardDefinitionEntity?

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from GatewayBoardDefinitionEntity b where b.key = :key")
    fun lockForPost(@Param("key") key: String): GatewayBoardDefinitionEntity?

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from GatewayBoardDefinitionEntity b where b.id = :id")
    fun lockReadById(@Param("id") id: Long): GatewayBoardDefinitionEntity?
}

@Service
class GatewayBoardDefinitionService(
    private val definitions: GatewayBoardDefinitionRepository,
    private val posts: GatewayBoardPostRepository,
) {
    @Transactional(readOnly = true)
    fun list(): List<GatewayBoardDefinitionResponse> = definitions.findAllByOrderBySortOrderAscIdAsc().map(::response)

    @Transactional(readOnly = true)
    fun requireExisting(category: GatewayBoardCategory) {
        definitions.findByKey(category.name) ?: throw GatewayBoardNotFoundException()
    }

    /** 호출자의 글 transaction이 공유 잠금을 commit까지 보유하여 삭제/쓰기 금지 변경과 직렬화한다. */
    @Transactional
    fun requireWritable(category: GatewayBoardCategory) {
        val definition = definitions.lockForPost(category.name) ?: throw GatewayBoardNotFoundException()
        if (!definition.writable) throw GatewayBoardForbiddenException("이 게시판은 읽기만 허용합니다.")
    }

    /** 원본이 readonly이면 다른 게시판으로 옮기는 수정도 거절한다. 삭제와 같은 id 순서로 잠근다. */
    @Transactional
    fun requireWritableForUpdate(source: GatewayBoardCategory, target: GatewayBoardCategory) {
        val ids = listOf(source, target).distinct().map {
            requireNotNull((definitions.findByKey(it.name) ?: throw GatewayBoardNotFoundException()).id)
        }.sorted()
        for (id in ids) {
            val definition = definitions.lockReadById(id) ?: throw GatewayBoardNotFoundException()
            if (!definition.writable) throw GatewayBoardForbiddenException("이 게시판은 읽기만 허용합니다.")
        }
    }

    @Transactional
    fun create(request: CreateGatewayBoardDefinitionRequest, principal: BoardUserDetails): GatewayBoardDefinitionResponse {
        requireAdmin(principal)
        val key = GatewayBoardCategory(request.key).name
        val name = request.name.trim()
        require(name.isNotEmpty() && name.length <= 80) { "게시판 이름이 올바르지 않습니다." }
        try {
            return response(definitions.saveAndFlush(GatewayBoardDefinitionEntity(key, name, request.sortOrder, request.writable ?: true)))
        } catch (_: DataIntegrityViolationException) {
            throw GatewayBoardConflictException("이미 사용 중인 게시판 키입니다.")
        }
    }

    @Transactional
    fun update(id: Long, request: UpdateGatewayBoardDefinitionRequest, principal: BoardUserDetails): GatewayBoardDefinitionResponse {
        requireAdmin(principal)
        val definition = definitions.lockById(id) ?: throw GatewayBoardNotFoundException()
        request.name?.let {
            val name = it.trim()
            require(name.isNotEmpty() && name.length <= 80) { "게시판 이름이 올바르지 않습니다." }
            definition.name = name
        }
        request.sortOrder?.let { definition.sortOrder = it }
        request.writable?.let { definition.writable = it }
        return response(definition)
    }

    @Transactional
    fun delete(id: Long, moveTo: Long?, principal: BoardUserDetails) {
        requireAdmin(principal)
        require(moveTo != id) { "다른 게시판으로 옮겨야 합니다." }
        // 동시에 서로를 이동 대상으로 삼아도 게시판 잠금 순서가 같다.
        val locked = listOfNotNull(id, moveTo).sorted().associateWith {
            definitions.lockById(it) ?: throw GatewayBoardNotFoundException()
        }
        val source = locked.getValue(id)
        val category = GatewayBoardCategory(source.key)
        if (moveTo == null && posts.countByCategory(category) > 0) {
            throw GatewayBoardConflictException("게시글이 있는 게시판은 옮길 게시판을 지정해야 합니다.")
        }
        moveTo?.let { posts.moveAllTo(category, GatewayBoardCategory(locked.getValue(it).key)) }
        // 글을 삭제하지 않는다. FK가 남은 글이 있는 정의 삭제를 마지막으로 차단한다.
        definitions.delete(source)
        definitions.flush()
    }

    private fun requireAdmin(principal: BoardUserDetails) {
        if (principal.authorities.none { it.authority == "ROLE_ADMIN" }) {
            throw GatewayBoardForbiddenException("게시판 관리는 관리자만 할 수 있습니다.")
        }
    }

    private fun response(value: GatewayBoardDefinitionEntity) = GatewayBoardDefinitionResponse(
        requireNotNull(value.id), value.key, value.name, value.sortOrder, value.writable, value.createdAt)
}
