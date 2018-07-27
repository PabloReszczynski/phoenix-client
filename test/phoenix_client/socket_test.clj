(ns phoenix-client.socket-test
  (:require [phoenix-client.socket :refer :all]
            [phoenix-client.channel :refer [make-channel]]
            [phoenix-client.mock-transport :refer [make-mock-transport]]
            [phoenix-client.transports.core :refer [close!]]
            [phoenix-client.push :refer [make-push]]
            [clojure.data.json :as json]
            [clojure.test :refer :all]
            [clojure.core.async :as async]))

;; Helper functions by Leon Grapenthin:
;; https://stackoverflow.com/a/30781278
;; =============================================================================================

(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  (async/<!! ch))

(defn test-within
  "Asserts that ch does not close or produce a value within ms. Returns a
   channel from which the value can be taken."
  [ms ch]
  (async/go (let [t (async/timeout ms)
                  [v ch] (async/alts! [ch t])]
              (is (not= ch t)
                  (str "Test should have finished within " ms "ms."))
              v)))

;; =============================================================================================

(def atransport (atom nil))

(defn transport-fixture [f]
  (reset! atransport (make-mock-transport))
  (f)
  (close! @atransport))

(use-fixtures :each transport-fixture)

(deftest Socket

  (defn make-default-socket [& [opts]]
    (let [transport @atransport]
      (make-socket "ws://localhost:4000/socket"
                   (merge {:transport transport} opts))))

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
    (let [transport @atransport
          msg {:name "foo" :message "bar"}
          expected (json/write-str msg)]
      (async/go
        (send-message! "ws://localhost:4000/socket"
                       msg
                       transport))
      (test-async
       (test-within 200
                    (async/go
                      (is (= expected
                             (async/<!! (:queue transport)))))))))

  (testing "emit!"
    (let [socket (make-default-socket)
          transport @atransport
          expected (json/write-str {:event "phx_join"
                                    :topic "rooms:lobby"
                                    :payload {}
                                    :ref 0})]
      (async/go (emit! socket "phx_join" "rooms:lobby" {}))
      (test-async
       (test-within 200
                    (async/go
                      (is (= expected
                             (async/<!! (:queue transport)))))))))

  (testing "push"
    (let [socket (make-default-socket)
          transport @atransport
          pusheable (make-push "phx_join" "rooms:lobby" {})
          expected (json/write-str {:event "phx_join" :topic "rooms:lobby" :payload {} :ref 1})]
      (async/go (push pusheable socket))
      (test-async
       (test-within 200
                    (async/go
                      (is (= expected
                             (async/<!! (:queue transport)))))))))

  (testing "on-heartbeat"
    (let [socket (make-default-socket)
          transport @atransport
          expected (json/write-str {:event "heartbeat" :topic "phoenix" :payload {} :ref 1})]
      (async/go (on-heartbeat socket))
      (test-async
       (test-within 200
                    (async/go
                      (is (= expected
                             (async/<!! (:queue transport)))))))))

  (testing "join-channel"
    (let [channel (make-channel "rooms:lobby")
          socket (make-default-socket)
          msg-queue (:queue @atransport)
          expected (json/write-str {:event "phx_join"
                                    :topic "rooms:lobby"
                                    :payload {}
                                    :ref 1})]
      (async/go (#'join-channel channel socket))
      (test-async
       (test-within 200
                    (async/go
                      (is (= expected
                             (async/<!! msg-queue))))))))

  (testing "join should send a message"
    (let [channel (make-channel "rooms:lobby")
          socket (make-default-socket)
          msg-queue (:queue @atransport)
          expected (json/write-str {:event "phx_join"
                                    :topic "rooms:lobby"
                                    :payload {}
                                    :ref 1})]
      (async/go (-> socket
                    (join channel)))
      (test-async
       (test-within 200
                    (async/go
                      (is (= expected
                             (async/<!! msg-queue)))))))

    (testing "join should update the state of the socket"
      (let [channel (make-channel "rooms:lobby")
            socket (make-default-socket)
            msg-queue (:queue @atransport)]
        (is (= :joining
               (get-in (join socket channel) [:channels "rooms:lobby" :state])))
        (is (.equals {:event "phx_join"
                      :channel "rooms:lobby"
                      :payload {}
                      :on-ok identity
                      :on-error identity}
                     (get-in (join socket channel) [:pushes 0])))))

    (testing "when it receives an ok from the server after joining, the socket state should update"
      (let [channel (make-channel "rooms:lobby")
            socket (make-default-socket)
            server-msg (->ChannelJoined "rooms:lobby")]
        (is (= :joined
               (get-in (update-socket server-msg (join socket channel))
                       [:channels "rooms:lobby" :state])))))

    ;; FIXME I need the updated value of the socket
    (comment (testing "when it receives an ok from the server in the form of a msg, the socket state should update"
               (let [socket (make-default-socket {:channels {"rooms:lobby" (assoc (make-channel "rooms:lobby") :state :joining)}})
                     msg-queue (:queue @atransport)]
                 (async/go
                   (phoenix-messages socket)
                   (async/>!! msg-queue (json/write-str {:event "phx_reply" :status "ok" :ref 1})))
                 (test-async
                  (test-within 200
                               (async/go (is (= :joined
                                                (get-in socket)))))))))))
