(ns cmr.access-control.test.util
  (:require [cmr.transmit.access-control :as ac]
            [clojure.test :refer [is]]
            [clj-http.client :as client]
            [cmr.transmit.config :as config]
            [cmr.transmit.metadata-db2 :as mdb]
            [cmr.elastic-utils.config :as es-config]
            [cmr.metadata-db.config :as mdb-config]
            [cmr.common-app.test.side-api :as side]
            [cmr.common.mime-types :as mt]
            [cmr.message-queue.test.queue-broker-side-api :as qb-side-api]
            [cmr.common.util :as util]
            [clojure.string :as str]
            [cmr.umm-spec.umm-spec-core :as umm-spec]
            [cmr.umm-spec.test.expected-conversion :refer [example-collection-record]]))

(def conn-context-atom
  "An atom containing the cached connection context map."
  (atom nil))

(defn conn-context
  "Retrieves a context map that contains a connection to the access control app."
  []
  (when-not @conn-context-atom
    (reset! conn-context-atom {:system (config/system-with-connections
                                         {}
                                         [:ingest :access-control :echo-rest :metadata-db :urs])}))
  @conn-context-atom)

(defn refresh-elastic-index
  []
  (client/post (format "http://localhost:%s/_refresh" (es-config/elastic-port))))

(defn wait-until-indexed
  "Waits until all messages are processed and then flushes the elasticsearch index"
  []
  (qb-side-api/wait-for-terminal-states)
  (refresh-elastic-index))

(defn make-group
  "Makes a valid group"
  ([]
   (make-group nil))
  ([attributes]
   (merge {:name "Administrators2"
           :description "A very good group"}
          attributes)))

(defn- process-response
  "Takes an HTTP response that may have a parsed body. If the body was parsed into a JSON map then it
  will associate the status with the body otherwise returns a map of the unparsed body and status code."
  [{:keys [status body]}]
  (if (map? body)
    (assoc body :status status)
    {:status status
     :body body}))

(defn create-group
  "Creates a group."
  ([token group]
   (create-group token group nil))
  ([token group {:keys [allow-failure?] :as options}]
   (let [options (merge {:raw? true :token token} options)
         {:keys [status] :as response} (process-response (ac/create-group (conn-context) group options))]
     (when (and (not allow-failure?)
                (or (> status 299) (< status 200)))
       (throw (Exception. (format "Unexpected status [%s] when creating group" (pr-str response)))))
     response)))

(defn get-group
  "Retrieves a group by concept id"
  ([token concept-id params]
   (process-response (ac/get-group (conn-context) concept-id {:raw? true :token token :http-options {:query-params params}})))
  ([token concept-id]
   (get-group token concept-id nil)))

(defn update-group
  "Updates a group."
  ([token concept-id group]
   (update-group token concept-id group nil))
  ([token concept-id group options]
   (let [options (merge {:raw? true :token token} options)]
     (process-response (ac/update-group (conn-context) concept-id group options)))))

(defn delete-group
  "Deletes a group"
  ([token concept-id]
   (delete-group token concept-id nil))
  ([token concept-id options]
   (let [options (merge {:raw? true :token token} options)]
     (process-response (ac/delete-group (conn-context) concept-id options)))))

(defn search-for-groups
  "Searches for groups using the given parameters"
  ([token params]
   (search-for-groups token params nil))
  ([token params options]
   (let [options (merge {:raw? true :token token} options)]
    (process-response (ac/search-for-groups (conn-context) params options)))))

(defn add-members
  "Adds members to the group"
  ([token concept-id members]
   (add-members token concept-id members nil))
  ([token concept-id members options]
   (let [options (merge {:raw? true :token token} options)]
    (process-response (ac/add-members (conn-context) concept-id members options)))))

(defn remove-members
  "Removes members from the group"
  ([token concept-id members]
   (remove-members token concept-id members nil))
  ([token concept-id members options]
   (let [options (merge {:raw? true :token token} options)]
    (process-response (ac/remove-members (conn-context) concept-id members options)))))

(defn get-members
  "Gets members in the group"
  ([token concept-id]
   (get-members token concept-id nil))
  ([token concept-id options]
   (let [options (merge {:raw? true :token token} options)]
    (process-response (ac/get-members (conn-context) concept-id options)))))

(defn create-group-with-members
  "Creates a group with the given list of members."
  ([token group members]
   (create-group-with-members token group members nil))
  ([token group members options]
   (let [group (create-group token group options)]
     (if (seq members)
       (let [{:keys [revision_id status] :as resp} (add-members token (:concept_id group) members options)]
         (when-not (= status 200)
           (throw (Exception. (format "Unexpected status [%s] when adding members: %s" status (pr-str resp)))))
         (assoc group :revision_id revision_id))
       group))))

(defn ingest-group
 "Ingests the group and returns a group such that it can be matched with a search result."
 [token attributes members]
 (let [group (make-group attributes)
       {:keys [concept_id status revision_id] :as resp} (create-group-with-members token group members)]
   (when-not (= status 200)
     (throw (Exception. (format "Unexpected status [%s] when creating group %s" status (pr-str resp)))))
   (assoc group
          :members members
          :concept_id concept_id
          :revision_id revision_id)))

(defn disable-publishing-messages
  "Configures metadata db to not publish messages for new data."
  []
  (side/eval-form `(mdb-config/set-publish-messages! false)))

(defn enable-publishing-messages
  "Configures metadata db to start publishing messages for new data it sees."
  []
  (side/eval-form `(mdb-config/set-publish-messages! true)))

(defmacro without-publishing-messages
  "Temporarily configures metadata db not to publish messages while executing the body."
  [& body]
  `(do
     (disable-publishing-messages)
     (try
       ~@body
       (finally
         (enable-publishing-messages)))))

(defn save-collection
  "Test helper. Saves collection to Metadata DB and returns its concept id."
  [options]
  (let [{:keys [native-id entry-title short-name access-value provider-id temporal-range no-temporal]} options
        base-umm (-> example-collection-record
                     (assoc-in [:SpatialExtent :GranuleSpatialRepresentation] "NO_SPATIAL"))
        umm (cond-> base-umm
              entry-title (assoc :EntryTitle entry-title)
              short-name (assoc :ShortName short-name)
              (contains? options :access-value) (assoc-in [:AccessConstraints :Value] access-value)
              no-temporal (assoc :TemporalExtents nil)
              temporal-range (assoc-in [:TemporalExtents 0 :RangeDateTimes] [temporal-range]))
        extra-fields (if access-value
                       {:short-name short-name
                        :entry-title entry-title
                        :entry-id short-name
                        :access-value access-value
                        :version-id "v1"}
                       {:short-name short-name
                        :entry-title entry-title
                        :entry-id short-name
                        :version-id "v1"})]

    ;; We don't want to publish messages in metadata db since different envs may or may not be running
    ;; the indexer when we run this test.
    (without-publishing-messages
     (:concept-id
       (mdb/save-concept (conn-context)
                         {:format "application/echo10+xml"
                          :metadata (umm-spec/generate-metadata (conn-context) umm :echo10)
                          :concept-type :collection
                          :provider-id provider-id
                          :native-id native-id
                          :revision-id 1
                          :extra-fields extra-fields})))))

(defn assert-group-saved
  "Checks that a group was persisted correctly in metadata db. The user-id indicates which user
  updated this revision."
  [group user-id concept-id revision-id]
  (let [concept (mdb/get-concept (conn-context) concept-id revision-id)]
    (is (= {:concept-type :access-group
            :provider-id (:provider_id group "CMR")
            :format mt/edn
            :metadata (pr-str (util/map-keys->kebab-case group))
            :user-id user-id
            :deleted false
            :concept-id concept-id
            :revision-id revision-id}
           (dissoc concept :revision-date :transaction-id :native-id)))))

(defn assert-group-deleted
  "Checks that a group tombstone was persisted correctly in metadata db."
  [group user-id concept-id revision-id]
  (let [concept (mdb/get-concept (conn-context) concept-id revision-id)]
    (is (= {:concept-type :access-group
            :provider-id (:provider_id group "CMR")
            :metadata ""
            :format mt/edn
            :user-id user-id
            :deleted true
            :concept-id concept-id
            :revision-id revision-id}
           (dissoc concept :revision-date :transaction-id :native-id)))))

(defn create-acl
  "Creates an acl."
  ([token acl]
   (create-acl token acl nil))
  ([token acl options]
   (let [options (merge {:raw? true :token token} options)]
     (process-response (ac/create-acl (conn-context) acl options)))))

(defn get-acl
  "Retrieves an ACL by concept id"
  ([token concept-id params]
   (process-response (ac/get-acl (conn-context) concept-id {:raw? true :token token :http-options {:query-params params}})))
  ([token concept-id]
   (get-acl token concept-id nil)))

(defn search-for-acls
  "Searches for groups using the given parameters"
  ([token params]
   (search-for-acls token params nil))
  ([token params options]
   (let [options (merge {:raw? true :token token} options)]
     (process-response (ac/search-for-acls (conn-context) params options)))))
