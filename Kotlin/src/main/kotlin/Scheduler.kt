import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.math.*
import kotlin.random.Random

enum class Rating(val value: Int) {
    Again(1), Hard(2), Good(3), Easy(4)
}

enum class State {
    Learning, Review, Relearning
}

data class ReviewedCard(
    val state: State,
    val step: Int,
    val stability: Double,
    val difficulty: Double
)

sealed class CardPhase {
    data object New : CardPhase()
    data class Reviewed(val data: ReviewedCard) : CardPhase()
}

data class Card(
    val cardId: Long,
    val interval: Duration,
    val phase: CardPhase
)

data class SchedulerConfig(
    val w: DoubleArray = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    ),
    val desiredRetention: Double = 0.9,
    val learningSteps: List<Duration> = listOf(1.minutes, 10.minutes),
    val relearningSteps: List<Duration> = listOf(10.minutes),
    val maximumInterval: Int = 36500,
    val enableFuzzing: Boolean = true
)

class Scheduler(config: SchedulerConfig, private val random: Random = Random.Default) {
    private val processedConfig: SchedulerConfig
    private val w: DoubleArray
    private val decay: Double
    private val factor: Double

    init {
        w = checkAndFillWeights(config.w)
        decay = -w[20]
        factor = 0.9.pow(1.0 / decay) - 1.0
        processedConfig = config.copy(w = w)
    }

    fun reviewCard(card: Card, rating: Rating, reviewInterval: Duration): Card {
        val nextReviewedState = calculateNextReviewedState(card, rating, reviewInterval)
        val (finalReviewedState, baseInterval) = determineNextPhaseAndInterval(
            nextReviewedState,
            rating
        )
        val finalInterval = applyFuzzing(finalReviewedState, baseInterval)
        return card.copy(interval = finalInterval, phase = CardPhase.Reviewed(finalReviewedState))
    }

    private fun calculateNextReviewedState(
        card: Card,
        rating: Rating,
        reviewInterval: Duration
    ): ReviewedCard {
        val (stability, difficulty) = when (val phase = card.phase) {
            is CardPhase.New ->
                FsrsAlgorithm.initialStability(w, rating) to FsrsAlgorithm.initialDifficulty(
                    w,
                    rating
                )

            is CardPhase.Reviewed -> {
                val data = phase.data
                val newDifficulty = FsrsAlgorithm.nextDifficulty(w, data.difficulty, rating)
                val newStability = if (reviewInterval < 1.days) {
                    FsrsAlgorithm.shortTermStability(w, data.stability, rating)
                } else {
                    val retrievability = getCardRetrievability(data, reviewInterval)
                    FsrsAlgorithm.nextStability(w, data, retrievability, rating)
                }
                newStability to newDifficulty
            }
        }
        val (state, step) = when (val phase = card.phase) {
            is CardPhase.New -> State.Learning to 0
            is CardPhase.Reviewed -> phase.data.state to phase.data.step
        }
        return ReviewedCard(state, step, stability, difficulty)
    }

    private fun getCardRetrievability(cardData: ReviewedCard, reviewInterval: Duration): Double {
        val elapsedDays = max(0.0, reviewInterval.toDouble(DurationUnit.DAYS))
        return (1.0 + factor * elapsedDays / cardData.stability).pow(decay)
    }

    private fun determineNextPhaseAndInterval(
        reviewed: ReviewedCard,
        rating: Rating
    ): Pair<ReviewedCard, Duration> {
        return when (reviewed.state) {
            State.Learning -> handleSteps(reviewed, rating, processedConfig.learningSteps)
            State.Relearning -> handleSteps(reviewed, rating, processedConfig.relearningSteps)
            State.Review -> {
                if (rating == Rating.Again && processedConfig.relearningSteps.isNotEmpty()) {
                    reviewed.copy(
                        state = State.Relearning,
                        step = 0
                    ) to processedConfig.relearningSteps.first()
                } else {
                    toReviewState(reviewed)
                }
            }
        }
    }

    private fun toReviewState(reviewed: ReviewedCard): Pair<ReviewedCard, Duration> {
        val interval = calculateNextReviewInterval(reviewed.stability)
        return reviewed.copy(state = State.Review, step = 0) to interval
    }

    private fun handleSteps(
        reviewed: ReviewedCard,
        rating: Rating,
        steps: List<Duration>
    ): Pair<ReviewedCard, Duration> {
        if (steps.isEmpty()) return toReviewState(reviewed)

        return when (rating) {
            Rating.Again -> reviewed.copy(state = State.Learning, step = 0) to steps.first()
            Rating.Hard -> reviewed to hardIntervalStep(reviewed.step, steps)
            Rating.Good -> {
                val nextStep = reviewed.step + 1
                if (nextStep >= steps.size) {
                    toReviewState(reviewed)
                } else {
                    reviewed.copy(state = State.Learning, step = nextStep) to steps[nextStep]
                }
            }

            Rating.Easy -> toReviewState(reviewed)
        }
    }

    private fun hardIntervalStep(currentStep: Int, steps: List<Duration>): Duration {
        return when {
            currentStep == 0 && steps.size == 1 -> steps.first() * 1.5
            currentStep == 0 && steps.size >= 2 -> (steps[0] + steps[1]) / 2.0
            else -> steps[currentStep]
        }
    }

    internal fun calculateNextReviewInterval(stability: Double): Duration {
        return FsrsAlgorithm.nextInterval(
            factor,
            processedConfig.desiredRetention,
            decay,
            processedConfig.maximumInterval,
            stability
        )
    }

    private fun applyFuzzing(reviewed: ReviewedCard, interval: Duration): Duration {
        return if (processedConfig.enableFuzzing && reviewed.state == State.Review) {
            getFuzzedInterval(interval)
        } else {
            interval
        }
    }

    private fun getFuzzedInterval(interval: Duration): Duration {
        data class FuzzRange(val start: Double, val end: Double, val factor: Double)

        val fuzzRanges = listOf(
            FuzzRange(2.5, 7.0, 0.15),
            FuzzRange(7.0, 20.0, 0.1),
            FuzzRange(20.0, Double.POSITIVE_INFINITY, 0.05)
        )

        val intervalDays = interval.toDouble(DurationUnit.DAYS)
        if (intervalDays < 2.5) return interval

        val delta = fuzzRanges.sumOf { range ->
            range.factor * max(0.0, min(intervalDays, range.end) - range.start)
        }

        val minIvl = max(2, (intervalDays - delta).roundToInt())
        val maxIvl = (intervalDays + delta).roundToInt()
        val fuzzedDays = random.nextInt(minIvl, maxIvl + 1)
            .coerceAtMost(processedConfig.maximumInterval)
        return fuzzedDays.days
    }

    private fun checkAndFillWeights(w: DoubleArray): DoubleArray {
        require(w.size in listOf(17, 19, 21)) { "Invalid number of w. Supported: 17, 19, or 21." }
        require(w.all { it.isFinite() }) { "Invalid w: contains non-finite values." }

        val fsrs5DefaultDecay = 0.5
        return when (w.size) {
            17 -> {
                val p = w.copyOf()
                p[4] = p[5] * 2.0 + p[4]
                p[5] = ln(p[5] * 3.0 + 1.0) / 3.0
                p[6] = p[6] + 0.5
                p + doubleArrayOf(0.0, 0.0, 0.0, fsrs5DefaultDecay)
            }

            19 -> w + doubleArrayOf(0.0, fsrs5DefaultDecay)
            else -> w
        }
    }
}

private object FsrsAlgorithm {
    private const val MIN_DIFFICULTY = 1.0
    private const val MAX_DIFFICULTY = 10.0
    private const val STABILITY_MIN = 0.001

    private fun clampDifficulty(difficulty: Double): Double =
        difficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)

    private fun clampStability(stability: Double): Double = max(stability, STABILITY_MIN)

    fun initialStability(w: DoubleArray, rating: Rating): Double =
        clampStability(w[rating.value - 1])

    fun initialDifficulty(w: DoubleArray, rating: Rating): Double =
        clampDifficulty(rawInitialDifficulty(w, rating))

    private fun rawInitialDifficulty(w: DoubleArray, rating: Rating): Double {
        val ratingValue = rating.value.toDouble()
        return w[4] - (exp(w[5] * (ratingValue - 1.0))) + 1.0
    }

    fun nextInterval(
        factor: Double,
        retention: Double,
        decay: Double,
        maxInterval: Int,
        stability: Double
    ): Duration {
        val days = (stability / factor) * (retention.pow(1.0 / decay) - 1.0)
        return days.roundToInt().coerceIn(1, maxInterval).days
    }

    fun nextDifficulty(w: DoubleArray, difficulty: Double, rating: Rating): Double {
        val ratingValue = rating.value.toDouble()
        val deltaDifficulty = -(w[6] * (ratingValue - 3.0))
        val dampedDelta = (MAX_DIFFICULTY - difficulty) * deltaDifficulty /
                (MAX_DIFFICULTY - MIN_DIFFICULTY)
        val initialEasyDifficulty = rawInitialDifficulty(w, Rating.Easy)

        val newDifficulty = w[7] * initialEasyDifficulty +
                (1.0 - w[7]) * (difficulty + dampedDelta)
        return clampDifficulty(newDifficulty)
    }

    fun nextStability(
        w: DoubleArray,
        data: ReviewedCard,
        retrievability: Double,
        rating: Rating
    ): Double {
        return clampStability(
            when (rating) {
                Rating.Again -> nextForgetStability(w, data, retrievability)
                else -> nextRecallStability(w, data, retrievability, rating)
            }
        )
    }

    fun shortTermStability(w: DoubleArray, stability: Double, rating: Rating): Double {
        val ratingValue = rating.value.toDouble()
        val increase = exp(w[17] * (ratingValue - 3.0 + w[18])) * stability.pow(-w[19])
        val finalIncrease =
            if (rating == Rating.Good || rating == Rating.Easy) max(increase, 1.0) else increase
        return clampStability(stability * finalIncrease)
    }

    private fun nextForgetStability(
        w: DoubleArray,
        data: ReviewedCard,
        retrievability: Double
    ): Double {
        val longTerm = w[11] * data.difficulty.pow(-w[12]) *
                ((data.stability + 1.0).pow(w[13]) - 1.0) *
                exp((1.0 - retrievability) * w[14])
        val shortTerm = data.stability / exp(w[17] * w[18])
        return min(longTerm, shortTerm)
    }

    private fun nextRecallStability(
        w: DoubleArray,
        data: ReviewedCard,
        retrievability: Double,
        rating: Rating
    ): Double {
        val hardPenalty = if (rating == Rating.Hard) w[15] else 1.0
        val easyBonus = if (rating == Rating.Easy) w[16] else 1.0

        val difficultyWeight = (11.0 - data.difficulty)
        val stabilityDecay = data.stability.pow(-w[9])
        val memoryFactor = exp((1.0 - retrievability) * w[10]) - 1.0
        val recallFactor = exp(w[8])

        val stabilityIncrease = recallFactor *
                difficultyWeight *
                stabilityDecay *
                memoryFactor *
                hardPenalty *
                easyBonus

        return data.stability * (1.0 + stabilityIncrease)
    }
}
