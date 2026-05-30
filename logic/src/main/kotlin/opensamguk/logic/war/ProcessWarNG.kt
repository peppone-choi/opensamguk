package opensamguk.logic.war

import opensamguk.common.rng.RandUtil
import opensamguk.logic.war.trigger.WarUnitTriggerCaller
import kotlin.math.ceil

/**
 * F3 / FP1 — port target = PHP `sammo/../process_war.php:228-502` (`processWar_NG`).
 *
 * **THE #1 PARITY RISK.** The deterministic battle phase machine as a PURE function. The WHOLE battle runs
 * on ONE [RandUtil] (`RandUtil(LiteHashDrbg(warSeed))`), built ONCE in the OUTER `processWar()` (BO1,
 * `process_war.php:11`) and threaded BY REFERENCE into every [WarUnit] + the [getNextDefender] closure.
 * **[processWarNG] NEVER re-creates or re-seeds the rng** — `warSeed` is only ECHOED into the 진격 log
 * (`:252-253`, emitted here via [WarBattleHooks.onAdvanceLog]). A single mis-ordered/extra/missing draw
 * desyncs the whole battle + conquest, so trigger order = draw order and the per-phase macro order below is
 * the load-bearing parity surface.
 *
 * **CONSUMES, never seeds.** The base-7 phase trigger seed + per-source merge is the SINGLE indivisible
 * `getBattlePhaseSkillTriggerList` op (pipeline-owned, F5 / `General.php:918-938`). F3 reaches it via the
 * [WarBattleHooks.battlePhaseCaller] / [WarBattleHooks.battleInitCaller] seam (the real B1 hooks delegate to
 * `unit.getGeneral().getBattle…SkillTriggerList(unit)`; the FP1 test injects stub callers). F3 NEVER seeds
 * named trigger classes itself.
 *
 * **Defenders are pulled LAZILY** through [getNextDefender] (not a flat list): first-contact `(null,true)`
 * (`:246`), mid-loop `(prev,true)` on a defeated defender (`:464`), terminal release `(defender,false)`
 * (`:499`). The [city] is a DISTINCT param appended to the siege only conditionally.
 *
 * PURE: no IO, no module-level state; only the passed-in [WarUnit] instances are mutated. The caller (B1)
 * emits ChangeRecorder deltas; the non-draw cross-area effects (진격/전투결과 logs, [ConflictMap.addConflict],
 * nation-rice / supply checks, addTrain / heavyDecreaseWealth bookkeeping) flow through [WarBattleHooks] so
 * the skeleton stays draw-order-pure and fully stubbable.
 *
 * @return the `conquerCity` flag — true ⇒ the OUTER `processWar()` runs `ConquerCity` (B2).
 */
fun processWarNG(
    rng: RandUtil,
    attacker: WarUnitGeneral,
    getNextDefender: (prev: WarUnit?, reqNext: Boolean) -> WarUnit?,
    city: WarUnitCity,
    hooks: WarBattleHooks = WarBattleHooks.NOOP,
): Boolean {
    // The single shared rng is built ONCE in the OUTER processWar() and threaded into every unit by
    // reference; we assert (not re-seed) that the units already hold it (the #1 parity invariant).
    require(attacker.rng === rng) { "attacker must hold the ONE shared war rng (no re-seed)" }
    require(city.rng === rng) { "city must hold the ONE shared war rng (no re-seed)" }

    // warSeed is only ECHOED to the 진격 log (process_war.php:252-253); the rng is NOT rebuilt from it here.
    hooks.onAdvanceLog(attacker, city)

    var defender: WarUnit? = getNextDefender(null, true)   // :246 — LAZY first pull
    var conquerCity = false
    var logWritten = false

    // The phase `while` loop — `process_war.php:257-470`.
    while (attacker.getPhase() < attacker.getMaxPhase()) {
        logWritten = false

        if (defender == null) {
            // No (more) general defenders → the city becomes the siege defender (`:259-263`).
            city.setSiege()
            defender = city

            // 병량 패퇴 — the supplyless capital with no rice falls without a phase (`:265-285`).
            if (hooks.defenderNationRice(city) <= 0 && hooks.citySupply(city)) {
                attacker.setOppose(defender)
                defender.setOppose(attacker)

                hooks.addTrain(attacker, 1)

                attacker.addWin()
                defender.addLose()
                hooks.heavyDecreaseWealth(city)

                hooks.onSupplyRout(attacker, city)

                conquerCity = true
                break
            }
        }

        val def = defender!!

        // --- first contact with a NEW defender: init caller fires (`:289-334`) ---
        if (def.getPhase() == 0 && def.getOppose() == null) {
            def.setPrePhase(attacker.getPhase())

            hooks.addTrain(attacker, 1)
            hooks.addTrain(def, 1)

            attacker.setOppose(def)
            def.setOppose(attacker)

            hooks.onContactLog(attacker, def)

            // initCaller = attacker.init ∪ defender.init, fired on the SHARED rng (`:331-334`).
            val initCaller: WarUnitTriggerCaller? = hooks.battleInitCaller(attacker)
            val mergedInit = initCaller ?: WarUnitTriggerCaller()
            mergedInit.merge(hooks.battleInitCaller(def))
            mergedInit.fire(rng, linkedMapOf(), listOf(attacker, def))
        }

        // --- (2)(3) beginPhase: attacker THEN defender — computeWarPower (conditional <100 floor) (`:337-338`) ---
        attacker.beginPhase()
        def.beginPhase()

        // --- (4) battle caller: PRE→BODY→POST priority-ascending, attacker-before-defender (`:340-343`) ---
        val phaseCaller: WarUnitTriggerCaller? = hooks.battlePhaseCaller(attacker)
        val mergedPhase = phaseCaller ?: WarUnitTriggerCaller()
        mergedPhase.merge(hooks.battlePhaseCaller(def))
        mergedPhase.fire(rng, linkedMapOf(), listOf(attacker, def))

        // --- (5)(6) calcDamage: attacker first, THEN defender — EXACTLY two draws (`:345-346`) ---
        var deadDefender = attacker.calcDamage().toDouble()
        var deadAttacker = def.calcDamage().toDouble()

        val attackerHP = attacker.getHP()
        val defenderHP = def.getHP()

        // --- (7) HP-ratio clamp + ceil + decreaseHP/increaseKilled — NO draws (`:347-373`) ---
        if (deadAttacker > attackerHP || deadDefender > defenderHP) {
            val deadAttackerRatio = deadAttacker / maxOf(1, attackerHP)
            val deadDefenderRatio = deadDefender / maxOf(1, defenderHP)

            // STRICT-greater tie-break: equal/less → attacker (else) branch (`:359`).
            if (deadDefenderRatio > deadAttackerRatio) {
                deadAttacker /= deadDefenderRatio
                deadDefender = defenderHP.toDouble()
            } else {
                deadDefender /= deadAttackerRatio
                deadAttacker = attackerHP.toDouble()
            }
        }

        // CEIL at the loop level — DISTINCT from the Util::round inside calcDamage (`:368-369`).
        val deadAttackerI = minOf(ceil(deadAttacker).toInt(), attackerHP)
        val deadDefenderI = minOf(ceil(deadDefender).toInt(), defenderHP)

        attacker.decreaseHP(deadAttackerI)
        def.decreaseHP(deadDefenderI)

        // killed = damage DEALT to opponent (do NOT swap) (`:372-373`).
        attacker.increaseKilled(deadDefenderI)
        def.increaseKilled(deadAttackerI)

        hooks.onPhaseLog(attacker, def, deadAttackerI, deadDefenderI)

        attacker.addPhase()
        def.addPhase()

        // --- (8)(9) continuation + the terminal tryWound sites (`:398-470`) ---
        val attackerCont = attacker.continueWar()
        if (!attackerCont.canContinue) {
            // 공격자 퇴각 (`:398-419`).
            logWritten = true
            hooks.onBattleResultLog(attacker)
            hooks.onBattleResultLog(def)

            attacker.addLose()
            def.addWin()

            attacker.tryWound()   // :407 — terminal site 1
            def.tryWound()        // :408

            hooks.onRetreatLog(attacker, def, attackerCont.noRice)
            break
        }

        val defenderCont = def.continueWar()
        if (!defenderCont.canContinue) {
            // 수비자 전멸/패퇴 (`:422-468`).
            logWritten = true
            hooks.onBattleResultLog(attacker)
            hooks.onBattleResultLog(def)

            val defIsCity = def is WarUnitCity
            val siegeWin = !defIsCity || (def as WarUnitCity).isSiege()
            if (siegeWin) {
                attacker.addWin()
                def.addLose()

                attacker.tryWound()   // :432 — terminal site 2
                def.tryWound()        // :433

                if (def === city) {
                    hooks.addLevelExp(attacker, 1000.0)
                    conquerCity = true
                    break
                }
            }

            if (defIsCity && !(def as WarUnitCity).isSiege()) {
                // 실제 공성을 위해 다시 초기화 — the non-siege city eats a hit then re-arms (`:451-453`).
                def.setOppose(null)
            } else {
                hooks.onDefenderDownLog(attacker, def, defenderCont.noRice)
            }

            if (attacker.getPhase() >= attacker.getMaxPhase()) {
                break
            }

            finishDefenderBattle(def)
            defender = getNextDefender(def, true)   // :464 — LAZY mid-loop pull

            if (defender != null && defender !is WarUnitGeneral) {
                throw IllegalStateException("다음 수비자를 받아오는데 실패")
            }
        }
    }

    // --- AFTER THE LOOP (`:472-499`) ---
    // (P4-C2) the !logWritten post-loop tryWound pair — the max-phase-exhausted catch-all, fires ONLY when no
    // in-loop branch already wrote. tryWound fires EXACTLY ONCE per battle across the three sites.
    if (!logWritten) {
        hooks.onBattleResultLog(attacker)
        defender?.let { hooks.onBattleResultLog(it) }

        attacker.tryWound()   // :477 — terminal site 3 (post-loop pair)
        defender?.tryWound()  // :478
    }

    attacker.finishBattle()
    finishDefenderBattle(defender)

    // (P4-C6) post-loop city block + addConflict (NO draws) (`:484-497`).
    if (city.getDead() > 0 || defender is WarUnitCity) {
        if (city !== defender) {
            // the WarUnitCity def/wall round on a city that was NEVER the active siege defender — do NOT omit.
            city.setOppose(attacker)
            city.setSiege()
            city.finishBattle()
        }
        val newConflict = hooks.addConflict(city, attacker)
        if (newConflict) {
            hooks.onConflictLog(attacker, city)
        }
    }

    getNextDefender(defender, false)   // :499 — terminal release (applyDB on the last defender)

    return conquerCity
}

/** Dispatch finishBattle to the concrete leaf (general vs city); a null defender (no contact) is a no-op. */
private fun finishDefenderBattle(defender: WarUnit?) {
    when (defender) {
        is WarUnitGeneral -> defender.finishBattle()
        is WarUnitCity -> defender.finishBattle()
        else -> {}
    }
}
