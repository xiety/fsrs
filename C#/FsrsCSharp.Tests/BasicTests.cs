namespace FsrsCSharp.Tests;

[TestClass]
public class BasicTests
{
    readonly Random rand = new();

    Scheduler CreateDefaultScheduler() => new(SchedulerConfig.Default, rand);

    [TestMethod]
    public void TestNextInterval()
    {
        var desiredRetentions = Enumerable.Range(1, 10).Select(i => i / 10.0).ToArray();
        var expected = new[] { 3116769, 34793, 2508, 387, 90, 27, 9, 3, 1, 1 };

        var config = SchedulerConfig.Default with { LearningSteps = [], EnableFuzzing = false, MaximumInterval = int.MaxValue };

        var actual = desiredRetentions.Select(r =>
        {
            var scheduler = new Scheduler(config with { DesiredRetention = r }, rand);
            var interval = scheduler.CalculateNextReviewInterval(1.0);
            return (int)interval.TotalDays;
        }).ToArray();

        CollectionAssert.AreEqual(expected, actual);
    }

    [TestMethod]
    public void TestFsrs()
    {
        var config = SchedulerConfig.Default with { LearningSteps = [], RelearningSteps = [], EnableFuzzing = false };
        var scheduler = new Scheduler(config, rand);
        var card = Card.Create(1L);
        var ratings = new[] { Rating.Again, Rating.Good, Rating.Good, Rating.Good, Rating.Good, Rating.Good };
        var actualIntervals = new List<int>();

        foreach (var rating in ratings)
        {
            var reviewInterval = card.Interval;
            card = scheduler.ReviewCard(card, rating, reviewInterval);
            actualIntervals.Add((int)card.Interval.TotalDays);
        }

        CollectionAssert.AreEqual(new[] { 1, 2, 6, 17, 44, 102 }, actualIntervals);
    }

    [TestMethod]
    public void TestMemoState()
    {
        var parameters = new[] { 0.6845422, 1.6790825, 4.7349424, 10.042885, 7.4410233, 0.64219797, 1.071918, 0.0025195254, 1.432437,
                                 0.1544, 0.8692766, 2.0696752, 0.0953, 0.2975, 2.4691248, 0.19542035, 3.201072, 0.18046261, 0.121442534 };
        var scheduler = new Scheduler(SchedulerConfig.Default with { Parameters = parameters }, rand);
        var reviews = new[] { (Rating.Again, 0), (Rating.Good, 1), (Rating.Good, 3), (Rating.Good, 8), (Rating.Good, 21) };

        var finalCard1 = RunReviews(scheduler, reviews);
        CheckStabilityAndDifficulty(31.722992, 7.382128, finalCard1);

        var cardMod = new Card(1L, TimeSpan.FromDays(21), 20.925528, 7.005062, State.Review, 0);
        var finalCard2 = scheduler.ReviewCard(cardMod, Rating.Good, cardMod.Interval);

        CheckStabilityAndDifficulty(40.87456, 6.9913807, finalCard2);
    }

    [TestMethod]
    public void TestMemoryState()
    {
        var scheduler = CreateDefaultScheduler();
        var reviews = new[] { (Rating.Again, 0), (Rating.Good, 0), (Rating.Good, 1), (Rating.Good, 3), (Rating.Good, 8), (Rating.Good, 21) };

        var finalCard1 = RunReviews(scheduler, reviews);
        CheckStabilityAndDifficulty(53.62691, 6.3574867, finalCard1);

        var parameters2 = (double[])SchedulerConfig.Default.Parameters.Clone();
        parameters2[17] = 0.0;
        parameters2[18] = 0.0;
        parameters2[19] = 0.0;

        var scheduler2 = new Scheduler(SchedulerConfig.Default with { Parameters = parameters2 }, rand);

        var finalCard2 = RunReviews(scheduler2, reviews);
        CheckStabilityAndDifficulty(53.335106, 6.3574867, finalCard2);
    }

    [TestMethod]
    public void TestGoodLearningSteps()
    {
        var scheduler = CreateDefaultScheduler();
        var card = Card.Create(1L);
        Assert.AreEqual(State.New, card.State);

        card = scheduler.ReviewCard(card, Rating.Good, card.Interval);
        Assert.AreEqual(State.Learning, card.State);
        Assert.AreEqual(1, card.Step);
        Assert.AreEqual(10.0, card.Interval.TotalMinutes, 1.0 / 60.0);

        card = scheduler.ReviewCard(card, Rating.Good, card.Interval);
        Assert.AreEqual(State.Review, card.State);
        Assert.IsTrue(card.Interval.TotalDays >= 1.0);
    }

    [TestMethod]
    public void TestAgainLearningSteps()
    {
        var scheduler = CreateDefaultScheduler();
        var card = Card.Create(1L);
        card = scheduler.ReviewCard(card, Rating.Again, card.Interval);

        Assert.AreEqual(State.Learning, card.State);
        Assert.AreEqual(0, card.Step);
        Assert.AreEqual(1.0, card.Interval.TotalMinutes, 1.0 / 60.0);
    }

    [TestMethod]
    public void TestLearningCardRateHardOneLearningStep()
    {
        var config = SchedulerConfig.Default with { LearningSteps = [TimeSpan.FromMinutes(10.0)] };
        var scheduler = new Scheduler(config, rand);
        var card = Card.Create(1L);
        card = scheduler.ReviewCard(card, Rating.Hard, card.Interval);

        var expectedInterval = TimeSpan.FromMinutes(10.0 * 1.5);
        Assert.IsTrue(Math.Abs((card.Interval - expectedInterval).TotalSeconds) <= 1.0);
    }

    [TestMethod]
    public void TestNoLearningSteps()
    {
        var config = SchedulerConfig.Default with { LearningSteps = [] };
        var scheduler = new Scheduler(config, rand);
        var card = Card.Create(1L);
        card = scheduler.ReviewCard(card, Rating.Again, card.Interval);

        Assert.AreEqual(State.Review, card.State);
        Assert.IsTrue(card.Interval.TotalDays >= 1.0);
    }

    [TestMethod]
    public void TestMaximumInterval()
    {
        var config = SchedulerConfig.Default with { MaximumInterval = 100 };
        var scheduler = new Scheduler(config, rand);
        var card = Card.Create(1L);

        for (var i = 0; i < 10; i++)
            card = scheduler.ReviewCard(card, Rating.Easy, card.Interval);

        Assert.IsTrue(card.Interval.Days <= config.MaximumInterval);
    }

    [TestMethod]
    public void TestStabilityLowerBound()
    {
        var scheduler = CreateDefaultScheduler();
        const double stabilityMin = 0.001;
        var card = Card.Create(1L);

        for (var i = 0; i < 100; i++)
        {
            var nextReviewTime = card.Interval.Add(TimeSpan.FromDays(1.0));
            card = scheduler.ReviewCard(card, Rating.Again, nextReviewTime);
            Assert.IsTrue(card.Stability >= stabilityMin);
        }
    }

    static Card RunReviews(Scheduler scheduler, (Rating rating, int interval)[] reviews)
    {
        var card = Card.Create(1L);
        foreach (var (rating, interval) in reviews)
            card = scheduler.ReviewCard(card, rating, TimeSpan.FromDays(interval));
        return card;
    }

    static void CheckStabilityAndDifficulty(double expectedStability, double expectedDifficulty, Card card)
    {
        Assert.AreEqual(expectedStability, card.Stability, 1e-4);
        Assert.AreEqual(expectedDifficulty, card.Difficulty, 1e-4);
    }
}
