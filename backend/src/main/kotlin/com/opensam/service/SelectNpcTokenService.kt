package com.opensam.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.opensam.dto.NpcCard
import com.opensam.dto.NpcTokenResponse
import com.opensam.dto.SelectNpcResult
import com.opensam.dto.GeneralResponse
import com.opensam.entity.General
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import com.opensam.repository.WorldStateRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

@Service
class SelectNpcTokenService(
    private val generalRepository: GeneralRepository,
    private val nationRepository: NationRepository,
    private val worldStateRepository: WorldStateRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private data class StoredNpcToken(
        val nonce: String,
        val generalIds: List<Long>,
        val validUntil: Instant,
        val pickMoreAfter: Instant,
        val keepCount: Int,
    )

    fun generateToken(worldId: Long, userId: Long): NpcTokenResponse {
        ensureUserHasNoGeneral(worldId, userId)
        val now = Instant.now()
        val validitySeconds = validitySeconds(worldId)
        val cooldownSeconds = pickMoreCooldownSeconds(worldId)
        val picked = drawNpcCards(worldId, 5, emptySet())

        val token = StoredNpcToken(
            nonce = UUID.randomUUID().toString(),
            generalIds = picked.map { it.id },
            validUntil = now.plusSeconds(validitySeconds),
            pickMoreAfter = now.plusSeconds(cooldownSeconds),
            keepCount = 0,
        )
        saveToken(worldId, userId, token)
        return toResponse(worldId, token, picked)
    }

    fun refreshToken(worldId: Long, userId: Long, nonce: String, keepIds: List<Long>): NpcTokenResponse {
        ensureUserHasNoGeneral(worldId, userId)
        val current = loadToken(worldId, userId) ?: throw IllegalStateException("토큰이 만료되었습니다.")
        if (current.nonce != nonce) {
            throw IllegalArgumentException("유효하지 않은 nonce입니다.")
        }

        val now = Instant.now()
        if (now.isAfter(current.validUntil)) {
            deleteToken(worldId, userId)
            throw IllegalStateException("토큰이 만료되었습니다.")
        }
        if (now.isBefore(current.pickMoreAfter)) {
            throw IllegalStateException("아직 다시 뽑을 수 없습니다.")
        }

        val distinctKeepIds = keepIds.distinct()
        if (distinctKeepIds.size > 3) {
            throw IllegalArgumentException("보존 카드는 최대 3장입니다.")
        }
        if (!distinctKeepIds.all { current.generalIds.contains(it) }) {
            throw IllegalArgumentException("보존할 카드가 현재 토큰에 없습니다.")
        }

        val kept = generalsByOrderedIds(worldId, distinctKeepIds)
        val needed = 5 - kept.size
        val drawn = if (needed > 0) {
            drawNpcCards(worldId, needed, distinctKeepIds.toSet())
        } else {
            emptyList()
        }

        val validitySeconds = validitySeconds(worldId)
        val cooldownSeconds = pickMoreCooldownSeconds(worldId)
        val nextToken = StoredNpcToken(
            nonce = UUID.randomUUID().toString(),
            generalIds = (kept + drawn).map { it.id },
            validUntil = now.plusSeconds(validitySeconds),
            pickMoreAfter = now.plusSeconds(cooldownSeconds),
            keepCount = kept.size,
        )
        saveToken(worldId, userId, nextToken)
        return toResponse(worldId, nextToken, kept + drawn)
    }

    fun selectNpc(worldId: Long, userId: Long, nonce: String, generalId: Long): SelectNpcResult {
        ensureUserHasNoGeneral(worldId, userId)
        val token = loadToken(worldId, userId) ?: throw IllegalStateException("토큰이 만료되었습니다.")
        if (token.nonce != nonce) {
            throw IllegalArgumentException("유효하지 않은 nonce입니다.")
        }
        if (Instant.now().isAfter(token.validUntil)) {
            deleteToken(worldId, userId)
            throw IllegalStateException("토큰이 만료되었습니다.")
        }
        if (!token.generalIds.contains(generalId)) {
            throw IllegalArgumentException("선택할 수 없는 NPC입니다.")
        }

        val general = generalRepository.findById(generalId).orElse(null)
            ?: throw IllegalArgumentException("NPC 장수를 찾을 수 없습니다.")
        if (general.worldId != worldId || !isSelectableNpc(general)) {
            throw IllegalArgumentException("이미 선택되었거나 선택 불가능한 NPC입니다.")
        }

        general.userId = userId
        general.npcState = 1
        general.killTurn = 6
        general.updatedAt = OffsetDateTime.now()

        val saved = generalRepository.save(general)
        deleteToken(worldId, userId)
        return SelectNpcResult(success = true, general = GeneralResponse.from(saved))
    }

    private fun ensureUserHasNoGeneral(worldId: Long, userId: Long) {
        if (generalRepository.findByWorldIdAndUserId(worldId, userId) != null) {
            throw IllegalStateException("이미 장수를 보유하고 있습니다.")
        }
    }

    private fun drawNpcCards(worldId: Long, count: Int, excludeIds: Set<Long>): List<General> {
        val candidates = generalRepository.findByWorldId(worldId)
            .filter { isSelectableNpc(it) && !excludeIds.contains(it.id) }
            .toMutableList()

        if (candidates.size < count) {
            throw IllegalStateException("선택 가능한 NPC가 부족합니다.")
        }

        val selected = mutableListOf<General>()
        repeat(count) {
            val totalWeight = candidates.sumOf(::weight)
            val pickedIndex = if (totalWeight <= 0.0) {
                Random.nextInt(candidates.size)
            } else {
                weightedPickIndex(candidates, totalWeight)
            }
            selected += candidates.removeAt(pickedIndex)
        }
        return selected
    }

    private fun weightedPickIndex(candidates: List<General>, totalWeight: Double): Int {
        var random = Random.nextDouble(totalWeight)
        candidates.forEachIndexed { index, general ->
            random -= weight(general)
            if (random <= 0.0) return index
        }
        return candidates.lastIndex
    }

    private fun weight(general: General): Double {
        val statSum = general.leadership.toInt() + general.strength.toInt() + general.intel.toInt()
        return max(1.0, statSum.toDouble().pow(1.5))
    }

    private fun isSelectableNpc(general: General): Boolean {
        return general.userId == null && general.npcState.toInt() >= 2
    }

    private fun generalsByOrderedIds(worldId: Long, ids: List<Long>): List<General> {
        if (ids.isEmpty()) return emptyList()
        val generalsById = generalRepository.findByWorldId(worldId)
            .filter { ids.contains(it.id) && isSelectableNpc(it) }
            .associateBy { it.id }

        if (generalsById.size != ids.size) {
            throw IllegalArgumentException("보존 카드 중 일부를 찾을 수 없습니다.")
        }
        return ids.map { generalsById[it]!! }
    }

    private fun toResponse(worldId: Long, token: StoredNpcToken, generals: List<General>): NpcTokenResponse {
        val nations = nationRepository.findByWorldId(worldId).associateBy { it.id }
        val cardsById = generals.associateBy { it.id }
        val orderedCards = token.generalIds.mapNotNull { generalId ->
            val general = cardsById[generalId] ?: return@mapNotNull null
            val nation = nations[general.nationId]
            NpcCard(
                id = general.id,
                name = general.name,
                picture = general.picture,
                imageServer = general.imageServer,
                leadership = general.leadership,
                strength = general.strength,
                intel = general.intel,
                politics = general.politics,
                charm = general.charm,
                nationId = general.nationId,
                nationName = nation?.name ?: "중립",
                nationColor = nation?.color ?: "#6b7280",
                personality = general.personalCode,
                special = general.specialCode,
            )
        }
        return NpcTokenResponse(
            nonce = token.nonce,
            npcs = orderedCards,
            validUntil = token.validUntil,
            pickMoreAfter = token.pickMoreAfter,
            keepCount = token.keepCount,
        )
    }

    private fun tokenKey(worldId: Long, userId: Long): String {
        return "npc-token:$userId:$worldId"
    }

    private fun saveToken(worldId: Long, userId: Long, token: StoredNpcToken) {
        val now = Instant.now()
        val ttl = Duration.between(now, token.validUntil)
        val normalizedTtl = if (ttl.isNegative || ttl.isZero) Duration.ofSeconds(1) else ttl
        val payload = objectMapper.writeValueAsString(token)
        redisTemplate.opsForValue().set(tokenKey(worldId, userId), payload, normalizedTtl)
    }

    private fun loadToken(worldId: Long, userId: Long): StoredNpcToken? {
        val payload = redisTemplate.opsForValue().get(tokenKey(worldId, userId)) ?: return null
        return objectMapper.readValue(payload, StoredNpcToken::class.java)
    }

    private fun deleteToken(worldId: Long, userId: Long) {
        redisTemplate.delete(tokenKey(worldId, userId))
    }

    private fun validitySeconds(worldId: Long): Long {
        val turnTerm = turnTermMinutes(worldId)
        return ceil(max(90.0, turnTerm * 40.0)).toLong()
    }

    private fun pickMoreCooldownSeconds(worldId: Long): Long {
        val turnTerm = turnTermMinutes(worldId)
        return ceil(max(10.0, turnTerm.pow(0.672) * 8.0)).toLong()
    }

    private fun turnTermMinutes(worldId: Long): Double {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null)
            ?: throw IllegalArgumentException("월드를 찾을 수 없습니다.")
        return max(0.0, world.tickSeconds.toDouble() / 60.0)
    }
}
