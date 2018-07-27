(ns phoenix-client.socket
  (:require [phoenix-client.push :refer [make-push] :as p]
            [phoenix-client.channel :as ch]
            [phoenix-client.message :refer [make-message encode-message decode-message]]
            [phoenix-client.transports.core :as transports]
            [phoenix-client.transports.websocket :as ws]
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

(s/def ::endpoint string?)
(s/def ::debug boolean?)
(s/def ::channels (s/keys string? ::ch/channel))
(s/def ::events (s/keys (s/cat string? string?) fn?))
(s/def ::pushes (s/keys int? ::p/push))
(s/def ::ref int?)
(s/def ::heartbeat-interval-seconds float?)
(s/def ::without-heartbeat boolean?)

(s/def ::socket
  (s/keys :req-un [::endpoint
                   ::debug
                   ::channels
                   ::events
                   ::pushes
                   ::ref
                   ::heartbeat-interval-seconds
                   ::without-heartbeat]))

(def default-timeout 10000)
(def ws-close-normal 1000)

(def default-params
  {:debug false
   :heartbeat-interval-seconds 30
   :without-heartbeat false
   :transport (ws/make-websocket)})

(defn make-socket [endpoint & opts]
  (merge default-params
         (first opts)
         {:endpoint endpoint
          :channels {}
          :events {}
          :pushes {}
          :ref 0}))

(defn send-message! [path message transport]
  (transports/emit! transport path (encode-message message)))

(defn emit! [{:keys [endpoint ref transport]} event channel payload]
  (send-message! endpoint (make-message event channel payload ref) transport))

(defn push [pusheable socket]
  (let [socket (-> socket
                   (update-in [:pushes] assoc (:ref socket) pusheable)
                   (update-in [:ref] inc))]
    (emit! socket
           (:event pusheable)
           (:channel pusheable)
           (:payload pusheable))
    socket))

(defprotocol UpdateSocket
  (update-socket [this socket]))

(defrecord ChannelJoined [content]
  UpdateSocket
  (update-socket [this socket]
    (let [channel-name (:content this)
          channel (get-in socket [:channels channel-name])]
      (if-not (nil? channel)
        (-> socket
            (update-in [:channels channel-name] assoc :state :joined)
            (update-in [:pushes] dissoc (:join-ref channel)))
        socket))))

(defrecord ChannelErrored [content]
  UpdateSocket
  (update-socket [this socket]
    (let [channel-name (:content this)]
      (update-in socket [:channels channel-name] assoc :state :errored))))

(defrecord ChannelClosed [content]
  UpdateSocket
  (update-socket [this socket]
    (let [channel-name (:content this)
          channel (get-in socket [:channels channel-name])]
      (if-not (nil? channel)
        (-> socket
            (update-in [:channels channel-name] assoc :state :closed)
            (update-in [:pushes] dissoc (:join-ref channel)))
        socket))))

(defrecord ExternalMsg [content])

(defrecord NoOp []
  UpdateSocket
  (update-socket [this socket] socket))

(defn on-heartbeat [socket]
  (let [pusheable (make-push "heartbeat" "phoenix" {})]
    (push pusheable socket)))

(defn ^:private join-channel [channel socket]
  (let [pusheable (make-push "phx_join"
                             (:name channel)
                             (:payload channel)
                             (:on-join channel)
                             (:on-join-error channel))
        channel (merge channel {:state :joining
                                :join-ref (:ref socket)})
        socket (assoc-in socket [:channels (:name channel)] channel)]
    (push pusheable socket)))

(defn join [socket channel]
  (let [state (get-in socket [:channels (:name channel) :state])]
    (if (nil? state)
      (join-channel channel socket)
      (if (or (= state :joined) (= state :joining))
        socket))))

(defn leave [socket channel-name]
  (let [channel (get-in socket [:channels channel-name])]
    (if (nil? channel)
      (if (or (= (:state channel) :joining) (= (:state channel) :joined))
        (let [pusheable (make-push "phx_leave" channel-name)
              channel (merge channel {:state :leaving
                                      :leave-ref (:ref socket)})
              socket (assoc-in socket [:channels channel-name] channel)]
          (push pusheable))
        socket)
      socket)))

(defn heartbeat [socket]
  (let [pusheable (make-push "heartbeat" "phoenix")]
    (push pusheable socket)))

(defn on [event-name channel-name on-receive]
  (fn [socket]
    (assoc-in socket [:events [event-name channel-name]] on-receive)))

(defn off [event-name channel-name]
  (fn [socket]
    (update-in socket [:events] dissoc [event-name channel-name])))

(defn phoenix-messages [socket]
  (transports/listen! (:transport socket) (:endpoint socket) decode-message))

(defn heartbeat-sub [socket]
  (every (* 1000 (:heartbeat-interval-seconds socket))
         heartbeat
         timer-pool))

(defn handle-internal-reply [socket message]
  (let [status (get-in message [:payload :status])
        ref (:ref message)
        topic (:topic message)
        channel (get-in socket [:channels topic])]
    (if (= status "ok")
      (cond
        (= ref (:join-ref channel)) (->ChannelJoined topic)
        (= ref (:leave-ref channel)) (->ChannelClosed topic)
        :else (->NoOp))
      (->NoOp))))


(defmulti map-internal-msgs
  (fn [socket msg]
    (:event msg)))

(defmethod map-internal-msgs "phx_reply" [socket msg]
  (handle-internal-reply socket msg))

(defmethod map-internal-msgs "phx_error" [socket msg]
  (->ChannelErrored (:topic msg)))

(defmethod map-internal-msgs "phx_close" [socket msg]
  (->ChannelClosed (:topic msg)))

(defmethod map-internal-msgs :default [socket msg]
  (->NoOp))

(defn internal-msgs [socket]
  (map (partial map-internal-msgs socket) (phoenix-messages socket)))

(defn handle-reply [socket message]
  (let [status (get-in message [:payload :status])
        response (get-in message [:payload :response])
        ref (:ref message)
        push (get-in socket [:pushes ref])
        on-ok (:on-ok push)
        on-error (:on-error push)]
    (match [status]
           ["ok"] (->ExternalMsg (on-ok response))
           ["error"] (->ExternalMsg (on-error response))
           :else (->NoOp))))

(defmulti map-external-msgs
  (fn [socket msg]
    (:event msg)))

(defmethod map-external-msgs "phx_reply"
  [socket msg]
  (handle-reply socket msg))

(defmethod map-external-msgs "phx_error"
  [socket msg]
  (let [channel (get-in socket [:channels (:topic msg)])
        on-error (:on-error channel)]
    (->ExternalMsg (on-error (:payload msg)))))

(defmethod map-external-msgs "phx_close"
  [socket msg]
  (let [channel (get-in socket [:channels (:topic msg)])
        on-close (:on-close channel)]
    (->ExternalMsg (on-close (:payload msg)))))

(defmethod map-external-msgs :default
  [socket msg]
  (->NoOp))

(defn external-msgs [socket]
  (map (partial map-external-msgs socket) (phoenix-messages socket)))

(defn handle-event [socket msg]
  (let [event (get-in socket [:events [(:event msg) (:topic msg)]])]
    (if (nil? event)
      (->NoOp)
      (->ExternalMsg (:payload msg)))))

(defn listen [f socket]
  (map f [(internal-msgs socket)
          (external-msgs socket)
          (heartbeat-sub socket)]))


(defn fmap [f socket]
  (-> socket
      (update-in [:channels] (fn [[key channel]] (into {} [key (ch/fmap f channel)])))
      (update-in [:events] (fn [[key event]] (into {} [key (comp f event)])))
      (update-in [:pushes] (fn [[key push]] (into {} [key (p/fmap f push)])))))
