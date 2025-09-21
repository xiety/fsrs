export module FsrsCpp;

import <vector>;
import <chrono>;
import <cmath>;
import <random>;
import <span>;
import <numeric>;
import <stdexcept>;
import <algorithm>;
import <concepts>;

export namespace FsrsCpp {

    using days_t = std::chrono::duration<double, std::ratio<86400>>;
    using minutes_t = std::chrono::duration<double, std::ratio<60>>;

    enum class Rating { Again = 1, Hard, Good, Easy };
    enum class State { New, Learning, Review, Relearning };

    struct Card {
        long long card_id{};
        days_t interval{};
        double stability{};
        double difficulty{};
        State state{ State::New };
        int step{};
        static Card create(long long id);
    };

    struct SchedulerConfig {
        std::vector<double> parameters;
        double desired_retention;
        std::vector<days_t> learning_steps;
        std::vector<days_t> relearning_steps;
        int maximum_interval;
        bool enable_fuzzing;
        static const SchedulerConfig& get_default();
    };

    class Scheduler {
    public:
        Scheduler(const SchedulerConfig& cfg, std::mt19937& rand_gen);
        auto review_card(Card card, Rating rating, days_t review_interval) -> Card;
        auto calculate_next_review_interval(double stability) const -> days_t;

    private:
        auto get_long_term_stability(const Card& card, Rating rating, days_t review_interval) const -> double;
        auto calculate_initial_reviewed_card(Card card, Rating rating, days_t review_interval) -> Card;
        auto to_review_state(Card card) -> Card;
        auto handle_steps(Card card, Rating rating, std::span<const days_t> steps) -> Card;
        auto determine_next_phase_and_interval(Card reviewed_card, Rating rating) -> Card;
        auto apply_fuzzing(Card card) -> Card;
        auto check_and_fill_parameters(std::span<const double> p) -> std::vector<double>;
        const SchedulerConfig& config;
        std::mt19937& random;
        std::vector<double> w;
        double decay;
        double factor;
    };
}

namespace FsrsAlgorithm {
    using namespace FsrsCpp;

    static constexpr double MIN_DIFFICULTY = 1.0;
    static constexpr double MAX_DIFFICULTY = 10.0;
    static constexpr double STABILITY_MIN = 0.001;

    static auto clamp_difficulty(double d) -> double { return std::clamp(d, MIN_DIFFICULTY, MAX_DIFFICULTY); }
    static auto clamp_stability(double s) -> double { return std::max(s, STABILITY_MIN); }

    static auto raw_initial_difficulty(std::span<const double> w, Rating r) -> double {
        return w[4] - std::exp(w[5] * (static_cast<double>(r) - 1.0)) + 1.0;
    }

    static auto initial_stability(std::span<const double> w, Rating r) -> double {
        return clamp_stability(w[static_cast<int>(r) - 1]);
    }

    static auto initial_difficulty(std::span<const double> w, Rating r) -> double {
        return clamp_difficulty(raw_initial_difficulty(w, r));
    }

    static auto next_interval(double factor, double retention, double decay, int max_interval, double stability) -> days_t {
        auto interval_days = stability / factor * (std::pow(retention, 1.0 / decay) - 1.0);
        auto rounded_days = std::round(interval_days);
        return days_t(std::clamp(rounded_days, 1.0, static_cast<double>(max_interval)));
    }

    static auto short_term_stability(std::span<const double> w, double stability, Rating rating) -> double {
        auto r_val = static_cast<double>(rating);
        auto increase = std::exp(w[17] * (r_val - 3.0 + w[18])) * std::pow(stability, -w[19]);
        auto final_increase = (rating == Rating::Good || rating == Rating::Easy) ? std::max(increase, 1.0) : increase;
        return clamp_stability(stability * final_increase);
    }

    static auto next_difficulty(std::span<const double> w, double d, Rating r) -> double {
        auto delta = -(w[6] * (static_cast<double>(r) - 3.0));
        auto damped = (MAX_DIFFICULTY - d) * delta / (MAX_DIFFICULTY - MIN_DIFFICULTY);
        return clamp_difficulty(w[7] * raw_initial_difficulty(w, Rating::Easy) + (1.0 - w[7]) * (d + damped));
    }

    static auto calculate_recall_stability(std::span<const double> w, double difficulty, double stability, double retrievability, Rating r) -> double {
        auto recall_factor = std::exp(w[8]);
        auto difficulty_weight = 11.0 - difficulty;
        auto stability_decay = std::pow(stability, -w[9]);
        auto memory_factor = std::exp((1.0 - retrievability) * w[10]) - 1.0;
        auto hard_penalty = (r == Rating::Hard) ? w[15] : 1.0;
        auto easy_bonus = (r == Rating::Easy) ? w[16] : 1.0;
        auto stability_increase = recall_factor * difficulty_weight * stability_decay * memory_factor * hard_penalty * easy_bonus;
        return stability * (1.0 + stability_increase);
    }

    static auto next_stability(std::span<const double> w, double difficulty, double stability, double retrievability, Rating r) -> double {
        auto next = (r == Rating::Again)
            ? w[11] * std::pow(difficulty, -w[12]) * (std::pow(stability + 1.0, w[13]) - 1.0) * std::exp((1.0 - retrievability) * w[14])
            : calculate_recall_stability(w, difficulty, stability, retrievability, r);
        return clamp_stability(next);
    }
}

namespace FsrsCpp {

    Card Card::create(long long id) {
        return { id };
    }

    const SchedulerConfig& SchedulerConfig::get_default() {
        static const SchedulerConfig Default = {
            .parameters = {0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542},
            .desired_retention = 0.9,
            .learning_steps = {minutes_t(1.0), minutes_t(10.0)},
            .relearning_steps = {minutes_t(10.0)},
            .maximum_interval = 36500,
            .enable_fuzzing = true
        };
        return Default;
    }

    Scheduler::Scheduler(const SchedulerConfig& cfg, std::mt19937& rand_gen)
        : config(cfg),
        random(rand_gen),
        w(check_and_fill_parameters(config.parameters)),
        decay(-w[20]),
        factor(std::pow(0.9, 1.0 / decay) - 1.0) {
    }

    auto Scheduler::review_card(Card card, Rating rating, days_t review_interval) -> Card {
        auto reviewed_card = calculate_initial_reviewed_card(card, rating, review_interval);
        auto card_with_next_state = determine_next_phase_and_interval(reviewed_card, rating);
        return apply_fuzzing(card_with_next_state);
    }

    auto Scheduler::calculate_next_review_interval(double stability) const -> days_t {
        return FsrsAlgorithm::next_interval(factor, config.desired_retention, decay, config.maximum_interval, stability);
    }

    auto Scheduler::get_long_term_stability(const Card& card, Rating rating, days_t review_interval) const -> double {
        auto elapsed_days = std::max(0.0, review_interval.count());
        auto retrievability = std::pow(1.0 + factor * elapsed_days / card.stability, decay);
        return FsrsAlgorithm::next_stability(w, card.difficulty, card.stability, retrievability, rating);
    }

    auto Scheduler::calculate_initial_reviewed_card(Card card, Rating rating, days_t review_interval) -> Card {
        if (card.state == State::New) {
            card.stability = FsrsAlgorithm::initial_stability(w, rating);
            card.difficulty = FsrsAlgorithm::initial_difficulty(w, rating);
            card.state = State::Learning;
            card.step = 0;
            return card;
        }

        auto new_difficulty = FsrsAlgorithm::next_difficulty(w, card.difficulty, rating);
        auto new_stability = (review_interval.count() < 1.0)
            ? FsrsAlgorithm::short_term_stability(w, card.stability, rating)
            : get_long_term_stability(card, rating, review_interval);

        card.difficulty = new_difficulty;
        card.stability = new_stability;
        return card;
    }

    auto Scheduler::to_review_state(Card card) -> Card {
        card.interval = calculate_next_review_interval(card.stability);
        card.state = State::Review;
        card.step = 0;
        return card;
    }

    auto Scheduler::handle_steps(Card card, Rating rating, std::span<const days_t> steps) -> Card {
        if (steps.empty()) {
            return to_review_state(card);
        }

        switch (rating) {
        case Rating::Again:
            card.state = State::Learning;
            card.step = 0;
            card.interval = steps[0];
            break;
        case Rating::Hard: {
            card.state = State::Learning;
            auto hard_interval = (card.step == 0 && steps.size() >= 2) ? (steps[0] + steps[1]) / 2.0
                : (card.step == 0 && steps.size() == 1) ? steps[0] * 1.5
                : steps[card.step];
            card.interval = hard_interval;
            break;
        }
        case Rating::Good:
            if (card.step + 1 >= steps.size()) {
                return to_review_state(card);
            }
            card.state = State::Learning;
            card.step++;
            card.interval = steps[card.step];
            break;
        case Rating::Easy:
            return to_review_state(card);
        }
        return card;
    }

    auto Scheduler::determine_next_phase_and_interval(Card reviewed_card, Rating rating) -> Card {
        switch (reviewed_card.state) {
        case State::Learning:
            return handle_steps(reviewed_card, rating, config.learning_steps);
        case State::Relearning:
            return handle_steps(reviewed_card, rating, config.relearning_steps);
        case State::Review:
            if (rating == Rating::Again && !config.relearning_steps.empty()) {
                reviewed_card.state = State::Relearning;
                reviewed_card.step = 0;
                reviewed_card.interval = config.relearning_steps[0];
                return reviewed_card;
            }
            return to_review_state(reviewed_card);
        default:
            return reviewed_card;
        }
    }

    auto Scheduler::apply_fuzzing(Card card) -> Card {
        if (!config.enable_fuzzing || card.state != State::Review || card.interval.count() < 2.5) {
            return card;
        }

        auto interval_days = card.interval.count();
        auto delta_factor = [interval_days](double start, double end, double factor) {
            return factor * std::max(0.0, std::min(interval_days, end) - start);
        };

        auto delta = delta_factor(2.5, 7.0, 0.15) +
            delta_factor(7.0, 20.0, 0.1) +
            delta_factor(20.0, std::numeric_limits<double>::infinity(), 0.05);

        auto min_days = static_cast<int>(std::round(interval_days - delta));
        auto max_days = static_cast<int>(std::round(interval_days + delta));

        auto dis = std::uniform_int_distribution<>(min_days, max_days);
        auto fuzzed = dis(random);
        card.interval = days_t(std::clamp(fuzzed, 2, config.maximum_interval));
        return card;
    }

    auto Scheduler::check_and_fill_parameters(std::span<const double> p) -> std::vector<double> {
        if (std::ranges::any_of(p, [](double val) { return !std::isfinite(val); })) {
            throw std::invalid_argument("Invalid parameters: contains non-finite values.");
        }

        auto result = std::vector<double>(p.begin(), p.end());
        switch (p.size()) {
        case 17: result.insert(result.end(), { 0.0, 0.0, 0.0, 0.5 }); break;
        case 19: result.insert(result.end(), { 0.0, 0.5 }); break;
        case 21: break;
        default:
            throw std::invalid_argument("Invalid number of parameters. Supported: 17, 19, or 21.");
        }
        return result;
    }
}
