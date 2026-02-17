package com.opensam.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "general")
class General(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "world_id", nullable = false)
    var worldId: Long = 0,

    @Column(name = "user_id")
    var userId: Long? = null,

    @Column(nullable = false)
    var name: String = "",

    @Column(name = "nation_id", nullable = false)
    var nationId: Long = 0,

    @Column(name = "city_id", nullable = false)
    var cityId: Long = 0,

    @Column(name = "troop_id", nullable = false)
    var troopId: Long = 0,

    @Column(name = "npc_state", nullable = false)
    var npcState: Short = 0,

    @Column(name = "npc_org")
    var npcOrg: Long? = null,

    @Column(nullable = false)
    var affinity: Short = 0,

    @Column(name = "born_year", nullable = false)
    var bornYear: Short = 180,

    @Column(name = "dead_year", nullable = false)
    var deadYear: Short = 300,

    @Column(nullable = false)
    var picture: String = "",

    @Column(name = "image_server", nullable = false)
    var imageServer: Short = 0,

    @Column(nullable = false)
    var leadership: Short = 50,

    @Column(name = "leadership_exp", nullable = false)
    var leadershipExp: Short = 0,

    @Column(nullable = false)
    var strength: Short = 50,

    @Column(name = "strength_exp", nullable = false)
    var strengthExp: Short = 0,

    @Column(nullable = false)
    var intel: Short = 50,

    @Column(name = "intel_exp", nullable = false)
    var intelExp: Short = 0,

    @Column(nullable = false)
    var politics: Short = 50,

    @Column(nullable = false)
    var charm: Short = 50,

    @Column(name = "dex_1", nullable = false)
    var dex1: Int = 0,

    @Column(name = "dex_2", nullable = false)
    var dex2: Int = 0,

    @Column(name = "dex_3", nullable = false)
    var dex3: Int = 0,

    @Column(name = "dex_4", nullable = false)
    var dex4: Int = 0,

    @Column(name = "dex_5", nullable = false)
    var dex5: Int = 0,

    @Column(nullable = false)
    var injury: Short = 0,

    @Column(nullable = false)
    var experience: Int = 0,

    @Column(nullable = false)
    var dedication: Int = 0,

    @Column(name = "officer_level", nullable = false)
    var officerLevel: Short = 0,

    @Column(name = "officer_city", nullable = false)
    var officerCity: Int = 0,

    @Column(nullable = false)
    var permission: String = "normal",

    @Column(nullable = false)
    var gold: Int = 1000,

    @Column(nullable = false)
    var rice: Int = 1000,

    @Column(nullable = false)
    var crew: Int = 0,

    @Column(name = "crew_type", nullable = false)
    var crewType: Short = 0,

    @Column(nullable = false)
    var train: Short = 0,

    @Column(nullable = false)
    var atmos: Short = 0,

    @Column(name = "weapon_code", nullable = false)
    var weaponCode: String = "None",

    @Column(name = "book_code", nullable = false)
    var bookCode: String = "None",

    @Column(name = "horse_code", nullable = false)
    var horseCode: String = "None",

    @Column(name = "item_code", nullable = false)
    var itemCode: String = "None",

    @Column(name = "owner_name", nullable = false)
    var ownerName: String = "",

    @Column(nullable = false)
    var newmsg: Short = 0,

    @Column(name = "turn_time", nullable = false)
    var turnTime: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "recent_war_time")
    var recentWarTime: OffsetDateTime? = null,

    @Column(name = "make_limit", nullable = false)
    var makeLimit: Short = 0,

    @Column(name = "kill_turn")
    var killTurn: Short? = null,

    @Column(name = "block_state", nullable = false)
    var blockState: Short = 0,

    @Column(name = "ded_level", nullable = false)
    var dedLevel: Short = 0,

    @Column(name = "exp_level", nullable = false)
    var expLevel: Short = 0,

    @Column(nullable = false)
    var age: Short = 20,

    @Column(name = "start_age", nullable = false)
    var startAge: Short = 20,

    @Column(nullable = false)
    var belong: Short = 1,

    @Column(nullable = false)
    var betray: Short = 0,

    @Column(name = "personal_code", nullable = false)
    var personalCode: String = "None",

    @Column(name = "special_code", nullable = false)
    var specialCode: String = "None",

    @Column(name = "spec_age", nullable = false)
    var specAge: Short = 0,

    @Column(name = "special2_code", nullable = false)
    var special2Code: String = "None",

    @Column(name = "spec2_age", nullable = false)
    var spec2Age: Short = 0,

    @Column(name = "defence_train", nullable = false)
    var defenceTrain: Short = 80,

    @Column(name = "tournament_state", nullable = false)
    var tournamentState: Short = 0,

    @Column(name = "command_points", nullable = false)
    var commandPoints: Int = 10,

    @Column(name = "command_end_time")
    var commandEndTime: OffsetDateTime? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_turn", columnDefinition = "jsonb", nullable = false)
    var lastTurn: MutableMap<String, Any> = mutableMapOf(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var meta: MutableMap<String, Any> = mutableMapOf(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var penalty: MutableMap<String, Any> = mutableMapOf(),

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
