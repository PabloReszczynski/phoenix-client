(ns phoenix-client.transports.core)

(defprotocol Transport
  (emit! [transport path json])
  (listen! [transport path cb]))
