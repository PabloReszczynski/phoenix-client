(ns phoenix-client.core
  (:require [phoenix-client.socket :as socket]
            [phoenix-client.channel :as channel]
            [phoenix-client.push :as push]
            [phoenix-client.websocket :as ws]))

(def socket-server "ws://phoenixchat.herokuapp.com/ws")

(defn receive-message [value]
  (prn value))

(def soo (socket/make-socket socket-server))

(defn init-phx-socket []
  (->> soo
       (socket/join (channel/make-channel "rooms:lobby"))
       (socket/listen receive-message)))

(comment
  (defn emit [{:keys [path ref]} event channel payload]
    (send-message path (make-message event channel payload ref))))

(socket/emit soo "new:msg" "rooms:lobby" {:user "pablo" :body "caca"})
(socket/join-channel (channel/make-channel "rooms:lobby") soo)

(init-phx-socket)
