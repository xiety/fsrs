defmodule SchedulerTest do
  use ExUnit.Case, async: true

  alias Card
  alias Card.{Config, ReviewedCard}
  alias Scheduler

  defp run_reviews(scheduler_state, reviews) do
    initial_card = %Card{card_id: 1}

    Enum.reduce(reviews, initial_card, fn {rating, interval}, card ->
      Scheduler.review_card(scheduler_state, card, rating, interval)
    end)
  end

  defp check_stability_and_difficulty(card, expected_stability, expected_difficulty) do
    assert %ReviewedCard{
             stability: stability,
             difficulty: difficulty
           } = card.phase

    assert_in_delta stability, expected_stability, 1.0e-4
    assert_in_delta difficulty, expected_difficulty, 1.0e-4
  end

  test "TestNextInterval" do
    desired_retentions = for x <- 1..10, do: x / 10.0
    config = %Config{learning_steps: [], enable_fuzzing: false, maximum_interval: 1_000_000_000}

    actual =
      Enum.map(desired_retentions, fn r ->
        {:ok, scheduler_state} = Scheduler.create(%{config | desired_retention: r})
        Scheduler.calculate_next_interval(scheduler_state, 1.0)
        |> round()
      end)

    assert actual == [3_116_769, 34_793, 2_508, 387, 90, 27, 9, 3, 1, 1]
  end

  test "TestFsrs" do
    config = %Config{learning_steps: [], relearning_steps: [], enable_fuzzing: false}
    {:ok, scheduler_state} = Scheduler.create(config)
    initial_card = %Card{card_id: 1}
    ratings = [:again, :good, :good, :good, :good, :good]

    {_, actual_intervals} =
      Enum.reduce(ratings, {initial_card, []}, fn rating, {card, intervals} ->
        updated_card = Scheduler.review_card(scheduler_state, card, rating, card.interval)
        new_intervals = intervals ++ [round(updated_card.interval)]
        {updated_card, new_intervals}
      end)

    assert actual_intervals == [1, 2, 6, 17, 44, 102]
  end

  defp memo_state_weights,
    do: [
      0.6845422, 1.6790825, 4.7349424, 10.042885, 7.4410233, 0.64219797, 1.071918,
      0.0025195254, 1.432437, 0.1544, 0.8692766, 2.0696752, 0.0953, 0.2975, 2.4691248,
      0.19542035, 3.201072, 0.18046261, 0.121442534
    ]

  test "TestMemoState" do
    {:ok, scheduler_state} = Scheduler.create(%Config{w: memo_state_weights()})
    reviews = [again: 0, good: 1, good: 3, good: 8, good: 21]

    final_card1 = run_reviews(scheduler_state, reviews)
    check_stability_and_difficulty(final_card1, 31.722992, 7.382128)

    card_mod = %{
      final_card1
      | interval: 21,
        phase: %{final_card1.phase | stability: 20.925528, difficulty: 7.005062}
    }

    final_card2 = Scheduler.review_card(scheduler_state, card_mod, :good, card_mod.interval)
    check_stability_and_difficulty(final_card2, 40.87456, 6.9913807)
  end

  test "TestMemoryState" do
    {:ok, scheduler_state} = Scheduler.create(%Config{})
    reviews = [again: 0, good: 0, good: 1, good: 3, good: 8, good: 21]
    final_card1 = run_reviews(scheduler_state, reviews)
    check_stability_and_difficulty(final_card1, 53.62691, 6.3574867)

    w2_list =
      %Config{}.w
      |> List.replace_at(17, 0.0) |> List.replace_at(18, 0.0) |> List.replace_at(19, 0.0)
    {:ok, scheduler2_state} = Scheduler.create(%Config{w: w2_list})
    final_card2 = run_reviews(scheduler2_state, reviews)
    check_stability_and_difficulty(final_card2, 53.335106, 6.3574867)
  end

  test "TestGoodLearningSteps" do
    {:ok, scheduler} = Scheduler.create(%Config{})
    card = %Card{card_id: 1}

    card_after_good1 = Scheduler.review_card(scheduler, card, :good)
    assert %ReviewedCard{state: :learning, step: 1} = card_after_good1.phase
    assert_in_delta card_after_good1.interval * 1440, 10.0, 0.01

    card_after_good2 =
      Scheduler.review_card(scheduler, card_after_good1, :good, card_after_good1.interval)
    assert %ReviewedCard{state: :review} = card_after_good2.phase
    assert card_after_good2.interval >= 1.0
  end

  test "TestAgainLearningSteps" do
    {:ok, scheduler} = Scheduler.create(%Config{})
    card = %Card{card_id: 1}
    card_after_again = Scheduler.review_card(scheduler, card, :again)
    assert %ReviewedCard{state: :learning, step: 0} = card_after_again.phase
    assert_in_delta card_after_again.interval * 1440, 1.0, 0.01
  end

  test "TestLearningCardRateHardOneLearningStep" do
    config = %Config{learning_steps: [10 / 1440]}
    {:ok, scheduler_state} = Scheduler.create(config)
    card_after_hard = Scheduler.review_card(scheduler_state, %Card{}, :hard)

    expected_minutes = 10.0 * 1.5
    assert_in_delta card_after_hard.interval * 1440, expected_minutes, 0.01
  end

  test "TestNoLearningSteps" do
    {:ok, scheduler} = Scheduler.create(%Config{learning_steps: []})
    updated_card = Scheduler.review_card(scheduler, %Card{}, :again)
    assert %ReviewedCard{state: :review} = updated_card.phase
    assert updated_card.interval >= 1.0
  end

  test "TestMaximumInterval" do
    config = %Config{maximum_interval: 100}
    {:ok, scheduler} = Scheduler.create(config)
    card = %Card{card_id: 1}

    final_card =
      1..10
      |> Enum.reduce(card, fn _, current_card ->
        Scheduler.review_card(scheduler, current_card, :easy, current_card.interval)
      end)

    assert final_card.interval <= config.maximum_interval
  end

  test "TestStabilityLowerBound" do
    {:ok, scheduler} = Scheduler.create(%Config{})
    card = %Card{card_id: 1}
    stability_min = 0.001

    1..100
    |> Enum.reduce(card, fn _, current_card ->
      next_review_time = current_card.interval + 1.0
      updated_card = Scheduler.review_card(scheduler, current_card, :again, next_review_time)
      assert updated_card.phase.stability >= stability_min
      updated_card
    end)
  end
end
