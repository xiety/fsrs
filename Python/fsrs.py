import math
import random
from dataclasses import dataclass, replace
from datetime import timedelta
from enum import Enum
from typing import List


class Rating(Enum):
    AGAIN = 1
    HARD = 2
    GOOD = 3
    EASY = 4


class State(Enum):
    NEW = 0
    LEARNING = 1
    REVIEW = 2
    RELEARNING = 3


@dataclass
class Card:
    card_id: int
    interval: timedelta = timedelta(0)
    state: State = State.NEW
    step: int = 0
    stability: float = 0.0
    difficulty: float = 0.0


@dataclass(frozen=True)
class SchedulerConfig:
    w: List[float]
    desired_retention: float
    learning_steps: List[timedelta]
    relearning_steps: List[timedelta]
    maximum_interval: int
    enable_fuzzing: bool


DEFAULT_CONFIG = SchedulerConfig(
    w=[0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722,
       0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425,
       0.0912, 0.0658, 0.1542],
    desired_retention=0.9,
    learning_steps=[timedelta(minutes=1), timedelta(minutes=10)],
    relearning_steps=[timedelta(minutes=10)],
    maximum_interval=36500,
    enable_fuzzing=True,
)

MIN_DIFFICULTY = 1.0
MAX_DIFFICULTY = 10.0
STABILITY_MIN = 0.001


def _clamp_difficulty(difficulty: float) -> float:
    return max(MIN_DIFFICULTY, min(difficulty, MAX_DIFFICULTY))


def _clamp_stability(stability: float) -> float:
    return max(stability, STABILITY_MIN)


def _raw_initial_difficulty(w: List[float], rating: Rating) -> float:
    return w[4] - (math.exp(w[5] * (rating.value - 1.0))) + 1.0


def initial_stability(w: List[float], rating: Rating) -> float:
    return _clamp_stability(w[rating.value - 1])


def initial_difficulty(w: List[float], rating: Rating) -> float:
    return _clamp_difficulty(_raw_initial_difficulty(w, rating))


def next_interval(factor: float, retention: float, decay: float,
                  max_interval: int, stability: float) -> timedelta:
    interval_days = (stability / factor) * (retention ** (1.0 / decay) - 1.0)
    return timedelta(days=min(max(1, round(interval_days)), max_interval))


def short_term_stability(w: List[float], stability: float, rating: Rating) -> float:
    increase = math.exp(w[17] * (rating.value - 3.0 + w[18])) * (stability ** -w[19])
    final_increase = max(increase, 1.0) if rating in [Rating.GOOD, Rating.EASY] else increase
    return _clamp_stability(stability * final_increase)


def next_difficulty(w: List[float], difficulty: float, rating: Rating) -> float:
    delta_difficulty = -w[6] * (rating.value - 3.0)
    damped_delta = ((MAX_DIFFICULTY - difficulty) * delta_difficulty /
                    (MAX_DIFFICULTY - MIN_DIFFICULTY))
    initial_easy_difficulty = _raw_initial_difficulty(w, Rating.EASY)
    new_difficulty = (w[7] * initial_easy_difficulty + (1.0 - w[7]) * (difficulty + damped_delta))
    return _clamp_difficulty(new_difficulty)


def _next_forget_stability(w: List[float], difficulty: float, stability: float, retrievability: float) -> float:
    long_term = (w[11] * (difficulty ** -w[12]) *
                 (((stability + 1.0) ** w[13]) - 1.0) *
                 (math.exp((1.0 - retrievability) * w[14])))
    short_term = stability / (math.exp(w[17] * w[18]))
    return min(long_term, short_term)


def _next_recall_stability(w: List[float], difficulty: float, stability: float,
                           retrievability: float, rating: Rating) -> float:
    hard_penalty = w[15] if rating == Rating.HARD else 1.0
    easy_bonus = w[16] if rating == Rating.EASY else 1.0
    return (stability *
            (1.0 + math.exp(w[8]) * (11.0 - difficulty) *
             (stability ** -w[9]) *
             (math.exp((1.0 - retrievability) * w[10]) - 1.0) *
             hard_penalty * easy_bonus))


def next_stability(w: List[float], difficulty: float, stability: float,
                   retrievability: float, rating: Rating) -> float:
    if rating == Rating.AGAIN:
        new_stability = _next_forget_stability(w, difficulty, stability, retrievability)
    else:
        new_stability = _next_recall_stability(w, difficulty, stability, retrievability, rating)
    return _clamp_stability(new_stability)


@dataclass(frozen=True)
class _FuzzRange:
    start: float
    end: float
    factor: float


class Scheduler:
    def __init__(self, config: SchedulerConfig = DEFAULT_CONFIG):
        self.config = config
        self.w = self._check_and_fill_parameters(config.w)
        self.decay = -self.w[20]
        self.factor = 0.9 ** (1.0 / self.decay) - 1.0
        self.rand = random.Random()
        self._fuzz_ranges = (
            _FuzzRange(start=2.5, end=7.0, factor=0.15),
            _FuzzRange(start=7.0, end=20.0, factor=0.1),
            _FuzzRange(start=20.0, end=float('inf'), factor=0.05),
        )

    def _check_and_fill_parameters(self, w: List[float]) -> List[float]:
        if any(not math.isfinite(val) for val in w):
            raise ValueError("Invalid parameters: contains non-finite values.")
        match len(w):
            case 21:
                return w
            case 19:
                return w + [0.0, 0.5]
            case 17:
                wn = list(w)
                wn[4] = wn[5] * 2.0 + wn[4]
                wn[5] = math.log(wn[5] * 3.0 + 1.0) / 3.0
                wn[6] = wn[6] + 0.5
                return wn + [0.0, 0.0, 0.0, 0.5]
            case _:
                raise ValueError(f"Invalid number of parameters: {len(w)}. Supported: 17, 19, or 21.")

    def _get_card_retrievability(self, card: Card, review_interval: timedelta) -> float:
        elapsed_days = max(0.0, review_interval.total_seconds() / 86400)
        return (1.0 + self.factor * elapsed_days / card.stability) ** self.decay

    def _calculate_next_reviewed_state(self, card: Card, rating: Rating, review_interval: timedelta):
        if card.state == State.NEW:
            card.stability = initial_stability(self.w, rating)
            card.difficulty = initial_difficulty(self.w, rating)
            card.state = State.LEARNING
            card.step = 0
        else:
            retrievability = self._get_card_retrievability(card, review_interval)

            new_difficulty = next_difficulty(self.w, card.difficulty, rating)
            new_stability = (short_term_stability(self.w, card.stability, rating)
                             if review_interval.total_seconds() < 86400
                             else next_stability(self.w, card.difficulty, card.stability, retrievability, rating))

            card.difficulty = new_difficulty
            card.stability = new_stability

    def _calculate_next_review_interval(self, stability: float) -> timedelta:
        return next_interval(
            self.factor, self.config.desired_retention, self.decay,
            self.config.maximum_interval, stability
        )

    def _to_review_state(self, card: Card):
        card.state = State.REVIEW
        card.step = 0
        card.interval = self._calculate_next_review_interval(card.stability)

    def _hard_interval_step(self, card: Card, steps: List[timedelta]) -> timedelta:
        if card.step == 0 and len(steps) == 1:
            return timedelta(minutes=steps[0].total_seconds() / 60 * 1.5)
        if card.step == 0 and len(steps) > 1:
            return timedelta(minutes=(steps[0].total_seconds() / 60 + steps[1].total_seconds() / 60) / 2.0)
        return steps[card.step]

    def _handle_steps(self, card: Card, rating: Rating, steps: List[timedelta]):
        if not steps:
            self._to_review_state(card)
            return
        match rating:
            case Rating.AGAIN:
                card.state = State.LEARNING
                card.step = 0
                card.interval = steps[0]
            case Rating.HARD:
                card.interval = self._hard_interval_step(card, steps)
            case Rating.GOOD:
                next_step = card.step + 1
                if next_step >= len(steps):
                    self._to_review_state(card)
                else:
                    card.state = State.LEARNING
                    card.step = next_step
                    card.interval = steps[next_step]
            case Rating.EASY:
                self._to_review_state(card)

    def _determine_next_card_state(self, card: Card, rating: Rating):
        match card.state:
            case State.LEARNING:
                self._handle_steps(card, rating, self.config.learning_steps)
            case State.RELEARNING:
                self._handle_steps(card, rating, self.config.relearning_steps)
            case State.REVIEW:
                if rating == Rating.AGAIN and self.config.relearning_steps:
                    card.state = State.RELEARNING
                    card.step = 0
                    card.interval = self.config.relearning_steps[0]
                else:
                    self._to_review_state(card)

    def _get_fuzzed_interval(self, interval: timedelta) -> timedelta:
        interval_days = interval.total_seconds() / 86400
        if interval_days < 2.5:
            return interval
        delta = sum(r.factor * max(0.0, min(interval_days, r.end) - r.start) for r in self._fuzz_ranges)
        min_ivl = max(2, round(interval_days - delta))
        max_ivl = round(interval_days + delta)
        fuzzed_days = self.rand.randint(min_ivl, max_ivl)
        return timedelta(days=min(fuzzed_days, self.config.maximum_interval))

    def _apply_fuzzing(self, card: Card):
        if self.config.enable_fuzzing and card.state == State.REVIEW:
            card.interval = self._get_fuzzed_interval(card.interval)

    def review_card(self, card: Card, rating: Rating, review_interval: timedelta) -> None:
        self._calculate_next_reviewed_state(card, rating, review_interval)
        self._determine_next_card_state(card, rating)
        self._apply_fuzzing(card)
