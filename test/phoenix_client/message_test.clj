(ns phoenix-client.message-test
  (:require [phoenix-client.message :refer :all]
            [clojure.test :refer :all]))

(deftest Message
  (is (.equals (make-message "msg:new" "rooms:lobby" {:name "foo" :message "bar"} 0)
               {:event "msg:new"
                :topic "rooms:lobby"
                :payload {:name "foo"
                          :message "bar"}
                :ref 0}))
  (is (.equals (make-message "msg:new" "rooms:lobby" {:name "foo" :message "bar"})
               {:event "msg:new"
                :topic "rooms:lobby"
                :payload {:name "foo"
                          :message "bar"}
                :ref nil}))
  (is (= "{\"name\":\"foo\",\"message\":\"bar\"}"
         (encode-message {:name "foo" :message "bar"})))

  (is (= {:name "foo" :message "bar"}
         (decode-message "{\"name\":\"foo\",\"message\":\"bar\"}"))))
