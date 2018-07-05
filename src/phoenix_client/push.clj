(ns phoenix-client.push
  (:require [clojure.spec.alpha :as s]))

(s/def ::event string?)
(s/def ::channel string?)
(s/def ::payload any?)

(s/def ::push
  (s/keys :req [::event ::channel ::payload]))

(defrecord Push [event channel payload])

(defn make-push [event channel payload & [on-ok on-error]]
  (merge (->Push event channel payload)
         {:on-ok (if (nil? on-ok) identity on-ok)
          :on-error (if (nil? on-error) identity on-ok)}))

(defn init [event channel]
  (make-push event channel {}))

(defn fmap [f push]
  (let [transform (fn [g] (comp f g))]
    (-> push
        (update-in [:on-ok] transform)
        (update-in [:on-error] transform))))
