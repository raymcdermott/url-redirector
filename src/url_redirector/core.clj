(ns url-redirector.core
  (:import (java.net URL))
  (:require [clojure.core.async :as async :refer [<! >! <!! chan timeout go]]
            [ring.util.request :as request]
            [ring.util.response :as response]
            [monger.core :as mg]
            [monger.collection :as mc]
            [taoensso.carmine :as redis :refer (wcar)]
            [qbits.jet.server :as jet]
            [qbits.jet.client.http :as http-client]
            [environ.core :refer [env]]))

; using defrecord because free constructor and documentation
(defrecord s3-cache-record [domain bucket brand country])
(defrecord url-record [scheme domain port path])

; -------*** MONGO HELPERS ... push out to another file
;
; read data from Mongo ... source of truth

(defn mongo-query [query fields]
  (let [mongo-uri (or (env :mongo-url) "mongodb://localhost/test")
        {:keys [conn db]} (mg/connect-via-uri mongo-uri)
        mongo-collection (or (env :mongo-collection) "redirections")
        result (mc/find-one-as-map db mongo-collection query fields)]
    (mg/disconnect conn)
    result))

(defn get-map-from-mongo [brand country]
  (let [{:keys [domain bucket]} (mongo-query {:brand brand :country country} [:domain :bucket])]
    (if (and domain bucket)
      (->s3-cache-record domain bucket brand country))))

; -------*** REDIS HELPERS ... push out to another file
;
; read data from REDIS ... source of speed

(defn get-redis-connection-pool []
  (let [spec {:pool {} :spec (if-let [uri (env :redis-url)]
                               {:uri uri}
                               {:host "127.0.0.1" :port 6379})}]
    spec))


(defn set-s3-map-in-cache!
  ([k v]
   (let [ttl (or (env :redis-ttl-seconds) 30)]
     (set-s3-map-in-cache! k v ttl)))
  ([k v ttl]
   (if (and k v ttl)
     (redis/wcar (get-redis-connection-pool) (redis/setex k ttl v)))))

(defn get-domain-from-cache [k]
  (if-let [value (redis/wcar (get-redis-connection-pool) (redis/get k))]
    value))

; -------*** WORK
;
; get data from REDIS or get data from Mongo
; and set the value into REDIS
;

(defn get-s3-map-from-storage [brand-country]
  (let [brand (first brand-country)
        country (second brand-country)]
    (if-let [cached-s3-map (get-domain-from-cache (str brand country))]
      cached-s3-map
      (if-let [s3-map (get-map-from-mongo brand country)]
        (and (set-s3-map-in-cache! (:brand s3-map) (:country s3-map))
             s3-map)
        nil))))

; Here we use functions rather than regex to allow us to inject
; data into the result in a dynamic manner rather than just when
; the regex is defined
; Compared to regexes this approach is
; less powerful
; less general
; probably slower
; However it is targetted at some specific URL management use cases so does
; not require such generality. It is simpler to encode, test and code functions
; for these specific rules
; One note on the implementation: the complexity of correctly parsing URLs is
; delegated to the tried and trusted network library in the JDK

; functions to split and re-compose URLs
(defn url-string-to-record [url-string]
  (let [url-object (URL. url-string)
        domain (.getHost url-object)
        path (.getPath url-object)
        scheme (.getProtocol url-object)
        port (.getPort url-object)]
    (->url-record scheme domain port path)))

; TODO - I don't like the -1 magic here
(defn url-string-from-record [url-parts-map]
  (let [port (if (= -1 (:port url-parts-map)) "" (str ":" (:port url-parts-map)))
        url-str (str (:scheme url-parts-map) "://"
                     (:domain url-parts-map)
                     port
                     (:path url-parts-map))]
    url-str))

(defn path-as-seq [path]
  (rest (seq (.split #"/" path))))

(defn find-brand-country-from-url [url]
  (let [url-object (url-string-to-record url)
        path-as-seq (path-as-seq (:path url-object))
        brand-from-path (first path-as-seq)
        country-from-path (second path-as-seq)]
    [brand-from-path country-from-path]))

(defn get-s3-map [url]
  (let [brand-country (find-brand-country-from-url url)]
    (get-s3-map-from-storage brand-country)))

; there can be several path-parsers

(defn s3-dropping-brand-path-parser [s3-map path]
  "if brand matches the first element of the path
  /brand/country/rest/of/path.xyz drop the /brand
  otherwise return path in tact"
  (let [path-as-seq (path-as-seq path)
        brand-from-path (first path-as-seq)
        target-path-as-seq (if (= (:brand s3-map) brand-from-path)
                             (drop 1 path-as-seq)
                             path-as-seq)]
    (apply str "/" (interpose "/" target-path-as-seq))))

(defn s3-dropping-brand-country-path-parser [s3-map path]
  "if brand and country matches the first elements of the path
  /brand/country/rest/of/path.xyz drop /brand/country
  otherwise return path in tact"
  (let [path-as-seq (path-as-seq path)
        brand-from-path (first path-as-seq)
        country-from-path (second path-as-seq)
        target-path-as-seq (if (and (= brand-from-path (:brand s3-map))
                                    (= country-from-path (:country s3-map)))
                             (drop 2 path-as-seq)
                             path-as-seq)]
    (apply str "/" (interpose "/" target-path-as-seq))))

(defn s3-retaining-all-path-parser [s3-map-unused path]
  "do nothing, return the path untouched"
  path)

; there can be several url transformers

; s3-map will have the properties :domain :bucket
; :domain is a URL (like "https://s3.eu-west.aws.com"
; :bucket is the name of an s3 bucket
; incoming url has this form: "http://example.redirector.com/<path>"
; path-parser is a transforming function for the path part of the URL
; output url is "https://s3.eu-west.aws.com/bucket/<transformed-path>"
(defn s3-lookup-url-parser [path-parser url]
  "returns the s3 url for the path from an incoming URL"
  (let [s3-map (get-s3-map url)
        s3-parts (url-string-to-record (:domain s3-map))
        source-parts (url-string-to-record url)
        target-path (path-parser s3-map (:path source-parts))]
    (url-string-from-record
      {:scheme (:scheme s3-parts)
       :domain (:domain s3-parts)
       :port   (:port s3-parts)
       :path   (str "/" (:bucket s3-map) target-path)})))

; the transformers and the path parsers are combined into partial functions
; that operate on given URLs

(defn s3-transform-dropping-brand [url]
  (let [target-url (s3-lookup-url-parser s3-dropping-brand-path-parser url)]
    (prn (str "s3-transform-dropping-brand from " url " to " target-url))
    target-url))

(defn s3-transform-dropping-brand-country [url]
  (let [target-url (s3-lookup-url-parser s3-dropping-brand-country-path-parser url)]
    (prn (str "s3-transform-dropping-brand-country from " url " to " target-url))
    target-url))

(defn s3-transform-full-path [url]
  (let [target-url (apply s3-lookup-url-parser s3-retaining-all-path-parser url)]
    (prn (str "s3-transform-full-path from " url " to " target-url))
    target-url))


; these names will be referenced from the mapping document in MongoDB

(def strategy-map-list [{:strategy-name "s3-transform-dropping-brand" :strategy-function s3-transform-dropping-brand}
                        {:strategy-name "s3-transform-dropping-brand-country" :strategy-function s3-transform-dropping-brand-country}
                        {:strategy-name "s3-transform-full-path" :strategy-function s3-transform-full-path}])

(def default-s3-transform-strategy {:strategy-name "s3-transform-dropping-brand" :strategy-function s3-transform-dropping-brand})


; -------*** GUARANTEED RESPONSE TIMES
;

(defn respond-within-sla [expected-result-milliseconds respond-ok respond-later route-transformer & args]
  (let [data-channel (timeout expected-result-milliseconds)
        response-channel (chan)]
    (go (if-let [data (<! data-channel)]
          (time (let [response (<! (respond-ok data))
                      response-headers (:headers response)]
                  (if (= 200 (:status response))
                    (>! response-channel {:body    (<! (:body response))
                                          :headers response-headers
                                          :status  200})
                    (>! response-channel {:body   (str "Problem getting data from S3. Status code: " (:status response))
                                          :status (:status response)}))))
          ; else, nothing back from the URL transformer
          (>! response-channel (respond-later))))
    (go
      (>! data-channel (apply route-transformer args)))
    response-channel))

(defn s3-data-fetcher [url]
  (http-client/get (http-client/client) url))

(defn sla-fail-message []
  {:body    (str "Unable to fetch S3 URL mapping data - please try again later")
   :headers {"Content-Type" "text/plain"}
   :status  500})

(defn generate-response [url]
  (let [sla (or (env :sla-milliseconds) 500)]
    (respond-within-sla sla s3-data-fetcher sla-fail-message s3-transform-dropping-brand-country url)))

(defn async-responder [request]
  (generate-response (request/request-url request)))

; -------*** START WEB SERVER
;

(def bufsize (* 32768 16))                                   ; does it make any difference?

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))]
    (jet/run-jetty {:output-buffer-size bufsize :port port :join? false :ring-handler async-responder})))