(ns fsrs
  (:import [java.time Duration]
           [java.util Random]))

(def ^:private ratings {:again 1, :hard 2, :good 3, :easy 4})

(def default-config
  {:w [0.212 1.2931 2.3065 8.2956 6.4133 0.8334 3.0194 0.001 1.8722 0.1666 0.796 1.4835 0.0614 0.2629 1.6483 0.6014 1.8729 0.5425 0.0912 0.0658 0.1542]
   :desired-retention 0.9
   :learning-steps [(Duration/ofMinutes 1) (Duration/ofMinutes 10)]
   :relearning-steps [(Duration/ofMinutes 10)]
   :maximum-interval 36500
   :enable-fuzzing true})

(defn new-card []
  {:phase :new, :interval Duration/ZERO})

(def ^:private min-difficulty 1.0)
(def ^:private max-difficulty 10.0)
(def ^:private stability-min 0.001)

(defn- clamp [min-val max-val val]
  (-> val (max min-val) (min max-val)))

(defn- clamp-difficulty [d]
  (clamp min-difficulty max-difficulty d))

(defn- clamp-stability [s]
  (max stability-min s))

(defn- raw-initial-difficulty [w rating]
  (let [rating-value (double (get ratings rating))]
    (+ 1.0 (- (w 4) (Math/exp (* (w 5) (dec rating-value)))))))

(defn- initial-stability [w rating]
  (-> (get w (dec (get ratings rating))) clamp-stability))

(defn- initial-difficulty [w rating]
  (-> (raw-initial-difficulty w rating) clamp-difficulty))

(defn- next-interval
  [{:keys [factor decay config]} stability]
  (let [{:keys [desired-retention maximum-interval]} config
        increase-factor (- (Math/pow desired-retention (/ 1.0 decay)) 1.0)
        raw-interval (* (/ stability factor) increase-factor)
        clamped-days (-> raw-interval
                         Math/round
                         (max 1)
                         (min maximum-interval))]
    (Duration/ofDays clamped-days)))

(defn- next-difficulty
  [{:keys [w]} difficulty rating]
  (let [initial-easy-difficulty (raw-initial-difficulty w :easy)
        rating-value (double (get ratings rating))
        delta-difficulty (* (- (w 6)) (- rating-value 3.0))
        damped-delta (/ (* (- max-difficulty difficulty) delta-difficulty)
                        (- max-difficulty min-difficulty))]
    (-> (+ (* (w 7) initial-easy-difficulty)
           (* (- 1.0 (w 7)) (+ difficulty damped-delta)))
        clamp-difficulty)))

(defn- next-stability
  [{:keys [w factor decay]}
   {:keys [stability difficulty]}
   rating
   review-interval]
  (let [retrievability (Math/pow (inc (/ (* factor (.toDays review-interval)) stability)) decay)]
    (-> (if (= rating :again)
          (let [long-term (* (w 11)
                             (Math/pow difficulty (- (w 12)))
                             (- (Math/pow (inc stability) (w 13)) 1.0)
                             (Math/exp (* (- 1.0 retrievability) (w 14))))
                short-term (/ stability (Math/exp (* (w 17) (w 18))))]
            (min long-term short-term))
          (let [hard-penalty (if (= rating :hard) (w 15) 1.0)
                easy-bonus (if (= rating :easy) (w 16) 1.0)]
            (* stability
               (+ 1.0 (* (Math/exp (w 8))
                         (- 11.0 difficulty)
                         (Math/pow stability (- (w 9)))
                         (- (Math/exp (* (- 1.0 retrievability) (w 10))) 1.0)
                         hard-penalty
                         easy-bonus)))))
        clamp-stability)))

(defmulti -calculate-reviewed-properties
  (fn [_scheduler card _rating _review-interval] (:phase card)))

(defmethod -calculate-reviewed-properties :new
  [{:keys [w]} _card rating _review-interval]
  {:state :learning, :step 0
   :stability (initial-stability w rating)
   :difficulty (initial-difficulty w rating)})

(defmethod -calculate-reviewed-properties :reviewed
  [{:keys [w] :as scheduler} {:keys [stability difficulty] :as card} rating review-interval]
  (let [new-difficulty (next-difficulty scheduler difficulty rating)
        new-stability (if (< (.toDays review-interval) 1.0)
                        (let [rating-value (double (get ratings rating))
                              increase (-> (Math/exp (* (w 17)
                                                        (+ (- rating-value 3.0) (w 18))))
                                           (* (Math/pow stability (- (w 19)))))
                              final-increase (if (#{:good :easy} rating) (max increase 1.0) increase)]
                          (-> (* stability final-increase) clamp-stability))
                        (next-stability scheduler card rating review-interval))]
    {:stability new-stability, :difficulty new-difficulty}))

(defn- transition-to-state [card state step interval]
  (assoc card :state state :step step :interval interval))

(defn- hard-interval-step [current-step steps]
  (cond
    (and (zero? current-step) (= 1 (count steps)))
    (-> (first steps) .toMinutes (* 1.5) Duration/ofMinutes)

    (and (zero? current-step) (> (count steps) 1))
    (-> (+ (.toMinutes (first steps)) (.toMinutes (second steps)))
        (/ 2.0)
        Duration/ofMinutes)

    :else (get steps current-step)))

(defn- handle-steps [scheduler card rating steps]
  (let [{:keys [state step stability]} card]
    (if (empty? steps)
      (transition-to-state card :review 0 (next-interval scheduler stability))
      (case rating
        :again (transition-to-state card state 0 (first steps))
        :hard (assoc card :interval (hard-interval-step step steps))
        :good (if (>= (inc step) (count steps))
                (transition-to-state card :review 0 (next-interval scheduler stability))
                (transition-to-state card state (inc step) (get steps (inc step))))
        :easy (transition-to-state card :review 0 (next-interval scheduler stability))))))

(defn- determine-next-phase-and-interval
  [scheduler card rating]
  (let [{:keys [config]} scheduler
        {:keys [learning-steps relearning-steps]} config
        {:keys [state stability]} card
        do-handle-steps (partial handle-steps scheduler card rating)]
    (case state
      :learning   (do-handle-steps learning-steps)
      :relearning (do-handle-steps relearning-steps)
      :review     (if (and (= rating :again) (seq relearning-steps))
                    (transition-to-state card :relearning 0 (first relearning-steps))
                    (transition-to-state card :review 0 (next-interval scheduler stability))))))

(defn- get-fuzzed-interval [^Random rand max-interval interval]
  (let [days (.toDays interval)]
    (if (< days 2.5)
      interval
      (let [delta (reduce (fn [acc {:keys [start end factor]}]
                            (+ acc (* factor (max 0.0 (- (min days end) start)))))
                          0.0
                          [{:start 2.5, :end 7.0, :factor 0.15}
                           {:start 7.0, :end 20.0, :factor 0.1}
                           {:start 20.0, :end Double/POSITIVE_INFINITY, :factor 0.05}])
            min-ivl (max 2 (int (Math/round (- days delta))))
            max-ivl (int (Math/round (+ days delta)))]
        (-> (.nextInt rand min-ivl (inc max-ivl))
            (min max-interval)
            long
            Duration/ofDays)))))

(defn- apply-fuzzing [scheduler ^Random rand {:keys [state]} interval]
  (if (and (get-in scheduler [:config :enable-fuzzing]) (= :review state))
    (get-fuzzed-interval rand (get-in scheduler [:config :maximum-interval]) interval)
    interval))

(defn- review-card-impl
  [scheduler ^Random rand card rating review-interval]
  (let [reviewed-properties (-calculate-reviewed-properties scheduler card rating review-interval)
        card-with-properties (merge card reviewed-properties)
        final-card (determine-next-phase-and-interval scheduler card-with-properties rating)
        final-interval (apply-fuzzing scheduler rand final-card (:interval final-card))]
    (assoc final-card :phase :reviewed :interval final-interval)))

(defn- check-and-fill-parameters [w]
  (let [fsrs-5-default-decay 0.5]
    (condp = (count w)
      17 (let [w-arr (to-array w)]
           (aset-double w-arr 4 (+ (* (aget w-arr 5) 2.0) (aget w-arr 4)))
           (aset-double w-arr 5 (/ (Math/log (inc (* (aget w-arr 5) 3.0))) 3.0))
           (aset-double w-arr 6 (+ (aget w-arr 6) 0.5))
           (vec (concat (vec w-arr) [0.0 0.0 0.0 fsrs-5-default-decay])))
      19 (vec (concat w [0.0 fsrs-5-default-decay]))
      21 (vec w)
      nil)))

(defn- build-scheduler-state [config]
  (let [w (check-and-fill-parameters (:w config))]
    (when (or (nil? w) (some #(not (Double/isFinite %)) w))
      (throw (IllegalArgumentException. "Invalid FSRS parameters provided.")))
    (let [decay (- (w 20))]
      {:config config
       :w w
       :decay decay
       :factor (- (Math/pow 0.9 (/ 1.0 decay)) 1.0)})))

(defn create
  ([config]
   (let [scheduler (build-scheduler-state config)]
     {:review-card (partial review-card-impl scheduler)}))
  ([] (create default-config)))
