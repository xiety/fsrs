package fsrs

import (
	"errors"
	"fmt"
	"math"
	"math/rand"
	"time"
)

const dayDuration = 24 * time.Hour

type Rating int

const (
	Again Rating = 1
	Hard  Rating = 2
	Good  Rating = 3
	Easy  Rating = 4
)

type State int

const (
	New        State = 0
	Learning   State = 1
	Review     State = 2
	Relearning State = 3
)

type Card struct {
	CardID     int64
	Interval   time.Duration
	Stability  float64
	Difficulty float64
	State      State
	Step       int
}

func NewCard(cardID int64) Card {
	return Card{
		CardID: cardID,
	}
}

type SchedulerConfig struct {
	Parameters       []float64
	DesiredRetention float64
	LearningSteps    []time.Duration
	RelearningSteps  []time.Duration
	MaximumInterval  int
	EnableFuzzing    bool
}

func DefaultSchedulerConfig() SchedulerConfig {
	return SchedulerConfig{
		Parameters: []float64{0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
			1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542},
		DesiredRetention: 0.9,
		LearningSteps:    []time.Duration{time.Minute, 10 * time.Minute},
		RelearningSteps:  []time.Duration{10 * time.Minute},
		MaximumInterval:  36500,
		EnableFuzzing:    true,
	}
}

type Scheduler struct {
	config SchedulerConfig
	random *rand.Rand
	w      []float64
	decay  float64
	factor float64
}

func NewScheduler(config SchedulerConfig, random *rand.Rand) (*Scheduler, error) {
	w, err := checkAndFillParameters(config.Parameters)
	if err != nil {
		return nil, err
	}
	decay := -w[20]
	factor := math.Pow(0.9, 1.0/decay) - 1.0
	return &Scheduler{
		config: config,
		random: random,
		w:      w,
		decay:  decay,
		factor: factor,
	}, nil
}

func (s *Scheduler) ReviewCard(card Card, rating Rating, reviewInterval time.Duration) Card {
	reviewedCard := s.calculateInitialReviewedCard(card, rating, reviewInterval)
	cardWithNextState := s.determineNextPhaseAndInterval(reviewedCard, rating)
	finalCard := s.applyFuzzing(cardWithNextState)
	return finalCard
}

func (s *Scheduler) calculateInitialReviewedCard(card Card, rating Rating, reviewInterval time.Duration) Card {
	if card.State == New {
		stability := initialStability(s.w, rating)
		difficulty := initialDifficulty(s.w, rating)
		card.Stability = stability
		card.Difficulty = difficulty
		card.State = Learning
		card.Step = 0
		return card
	}

	newDifficulty := nextDifficulty(s.w, card.Difficulty, rating)
	var newStability float64
	if reviewInterval < dayDuration {
		newStability = shortTermStability(s.w, card.Stability, rating)
	} else {
		newStability = s.getLongTermStability(card, rating, reviewInterval)
	}

	card.Stability = newStability
	card.Difficulty = newDifficulty
	return card
}

func (s *Scheduler) getLongTermStability(card Card, rating Rating, reviewInterval time.Duration) float64 {
	elapsedDays := math.Max(0.0, reviewInterval.Hours()/dayDuration.Hours())
	retrievability := math.Pow(1.0+s.factor*elapsedDays/card.Stability, s.decay)
	return nextStability(s.w, card.Difficulty, card.Stability, retrievability, rating)
}

func (s *Scheduler) determineNextPhaseAndInterval(reviewedCard Card, rating Rating) Card {
	switch reviewedCard.State {
	case Learning:
		return s.handleSteps(reviewedCard, rating, s.config.LearningSteps)
	case Relearning:
		return s.handleSteps(reviewedCard, rating, s.config.RelearningSteps)
	case Review:
		if rating == Again && len(s.config.RelearningSteps) > 0 {
			reviewedCard.State = Relearning
			reviewedCard.Step = 0
			reviewedCard.Interval = s.config.RelearningSteps[0]
			return reviewedCard
		}
		return s.toReviewState(reviewedCard)
	}
	return reviewedCard
}

func (s *Scheduler) handleSteps(card Card, rating Rating, steps []time.Duration) Card {
	if len(steps) == 0 {
		return s.toReviewState(card)
	}

	switch rating {
	case Again:
		card.State = Learning
		card.Step = 0
		card.Interval = steps[0]
		return card
	case Hard:
		card.State = Learning
		card.Interval = hardIntervalStep(card.Step, steps)
		return card
	case Good:
		if card.Step+1 >= len(steps) {
			return s.toReviewState(card)
		}
		card.State = Learning
		card.Step++
		card.Interval = steps[card.Step]
		return card
	case Easy:
		return s.toReviewState(card)
	}
	return card
}

func (s *Scheduler) toReviewState(card Card) Card {
	interval := s.CalculateNextReviewInterval(card.Stability)
	card.State = Review
	card.Step = 0
	card.Interval = interval
	return card
}

func (s *Scheduler) CalculateNextReviewInterval(stability float64) time.Duration {
	return nextInterval(s.factor, s.config.DesiredRetention, s.decay, s.config.MaximumInterval, stability)
}

func (s *Scheduler) applyFuzzing(card Card) Card {
	if s.config.EnableFuzzing && card.State == Review {
		fuzzedInterval := getFuzzedInterval(s.random, s.config.MaximumInterval, card.Interval)
		card.Interval = fuzzedInterval
	}
	return card
}

func getFuzzedInterval(rand *rand.Rand, maxInterval int, interval time.Duration) time.Duration {
	intervalDays := interval.Hours() / dayDuration.Hours()
	if intervalDays < 2.5 {
		return interval
	}

	type fuzzRange struct {
		start, end, factor float64
	}

	ranges := []fuzzRange{
		{2.5, 7.0, 0.15},
		{7.0, 20.0, 0.1},
		{20.0, math.Inf(1), 0.05},
	}

	var delta float64
	for _, r := range ranges {
		delta += r.factor * math.Max(0.0, math.Min(intervalDays, r.end)-r.start)
	}

	minDays := int(math.Round(intervalDays - delta))
	maxDays := int(math.Round(intervalDays + delta))
	fuzzed := rand.Intn(maxDays-minDays+1) + minDays

	days := math.Min(float64(maxInterval), math.Max(2, float64(fuzzed)))
	return time.Duration(days) * dayDuration
}

func hardIntervalStep(currentStep int, steps []time.Duration) time.Duration {
	if currentStep == 0 {
		if len(steps) == 1 {
			return time.Duration(steps[0].Minutes()*1.5) * time.Minute
		}
		if len(steps) > 1 {
			return time.Duration((steps[0].Minutes()+steps[1].Minutes())/2.0) * time.Minute
		}
	}

	return steps[currentStep]
}

func checkAndFillParameters(w []float64) ([]float64, error) {
	for _, p := range w {
		if math.IsNaN(p) || math.IsInf(p, 0) {
			return nil, errors.New("invalid parameters: contains non-finite values")
		}
	}

	switch len(w) {
	case 17:
		return append(w, 0.0, 0.0, 0.0, 0.5), nil
	case 19:
		return append(w, 0.0, 0.5), nil
	case 21:
		return w, nil
	default:
		return nil, fmt.Errorf("invalid number of parameters. Supported: 17, 19, or 21, but got %d", len(w))
	}
}

const (
	minDifficulty = 1.0
	maxDifficulty = 10.0
	stabilityMin  = 0.001
)

func clampDifficulty(d float64) float64 {
	return math.Max(minDifficulty, math.Min(d, maxDifficulty))
}

func clampStability(s float64) float64 {
	return math.Max(s, stabilityMin)
}

func rawInitialDifficulty(w []float64, r Rating) float64 {
	return w[4] - math.Exp(w[5]*(float64(r)-1.0)) + 1.0
}

func initialStability(w []float64, r Rating) float64 {
	return clampStability(w[int(r)-1])
}

func initialDifficulty(w []float64, r Rating) float64 {
	return clampDifficulty(rawInitialDifficulty(w, r))
}

func nextInterval(factor, retention, decay float64, maxInterval int, stability float64) time.Duration {
	intervalDays := stability / factor * (math.Pow(retention, 1.0/decay) - 1.0)
	days := math.Min(float64(maxInterval), math.Max(1, math.Round(intervalDays)))
	return time.Duration(days) * dayDuration
}

func shortTermStability(w []float64, stability float64, rating Rating) float64 {
	increase := math.Exp(w[17]*(float64(rating)-3.0+w[18])) * math.Pow(stability, -w[19])
	finalIncrease := increase
	if rating == Good || rating == Easy {
		finalIncrease = math.Max(increase, 1.0)
	}
	return clampStability(stability * finalIncrease)
}

func nextDifficulty(w []float64, d float64, r Rating) float64 {
	delta := -(w[6] * (float64(r) - 3.0))
	damped := (maxDifficulty - d) * delta / (maxDifficulty - minDifficulty)
	return clampDifficulty(w[7]*rawInitialDifficulty(w, Easy) + (1.0-w[7])*(d+damped))
}

func nextStability(w []float64, difficulty, stability, retrievability float64, r Rating) float64 {
	var next float64
	if r == Again {
		next = w[11] * math.Pow(difficulty, -w[12]) *
			(math.Pow(stability+1.0, w[13]) - 1.0) *
			math.Exp((1.0-retrievability)*w[14])
	} else {
		next = calculateRecallStability(w, difficulty, stability, retrievability, r)
	}
	return clampStability(next)
}

func calculateRecallStability(w []float64, difficulty, stability, retrievability float64, r Rating) float64 {
	recallFactor := math.Exp(w[8])
	difficultyWeight := 11.0 - difficulty
	stabilityDecay := math.Pow(stability, -w[9])
	memoryFactor := math.Exp((1.0-retrievability)*w[10]) - 1.0

	hardPenalty := 1.0
	if r == Hard {
		hardPenalty = w[15]
	}
	easyBonus := 1.0
	if r == Easy {
		easyBonus = w[16]
	}

	stabilityIncrease := recallFactor *
		difficultyWeight *
		stabilityDecay *
		memoryFactor *
		hardPenalty *
		easyBonus

	return stability * (1.0 + stabilityIncrease)
}
