(ns carmine-sentinel.core-test
  (:require [clojure.test :refer :all]
            [taoensso.carmine :as car]
            [carmine-sentinel.core :refer :all]))


;;; NOTE:
;;; add the following to `redis.conf`:
;;; requirepass foobar

;;; NOTE:
;;; SENTINEL CONF `PORT` SHOULD BE DIFFERENT FOR EACH SENTINEL
;;; MAKE SURE TO REMOVE `myid` OR HARDCODE DIFFERENT IDS FOR EACH SENTINEL
;;; NOTE:
;;; Use different files for each sentinel

;;; > cat sentinel.conf
;;; port 5000
;;; daemonize no
;;; pidfile "/var/run/redis-sentinel.pid"
;;; logfile ""
;;; dir "/tmp"
;;; sentinel deny-scripts-reconfig yes
;;; sentinel monitor mymaster 127.0.0.1 6379 2
;;; protected-mode no
;;; requirepass "foobar"


(defn- get-env
  ([k]
   (get-env k nil))
  ([k default]
   (or (System/getenv k)
       default)))

(def sentinel-master "mymaster")
(def sentinel-group :group1)
(def redis-master-spec
  (->> (get-env "REDIS_MASTER_SPEC" "127.0.0.1:6379")
       parse-specs
       first))
(def sentinel-specs
  (->> (get-env "SENTINEL_SPECS" "127.0.0.1:5000")
       parse-specs))
(def server-conn
  {:pool {}
   :spec (dissoc redis-master-spec :host :port)
   :sentinel-group sentinel-group
   :master-name sentinel-master})

(set-sentinel-groups!
 {sentinel-group
  {:specs sentinel-specs}})


(deftest resolve-master-spec
  (testing "Try to resolve the master's spec using the sentinels' specs"
    (is (=
         [redis-master-spec ()]
         (let [server-conn     {:pool {},
                                :spec (dissoc redis-master-spec :host :port),
                                :sentinel-group :group1,
                                :master-name sentinel-master}
               specs           sentinel-specs]
           (@#'carmine-sentinel.core/try-resolve-master-spec
            server-conn specs sentinel-group sentinel-master))))))

(deftest subscribing-all-sentinels
  (testing "Check if sentinels are subscribed to correctly"
    (is (= sentinel-specs
           (@#'carmine-sentinel.core/subscribe-all-sentinels
            sentinel-group
            sentinel-master)))))

(deftest asking-sentinel-master
  (testing "Testing if master is found through sentinel"
    (is (= redis-master-spec
           (@#'carmine-sentinel.core/ask-sentinel-master
            sentinel-group
            sentinel-master
            server-conn)))))

(deftest sentinel-redis-spec
  (testing "Trying to get redis spec by sentinel-group and master name"
    (is (= redis-master-spec
           (get-sentinel-redis-spec (:sentinel-group server-conn)
                                    (:master-name server-conn)
                                    server-conn)))))

(try
  (defmacro test-wcar* [& body] `(wcar server-conn ~@body))
  (catch Exception e
    (println "WARNING: caught exception while defining wcar*,"
             "can occur when re-running tests in the same repl."
             "Please verify and check if it isn't the cause of your tests' failure:"
             e)))


(deftest ping
  (testing "Checking if ping works."
    (is (= "PONG" (test-wcar* (car/ping))))))

