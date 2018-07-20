(ns phoenix-client.transports.websocket
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [phoenix-client.transports.core :refer [Transport]]))

(defonce socket (atom nil))

(defn get-socket [transport path]
  (let [socket @(:socket transport)]
    (if (or (nil? socket) (s/closed? socket))
      (let [conn @(http/websocket-client path)]
        (reset! socket conn)
        conn)
      socket)))

(defn emit [path json]
  (let [conn (get-socket path)]
    (println "outcoming: " json)
    (s/put! conn json)))

(defn listen [path cb]
  (let [conn (get-socket path)]
    (s/consume (fn [res]
                 (println "incoming: " res)
                 (cb res))
               conn)))

(defrecord Websocket [asocket]
  Transport
  (emit! [this path json]
    (let [conn (get-socket this path)]
      (s/put! conn json)))
  (listen! [this path cb]
    (let [conn (get-socket this path)]
      (s/consume cb conn))))

(defn make-websocket []
  (->Websocket (atom nil)))
