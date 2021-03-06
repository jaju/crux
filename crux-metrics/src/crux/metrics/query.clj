(ns ^:no-doc crux.metrics.query
  (:require [crux.bus :as bus]
            [crux.query :as q]
            [crux.metrics.dropwizard :as dropwizard]))

(defn assign-listeners
  [registry {:crux/keys [bus]}]
  (let [!timer-store (atom {})
        query-timer (dropwizard/timer registry ["query" "timer"])]
    (bus/listen bus {:crux/event-types #{::q/submitted-query
                                         ::q/completed-query
                                         ::q/failed-query}}
                (fn [{:keys [crux.query/query-id crux/event-type]}]
                  (case event-type
                    ::q/submitted-query
                    (swap! !timer-store assoc query-id (dropwizard/start query-timer))

                    (::q/completed-query ::q/failed-query)
                    (do
                      (dropwizard/stop (get @!timer-store query-id))
                      (swap! !timer-store dissoc query-id)))))
    {:query-timer query-timer
     :current-query-count (dropwizard/gauge registry
                                            ["query" "currently-running"]
                                            (fn [] (count @!timer-store)))}))
