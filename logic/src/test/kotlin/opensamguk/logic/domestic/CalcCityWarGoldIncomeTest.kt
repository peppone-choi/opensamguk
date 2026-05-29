package opensamguk.logic.domestic

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port-faithful tests for `calcCityWarGoldIncome` (P3 / AREA A1 / Task IN1) — research Unit 2.
 *
 * PHP grand truth (func_time_event.php:78-86):
 * ```
 * function calcCityWarGoldIncome(array $rawCity, iAction $nationType):int{
 *     if($rawCity['supply'] == 0){ return 0; }
 *     $warIncome = $rawCity['dead'] / 10;
 *     $warIncome = Util::round($nationType->onCalcNationalIncome('gold', $warIncome));
 *     return $warIncome;
 * }
 * ```
 * `supply==0 → 0` (no fold); else `phpRound(nationTypeFold('gold', dead/10.0))`. The fold runs over the
 * NATION-TYPE source ONLY ([GeneralActionPipeline.nationIncomeFold]); a null nation type folds as identity.
 * `Util::round` = [phpRound] (half-away-from-zero). This is the per-city war-gold term ProcessWarIncome sums.
 */
class CalcCityWarGoldIncomeTest {

    private val pipeline = GeneralActionPipeline(emptyList())

    /** A nation-type income module that scales 'gold' by 1.1 (to assert the income fold is applied). */
    private val goldBuffNationType = object : GeneralActionModule {
        override fun onCalcNationalIncome(general: General, type: String, value: Double): Double =
            if (type == "gold") value * 1.1 else value
    }

    private fun city(supply: Int = 1, dead: Int = 0) = City(
        id = 1, nationId = 1, level = 5,
        commerce = 0, commerceMax = 1, agriculture = 0, agricultureMax = 1,
        supplyState = supply, frontState = 0, trust = 0.0,
        security = 0, securityMax = 1, defense = 0, wall = 0, wallMax = 1,
        population = 0, populationMax = 1, dead = dead,
    )

    @Test
    fun `unsupplied city yields zero war gold (no fold)`() {
        // supply==0 short-circuits BEFORE the fold — even a non-identity nation type must yield 0.
        assertEquals(0, calcCityWarGoldIncome(city(supply = 0, dead = 1000), null, pipeline))
        assertEquals(0, calcCityWarGoldIncome(city(supply = 0, dead = 1000), goldBuffNationType, pipeline))
    }

    @Test
    fun `supplied city with null nation type folds as identity`() {
        // dead=1000 → 1000/10 = 100.0 → phpRound(identity(100.0)) = 100
        assertEquals(100, calcCityWarGoldIncome(city(supply = 1, dead = 1000), null, pipeline))
    }

    @Test
    fun `supplied city applies the nation-type gold fold then rounds half-away`() {
        // dead=105 → 105/10 = 10.5 → fold *1.1 = 11.55 → phpRound(11.55) = 12 (half-away).
        val expected = phpRound(10.5 * 1.1)
        assertEquals(12, expected)
        assertEquals(expected, calcCityWarGoldIncome(city(supply = 1, dead = 105), goldBuffNationType, pipeline))
    }

    @Test
    fun `the dead-over-ten term rounds half-away from zero`() {
        // dead=5 → 0.5 → phpRound(0.5) = 1 (NOT banker's round-to-0). Identity fold.
        assertEquals(1, calcCityWarGoldIncome(city(supply = 1, dead = 5), null, pipeline))
        // dead=15 → 1.5 → phpRound(1.5) = 2.
        assertEquals(2, calcCityWarGoldIncome(city(supply = 1, dead = 15), null, pipeline))
    }
}
