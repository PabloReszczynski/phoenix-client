(ns phoenix-client.socket
  (:require [phoenix-client.push :refer [make-push] :as p]
            [phoenix-client.channel :as ch]
            [phoenix-client.helpers :refer [make-message encode-message decode-message]]
            [phoenix-client.websocket :as ws]
            [clojure.spec.alpha :as s]
            [clojure.core.match :refer [match]]
            [overtone.at-at :refer [mk-pool every]]))

(def timer-pool (mk-pool))

(s/def ::state
  #{:connecting :open :closing :closed})

(s/def ::msg-type
  #{:no-op
    :external-msg
    :channel-errored
    :channel-closed
    :channel-joined
    :receive-reply
    :heartbeat})

(def default-timeout 10000)
(def ws-close-normal 1000)

(defn make-socket [endpoint]
  {:endpoint endpoint
   :ref 0
   :heartbeat-interval-seconds 30})

(defn send-message [path message]
  (ws/emit path (encode-message message)))

(defn emit [{:keys [endpoint ref]} event channel payload]
  (send-message endpoint (make-message event channel payload ref)))

(defn push [pusheable socket]
  (-> socket
      (update-in [:pushes] assoc (:ref socket) pusheable)
      (update-in [:ref] inc)
      (emit (:event pusheable) (:channel pusheable) (:payload pusheable))))

(defn on-channel-errored [channel-name socket]
  [(update-in socket [:channels channel-name] assoc :state :errored)
   nil])

(defn on-channel-closed [channel-name socket]
  (let [channel (get-in socket [:channels channel-name])]
    (if (nil? channel)
      [socket nil]
      [(-> socket
           (update-in [:channels channel-name] assoc :state :closed)
           (update-in [:pushes] dissoc (:join-ref channel)))
       nil])))

(defn on-channel-joined [channel-name socket]
  (let [channel (get-in socket [:channels channel-name])]
    (if (nil? channel)
      [socket nil]
      [(-> socket
           (update-in [:channels channel-name] assoc :state :joined)
           (update-in [:pushes] dissoc (:join-ref channel)))
       nil])))


(defn on-heartbeat [socket]
  (let [pusheable (make-push "heartbeat" "phoenix")]
    (push pusheable socket)))

(defn update-socket [{:keys [content type]} socket]
  (match [type]
         [:channel-errored] (on-channel-errored content socket)
         [:channel-closed] (on-channel-closed content socket)
         [:channel-joined] (on-channel-joined content socket)
         [:heartbeat] (on-heartbeat socket)
         :else [socket nil]))

(defn join-channel [channel socket]
  (let [pusheable (make-push (:name channel)
                             (:payload channel)
                             (:on-join channel)
                             (:on-join-error channel))
        channel (merge channel {:state :joining
                                :join-ref (:ref socket)})
        socket (update-in socket [:channels] assoc (:name channel) channel)]
    (push pusheable socket)))

(defn join [channel socket]
  (let [state (get-in socket [:channels (:name channel) :state])]
    (if (nil? state)
      (join-channel channel socket)
      (if (or (= state :joined) (= state :joining))
        [socket nil]))))

(defn leave [channel-name socket]
  (let [channel (get-in socket [:channels channel-name])]
    (if (nil? channel)
      (if (or (= (:state channel) :joining) (= (:state channel) :joined))
        (let [pusheable (make-push "phx_leave" channel-name)
              channel (merge channel {:state :leaving
                                      :leave-ref (:ref socket)})
              socket (assoc-in socket [:channels channel-name] channel)]
          (push pusheable))
        [socket nil])
      [socket nil])))

(defn heartbeat [socket]
  (let [pusheable (make-push "heartbeat" "phoenix")]
    (push pusheable socket)))

(defn on [event-name channel-name on-receive socket]
  (assoc-in socket [:events [event-name channel-name]] on-receive))

(defn off [event-name channel-name socket]
  (update-in socket [:events] dissoc [event-name channel-name]))

(defn phoenix-messages [socket]
  (ws/listen (:path socket) decode-message))

(defn heartbeat-sub [socket]
  (every (* 1000 (:heartbeat-interval-seconds socket))
         heartbeat
         timer-pool))

;; TODO
(defn handle-internal-reply [socket message])

(defn map-internal-msgs [socket msg]
  (if (nil? msg)
    {:type :no-op}
    (match [(:event msg)]
           ["phx_reply"] (handle-internal-reply socket msg)
           ["phx_error"] {:type :channel-errored :content (:topic msg)}
           ["phx_close"] {:type :channel-closed :content (:topic msg)}
           :else {:type :no-op})))


(defn internal-msgs [socket]
  (map (partial map-internal-msgs socket) (phoenix-messages socket)))

;; TODO
(defn handle-reply [socket message])

(defn map-external-msgs [socket msg]
  (if (nil? msg)
    {:type :no-op}
    (match [(:event msg)]
           ["phx_reply"] (handle-reply socket msg)
           ["phx_error"] (let [channel (get-in socket [:channels (:topic msg)])
                               on-error (:on-error channel)]
                           {:type :external-msg :content (on-error (:payload msg))})
           ["pgx_close"] (let [channel (get-in socket [:channels (:topic msg)])
                               on-close (:on-close channel)]
                           {:type :external-msg :content (on-close (:payload msg))})
           :else {:type :no-op})))

(defn external-msgs [socket]
  (map (partial map-external-msgs socket) (phoenix-messages socket)))

(defn handle-event [socket msg]
  (let [event (get-in socket [:events [(:event msg) (:topic msg)]])]
    (if (nil? event)
      {:type :no-op}
      {:type :external-msg :content (:payload msg)})))


(defn fmap [f socket]
  (-> socket
      (update-in [:channels] (fn [[key channel]] (into {} [key (ch/fmap f channel)])))
      (update-in [:events] (fn [[key event]] (into {} [key (comp f event)])))
      (update-in [:pushes] (fn [[key push]] (into {} [key (p/fmap f push)])))))
