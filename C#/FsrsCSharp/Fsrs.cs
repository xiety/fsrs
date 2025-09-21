namespace FsrsCSharp;

public class Scheduler
{
    readonly SchedulerConfig config;
    readonly Random random;
    readonly double[] w;
    readonly double decay;
    readonly double factor;

    public Scheduler(SchedulerConfig config, Random random)
    {
        this.config = config;
        this.random = random;
        w = CheckAndFillParameters(config.Parameters);
        decay = -w[20];
        factor = Math.Pow(0.9, 1.0 / decay) - 1.0;
    }

    public Card ReviewCard(Card card, Rating rating, TimeSpan reviewInterval)
    {
        var reviewedCard = CalculateInitialReviewedCard(card, rating, reviewInterval);
        var cardWithNextState = DetermineNextPhaseAndInterval(reviewedCard, rating);
        var finalCard = ApplyFuzzing(cardWithNextState);

        return finalCard;
    }

    Card CalculateInitialReviewedCard(Card card, Rating rating, TimeSpan reviewInterval)
    {
        if (card.State == State.New)
        {
            var stability = FsrsAlgorithm.InitialStability(w, rating);
            var difficulty = FsrsAlgorithm.InitialDifficulty(w, rating);
            return card with { Stability = stability, Difficulty = difficulty, State = State.Learning, Step = 0 };
        }

        var newDifficulty = FsrsAlgorithm.NextDifficulty(w, card.Difficulty, rating);
        var newStability = reviewInterval.TotalDays < 1.0
            ? FsrsAlgorithm.ShortTermStability(w, card.Stability, rating)
            : GetLongTermStability(card, rating, reviewInterval);

        return card with { Stability = newStability, Difficulty = newDifficulty };
    }

    double GetLongTermStability(Card card, Rating rating, TimeSpan reviewInterval)
    {
        var elapsedDays = Math.Max(0.0, reviewInterval.TotalDays);
        var retrievability = Math.Pow(1.0 + factor * elapsedDays / card.Stability, decay);
        return FsrsAlgorithm.NextStability(w, card.Difficulty, card.Stability, retrievability, rating);
    }

    Card DetermineNextPhaseAndInterval(Card reviewedCard, Rating rating) =>
        reviewedCard.State switch
        {
            State.Learning => HandleSteps(reviewedCard, rating, config.LearningSteps),
            State.Relearning => HandleSteps(reviewedCard, rating, config.RelearningSteps),
            State.Review when rating == Rating.Again && config.RelearningSteps.Any()
                => reviewedCard with { State = State.Relearning, Step = 0, Interval = config.RelearningSteps[0] },
            State.Review => ToReviewState(reviewedCard)
        };

    Card HandleSteps(Card card, Rating rating, TimeSpan[] steps)
    {
        if (!steps.Any())
            return ToReviewState(card);

        return rating switch
        {
            Rating.Again => card with { State = State.Learning, Step = 0, Interval = steps[0] },
            Rating.Hard => card with { State = State.Learning, Interval = HardIntervalStep(card.Step, steps) },
            Rating.Good when card.Step + 1 >= steps.Length => ToReviewState(card),
            Rating.Good => card with { State = State.Learning, Step = card.Step + 1, Interval = steps[card.Step + 1] },
            Rating.Easy => ToReviewState(card)
        };
    }

    Card ToReviewState(Card card)
    {
        var interval = CalculateNextReviewInterval(card.Stability);
        return card with { State = State.Review, Step = 0, Interval = interval };
    }

    internal TimeSpan CalculateNextReviewInterval(double stability) =>
        FsrsAlgorithm.NextInterval(factor, config.DesiredRetention, decay, config.MaximumInterval, stability);

    Card ApplyFuzzing(Card card)
    {
        if (config.EnableFuzzing && card.State == State.Review)
        {
            var fuzzedInterval = GetFuzzedInterval(random, config.MaximumInterval, card.Interval);
            return card with { Interval = fuzzedInterval };
        }

        return card;
    }

    public static TimeSpan GetFuzzedInterval(Random rand, int maxInterval, TimeSpan interval)
    {
        if (interval.TotalDays < 2.5)
            return interval;

        var delta = new[] {
                (Start: 2.5, End: 7.0, Factor: 0.15),
                (Start: 7.0, End: 20.0, Factor: 0.1),
                (Start: 20.0, End: double.PositiveInfinity, Factor: 0.05)
            }.Sum(r => r.Factor * Math.Max(0.0, Math.Min(interval.TotalDays, r.End) - r.Start));

        var fuzzed = rand.Next((int)Math.Round(interval.TotalDays - delta), (int)Math.Round(interval.TotalDays + delta) + 1);
        return TimeSpan.FromDays(Math.Min(Math.Max(2, fuzzed), maxInterval));
    }

    public static TimeSpan HardIntervalStep(int currentStep, TimeSpan[] steps) =>
        (currentStep, steps) switch
        {
            (0, [var item1]) => TimeSpan.FromMinutes(item1.TotalMinutes * 1.5),
            (0, [var item1, var item2, ..]) => TimeSpan.FromMinutes((item1.TotalMinutes + item2.TotalMinutes) / 2.0),
            _ => steps[currentStep]
        };

    static double[] CheckAndFillParameters(double[] w)
    {
        if (w.Any(p => !double.IsFinite(p)))
            throw new ArgumentException("Invalid parameters: contains non-finite values.");

        return w.Length switch
        {
            17 => [.. w, 0.0, 0.0, 0.0, 0.5],
            19 => [.. w, 0.0, 0.5],
            21 => w,
            _ => throw new ArgumentException("Invalid number of parameters. Supported: 17, 19, or 21.")
        };
    }
}

static class FsrsAlgorithm
{
    const double MinDifficulty = 1.0;
    const double MaxDifficulty = 10.0;
    const double StabilityMin = 0.001;

    static double ClampDifficulty(double d) => Math.Clamp(d, MinDifficulty, MaxDifficulty);
    static double ClampStability(double s) => Math.Max(s, StabilityMin);

    static double RawInitialDifficulty(double[] w, Rating r) =>
        w[4] - Math.Exp(w[5] * ((double)r - 1.0)) + 1.0;

    public static double InitialStability(double[] w, Rating r) =>
        ClampStability(w[(int)r - 1]);

    public static double InitialDifficulty(double[] w, Rating r) =>
        ClampDifficulty(RawInitialDifficulty(w, r));

    public static TimeSpan NextInterval(double factor, double retention, double decay, int maxInterval, double stability)
    {
        var interval = stability / factor * (Math.Pow(retention, 1.0 / decay) - 1.0);
        return TimeSpan.FromDays(Math.Min(maxInterval, Math.Max(1, (int)Math.Round(interval))));
    }

    public static double ShortTermStability(double[] w, double stability, Rating rating)
    {
        var increase = Math.Exp(w[17] * ((double)rating - 3.0 + w[18])) * Math.Pow(stability, -w[19]);
        var finalIncrease = rating is Rating.Good or Rating.Easy ? Math.Max(increase, 1.0) : increase;
        return ClampStability(stability * finalIncrease);
    }

    public static double NextDifficulty(double[] w, double d, Rating r)
    {
        var delta = -(w[6] * ((double)r - 3.0));
        var damped = (MaxDifficulty - d) * delta / (MaxDifficulty - MinDifficulty);
        return ClampDifficulty(w[7] * RawInitialDifficulty(w, Rating.Easy) + (1.0 - w[7]) * (d + damped));
    }

    public static double NextStability(double[] w, double difficulty, double stability, double retrievability, Rating r)
    {
        var next = (r == Rating.Again)
            ? w[11] * Math.Pow(difficulty, -w[12])
                    * (Math.Pow(stability + 1.0, w[13]) - 1.0)
                    * Math.Exp((1.0 - retrievability) * w[14])
            : CalculateRecallStability(w, difficulty, stability, retrievability, r);

        return ClampStability(next);
    }

    static double CalculateRecallStability(double[] w, double difficulty, double stability, double retrievability, Rating r)
    {
        var recallFactor = Math.Exp(w[8]);
        var difficultyWeight = 11.0 - difficulty;
        var stabilityDecay = Math.Pow(stability, -w[9]);
        var memoryFactor = Math.Exp((1.0 - retrievability) * w[10]) - 1.0;

        var hardPenalty = r == Rating.Hard ? w[15] : 1.0;
        var easyBonus = r == Rating.Easy ? w[16] : 1.0;

        var stabilityIncrease = recallFactor
                              * difficultyWeight
                              * stabilityDecay
                              * memoryFactor
                              * hardPenalty
                              * easyBonus;

        return stability * (1.0 + stabilityIncrease);
    }
}

public record SchedulerConfig(
    double[] Parameters,
    double DesiredRetention,
    TimeSpan[] LearningSteps,
    TimeSpan[] RelearningSteps,
    int MaximumInterval,
    bool EnableFuzzing)
{
    public static readonly SchedulerConfig Default = new(
        Parameters: [0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
                     1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542],
        DesiredRetention: 0.9,
        LearningSteps: [TimeSpan.FromMinutes(1.0), TimeSpan.FromMinutes(10.0)],
        RelearningSteps: [TimeSpan.FromMinutes(10.0)],
        MaximumInterval: 36500,
        EnableFuzzing: true
    );
}

public enum Rating { Again = 1, Hard, Good, Easy }
public enum State { New, Learning, Review, Relearning }

public record Card(long CardId, TimeSpan Interval, double Stability, double Difficulty, State State, int Step)
{
    public static Card Create(long cardId) => new(cardId, TimeSpan.Zero, 0, 0, State.New, 0);
}
