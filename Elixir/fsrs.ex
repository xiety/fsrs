defmodule Card do
  defstruct card_id: nil, interval: 0, phase: :new

  defmodule ReviewedCard do
    defstruct state: :learning, step: 0, stability: 0.0, difficulty: 0.0
  end

  defmodule Config do
    defstruct w: [
                0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666,
                0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912,
                0.0658, 0.1542
              ],
              desired_retention: 0.9,
              learning_steps: [1 / 1440, 10 / 1440],
              relearning_steps: [10 / 1440],
              maximum_interval: 36_500,
              enable_fuzzing: true
  end
end

defmodule Algorithm do
  alias Card.ReviewedCard

  @min_difficulty 1.0
  @max_difficulty 10.0
  @stability_min 0.001

  defp rating_to_value(:again), do: 1
  defp rating_to_value(:hard), do: 2
  defp rating_to_value(:good), do: 3
  defp rating_to_value(:easy), do: 4

  defp clamp_difficulty(difficulty), do: max(@min_difficulty, difficulty) |> min(@max_difficulty)
  defp clamp_stability(stability), do: max(stability, @stability_min)

  defp raw_initial_difficulty(w, rating) do
    rating_value = rating_to_value(rating)
    elem(w, 4) - :math.exp(elem(w, 5) * (rating_value - 1.0)) + 1.0
  end

  def initial_stability(w, rating) do
    w
    |> elem(rating_to_value(rating) - 1)
    |> clamp_stability()
  end

  def initial_difficulty(w, rating) do
    w
    |> raw_initial_difficulty(rating)
    |> clamp_difficulty()
  end

  def next_interval(factor, retention, decay, max_interval, stability) do
    (stability / factor) * (retention**(1.0 / decay) - 1.0)
    |> round()
    |> max(1)
    |> min(max_interval)
    |> Kernel.to_float()
  end

  def next_stability(w, data, retrievability, :again) do
    long_term =
      elem(w, 11) * data.difficulty**-elem(w, 12) *
        ((data.stability + 1.0)**elem(w, 13) - 1.0) *
        :math.exp((1.0 - retrievability) * elem(w, 14))

    short_term = data.stability / :math.exp(elem(w, 17) * elem(w, 18))

    min(long_term, short_term)
    |> clamp_stability()
  end

  def next_stability(w, data, retrievability, rating) do
    hard_penalty = if rating == :hard, do: elem(w, 15), else: 1.0
    easy_bonus = if rating == :easy, do: elem(w, 16), else: 1.0

    data.stability *
      (1.0 +
         :math.exp(elem(w, 8)) * (11.0 - data.difficulty) * data.stability**-elem(w, 9) *
           (:math.exp((1.0 - retrievability) * elem(w, 10)) - 1.0) * hard_penalty *
           easy_bonus)
    |> clamp_stability()
  end

  def next_difficulty(w, difficulty, rating) do
    rating_value = rating_to_value(rating)
    delta_difficulty = -(elem(w, 6) * (rating_value - 3.0))

    damped_delta =
      (@max_difficulty - difficulty) * delta_difficulty / (@max_difficulty - @min_difficulty)

    initial_easy_difficulty = raw_initial_difficulty(w, :easy)

    elem(w, 7) * initial_easy_difficulty +
      (1.0 - elem(w, 7)) * (difficulty + damped_delta)
    |> clamp_difficulty()
  end

  def short_term_stability(w, stability, rating) do
    rating_value = rating_to_value(rating)

    increase =
      :math.exp(elem(w, 17) * (rating_value - 3.0 + elem(w, 18))) *
        stability**-elem(w, 19)

    final_increase = if rating in [:good, :easy], do: max(increase, 1.0), else: increase

    stability * final_increase
    |> clamp_stability()
  end
end

defmodule Scheduler do
  alias Card
  alias Card.{ReviewedCard, Config}
  alias Algorithm

  defmodule State do
    defstruct config: nil, w: nil, decay: 0.0, factor: 0.0
  end

  @fsrs5_default_decay 0.5

  def create(config \\ %Config{}) do
    with {:ok, w} <- check_and_fill_parameters(config.w) do
      decay = -elem(w, 20)
      factor = 0.9**(1.0 / decay) - 1.0
      state = %State{config: config, w: w, decay: decay, factor: factor}
      {:ok, state}
    end
  end

  def review_card(scheduler_state, card, rating, review_interval \\ 0) do
    card
    |> calculate_next_reviewed_state(scheduler_state, rating, review_interval)
    |> determine_next_phase_and_interval(scheduler_state, rating)
    |> apply_fuzzing(scheduler_state)
    |> update_card(card)
  end

  def calculate_next_interval(scheduler_state, stability) do
    Algorithm.next_interval(
      scheduler_state.factor,
      scheduler_state.config.desired_retention,
      scheduler_state.decay,
      scheduler_state.config.maximum_interval,
      stability
    )
  end

  defp update_card({final_reviewed, final_interval}, original_card) do
    %{original_card | interval: final_interval, phase: final_reviewed}
  end

  defp check_and_fill_parameters(w_list) when is_list(w_list) do
    filled =
      case length(w_list) do
        17 ->
          w5 = Enum.at(w_list, 5)
          w_list
          |> List.update_at(4, &(&1 + w5 * 2.0))
          |> List.update_at(5, fn _ -> :math.log(w5 * 3.0 + 1.0) / 3.0 end)
          |> List.update_at(6, &(&1 + 0.5))
          |> Kernel.++([0.0, 0.0, 0.0, @fsrs5_default_decay])
        19 ->
          w_list ++ [0.0, @fsrs5_default_decay]
        21 ->
          w_list
        _ ->
          :error
      end
    if filled == :error,
      do: {:error, "Invalid number of parameters. Supported: 17, 19, or 21."},
      else:
        if Enum.any?(filled, fn val -> !is_float(val) or !Float.is_finite(val) end),
          do: {:error, "Invalid parameters: contains non-finite values."},
          else: {:ok, List.to_tuple(filled)}
  end

  defp calculate_next_reviewed_state(%Card{phase: :new}, scheduler, rating, _) do
    stability = Algorithm.initial_stability(scheduler.w, rating)
    difficulty = Algorithm.initial_difficulty(scheduler.w, rating)
    %ReviewedCard{state: :learning, step: 0, stability: stability, difficulty: difficulty}
  end

  defp calculate_next_reviewed_state(%Card{phase: data}, scheduler, rating, review_interval) do
    new_difficulty = Algorithm.next_difficulty(scheduler.w, data.difficulty, rating)
    new_stability =
      if review_interval < 1.0,
        do: Algorithm.short_term_stability(scheduler.w, data.stability, rating),
        else:
          get_card_retrievability(scheduler, data, review_interval)
          |> Algorithm.next_stability(scheduler.w, data, rating)
    %ReviewedCard{
      state: data.state,
      step: data.step,
      stability: new_stability,
      difficulty: new_difficulty
    }
  end

  defp get_card_retrievability(scheduler, card_data, review_interval) do
    elapsed_days = max(0.0, review_interval)
    (1.0 + scheduler.factor * elapsed_days / card_data.stability)**scheduler.decay
  end

  defp determine_next_phase_and_interval(
         %ReviewedCard{state: :learning} = reviewed,
         scheduler,
         rating
       ),
       do: handle_steps(scheduler, reviewed, rating, scheduler.config.learning_steps)

  defp determine_next_phase_and_interval(
         %ReviewedCard{state: :relearning} = reviewed,
         scheduler,
         rating
       ),
       do: handle_steps(scheduler, reviewed, rating, scheduler.config.relearning_steps)

  defp determine_next_phase_and_interval(
         %ReviewedCard{state: :review} = reviewed,
         scheduler,
         rating
       ),
       do: handle_review(scheduler, reviewed, rating)

  defp handle_review(scheduler, reviewed, :again) when scheduler.config.relearning_steps == [],
    do: to_review_state(scheduler, reviewed)

  defp handle_review(scheduler, reviewed, :again) do
    [h | _] = scheduler.config.relearning_steps
    {%{reviewed | state: :relearning, step: 0}, h}
  end

  defp handle_review(scheduler, reviewed, _), do: to_review_state(scheduler, reviewed)

  defp handle_steps(scheduler, reviewed, _, []), do: to_review_state(scheduler, reviewed)
  defp handle_steps(_, reviewed, :again, [h | _]), do: {%{reviewed | state: :learning, step: 0}, h}

  defp handle_steps(_, reviewed, :hard, steps) do
    interval =
      case {reviewed.step, steps} do
        {0, [s1]} -> s1 * 1.5
        {0, [s1, s2 | _]} -> (s1 + s2) / 2.0
        {step, _} -> Enum.at(steps, step)
      end
    {%{reviewed | state: reviewed.state}, interval}
  end

  defp handle_steps(scheduler, reviewed, :good, steps) do
    next_step = reviewed.step + 1
    handle_good_step(scheduler, reviewed, steps, next_step)
  end

  defp handle_steps(scheduler, reviewed, :easy, _), do: to_review_state(scheduler, reviewed)

  defp handle_good_step(scheduler, reviewed, steps, next_step) when next_step >= length(steps),
    do: to_review_state(scheduler, reviewed)

  defp handle_good_step(_, reviewed, steps, next_step) do
    {%{reviewed | state: :learning, step: next_step}, Enum.at(steps, next_step)}
  end

  defp to_review_state(scheduler, reviewed) do
    interval = calculate_next_interval(scheduler, reviewed.stability)
    {%{reviewed | state: :review, step: 0}, interval}
  end

  defp apply_fuzzing({%ReviewedCard{state: :review} = reviewed, interval}, scheduler)
       when scheduler.config.enable_fuzzing and interval >= 2.5 do
    fuzzed_interval = get_fuzzed_interval(scheduler.config.maximum_interval, interval)
    {reviewed, fuzzed_interval}
  end

  defp apply_fuzzing({reviewed, interval}, _) do
    {reviewed, interval}
  end

  defp get_fuzzed_interval(max_interval, interval) do
    fuzz_ranges = [
      %{start: 2.5, end: 7.0, factor: 0.15},
      %{start: 7.0, end: 20.0, factor: 0.1},
      %{start: 20.0, end: :infinity, factor: 0.05}
    ]
    delta =
      Enum.reduce(fuzz_ranges, 0.0, fn range, acc ->
        range_end = if range.end == :infinity, do: interval, else: range.end
        acc + range.factor * max(0.0, min(interval, range_end) - range.start)
      end)
    min_ivl = round(interval - delta) |> max(2)
    max_ivl = round(interval + delta)
    if min_ivl >= max_ivl,
      do: min(min_ivl, max_interval) |> Kernel.to_float(),
      else:
        :rand.uniform(max_ivl - min_ivl + 1) + min_ivl - 1
        |> min(max_interval)
        |> Kernel.to_float()
  end
end
