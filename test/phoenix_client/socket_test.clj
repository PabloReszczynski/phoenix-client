(ns phoenix-client.socket-test
  (:require [phoenix-client.socket :refer :all]
            [phoenix-client.mock-transport :refer [make-mock-transport]]
            [phoenix-client.transports.core :refer [close!]]
            [clojure.test :refer :all]
            [clojure.core.async :as async]))

(deftest Socket

  (def atransport (atom nil))

  (defn make-default-socket [& [opts]]
    (let [transport @atransport]'
      (make-socket "ws://localhost:4000/socket"
                   (merge {:transport transport} opts))))

  (defn transport-fixture [f]
    (swap! atransport make-mock-transport)
    (f)
    (close! @atransport)
    (reset! atransport nil))

  (use-fixtures :each transport-fixture)

  (testing "make socket"
    (let [transport @atransport]
      (is (= (make-socket "ws://localhost:4000/socket"
                          {:transport transport})
             {:endpoint "ws://localhost:4000/socket"
              :channels {}
              :events {}
              :pushes {}
              :ref 0
              :debug false
              :heartbeat-interval-seconds 30
              :without-heartbeat false
              :transport transport}))))

  (testing "send-message!"
    (let [transport @atransport]
      (async/go (send-message! "ws://localhost:4000/socket"
                               "{\"name\": \"foo\", \"message\": \"bar\"}"
                               transport))
      (is (= (async/<!! (:queue transport))
             "{\"name\": \"foo\", \"message\": \"bar\"}")))))

