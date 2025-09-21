import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BasicTests {

    private val random = Random(0)

    private fun getReviewedData(card: Card): ReviewedCard {
        return (card.phase as? CardPhase.Reviewed)?.data
            ?: throw IllegalStateException("Expected card to be in a Reviewed state, but it was New.")
    }

    private fun createTestCard() = Card(1L, Duration.ZERO, CardPhase.New)

    private fun createDefaultScheduler() = Scheduler(SchedulerConfig(), random)

    private fun runReviews(scheduler: Scheduler, reviews: List<Pair<Rating, Int>>): Card {
        return reviews.fold(createTestCard()) { card, (rating, interval) ->
            scheduler.reviewCard(card, rating, interval.days)
        }
    }

    private fun checkStabilityAndDifficulty(
        expectedStability: Double,
        expectedDifficulty: Double,
        card: Card
    ) {
        val data = getReviewedData(card)
        assertEquals(expectedStability, data.stability, 1e-4)
        assertEquals(expectedDifficulty, data.difficulty, 1e-4)
    }

    private fun changeCard(
        interval: Int,
        stability: Double,
        difficulty: Double,
        card: Card
    ): Card {
        val reviewedPhase = card.phase as? CardPhase.Reviewed
            ?: throw IllegalStateException("Cannot modify a card that is not in the Reviewed phase.")

        return card.copy(
            interval = interval.days,
            phase = reviewedPhase.copy(
                data = reviewedPhase.data.copy(stability = stability, difficulty = difficulty)
            )
        )
    }

    @Test
    fun `Test Next Interval`() {
        val desiredRetentions = (1..10).map { it / 10.0 }
        val config = SchedulerConfig(
            learningSteps = emptyList(),
            enableFuzzing = false,
            maximumInterval = Int.MAX_VALUE
        )

        val actual = desiredRetentions.map { r ->
            val scheduler = Scheduler(config.copy(desiredRetention = r), random)
            val interval = scheduler.calculateNextReviewInterval(1.0)
            interval.inWholeDays.toInt()
        }
        assertEquals(listOf(3116769, 34793, 2508, 387, 90, 27, 9, 3, 1, 1), actual)
    }

    @Test
    fun `Test FSRS`() {
        val config = SchedulerConfig(
            learningSteps = emptyList(),
            relearningSteps = emptyList(),
            enableFuzzing = false
        )
        val scheduler = Scheduler(config, random)
        val initialCard = createTestCard()
        val ratings = listOf(
            Rating.Again, Rating.Good, Rating.Good, Rating.Good, Rating.Good, Rating.Good
        )

        val (_, actualIntervals) = ratings.fold(initialCard to emptyList<Int>()) { (card, intervals), rating ->
            val updatedCard = scheduler.reviewCard(card, rating, card.interval)
            updatedCard to intervals + updatedCard.interval.inWholeDays.toInt()
        }

        assertEquals(listOf(1, 2, 6, 17, 44, 102), actualIntervals)
    }

    @Test
    fun `Test Memo State`() {
        val w = doubleArrayOf(
            0.6845422, 1.6790825, 4.7349424, 10.042885, 7.4410233, 0.64219797,
            1.071918, 0.0025195254, 1.432437, 0.1544, 0.8692766, 2.0696752,
            0.0953, 0.2975, 2.4691248, 0.19542035, 3.201072, 0.18046261, 0.121442534
        )
        val scheduler = Scheduler(SchedulerConfig(w = w), random)
        val reviews = listOf(
            Rating.Again to 0,
            Rating.Good to 1,
            Rating.Good to 3,
            Rating.Good to 8,
            Rating.Good to 21
        )

        val finalCard1 = runReviews(scheduler, reviews)
        checkStabilityAndDifficulty(31.722992, 7.382128, finalCard1)

        val cardMod = changeCard(21, 20.925528, 7.005062, finalCard1)
        val finalCard2 = scheduler.reviewCard(cardMod, Rating.Good, cardMod.interval)
        checkStabilityAndDifficulty(40.87456, 6.9913807, finalCard2)
    }

    @Test
    fun `Test Memory State`() {
        val scheduler = createDefaultScheduler()
        val reviews = listOf(
            Rating.Again to 0,
            Rating.Good to 0,
            Rating.Good to 1,
            Rating.Good to 3,
            Rating.Good to 8,
            Rating.Good to 21
        )

        val finalCard1 = runReviews(scheduler, reviews)
        checkStabilityAndDifficulty(53.62691, 6.3574867, finalCard1)

        val w2 = SchedulerConfig().w.copyOf()
        for (i in 17..19) {
            w2[i] = 0.0
        }
        val scheduler2 = Scheduler(SchedulerConfig(w = w2), random)

        val finalCard2 = runReviews(scheduler2, reviews)
        checkStabilityAndDifficulty(53.335106, 6.3574867, finalCard2)
    }

    @Test
    fun `Test Good Learning Steps`() {
        val scheduler = createDefaultScheduler()
        val card = createTestCard()

        val cardAfterGood1 = scheduler.reviewCard(card, Rating.Good, card.interval)
        val data1 = getReviewedData(cardAfterGood1)
        assertEquals(State.Learning, data1.state)
        assertEquals(1, data1.step)
        assertEquals(10.0, cardAfterGood1.interval.inWholeMinutes.toDouble(), 1.0 / 60.0)

        val cardAfterGood2 =
            scheduler.reviewCard(cardAfterGood1, Rating.Good, cardAfterGood1.interval)
        val data2 = getReviewedData(cardAfterGood2)
        assertEquals(State.Review, data2.state)
        assertTrue(cardAfterGood2.interval.inWholeDays >= 1)
    }

    @Test
    fun `Test Again Learning Steps`() {
        val scheduler = createDefaultScheduler()
        val card = createTestCard()
        val cardAfterAgain = scheduler.reviewCard(card, Rating.Again, card.interval)
        val data = getReviewedData(cardAfterAgain)
        assertEquals(State.Learning, data.state)
        assertEquals(0, data.step)
        assertEquals(1.0, cardAfterAgain.interval.inWholeMinutes.toDouble(), 1.0 / 60.0)
    }

    @Test
    fun `Test Learning Card Rate Hard One Learning Step`() {
        val config = SchedulerConfig(learningSteps = listOf(10.minutes))
        val scheduler = Scheduler(config, random)
        val card = createTestCard()
        val cardAfterHard = scheduler.reviewCard(card, Rating.Hard, card.interval)

        val expectedInterval = 10.minutes * 1.5
        assertTrue(
            (cardAfterHard.interval - expectedInterval).absoluteValue < 1.seconds
        )
    }

    @Test
    fun `Test No Learning Steps`() {
        val config = SchedulerConfig(learningSteps = emptyList())
        val scheduler = Scheduler(config, random)
        val card = createTestCard()
        val updatedCard = scheduler.reviewCard(card, Rating.Again, card.interval)
        val updatedData = getReviewedData(updatedCard)
        assertEquals(State.Review, updatedData.state)
        assertTrue(updatedCard.interval.inWholeDays >= 1)
    }

    @Test
    fun `Test Maximum Interval`() {
        val config = SchedulerConfig(maximumInterval = 100)
        val scheduler = Scheduler(config, random)
        val card = createTestCard()

        val finalCard = (1..10).fold(card) { currentCard, _ ->
            scheduler.reviewCard(currentCard, Rating.Easy, currentCard.interval)
        }
        assertTrue(finalCard.interval.inWholeDays <= config.maximumInterval)
    }

    @Test
    fun `Test Stability Lower Bound`() {
        val scheduler = createDefaultScheduler()
        val stabilityMin = 0.001
        val card = createTestCard()

        (1..100).fold(card) { currentCard, _ ->
            val nextReviewTime = currentCard.interval + 1.days
            val updatedCard = scheduler.reviewCard(currentCard, Rating.Again, nextReviewTime)
            val updatedData = getReviewedData(updatedCard)
            assertTrue(updatedData.stability >= stabilityMin)
            updatedCard
        }
    }
}
