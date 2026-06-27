package opensamguk.engine.config

import opensamguk.logic.tick.CheckStatistic
import opensamguk.logic.tick.EventDispatcher
import opensamguk.logic.tick.GameDate
import opensamguk.logic.tick.MonthlyClock
import opensamguk.logic.tick.MonthlyPipeline
import opensamguk.logic.tick.MonthlyRngFactory
import opensamguk.logic.tick.PostUpdateMonthly
import opensamguk.logic.tick.PreUpdateMonthly
import org.springframework.aop.TargetSource
import org.springframework.aop.framework.ProxyFactory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression — the turn-daemon **clock-freeze** prod incident (2026-06-05).
 *
 * [opensamguk.engine.config.EngineEventConfig] exposes [MonthlyPipeline] as a `@Bean @Lazy`, so Spring
 * builds a CGLIB **class** proxy. The proxy shell is instantiated via Objenesis (the 5-arg constructor is
 * skipped) → ALL of its fields, including `monthlyRngFactory`, are `null` on the shell. CGLIB delegates an
 * *intercepted* (open) method to the lazily-resolved real bean; but a **final** method cannot be overridden,
 * so calling it runs the inherited final body **on the null-field proxy shell** → `monthlyRngFactory` is
 * null → NPE at `MonthlyPipeline.runMonth` (L4) on EVERY tick → `world_state` never advances (health stays
 * green while the world clock is frozen — the silent-freeze failure mode).
 *
 * The class was already `open` (PR#18) but `runMonth` was left final — this test reproduces the exact lazy
 * CGLIB proxy and asserts `runMonth` delegates to the real bean (i.e. stays `open`). A plain unit test that
 * constructs [MonthlyPipeline] directly CANNOT catch this (no proxy, non-null fields) — which is why 2406
 * green tests missed it.
 */
class MonthlyPipelineLazyProxyTest {

    @Test
    fun `runMonth delegates through a Spring lazy CGLIB class proxy (must stay open)`() {
        var reachedRealBean = false
        val real = MonthlyPipeline<Unit>(
            monthlyRngFactory = MonthlyRngFactory { _, _ -> },              // non-null ONLY on the real bean
            clock = MonthlyClock { _, _ -> GameDate(200, 1, 1) },
            // L6 aborts right after L4 (rng) + L5 (PreMonth) — proving execution reached the real bean.
            preUpdateMonthly = PreUpdateMonthly { reachedRealBean = true; false },
            checkStatistic = CheckStatistic { },
            postUpdateMonthly = PostUpdateMonthly { },
        )

        // Mirror Spring @Lazy: a CGLIB class proxy (proxyTargetClass=true) whose own fields are null,
        // delegating intercepted methods to the lazily-resolved real target.
        val proxy = ProxyFactory().apply {
            setProxyTargetClass(true)
            targetSource = object : TargetSource {
                override fun getTargetClass(): Class<*> = MonthlyPipeline::class.java
                override fun isStatic(): Boolean = false
                override fun getTarget(): Any = real
                override fun releaseTarget(target: Any) {}
            }
        }.proxy.let {
            @Suppress("UNCHECKED_CAST")
            it as MonthlyPipeline<Unit>
        }

        // FINAL runMonth → CGLIB can't intercept → runs on the null-field shell → NPE (the prod freeze).
        // OPEN  runMonth → CGLIB delegates to `real` (non-null factory) → preUpdateMonthly aborts cleanly.
        proxy.runMonth(
            nextTurn = Instant.EPOCH,
            startYear = 184,
            startTime = Instant.EPOCH,
            turnTerm = 60,
            oldYear = 199,
            oldMonth = 12,
            oldPhase = 3,
            dispatcher = EventDispatcher { _, _ -> },
        )

        assertTrue(reachedRealBean, "runMonth must delegate to the real bean through the @Lazy CGLIB proxy")
    }
}
