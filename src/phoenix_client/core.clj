(ns phoenix-client.core
  (:require [phoenix-client.socket :as socket]
            [phoenix-client.channel :as channel]
            [phoenix-client.push :as push]))


(def socket-server
  "http://localhost:4000/socket")

(defn receive-message [value]
  (prn value))

(def soo (socket/make-socket socket-server))

(defn init-phx-socket []
  (->> soo
       (socket/on "new:msg" "rooms:lobby" receive-message)))

(comment
  (defn emit [{:keys [path ref]} event channel payload]
    (send-message path (make-message event channel payload ref))))

(socket/emit soo "new:msg" "rooms:lobby" {:user "pablo" :body "caca"})

