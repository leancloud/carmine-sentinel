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
(def redis-specs
  (->> (get-env "REDIS_SPECS" "127.0.0.1:6379")
       parse-specs))

;; assuming the first is master
(def redis-master-spec
  (assoc (first redis-specs)
         :timeout-ms 500))

(def redis-slave-specs
  (->> (rest redis-specs)
       (map #(assoc % :timeout-ms 500))))

(def sentinel-specs
  (->> (get-env "SENTINEL_SPECS" "127.0.0.1:5000")
       parse-specs))

(def server-conn
  {:pool {}
   :spec (-> redis-master-spec
             (dissoc :host :port)
             ;; add timeout here so we could test failover
             (assoc :timeout-ms 500))
   :sentinel-group sentinel-group
   :master-name sentinel-master})

(def slave-conn
  (assoc server-conn
         :slaves-balancer first
         :prefer-slave? true))

(set-sentinel-groups!
 {sentinel-group
  {:specs sentinel-specs}})

(deftest resolve-master-spec
  (testing "Try to resolve the master's spec using the sentinels' specs"
    (is (=
         [redis-master-spec redis-slave-specs]
         (@#'carmine-sentinel.core/try-resolve-master-spec
          server-conn sentinel-specs sentinel-group sentinel-master)))))

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

(deftest test-conn-fail-refresh
  (testing "A failed cmd will trigger refreshing redis nodes"
    (is (= "PONG" (test-wcar* (car/ping))))
    (future
      (car/wcar {:pool {}
                 :spec redis-master-spec}
                (car/redis-call [:debug "SLEEP" "2"])))
    (loop []
      (if-let [ret (try
                     (test-wcar* (car/ping))
                     (catch Exception ex
                       nil))]
        (is (= "PONG" ret))
        (do
          (is (= nil
                 (-> @#'carmine-sentinel.core/sentinel-resolved-specs
                     deref
                     (get sentinel-group))))
          (Thread/sleep 1000)
          (recur))))))

(deftest test-slave-conn-fail-refresh
  (testing "A failed slave cmd will trigger refreshing redis nodes"
    (is (= "PONG" (wcar slave-conn
                        (car/ping))))
    (future
      (car/wcar {:pool {}
                 :spec (first redis-slave-specs)}
                (car/redis-call [:debug "SLEEP" "2"])))
    (loop []
      (if-let [ret (try
                     (wcar slave-conn
                           (car/ping))
                     (catch Exception ex
                       nil))]
        (is (= "PONG" ret))
        (do
          (is (= nil
                 (-> @#'carmine-sentinel.core/sentinel-resolved-specs
                     deref
                     (get sentinel-group))))
          (Thread/sleep 1000)
          (recur))))))

(deftest test-update-conn-spec
  (let [server-password (-> server-conn :spec :password)
        sentinel-group  (:sentinel-group server-conn)
        master-name     (:master-name server-conn)
        check-fn        (fn [{old-spec :spec :as old-conn}
                             {new-spec :spec :as new-conn}]
                          (is (string? (:host new-spec)))
                          (is (number? (:port new-spec)))
                          (is (= (:db old-spec) (:db new-spec)))
                          (is (= (:password old-spec) (:password new-spec)))
                          (is (= (:sentinel-group old-conn) (:sentinel-group new-conn)))
                          (is (= (:master-name old-conn) (:master-name new-conn))))]
    (testing "Pass password in spec"
      (reset-resolved-specs sentinel-group master-name)

      (let [old-conn (assoc server-conn :spec {:password server-password})
            new-conn (update-conn-spec old-conn)]
        (check-fn old-conn new-conn)))
    (testing "Pass db in spec"
      (reset-resolved-specs sentinel-group master-name)

      (let [old-conn (assoc server-conn :spec {:password server-password :db 0})
            new-conn (update-conn-spec old-conn)]
        (check-fn old-conn new-conn))

      (let [old-conn (assoc server-conn :spec {:password server-password :db 1})
            new-conn (update-conn-spec old-conn)]
        (check-fn old-conn new-conn))

      (let [old-conn (assoc server-conn :spec {:password server-password :db 2})
            new-conn (update-conn-spec old-conn)]
        (check-fn old-conn new-conn)))))
