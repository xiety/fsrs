namespace Fsrs

open System

type Rating = | Again | Hard | Good | Easy

module Rating =
    let toValue = function | Again -> 1 | Hard -> 2 | Good -> 3 | Easy -> 4

type State = | Learning | Review | Relearning

type ReviewedCard = {
    State: State
    Step: int
    Stability: float
    Difficulty: float
}

type CardPhase =
    | New
    | Reviewed of ReviewedCard

type Card = {
    CardId: int64
    Interval: TimeSpan
    Phase: CardPhase
}

type SchedulerConfig = {
    W: float[]
    DesiredRetention: float
    LearningSteps: TimeSpan[]
    RelearningSteps: TimeSpan[]
    MaximumInterval: int
    EnableFuzzing: bool
}

type Scheduler = private {
    Config: SchedulerConfig
    W: float[]
    Decay: float
    Factor: float
}

type SchedulerApi = {
    ReviewCard : Card -> Rating -> TimeSpan -> Card
}

module internal FsrsAlgorithm =
    let private minDifficulty = 1.0
    let private maxDifficulty = 10.0
    let private stabilityMin = 0.001

    let private clampDifficulty difficulty = difficulty |> max minDifficulty |> min maxDifficulty
    let private clampStability stability = max stability stabilityMin

    let private rawInitialDifficulty (w: float[]) (rating: Rating) =
        let ratingValue = rating |> Rating.toValue |> float
        w.[4] - (exp (w.[5] * (ratingValue - 1.0))) + 1.0

    let initialStability (w: float[]) (rating: Rating) =
        w.[(Rating.toValue rating) - 1] |> clampStability

    let initialDifficulty (w: float[]) (rating: Rating) =
        rawInitialDifficulty w rating |> clampDifficulty

    let nextInterval (factor: float) (retention: float) (decay: float)
                     (maxInterval: int) (stability: float) =
        (stability / factor) * (retention ** (1.0 / decay) - 1.0)
        |> round |> int |> max 1 |> min maxInterval
        |> float |> TimeSpan.FromDays

    let shortTermStability (w: float[]) (stability: float) (rating: Rating) =
        let ratingValue = rating |> Rating.toValue |> float
        let increase =
            exp (w.[17] * (ratingValue - 3.0 + w.[18]))
            * (stability ** -w.[19])
        let finalIncrease = match rating with Good | Easy -> max increase 1.0 | _ -> increase
        stability * finalIncrease |> clampStability

    let nextDifficulty (w: float[]) (difficulty: float) (rating: Rating) =
        let ratingValue = rating |> Rating.toValue |> float
        let deltaDifficulty = -(w.[6] * (ratingValue - 3.0))
        let dampedDelta =
            (maxDifficulty - difficulty) * deltaDifficulty / (maxDifficulty - minDifficulty)
        let initialEasyDifficulty = rawInitialDifficulty w Easy
        w.[7] * initialEasyDifficulty
        + (1.0 - w.[7]) * (difficulty + dampedDelta)
        |> clampDifficulty

    let private nextForgetStability (w: float[]) (data: ReviewedCard)
                                    (retrievability: float) =
        let longTerm =
            w.[11] * (data.Difficulty ** -w.[12])
            * (((data.Stability + 1.0) ** w.[13]) - 1.0)
            * (exp ((1.0 - retrievability) * w.[14]))
        let shortTerm = data.Stability / (exp (w.[17] * w.[18]))
        min longTerm shortTerm

    let private nextRecallStability (w: float[]) (data: ReviewedCard)
                                    (retrievability: float) (rating: Rating) =
        let hardPenalty = if rating = Hard then w.[15] else 1.0
        let easyBonus = if rating = Easy then w.[16] else 1.0
        data.Stability
        * (1.0 + exp w.[8] * (11.0 - data.Difficulty) * (data.Stability ** -w.[9])
        * (exp ((1.0 - retrievability) * w.[10]) - 1.0) * hardPenalty * easyBonus)

    let nextStability (w: float[]) (data: ReviewedCard)
                      (retrievability: float) (rating: Rating) =
        match rating with
        | Again -> nextForgetStability w data retrievability
        | _ -> nextRecallStability w data retrievability rating
        |> clampStability

module Scheduler =
    open FsrsAlgorithm

    let DefaultConfig = {
        W = [| 0.212; 1.2931; 2.3065; 8.2956; 6.4133; 0.8334; 3.0194; 0.001; 1.8722; 0.1666; 0.796; 1.4835; 0.0614; 0.2629; 1.6483; 0.6014; 1.8729; 0.5425; 0.0912; 0.0658; 0.1542 |]
        DesiredRetention = 0.9
        LearningSteps = [| TimeSpan.FromMinutes 1.0; TimeSpan.FromMinutes 10.0 |]
        RelearningSteps = [| TimeSpan.FromMinutes 10.0 |]
        MaximumInterval = 36500
        EnableFuzzing = true
    }

    let private checkAndFillParameters (w: float[]) =
        let fsrs5DefaultDecay = 0.5
        let filled =
            match w.Length with
            | 17 ->
                let wn = Array.copy w
                wn.[4] <- wn.[5] * 2.0 + wn.[4]
                wn.[5] <- log (wn.[5] * 3.0 + 1.0) / 3.0
                wn.[6] <- wn.[6] + 0.5
                Array.concat [| wn; [| 0.0; 0.0; 0.0; fsrs5DefaultDecay |] |]
            | 19 -> Array.concat [| w; [| 0.0; fsrs5DefaultDecay |] |]
            | 21 -> w
            | _ -> raise (ArgumentException("Invalid number of parameters. Supported: 17, 19, or 21."))

        if filled |> Array.exists (Double.IsFinite >> not) then
            raise (ArgumentException("Invalid parameters: contains non-finite values."))

        filled

    let internal calculateNextReviewInterval (scheduler: Scheduler) (stability: float) =
        nextInterval
            scheduler.Factor
            scheduler.Config.DesiredRetention
            scheduler.Decay
            scheduler.Config.MaximumInterval
            stability

    let private calculateNextReviewedState (scheduler: Scheduler) (card: Card)
                                           (rating: Rating) (reviewInterval: TimeSpan) =
        let getCardRetrievability (cardData: ReviewedCard) =
            let elapsedDays = max 0.0 reviewInterval.TotalDays
            (1.0 + scheduler.Factor * elapsedDays / cardData.Stability) ** scheduler.Decay

        let (stability, difficulty) =
            match card.Phase with
            | New ->
                initialStability scheduler.W rating,
                initialDifficulty scheduler.W rating
            | Reviewed data ->
                let newDifficulty = nextDifficulty scheduler.W data.Difficulty rating
                let newStability =
                    if reviewInterval.TotalDays < 1.0 then
                        shortTermStability scheduler.W data.Stability rating
                    else
                        let retrievability = getCardRetrievability data
                        nextStability scheduler.W data retrievability rating
                (newStability, newDifficulty)

        let state, step = match card.Phase with | New -> Learning, 0 | Reviewed data -> data.State, data.Step
        { State = state; Step = step; Stability = stability; Difficulty = difficulty }

    let private toReviewState (scheduler: Scheduler) (reviewed: ReviewedCard) =
        let interval = calculateNextReviewInterval scheduler reviewed.Stability
        { reviewed with State = Review; Step = 0 }, interval

    let private hardIntervalStep (currentStep: int) (steps: TimeSpan[]) =
        match (currentStep, steps) with
        | (0, [| step1 |]) -> step1.TotalMinutes * 1.5 |> TimeSpan.FromMinutes
        | (0, [| step1; step2 |]) -> (step1.TotalMinutes + step2.TotalMinutes) / 2.0 |> TimeSpan.FromMinutes
        | _ -> steps.[currentStep]

    let private handleSteps (scheduler: Scheduler) (reviewed: ReviewedCard)
                            (rating: Rating) (steps: TimeSpan[]) =
        if Array.isEmpty steps then
            toReviewState scheduler reviewed
        else
            match rating with
            | Again -> { reviewed with State = Learning; Step = 0 }, steps.[0]
            | Hard ->
                let interval = hardIntervalStep reviewed.Step steps
                { reviewed with State = reviewed.State }, interval
            | Good ->
                let nextStep = reviewed.Step + 1
                if nextStep >= steps.Length then
                    toReviewState scheduler reviewed
                else
                    { reviewed with State = Learning; Step = nextStep }, steps.[nextStep]
            | Easy -> toReviewState scheduler reviewed

    let private determineNextPhaseAndInterval (scheduler: Scheduler) (reviewed: ReviewedCard)
                                              (rating: Rating) =
        let doHandleSteps = handleSteps scheduler reviewed rating

        match reviewed.State with
        | Learning -> doHandleSteps scheduler.Config.LearningSteps
        | Relearning -> doHandleSteps scheduler.Config.RelearningSteps
        | Review ->
            if rating = Again && not (Array.isEmpty scheduler.Config.RelearningSteps) then
                { reviewed with State = Relearning; Step = 0 }, scheduler.Config.RelearningSteps.[0]
            else
                toReviewState scheduler reviewed

    type private FuzzRange = { Start: float; End: float; Factor: float }
    let getFuzzedInterval (rand: Random) (maxInterval: int) (interval: TimeSpan) =
        let fuzzRanges = [
            { Start = 2.5; End = 7.0; Factor = 0.15 }
            { Start = 7.0; End = 20.0; Factor = 0.1 }
            { Start = 20.0; End = Double.PositiveInfinity; Factor = 0.05 }
        ]
        let intervalDays = interval.TotalDays
        if intervalDays < 2.5 then interval
        else
            let delta =
                fuzzRanges
                |> List.fold (fun acc range ->
                    acc + range.Factor * max 0.0 (min intervalDays range.End - range.Start)) 0.0
            let minIvl = intervalDays - delta |> round |> int |> max 2
            let maxIvl = intervalDays + delta |> round |> int
            rand.Next(minIvl, maxIvl + 1) |> min maxInterval |> float |> TimeSpan.FromDays

    let private applyFuzzing (rand: Random) (scheduler: Scheduler)
                             (reviewed: ReviewedCard) (interval: TimeSpan) =
        match scheduler.Config.EnableFuzzing, reviewed.State with
        | true, Review -> getFuzzedInterval rand scheduler.Config.MaximumInterval interval
        | _ -> interval

    let reviewCard (scheduler: Scheduler) (rand: Random) (card: Card)
                   (rating: Rating) (reviewInterval: TimeSpan) =
        let nextReviewedState = calculateNextReviewedState scheduler card rating reviewInterval
        let (finalReviewedState, baseInterval) =
            determineNextPhaseAndInterval scheduler nextReviewedState rating
        let finalInterval = applyFuzzing rand scheduler finalReviewedState baseInterval
        let updatedCard = { card with Interval = finalInterval; Phase = Reviewed finalReviewedState }
        updatedCard

    let createScheduler (config: SchedulerConfig) : Scheduler =
        let filledParams = checkAndFillParameters config.W
        let decay = -filledParams.[20]
        {
            Config = config
            W = filledParams
            Decay = decay
            Factor = 0.9 ** (1.0 / decay) - 1.0
        }

    let create (config: SchedulerConfig) (rand: Random) : SchedulerApi =
        let scheduler = createScheduler config
        {
            ReviewCard = reviewCard scheduler rand
        }
