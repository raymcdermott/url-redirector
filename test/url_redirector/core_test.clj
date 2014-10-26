(ns url-redirector.core-test
  (:import (java.net URL))
  (:require [clojure.test :refer :all]
            [functional-url-mapper.core :refer :all]))

(def test-brand "acme-fun-powder")
(def test-country "drawkcab-country")
(def test-s3-domain "https://s3-eu-west-1.amazonaws.com")
(def test-s3-bucket "ray-s3-test")

(def s3-map (->s3-cache-record test-s3-domain test-s3-bucket test-brand test-country))

(def test-path-remainder "path/elements/to/the/image.jpg")

(def path (str "/" test-brand "/" test-country "/" test-path-remainder))
(def non-b-path (str "/" test-country "/" test-path-remainder))
(def non-bc-path (str "/" test-path-remainder))

(deftest non-b-path-happy-test
  (testing "happy path - dropping brand path parser"
    (is (= non-b-path (s3-dropping-brand-path-parser s3-map path)))))

(deftest non-b-path-brand-mismatch-test
  (testing "brand mismatch - dropping brand path parser"
    (is (= non-bc-path (s3-dropping-brand-path-parser s3-map non-bc-path)))))

(def non-bc-path (s3-dropping-brand-country-path-parser s3-map path))

(deftest non-bc-path-happy-test
  (testing "happy path - dropping brand and country path parser"
    (is (= non-bc-path (s3-dropping-brand-country-path-parser s3-map path)))))

(deftest non-bc-path-brand-mismatch-test
  (testing "brand / country mismatch - dropping brand and country path parser"
    (is (= non-b-path (s3-dropping-brand-country-path-parser s3-map non-b-path)))))

(deftest noop-path-test
  (testing "happy path - noop path parser"
    (is (= path (s3-retaining-all-path-parser s3-map path)))))

(def source-portion "http://www.bar.com")

(def url-string (str source-portion path))

(deftest recreation-url-test
  (testing "breaking apart and reconstructing URLs"
    (is (= url-string (url-string-from-record (url-string-to-record url-string))))))

(def url-string-with-port (str source-portion ":8080" path))

(deftest recreation-url-with-port-test
  (testing "breaking apart and reconstructing URLs with a port number"
    (is (= url-string-with-port (url-string-from-record (url-string-to-record url-string-with-port))))))

(def s3-test-domain-portion (str test-s3-domain "/" test-s3-bucket))

(def s3-non-b-url (str s3-test-domain-portion non-b-path))
(deftest s3-non-b-url-test
  (testing "s3 - dropping brand"
    (is (= s3-non-b-url (s3-lookup-url-parser s3-dropping-brand-path-parser s3-map url-string)))))

(def s3-non-bc-url (str s3-test-domain-portion non-bc-path))
(deftest s3-non-bc-url-test
  (testing "s3 - dropping brand and country"
    (is (= s3-non-bc-url (s3-lookup-url-parser s3-dropping-brand-country-path-parser s3-map url-string)))))

(def s3-noop-url (str s3-test-domain-portion path))
(deftest s3-noop-url-test
  (testing "s3 - dropping nothing"
    (is (= s3-noop-url (s3-lookup-url-parser s3-retaining-all-path-parser s3-map url-string)))))

