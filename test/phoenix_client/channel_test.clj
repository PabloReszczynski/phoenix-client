(ns phoenix-client.channel-test
  (:require [phoenix-client.channel :refer :all]
            [clojure.test :refer :all]))

(deftest Channel
  (is (.equals (make-channel "rooms:lobby")
               {:name "rooms:lobby"
                :payload {}
                :state :closed
                :on-close identity
                :on-error identity
                :on-join identity
                :on-join-error identity
                :join-ref -1
                :leave-ref -1})))

