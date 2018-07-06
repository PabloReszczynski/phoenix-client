(ns phoenix-client.websocket
  (:require [aleph.http :as http]
            [manifold.stream :as s]))

(defonce socket (atom nil))

@socket

(defn get-socket [path]
  (if (or (nil? @socket) (s/closed? @socket))
    (let [conn @(http/websocket-client path)]
      (reset! socket conn)
      conn)
    @socket))

(defn emit [path json]
  (let [conn (get-socket path)]
    (s/put! conn json)))

(defn listen [path cb]
  (let [conn (get-socket path)]
    (s/consume (fn [res]
                 (println "incoming: " res)
                 (cb res))
               conn)))
