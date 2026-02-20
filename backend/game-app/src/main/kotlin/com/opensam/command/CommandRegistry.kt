package com.opensam.command

import com.opensam.command.general.*
import com.opensam.command.nation.*
import com.opensam.entity.General
import org.springframework.stereotype.Component

typealias GeneralCommandFactory = (General, CommandEnv, Map<String, Any>?) -> GeneralCommand
typealias NationCommandFactory = (General, CommandEnv, Map<String, Any>?) -> NationCommand

@Component
class CommandRegistry {
    private val generalCommands = mutableMapOf<String, GeneralCommandFactory>()
    private val nationCommands = mutableMapOf<String, NationCommandFactory>()

    init {
        // === General Commands (55) ===

        // Default
        registerGeneralCommand("휴식") { g, e, a -> 휴식(g, e, a) }

        // Civil/Domestic (18)
        registerGeneralCommand("농지개간") { g, e, a -> che_농지개간(g, e, a) }
        registerGeneralCommand("상업투자") { g, e, a -> che_상업투자(g, e, a) }
        registerGeneralCommand("치안강화") { g, e, a -> che_치안강화(g, e, a) }
        registerGeneralCommand("수비강화") { g, e, a -> che_수비강화(g, e, a) }
        registerGeneralCommand("성벽보수") { g, e, a -> che_성벽보수(g, e, a) }
        registerGeneralCommand("정착장려") { g, e, a -> che_정착장려(g, e, a) }
        registerGeneralCommand("주민선정") { g, e, a -> che_주민선정(g, e, a) }
        registerGeneralCommand("기술연구") { g, e, a -> che_기술연구(g, e, a) }
        registerGeneralCommand("모병") { g, e, a -> che_모병(g, e, a) }
        registerGeneralCommand("징병") { g, e, a -> che_징병(g, e, a) }
        registerGeneralCommand("훈련") { g, e, a -> che_훈련(g, e, a) }
        registerGeneralCommand("사기진작") { g, e, a -> che_사기진작(g, e, a) }
        registerGeneralCommand("소집해제") { g, e, a -> che_소집해제(g, e, a) }
        registerGeneralCommand("숙련전환") { g, e, a -> che_숙련전환(g, e, a) }
        registerGeneralCommand("물자조달") { g, e, a -> che_물자조달(g, e, a) }
        registerGeneralCommand("군량매매") { g, e, a -> che_군량매매(g, e, a) }
        registerGeneralCommand("헌납") { g, e, a -> che_헌납(g, e, a) }
        registerGeneralCommand("단련") { g, e, a -> che_단련(g, e, a) }

        // Military (15)
        registerGeneralCommand("출병") { g, e, a -> 출병(g, e, a) }
        registerGeneralCommand("이동") { g, e, a -> 이동(g, e, a) }
        registerGeneralCommand("집합") { g, e, a -> 집합(g, e, a) }
        registerGeneralCommand("귀환") { g, e, a -> 귀환(g, e, a) }
        registerGeneralCommand("접경귀환") { g, e, a -> 접경귀환(g, e, a) }
        registerGeneralCommand("강행") { g, e, a -> 강행(g, e, a) }
        registerGeneralCommand("거병") { g, e, a -> 거병(g, e, a) }
        registerGeneralCommand("전투태세") { g, e, a -> 전투태세(g, e, a) }
        registerGeneralCommand("화계") { g, e, a -> 화계(g, e, a) }
        registerGeneralCommand("첩보") { g, e, a -> 첩보(g, e, a) }
        registerGeneralCommand("선동") { g, e, a -> 선동(g, e, a) }
        registerGeneralCommand("탈취") { g, e, a -> 탈취(g, e, a) }
        registerGeneralCommand("파괴") { g, e, a -> 파괴(g, e, a) }
        registerGeneralCommand("요양") { g, e, a -> 요양(g, e, a) }
        registerGeneralCommand("방랑") { g, e, a -> 방랑(g, e, a) }

        // Political (19)
        registerGeneralCommand("등용") { g, e, a -> 등용(g, e, a) }
        registerGeneralCommand("등용수락") { g, e, a -> 등용수락(g, e, a) }
        registerGeneralCommand("임관") { g, e, a -> 임관(g, e, a) }
        registerGeneralCommand("랜덤임관") { g, e, a -> 랜덤임관(g, e, a) }
        registerGeneralCommand("장수대상임관") { g, e, a -> 장수대상임관(g, e, a) }
        registerGeneralCommand("하야") { g, e, a -> 하야(g, e, a) }
        registerGeneralCommand("은퇴") { g, e, a -> 은퇴(g, e, a) }
        registerGeneralCommand("건국") { g, e, a -> 건국(g, e, a) }
        registerGeneralCommand("무작위건국") { g, e, a -> 무작위건국(g, e, a) }
        registerGeneralCommand("모반시도") { g, e, a -> 모반시도(g, e, a) }
        registerGeneralCommand("선양") { g, e, a -> 선양(g, e, a) }
        registerGeneralCommand("해산") { g, e, a -> 해산(g, e, a) }
        registerGeneralCommand("견문") { g, e, a -> 견문(g, e, a) }
        registerGeneralCommand("인재탐색") { g, e, a -> 인재탐색(g, e, a) }
        registerGeneralCommand("증여") { g, e, a -> 증여(g, e, a) }
        registerGeneralCommand("장비매매") { g, e, a -> 장비매매(g, e, a) }
        registerGeneralCommand("내정특기초기화") { g, e, a -> 내정특기초기화(g, e, a) }
        registerGeneralCommand("전투특기초기화") { g, e, a -> 전투특기초기화(g, e, a) }

        // NPC/CR Special (3)
        registerGeneralCommand("NPC능동") { g, e, a -> NPC능동(g, e, a) }
        registerGeneralCommand("CR건국") { g, e, a -> CR건국(g, e, a) }
        registerGeneralCommand("CR맹훈련") { g, e, a -> CR맹훈련(g, e, a) }

        // === Nation Commands (38) ===

        // Default
        registerNationCommand("Nation휴식") { g, e, a -> Nation휴식(g, e, a) }

        // Resource/Management (10)
        registerNationCommand("포상") { g, e, a -> che_포상(g, e, a) }
        registerNationCommand("몰수") { g, e, a -> che_몰수(g, e, a) }
        registerNationCommand("감축") { g, e, a -> che_감축(g, e, a) }
        registerNationCommand("증축") { g, e, a -> che_증축(g, e, a) }
        registerNationCommand("발령") { g, e, a -> che_발령(g, e, a) }
        registerNationCommand("천도") { g, e, a -> che_천도(g, e, a) }
        registerNationCommand("백성동원") { g, e, a -> che_백성동원(g, e, a) }
        registerNationCommand("물자원조") { g, e, a -> che_물자원조(g, e, a) }
        registerNationCommand("국기변경") { g, e, a -> che_국기변경(g, e, a) }
        registerNationCommand("국호변경") { g, e, a -> che_국호변경(g, e, a) }

        // Diplomacy (7)
        registerNationCommand("선전포고") { g, e, a -> che_선전포고(g, e, a) }
        registerNationCommand("종전제의") { g, e, a -> che_종전제의(g, e, a) }
        registerNationCommand("종전수락") { g, e, a -> che_종전수락(g, e, a) }
        registerNationCommand("불가침제의") { g, e, a -> che_불가침제의(g, e, a) }
        registerNationCommand("불가침수락") { g, e, a -> che_불가침수락(g, e, a) }
        registerNationCommand("불가침파기제의") { g, e, a -> che_불가침파기제의(g, e, a) }
        registerNationCommand("불가침파기수락") { g, e, a -> che_불가침파기수락(g, e, a) }

        // Strategic (8)
        registerNationCommand("급습") { g, e, a -> che_급습(g, e, a) }
        registerNationCommand("수몰") { g, e, a -> che_수몰(g, e, a) }
        registerNationCommand("허보") { g, e, a -> che_허보(g, e, a) }
        registerNationCommand("초토화") { g, e, a -> che_초토화(g, e, a) }
        registerNationCommand("필사즉생") { g, e, a -> che_필사즉생(g, e, a) }
        registerNationCommand("이호경식") { g, e, a -> che_이호경식(g, e, a) }
        registerNationCommand("피장파장") { g, e, a -> che_피장파장(g, e, a) }
        registerNationCommand("의병모집") { g, e, a -> che_의병모집(g, e, a) }

        // Research (9)
        registerNationCommand("극병연구") { g, e, a -> event_극병연구(g, e, a) }
        registerNationCommand("대검병연구") { g, e, a -> event_대검병연구(g, e, a) }
        registerNationCommand("무희연구") { g, e, a -> event_무희연구(g, e, a) }
        registerNationCommand("산저병연구") { g, e, a -> event_산저병연구(g, e, a) }
        registerNationCommand("상병연구") { g, e, a -> event_상병연구(g, e, a) }
        registerNationCommand("원융노병연구") { g, e, a -> event_원융노병연구(g, e, a) }
        registerNationCommand("음귀병연구") { g, e, a -> event_음귀병연구(g, e, a) }
        registerNationCommand("화륜차연구") { g, e, a -> event_화륜차연구(g, e, a) }
        registerNationCommand("화시병연구") { g, e, a -> event_화시병연구(g, e, a) }

        // Special
        registerNationCommand("무작위수도이전") { g, e, a -> che_무작위수도이전(g, e, a) }
        registerNationCommand("부대탈퇴지시") { g, e, a -> che_부대탈퇴지시(g, e, a) }
        registerNationCommand("인구이동") { g, e, a -> cr_인구이동(g, e, a) }
    }

    fun registerGeneralCommand(key: String, factory: GeneralCommandFactory) {
        generalCommands[key] = factory
    }

    fun registerNationCommand(key: String, factory: NationCommandFactory) {
        nationCommands[key] = factory
    }

    fun createGeneralCommand(actionCode: String, general: General, env: CommandEnv, arg: Map<String, Any>? = null): GeneralCommand {
        val factory = generalCommands[actionCode] ?: generalCommands["휴식"]!!
        return factory(general, env, arg)
    }

    fun createNationCommand(actionCode: String, general: General, env: CommandEnv, arg: Map<String, Any>? = null): NationCommand? {
        val factory = nationCommands[actionCode] ?: return null
        return factory(general, env, arg)
    }

    fun hasGeneralCommand(actionCode: String): Boolean = actionCode in generalCommands
    fun hasNationCommand(actionCode: String): Boolean = actionCode in nationCommands
    fun getGeneralCommandNames(): Set<String> = generalCommands.keys
    fun getNationCommandNames(): Set<String> = nationCommands.keys
}
