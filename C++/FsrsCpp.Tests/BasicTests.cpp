#include "CppUnitTest.h"
#include <vector>
#include <random>
#include <chrono>
#include <numeric>
#include <span>
#include <string>
#include <format>

import FsrsCpp;

using namespace Microsoft::VisualStudio::CppUnitTestFramework;
using namespace FsrsCpp;

namespace FsrsTests
{
    TEST_CLASS(BasicTests)
    {
    private:
        std::mt19937 rand_gen;

        template<typename T>
        void AssertVectorsAreEqual(const std::vector<T>& expected, const std::vector<T>& actual)
        {
            Assert::AreEqual(expected.size(), actual.size(), L"Vector sizes do not match.");
            for (size_t i = 0; i < expected.size(); ++i) {
                if (expected[i] != actual[i]) {
                    auto message = std::format(L"Mismatch at index {}. Expected: {}, Actual: {}", i, expected[i], actual[i]);
                    Assert::Fail(message.c_str());
                }
            }
        }

        auto run_reviews(Scheduler& scheduler, const std::vector<std::pair<Rating, int>>& reviews) -> Card
        {
            auto card = Card::create(1L);
            for (const auto& [rating, interval_days] : reviews) {
                card = scheduler.review_card(card, rating, days_t(interval_days));
            }
            return card;
        }

        void check_stability_and_difficulty(double expected_stability, double expected_difficulty, const Card& card)
        {
            Assert::AreEqual(expected_stability, card.stability, 1e-4, L"Stability check failed.");
            Assert::AreEqual(expected_difficulty, card.difficulty, 1e-4, L"Difficulty check failed.");
        }

    public:
        BasicTests() : rand_gen(std::random_device{}()) {}

        TEST_METHOD(TestNextInterval)
        {
            auto desired_retentions = std::vector<double>(10);
            for (int i = 0; i < 10; ++i) {
                desired_retentions[i] = (i + 1) / 10.0;
            }
            const std::vector<int> expected = { 3116769, 34793, 2508, 387, 90, 27, 9, 3, 1, 1 };

            auto config = FsrsCpp::SchedulerConfig(SchedulerConfig::get_default());
            config.learning_steps.clear();
            config.enable_fuzzing = false;
            config.maximum_interval = std::numeric_limits<int>::max();

            auto actual = std::vector<int>();
            for (auto r : desired_retentions)
            {
                SchedulerConfig test_config = config;
                test_config.desired_retention = r;
                auto scheduler = Scheduler(test_config, rand_gen);

                auto interval = scheduler.calculate_next_review_interval(1.0);
                actual.push_back(static_cast<int>(interval.count()));
            }

            AssertVectorsAreEqual(expected, actual);
        }

        TEST_METHOD(TestFsrs)
        {
            auto config = FsrsCpp::SchedulerConfig(SchedulerConfig::get_default());
            config.learning_steps.clear();
            config.relearning_steps.clear();
            config.enable_fuzzing = false;

            auto scheduler = Scheduler(config, rand_gen);
            auto card = Card::create(1L);
            const auto ratings = { Rating::Again, Rating::Good, Rating::Good, Rating::Good, Rating::Good, Rating::Good };
            auto actual_intervals = std::vector<int>();

            for (auto rating : ratings) {
                auto review_interval = card.interval;
                card = scheduler.review_card(card, rating, review_interval);
                actual_intervals.push_back(static_cast<int>(card.interval.count()));
            }

            const std::vector<int> expected_intervals = { 1, 2, 6, 17, 44, 102 };
            AssertVectorsAreEqual(expected_intervals, actual_intervals);
        }

        TEST_METHOD(TestMemoState)
        {
            auto config = FsrsCpp::SchedulerConfig(SchedulerConfig::get_default());
            config.parameters = { 0.6845422, 1.6790825, 4.7349424, 10.042885, 7.4410233, 0.64219797, 1.071918, 0.0025195254, 1.432437, 0.1544, 0.8692766, 2.0696752, 0.0953, 0.2975, 2.4691248, 0.19542035, 3.201072, 0.18046261, 0.121442534 };
            config.enable_fuzzing = false;
            auto scheduler = Scheduler(config, rand_gen);
            const auto reviews = std::vector<std::pair<Rating, int>>{ {Rating::Again, 0}, {Rating::Good, 1}, {Rating::Good, 3}, {Rating::Good, 8}, {Rating::Good, 21} };

            auto final_card1 = run_reviews(scheduler, reviews);
            check_stability_and_difficulty(31.722992, 7.382128, final_card1);

            auto card_mod = Card{ .card_id = 1L, .interval = days_t(21), .stability = 20.925528, .difficulty = 7.005062, .state = State::Review, .step = 0 };
            auto final_card2 = scheduler.review_card(card_mod, Rating::Good, card_mod.interval);
            check_stability_and_difficulty(40.87456, 6.9913807, final_card2);
        }

        TEST_METHOD(TestMemoryState)
        {
            auto config = FsrsCpp::SchedulerConfig(SchedulerConfig::get_default());
            config.enable_fuzzing = false;
            auto scheduler = Scheduler(config, rand_gen);
            const auto reviews = std::vector<std::pair<Rating, int>>{ {Rating::Again, 0}, {Rating::Good, 0}, {Rating::Good, 1}, {Rating::Good, 3}, {Rating::Good, 8}, {Rating::Good, 21} };

            auto final_card1 = run_reviews(scheduler, reviews);
            check_stability_and_difficulty(53.62691, 6.3574867, final_card1);

            auto config2 = FsrsCpp::SchedulerConfig(SchedulerConfig::get_default());
            config2.parameters[17] = 0.0;
            config2.parameters[18] = 0.0;
            config2.parameters[19] = 0.0;
            config2.enable_fuzzing = false;
            auto scheduler2 = Scheduler(config2, rand_gen);

            auto final_card2 = run_reviews(scheduler2, reviews);
            check_stability_and_difficulty(53.335106, 6.3574867, final_card2);
        }

        TEST_METHOD(TestGoodLearningSteps)
        {
            auto scheduler = Scheduler(SchedulerConfig::get_default(), rand_gen);
            auto card = Card::create(1L);
            Assert::AreEqual(static_cast<int>(State::New), static_cast<int>(card.state));

            card = scheduler.review_card(card, Rating::Good, card.interval);
            Assert::AreEqual(static_cast<int>(State::Learning), static_cast<int>(card.state));
            Assert::AreEqual(1, card.step);
            Assert::AreEqual(10.0, std::chrono::duration_cast<minutes_t>(card.interval).count(), 1.0 / 60.0);

            card = scheduler.review_card(card, Rating::Good, card.interval);
            Assert::AreEqual(static_cast<int>(State::Review), static_cast<int>(card.state));
            Assert::IsTrue(card.interval.count() >= 1.0);
        }

        TEST_METHOD(TestAgainLearningSteps)
        {
            auto scheduler = Scheduler(SchedulerConfig::get_default(), rand_gen);
            auto card = Card::create(1L);
            card = scheduler.review_card(card, Rating::Again, card.interval);

            Assert::AreEqual(static_cast<int>(State::Learning), static_cast<int>(card.state));
            Assert::AreEqual(0, card.step);
            Assert::AreEqual(1.0, std::chrono::duration_cast<minutes_t>(card.interval).count(), 1.0 / 60.0);
        }

        TEST_METHOD(TestLearningCardRateHardOneLearningStep)
        {
            auto config = FsrsCpp::SchedulerConfig(SchedulerConfig::get_default());
            config.learning_steps = { minutes_t(10.0) };
            auto scheduler = Scheduler(config, rand_gen);
            auto card = Card::create(1L);
            card = scheduler.review_card(card, Rating::Hard, card.interval);

            auto expected_interval = minutes_t(10.0 * 1.5);
            using seconds_t = std::chrono::duration<double>;
            Assert::IsTrue(std::abs(std::chrono::duration_cast<seconds_t>(card.interval - expected_interval).count()) <= 1.0);
        }

        TEST_METHOD(TestNoLearningSteps)
        {
            auto config = FsrsCpp::SchedulerConfig(SchedulerConfig::get_default());
            config.learning_steps.clear();
            auto scheduler = Scheduler(config, rand_gen);
            auto card = Card::create(1L);
            card = scheduler.review_card(card, Rating::Again, card.interval);

            Assert::AreEqual(static_cast<int>(State::Review), static_cast<int>(card.state));
            Assert::IsTrue(card.interval.count() >= 1.0);
        }

        TEST_METHOD(TestMaximumInterval)
        {
            auto config = FsrsCpp::SchedulerConfig(SchedulerConfig::get_default());
            config.maximum_interval = 100;
            auto scheduler = Scheduler(config, rand_gen);
            auto card = Card::create(1L);

            for (auto i = 0; i < 10; i++)
                card = scheduler.review_card(card, Rating::Easy, card.interval);

            Assert::IsTrue(card.interval.count() <= config.maximum_interval);
        }

        TEST_METHOD(TestStabilityLowerBound)
        {
            auto scheduler = Scheduler(SchedulerConfig::get_default(), rand_gen);
            constexpr double stability_min = 0.001;
            auto card = Card::create(1L);

            for (auto i = 0; i < 100; i++)
            {
                auto next_review_time = card.interval + days_t(1.0);
                card = scheduler.review_card(card, Rating::Again, next_review_time);
                Assert::IsTrue(card.stability >= stability_min);
            }
        }
    };
}
