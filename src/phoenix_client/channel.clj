(ns phoenix-client.channel
  (:require [clojure.spec.alpha :as s]))

(s/def ::state
  #{:closed :errored :joined :joining :leaving})

(s/def ::event
  #{:phx-close :phx-error :phx-join :phx-reply :phx-leave})

(s/def ::valid-channel
  (s/keys :req-un [::state ::event]))

(defn make-channel [name]
  {:name name
   :payload {}
   :state :closed
   :on-close identity
   :on-error identity
   :on-join identity
   :on-join-error identity
   :join-ref -1
   :lave-ref -1})

(defn on-error [channel f]
  (assoc channel :on-error f))

(defn on-close [channel f]
  (assoc channel :on-close f))

(defn on-join [channel f]
  (assoc channel :on-join f))

(defn on-join-error [channel f]
  (assoc channel :on-join-error f))

(defn fmap [f channel]
  (let [transform (fn [g] (comp f g))]
    (-> channel
        (update-in [:on-close] transform)
        (update-in [:on-error] (transform))
        (update-in [:on-join] (transform))
        (update-in [:on-join-error] (transform)))))

