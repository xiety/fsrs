package fsrs

import (
	"math"
	"math/rand"
	"reflect"
	"testing"
	"time"
)

var testRand = rand.New(rand.NewSource(1))

func createDefaultScheduler() *Scheduler {
	scheduler, _ := NewScheduler(DefaultSchedulerConfig(), testRand)
	return scheduler
}

func TestNextInterval(t *testing.T) {
	var desiredRetentions []float64
	for i := 2; i <= 10; i++ {
		desiredRetentions = append(desiredRetentions, float64(i)/10.0)
	}

	expected := []int{34793, 2508, 387, 90, 27, 9, 3, 1, 1}

	config := DefaultSchedulerConfig()
	config.LearningSteps = []time.Duration{}
	config.EnableFuzzing = false
	config.MaximumInterval = math.MaxInt

	var actual []int
	for _, r := range desiredRetentions {
		config.DesiredRetention = r
		scheduler, _ := NewScheduler(config, testRand)
		interval := scheduler.CalculateNextReviewInterval(1.0)
		actual = append(actual, int(interval/dayDuration))
	}

	if !reflect.DeepEqual(expected, actual) {
		t.Errorf("Expected %v, but got %v", expected, actual)
	}
}

func TestFsrs(t *testing.T) {
	config := DefaultSchedulerConfig()
	config.LearningSteps = []time.Duration{}
	config.RelearningSteps = []time.Duration{}
	config.EnableFuzzing = false
	scheduler, _ := NewScheduler(config, testRand)
	card := NewCard(1)
	ratings := []Rating{Again, Good, Good, Good, Good, Good}
	var actualIntervals []int

	for _, rating := range ratings {
		reviewInterval := card.Interval
		card = scheduler.ReviewCard(card, rating, reviewInterval)
		actualIntervals = append(actualIntervals, int(card.Interval/dayDuration))
	}

	expectedIntervals := []int{1, 2, 6, 17, 44, 102}
	if !reflect.DeepEqual(expectedIntervals, actualIntervals) {
		t.Errorf("Expected intervals %v, but got %v", expectedIntervals, actualIntervals)
	}
}

func TestMemoState(t *testing.T) {
	parameters := []float64{0.6845422, 1.6790825, 4.7349424, 10.042885, 7.4410233, 0.64219797, 1.071918, 0.0025195254, 1.432437,
		0.1544, 0.8692766, 2.0696752, 0.0953, 0.2975, 2.4691248, 0.19542035, 3.201072, 0.18046261, 0.121442534}
	config := DefaultSchedulerConfig()
	config.Parameters = parameters
	scheduler, _ := NewScheduler(config, testRand)
	reviews := []struct {
		rating   Rating
		interval int
	}{
		{Again, 0},
		{Good, 1},
		{Good, 3},
		{Good, 8},
		{Good, 21},
	}

	finalCard1 := runReviews(scheduler, reviews)
	checkStabilityAndDifficulty(t, 31.722992, 7.382128, finalCard1)

	cardMod := Card{
		CardID:     1,
		Interval:   21 * dayDuration,
		Stability:  20.925528,
		Difficulty: 7.005062,
		State:      Review,
		Step:       0,
	}
	finalCard2 := scheduler.ReviewCard(cardMod, Good, cardMod.Interval)
	checkStabilityAndDifficulty(t, 40.87456, 6.9913807, finalCard2)
}

func TestMemoryState(t *testing.T) {
	scheduler := createDefaultScheduler()
	reviews := []struct {
		rating   Rating
		interval int
	}{
		{Again, 0},
		{Good, 0},
		{Good, 1},
		{Good, 3},
		{Good, 8},
		{Good, 21},
	}

	finalCard1 := runReviews(scheduler, reviews)
	checkStabilityAndDifficulty(t, 53.62691, 6.3574867, finalCard1)

	config2 := DefaultSchedulerConfig()
	parameters2 := make([]float64, len(config2.Parameters))
	copy(parameters2, config2.Parameters)
	parameters2[17] = 0.0
	parameters2[18] = 0.0
	parameters2[19] = 0.0
	config2.Parameters = parameters2
	scheduler2, _ := NewScheduler(config2, testRand)

	finalCard2 := runReviews(scheduler2, reviews)
	checkStabilityAndDifficulty(t, 53.335106, 6.3574867, finalCard2)
}

func TestGoodLearningSteps(t *testing.T) {
	scheduler := createDefaultScheduler()
	card := NewCard(1)
	if card.State != New {
		t.Errorf("Expected state New, but got %v", card.State)
	}

	card = scheduler.ReviewCard(card, Good, card.Interval)
	if card.State != Learning {
		t.Errorf("Expected state Learning, but got %v", card.State)
	}
	if card.Step != 1 {
		t.Errorf("Expected step 1, but got %v", card.Step)
	}
	if math.Abs(card.Interval.Minutes()-10.0) > 1e-9 {
		t.Errorf("Expected interval around 10 minutes, but got %v", card.Interval)
	}

	card = scheduler.ReviewCard(card, Good, card.Interval)
	if card.State != Review {
		t.Errorf("Expected state Review, but got %v", card.State)
	}
	if card.Interval < dayDuration {
		t.Errorf("Expected interval >= 1 day, but got %v", card.Interval)
	}
}

func TestAgainLearningSteps(t *testing.T) {
	scheduler := createDefaultScheduler()
	card := NewCard(1)
	card = scheduler.ReviewCard(card, Again, card.Interval)

	if card.State != Learning {
		t.Errorf("Expected state Learning, but got %v", card.State)
	}
	if card.Step != 0 {
		t.Errorf("Expected step 0, but got %v", card.Step)
	}
	if math.Abs(card.Interval.Minutes()-1.0) > 1e-9 {
		t.Errorf("Expected interval around 1 minute, but got %v", card.Interval)
	}
}

func TestLearningCardRateHardOneLearningStep(t *testing.T) {
	config := DefaultSchedulerConfig()
	config.LearningSteps = []time.Duration{10 * time.Minute}
	scheduler, _ := NewScheduler(config, testRand)
	card := NewCard(1)
	card = scheduler.ReviewCard(card, Hard, card.Interval)

	expectedInterval := time.Duration(10.0*1.5) * time.Minute
	if card.Interval != expectedInterval {
		t.Errorf("Expected interval %v, but got %v", expectedInterval, card.Interval)
	}
}

func TestNoLearningSteps(t *testing.T) {
	config := DefaultSchedulerConfig()
	config.LearningSteps = []time.Duration{}
	scheduler, _ := NewScheduler(config, testRand)
	card := NewCard(1)
	card = scheduler.ReviewCard(card, Again, card.Interval)

	if card.State != Review {
		t.Errorf("Expected state Review, but got %v", card.State)
	}
	if card.Interval < dayDuration {
		t.Errorf("Expected interval >= 1 day, but got %v", card.Interval)
	}
}

func TestMaximumInterval(t *testing.T) {
	config := DefaultSchedulerConfig()
	config.MaximumInterval = 100
	scheduler, _ := NewScheduler(config, testRand)
	card := NewCard(1)

	for range 10 {
		card = scheduler.ReviewCard(card, Easy, card.Interval)
	}

	if card.Interval > time.Duration(config.MaximumInterval)*dayDuration {
		t.Errorf("Interval %v exceeds maximum interval %v days", card.Interval, config.MaximumInterval)
	}
}

func TestStabilityLowerBound(t *testing.T) {
	scheduler := createDefaultScheduler()
	const stabilityMin = 0.001
	card := NewCard(1)

	for i := range 100 {
		nextReviewTime := card.Interval + dayDuration
		card = scheduler.ReviewCard(card, Again, nextReviewTime)
		if card.Stability < stabilityMin {
			t.Errorf("Stability %v is below lower bound %v on iteration %d", card.Stability, stabilityMin, i)
		}
	}
}

func runReviews(scheduler *Scheduler, reviews []struct {
	rating   Rating
	interval int
}) Card {
	card := NewCard(1)
	for _, review := range reviews {
		reviewInterval := time.Duration(review.interval) * dayDuration
		card = scheduler.ReviewCard(card, review.rating, reviewInterval)
	}
	return card
}

func checkStabilityAndDifficulty(t *testing.T, expectedStability, expectedDifficulty float64, card Card) {
	if math.Abs(expectedStability-card.Stability) > 1e-4 {
		t.Errorf("Expected stability %v, but got %v", expectedStability, card.Stability)
	}
	if math.Abs(expectedDifficulty-card.Difficulty) > 1e-4 {
		t.Errorf("Expected difficulty %v, but got %v", expectedDifficulty, card.Difficulty)
	}
}
