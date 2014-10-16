(ns functional-url-mapper.test_case
  (:import (java.net URL))
  (:require [clojure.core.async :as async :refer [<! >! <!! chan timeout go]]
            [qbits.jet.server :as jet]
            [qbits.jet.client.http :as http-client]))

(defn url-fetcher [url]
  (http-client/get (http-client/client) url))

(defn async-responder [url]
  (let [response-channel (chan)]
    (go (let [response (<! (url-fetcher url))
              response-headers (:headers response)]
          (if (= 200 (:status response))
            (>! response-channel {:body    (<! (:body response))
                                  :headers response-headers
                                  :status  200})
            (>! response-channel {:body   (str "Problem getting data from S3. Status code: " (:status response))
                                  :status (:status response)}))))
    response-channel))

(defn responder [request]
  ; ignore requst - hard code for the purpose of a minimal test case... this file is publicly available
  (async-responder "https://s3-eu-west-1.amazonaws.com/ray-s3-test/100k-file-0"))

; -------*** START WEB SERVER
;

; used to set output-buffer-size on jetty server, but makes no difference to this case
(def bufsize (* 32768 16))

(defn -main []
  (jet/run-jetty {:output-buffer-size bufsize :port 5000 :ring-handler responder}))