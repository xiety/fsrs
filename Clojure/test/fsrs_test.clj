(ns fsrs-test
  (:require [clojure.test :refer [deftest is]]
            [fsrs :as fsrs])
  (:import [java.time Duration]
           [java.util Random]))

(def ^:private rand-inst (Random.))

(defmacro float-is-near
  [actual expected tolerance]
  `(let [actual# ~actual
         expected# ~expected
         tolerance# ~tolerance
         diff# (abs (- actual# expected#))]
     (is (< diff# tolerance#)
         (str "Values not within tolerance " tolerance# ".\n"
              "Expected: " expected# "\n"
              "Actual:   " actual# "\n"
              "Diff:     " diff#))))

(defn- run-reviews
  [review-card-fn reviews]
  (reduce
   (fn [card [rating interval-days]]
     (review-card-fn rand-inst card rating (Duration/ofDays (double interval-days))))
   (fsrs/new-card)
   reviews))

(defn- check-stability-and-difficulty [expected-stability expected-difficulty card]
  (float-is-near (:stability card) expected-stability 1e-4)
  (float-is-near (:difficulty card) expected-difficulty 1e-4))

(defn- change-card [interval-days stability difficulty card]
  (merge card
         {:interval (Duration/ofDays (long interval-days))
          :stability stability
          :difficulty difficulty}))

(deftest next-interval-test
  (let [config (assoc fsrs/default-config
                      :learning-steps []
                      :enable-fuzzing false
                      :maximum-interval Integer/MAX_VALUE)
        desired-retentions (map #(/ (double %) 10.0) (range 1 11))]
    (is (= [3116769 34793 2508 387 90 27 9 3 1 1]
           (map (fn [r]
                  (let [scheduler-state (#'fsrs/build-scheduler-state (assoc config :desired-retention r))
                        interval (#'fsrs/next-interval scheduler-state 1.0)]
                    (int (.toDays interval))))
                desired-retentions)))))

(deftest fsrs-process-test
  (let [{:keys [review-card]} (fsrs/create (merge fsrs/default-config
                                                  {:learning-steps []
                                                   :relearning-steps []
                                                   :enable-fuzzing false}))
        ratings [:again :good :good :good :good :good]
        [_ actual-intervals] (reduce
                              (fn [[card intervals] rating]
                                (let [updated-card (review-card rand-inst card rating (:interval card))
                                      new-interval (int (.toDays (:interval updated-card)))]
                                  [updated-card (conj intervals new-interval)]))
                              [(fsrs/new-card) []]
                              ratings)]
    (is (= [1 2 6 17 44 102] actual-intervals))))

(deftest memo-state-test
  (let [w [0.6845422 1.6790825 4.7349424 10.042885 7.4410233 0.64219797 1.071918 0.0025195254 1.432437 0.1544
           0.8692766 2.0696752 0.0953 0.2975 2.4691248 0.19542035 3.201072 0.18046261 0.121442534]
        {:keys [review-card]} (fsrs/create (merge fsrs/default-config {:w w}))
        reviews [[:again 0] [:good 1] [:good 3] [:good 8] [:good 21]]
        final-card1 (run-reviews review-card reviews)]
    (check-stability-and-difficulty 31.722992 7.382128 final-card1)
    (let [card-mod (change-card 21 20.925528 7.005062 final-card1)
          final-card2 (review-card rand-inst card-mod :good (:interval card-mod))]
      (check-stability-and-difficulty 40.87456 6.9913807 final-card2))))

(deftest memory-state-test
  (let [reviews [[:again 0] [:good 0] [:good 1] [:good 3] [:good 8] [:good 21]]]
    (let [{:keys [review-card]} (fsrs/create)
          final-card1 (run-reviews review-card reviews)]
      (check-stability-and-difficulty 53.62691 6.3574867 final-card1))

    (let [{:keys [review-card]} (fsrs/create (merge fsrs/default-config
                                                    {:w (assoc (:w fsrs/default-config)
                                                               17 0.0, 18 0.0, 19 0.0)}))
          final-card2 (run-reviews review-card reviews)]
      (check-stability-and-difficulty 53.335106 6.3574867 final-card2))))

(deftest good-learning-steps-test
  (let [{:keys [review-card]} (fsrs/create)
        card (fsrs/new-card)
        card-after-good1 (review-card rand-inst card :good (:interval card))]
    (is (= :learning (:state card-after-good1)))
    (is (= 1 (:step card-after-good1)))
    (is (< (abs (- (.toMinutes (:interval card-after-good1)) 10.0)) 1e-2))

    (let [card-after-good2 (review-card rand-inst card-after-good1 :good (:interval card-after-good1))]
      (is (= :review (:state card-after-good2)))
      (is (>= (.toDays (:interval card-after-good2)) 1.0)))))

(deftest again-learning-steps-test
  (let [{:keys [review-card]} (fsrs/create)
        card (fsrs/new-card)
        card-after-again (review-card rand-inst card :again (:interval card))]
    (is (= :learning (:state card-after-again)))
    (is (= 0 (:step card-after-again)))
    (is (< (abs (- (.toMinutes (:interval card-after-again)) 1.0)) 1e-2))))

(deftest hard-rating-one-learning-step-test
  (let [{:keys [review-card]} (fsrs/create (merge fsrs/default-config
                                                  {:learning-steps [(Duration/ofMinutes 10.0)]}))
        card-after-hard (review-card rand-inst (fsrs/new-card) :hard Duration/ZERO)
        expected-secs (.getSeconds (Duration/ofMinutes (* 10.0 1.5)))]
    (is (< (abs (- (.getSeconds (:interval card-after-hard)) expected-secs)) 1.0))))

(deftest no-learning-steps-test
  (let [{:keys [review-card]} (fsrs/create (merge fsrs/default-config {:learning-steps []}))
        updated-card (review-card rand-inst (fsrs/new-card) :again Duration/ZERO)]
    (is (= :review (:state updated-card)))
    (is (>= (.toDays (:interval updated-card)) 1.0))))

(deftest maximum-interval-test
  (let [{:keys [review-card]} (fsrs/create (merge fsrs/default-config {:maximum-interval 100}))
        final-card (reduce
                    (fn [card _] (review-card rand-inst card :easy (:interval card)))
                    (fsrs/new-card)
                    (repeat 10 nil))]
    (is (<= (.toDays (:interval final-card)) 100))))

(deftest stability-lower-bound-test
  (let [{:keys [review-card]} (fsrs/create)
        stability-min 0.001]
    (reduce
     (fn [current-card _]
       (let [next-review-time (.plusDays (:interval current-card) 1)
             updated-card (review-card rand-inst current-card :again next-review-time)]
         (is (>= (:stability updated-card) stability-min))
         updated-card))
     (fsrs/new-card)
     (repeat 100 nil))))
