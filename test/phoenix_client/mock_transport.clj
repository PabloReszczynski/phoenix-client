(ns phoenix-client.mock-transport
  (:require [phoenix-client.transports.core :refer [Transport]]
            [clojure.core.async :as async]))

(defrecord MockTransport [queue]
  Transport
  (emit! [this _ json]
    (async/go (async/>! (:queue this) json))
    this)
  (listen! [this _ cb]
    (async/go-loop []
      (cb (async/<! (:queue this)))
      (recur))
    this)
  (close! [this]
    (async/close! (:queue this))
    nil))


(defn make-mock-transport []
  (->MockTransport (async/chan)))
