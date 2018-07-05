(ns phoenix-client.helpers
  (:require [clojure.spec.alpha :as s]
            [clojure.data.json :as json]))

(s/def ::event string?)
(s/def ::topic string?)
(s/def ::payload any?)
(s/def ::ref int?)

(s/def ::message
  (s/keys :req-un [::event ::topic ::payload]
          :opt-un [::ref]))

(defrecord Message [event topic payload])

(defn make-message [event topic payload & [ref]]
  (merge (->Message event topic payload)
         {:event event
          :topic topic
          :payload payload
          :ref ref}))

(defn encode-message [msg]
  (json/write-str msg))

(defn decode-message [raw-msg]
  (json/read-str raw-msg :key-fn keyword))
