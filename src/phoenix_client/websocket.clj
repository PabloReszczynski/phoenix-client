(ns phoenix-client.websocket
  (:require [org.httpkit.client :as http]))

(defn emit [path json]
  (let [options {:body json
                 :content-type "application/json"
                 :timeout 200
                 :keep-alive true}]
    (http/post path options (fn [res] (prn res)))))

(defn listen [path cb]
  (let [options {:timeout 200
                 :keep-alive true}]
    (http/get path options (fn [res] (prn res) (cb res)))))

