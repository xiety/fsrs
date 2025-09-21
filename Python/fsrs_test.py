import unittest
from dataclasses import replace
from datetime import timedelta

from fsrs import Card, Rating, Scheduler, State, DEFAULT_CONFIG, STABILITY_MIN


class FsrsTests(unittest.TestCase):

    def _check_stability_and_difficulty(self, card: Card, expected_stability: float, expected_difficulty: float):
        self.assertAlmostEqual(card.stability, expected_stability, places=4)
        self.assertAlmostEqual(card.difficulty, expected_difficulty, places=4)

    def test_next_interval(self):
        desired_retentions = [r / 10.0 for r in range(1, 11)]
        base_config = replace(DEFAULT_CONFIG, learning_steps=[], relearning_steps=[], maximum_interval=2**31 - 1, enable_fuzzing=False)
        actual_intervals = []
        for r in desired_retentions:
            current_config = replace(base_config, desired_retention=r)
            scheduler = Scheduler(current_config)
            interval = scheduler._calculate_next_review_interval(stability=1.0)
            actual_intervals.append(int(interval.days))
        expected_intervals = [3116769, 34793, 2508, 387, 90, 27, 9, 3, 1, 1]
        self.assertEqual(actual_intervals, expected_intervals)

    def test_fsrs(self):
        config = replace(DEFAULT_CONFIG, learning_steps=[], relearning_steps=[], enable_fuzzing=False)
        scheduler = Scheduler(config)
        card = Card(card_id=1)
        ratings = [Rating.AGAIN, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD]
        actual_intervals = []
        for rating in ratings:
            scheduler.review_card(card, rating, card.interval)
            actual_intervals.append(card.interval.days)
        self.assertEqual(actual_intervals, [1, 2, 6, 17, 44, 102])

    def test_memo_state(self):
        w = [0.6845422, 1.6790825, 4.7349424, 10.042885, 7.4410233, 0.64219797,
             1.071918, 0.0025195254, 1.432437, 0.1544, 0.8692766, 2.0696752,
             0.0953, 0.2975, 2.4691248, 0.19542035, 3.201072, 0.18046261, 0.121442534]
        config = replace(DEFAULT_CONFIG, w=w)
        scheduler = Scheduler(config)

        card = Card(card_id=1)
        reviews = [(Rating.AGAIN, 0), (Rating.GOOD, 1), (Rating.GOOD, 3), (Rating.GOOD, 8), (Rating.GOOD, 21)]
        for rating, elapsed_days in reviews:
            scheduler.review_card(card, rating, timedelta(days=elapsed_days))

        self._check_stability_and_difficulty(card, 31.722992, 7.382128)

        card.interval = timedelta(days=21)
        card.stability = 20.925528
        card.difficulty = 7.005062

        scheduler.review_card(card, Rating.GOOD, timedelta(days=21))
        self._check_stability_and_difficulty(card, 40.87456, 6.9913807)

    def test_memory_state(self):
        scheduler1 = Scheduler()
        card1 = Card(card_id=1)
        reviews = [(Rating.AGAIN, 0), (Rating.GOOD, 0), (Rating.GOOD, 1), (Rating.GOOD, 3), (Rating.GOOD, 8), (Rating.GOOD, 21)]
        for rating, elapsed_days in reviews:
            scheduler1.review_card(card1, rating, timedelta(days=elapsed_days))
        self._check_stability_and_difficulty(card1, 53.62691, 6.3574867)

        w2 = list(DEFAULT_CONFIG.w)
        w2[17:20] = [0.0, 0.0, 0.0]
        scheduler2 = Scheduler(replace(DEFAULT_CONFIG, w=w2))
        card2 = Card(card_id=1)
        for rating, elapsed_days in reviews:
            scheduler2.review_card(card2, rating, timedelta(days=elapsed_days))
        self._check_stability_and_difficulty(card2, 53.335106, 6.3574867)

    def test_good_learning_steps(self):
        scheduler = Scheduler()
        card = Card(card_id=1)

        scheduler.review_card(card, Rating.GOOD, card.interval)
        self.assertEqual(card.state, State.LEARNING)
        self.assertEqual(card.step, 1)
        self.assertAlmostEqual(card.interval.total_seconds() / 60, 10.0)

        scheduler.review_card(card, Rating.GOOD, card.interval)
        self.assertEqual(card.state, State.REVIEW)
        self.assertGreaterEqual(card.interval.days, 1.0)

    def test_again_learning_steps(self):
        scheduler = Scheduler()
        card = Card(card_id=1)
        scheduler.review_card(card, Rating.AGAIN, card.interval)
        self.assertEqual(card.state, State.LEARNING)
        self.assertEqual(card.step, 0)
        self.assertAlmostEqual(card.interval.total_seconds() / 60, 1.0)

    def test_learning_card_rate_hard_one_learning_step(self):
        config = replace(DEFAULT_CONFIG, learning_steps=[timedelta(minutes=10.0)])
        scheduler = Scheduler(config)
        card = Card(card_id=1)
        scheduler.review_card(card, Rating.HARD, card.interval)
        expected_interval = timedelta(minutes=10.0 * 1.5)
        self.assertAlmostEqual(card.interval.total_seconds(), expected_interval.total_seconds(), delta=1.0)

    def test_no_learning_steps(self):
        config = replace(DEFAULT_CONFIG, learning_steps=[])
        scheduler = Scheduler(config)
        card = Card(card_id=1)
        scheduler.review_card(card, Rating.AGAIN, card.interval)
        self.assertEqual(card.state, State.REVIEW)
        self.assertGreaterEqual(card.interval.days, 1.0)

    def test_maximum_interval(self):
        config = replace(DEFAULT_CONFIG, maximum_interval=100)
        scheduler = Scheduler(config)
        card = Card(card_id=1)
        for _ in range(10):
            scheduler.review_card(card, Rating.EASY, card.interval)
        self.assertLessEqual(card.interval.days, 100)

    def test_stability_lower_bound(self):
        scheduler = Scheduler()
        card = Card(card_id=1)
        for _ in range(100):
            next_review_time = card.interval + timedelta(days=1)
            scheduler.review_card(card, Rating.AGAIN, next_review_time)
            self.assertGreaterEqual(card.stability, STABILITY_MIN)


if __name__ == "__main__":
    unittest.main()
