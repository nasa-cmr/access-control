(ns cmr.access-control.services.acl-validation
  (:require
    [cheshire.core :as json]
    [clj-time.core :as t]
    [cmr.access-control.services.acl-search-service :as acl-search]
    [cmr.access-control.services.acl-service-util :as acl-util]
    [cmr.access-control.data.acls :as acls]
    [cmr.access-control.services.group-service :as groups]
    [cmr.access-control.services.messages :as msg]
    [cmr.common.date-time-parser :as dtp]
    [cmr.common.validations.core :as v]
    [cmr.transmit.echo.tokens :as tokens]
    [cmr.transmit.metadata-db :as mdb1]
    [cmr.transmit.metadata-db2 :as mdb]))

(defn- catalog-item-identity-collection-applicable-validation
  "Validates the relationship between collection_applicable and collection_identifier."
  [key-path cat-item-id]
  (when (and (:collection-identifier cat-item-id)
             (not (:collection-applicable cat-item-id)))
    {key-path ["collection_applicable must be true when collection_identifier is specified"]}))

(defn- catalog-item-identity-granule-applicable-validation
  "Validates the relationship between granule_applicable and granule_identifier."
  [key-path cat-item-id]
  (when (and (:granule-identifier cat-item-id)
             (not (:granule-applicable cat-item-id)))
    {key-path ["granule_applicable must be true when granule_identifier is specified"]}))

(defn- catalog-item-identity-collection-or-granule-validation
  "Validates minimal catalog_item_identity fields."
  [key-path cat-item-id]
  (when-not (or (:collection-applicable cat-item-id)
                (:granule-applicable cat-item-id))
    {key-path ["when catalog_item_identity is specified, one or both of collection_applicable or granule_applicable must be true"]}))

(defn- make-collection-entry-titles-validation
  "Returns a validation for the entry_titles part of a collection identifier, closed over the context and ACL to be validated."
  [context acl]
  (let [provider-id (-> acl :catalog-item-identity :provider-id)]
    (v/every (fn [key-path entry-title]
               (when-not (seq (mdb1/find-concepts context {:provider-id provider-id :entry-title entry-title} :collection))
                 {key-path [(format "collection with entry-title [%s] does not exist in provider [%s]" entry-title provider-id)]})))))

(defn- access-value-validation
  "Validates the access_value part of a collection or granule identifier."
  [key-path access-value-map]
  (let [{:keys [min-value max-value include-undefined-value]} access-value-map]
    (cond
      (and include-undefined-value (or min-value max-value))
      {key-path ["min_value and/or max_value must not be specified if include_undefined_value is true"]}

      (and (not include-undefined-value) (not (or min-value max-value)))
      {key-path ["min_value and/or max_value must be specified when include_undefined_value is false"]})))

(defn temporal-identifier-validation
  "A validation for the temporal part of an ACL collection or granule identifier."
  [key-path temporal]
  (let [{:keys [start-date stop-date]} temporal]
    (when (and start-date stop-date
               (t/after? (dtp/parse-datetime start-date) (dtp/parse-datetime stop-date)))
      {key-path ["start_date must be before stop_date"]})))

(defn- make-collection-identifier-validation
  "Returns a validation for an ACL catalog_item_identity.collection_identifier closed over the given context and ACL to be validated."
  [context acl]
  {:entry-titles (v/when-present (make-collection-entry-titles-validation context acl))
   :access-value (v/when-present access-value-validation)
   :temporal (v/when-present temporal-identifier-validation)})

(def granule-identifier-validation
  "Validation for the catalog_item_identity.granule_identifier portion of an ACL."
  {:access-value (v/when-present access-value-validation)
   :temporal (v/when-present temporal-identifier-validation)})

(def ^:private c "create")
(def ^:private r "read")
(def ^:private u "update")
(def ^:private d "delete")

(def ^:private grantable-permission-mapping
  {:single-instance-identity {"GROUP_MANAGEMENT"                [u d]}
   :provider-identity        {"AUDIT_REPORT"                    [r]
                              "OPTION_ASSIGNMENT"               [c r d]
                              "OPTION_DEFINITION"               [c d]
                              "OPTION_DEFINITION_DEPRECATION"   [c]
                              "DATASET_INFORMATION"             [r]
                              "PROVIDER_HOLDINGS"               [r]
                              "EXTENDED_SERVICE"                [c u d]
                              "PROVIDER_ORDER"                  [r]
                              "PROVIDER_ORDER_RESUBMISSION"     [c]
                              "PROVIDER_ORDER_ACCEPTANCE"       [c]
                              "PROVIDER_ORDER_REJECTION"        [c]
                              "PROVIDER_ORDER_CLOSURE"          [c]
                              "PROVIDER_ORDER_TRACKING_ID"      [u]
                              "PROVIDER_INFORMATION"            [u]
                              "PROVIDER_CONTEXT"                [r]
                              "AUTHENTICATOR_DEFINITION"        [c d]
                              "PROVIDER_POLICIES"               [r u d]
                              "USER"                            [r]
                              "GROUP"                           [c r]
                              "PROVIDER_OBJECT_ACL"             [c r u d]
                              "CATALOG_ITEM_ACL"                [c r u d]
                              "INGEST_MANAGEMENT_ACL"           [r u]
                              "DATA_QUALITY_SUMMARY_DEFINITION" [c u d]
                              "DATA_QUALITY_SUMMARY_ASSIGNMENT" [c d]
                              "PROVIDER_CALENDAR_EVENT"         [c u d]}
   :system-identity          {"SYSTEM_AUDIT_REPORT"             [r]
                              "METRIC_DATA_POINT_SAMPLE"        [r]
                              "SYSTEM_INITIALIZER"              [c]
                              "ARCHIVE_RECORD"                  [d]
                              "ERROR_MESSAGE"                   [u]
                              "TOKEN"                           [r d]
                              "TOKEN_REVOCATION"                [c]
                              "EXTENDED_SERVICE_ACTIVATION"     [c]
                              "ORDER_AND_ORDER_ITEMS"           [r d]
                              "PROVIDER"                        [c d]
                              "TAG_GROUP"                       [c u d]
                              "TAXONOMY"                        [c]
                              "TAXONOMY_ENTRY"                  [c]
                              "USER_CONTEXT"                    [r]
                              "USER"                            [r u d]
                              "GROUP"                           [c r]
                              "ANY_ACL"                         [c r u d]
                              "EVENT_NOTIFICATION"              [d]
                              "EXTENDED_SERVICE"                [d]
                              "SYSTEM_OPTION_DEFINITION"        [c d]
                              "SYSTEM_OPTION_DEFINITION_DEPRECATION" [c]
                              "INGEST_MANAGEMENT_ACL"                [r u]
                              "SYSTEM_CALENDAR_EVENT"                [c u d]}})

(comment
  ;; evaluate the following expression to generate Markdown for the API docs
  (doseq [[identity-type targets-permissions] (sort-by key grantable-permission-mapping)]
    (println "####" identity-type)
    (println)
    (println "| Target | Allowed Permissions |")
    (println "| ------ | ------------------- |")
    (doseq [[target permissions] (sort-by key targets-permissions)]
      (println "|" target "|" (clojure.string/join ", " permissions) "|"))
    (println)))

(defn- get-identity-type
  [acl]
  (cond
    (:single-instance-identity acl) :single-instance-identity
    (:provider-identity acl)        :provider-identity
    (:system-identity acl)          :system-identity
    (:catalog-item-identity acl)    :catalog-item-identity))

(defn make-single-instance-identity-target-id-validation
  "Validates that the acl group exists."
  [context]
  (fn [key-path target-id]
    (when-not (groups/group-exists? context target-id)
      {key-path [(format "Group with concept-id [%s] does not exist" target-id)]})))

(defn- make-single-instance-identity-validations
  "Returns a standard validation for an ACL single_instance_identity field closed over the given context and ACL to be validated."
  [context]
  {:target-id (v/when-present (make-single-instance-identity-target-id-validation context))})

(defn permissions-granted-by-provider-to-user
  "Returns true if acls grant permission on sids for target"
  [sids acls target]
  (for [x sids
        y acls
        :let [group-permissions (filter #(or
                                           (and (contains? % :user-type) (= x (:user-type %)))
                                           (and (contains? % :group-id) (= x (:group-id %))))
                                        (:group-permissions y))]
        :when (= (get-in y [:provider-identity :target]) target)]
    (map :permissions group-permissions)))

(defn validate-target-provider-grants-create
  "Checks if provider ACL grants permission for user to create given catalog item ACL"
  [context key-path acl]
  (let [token (:token context)
        user (if token (tokens/get-user-id context token) "guest")
        sids (cond
               (contains? #{"guest" "registered"} user) [user]
               (string? user) (concat ["registered"] (->> (groups/search-for-groups context {:member user})
                                                          :results
                                                          :items
                                                          (map :concept_id))))
        provider-id (:provider-id acl)
        provider-acls (map #(acl-util/get-acl context %)
                           (map #(get % "concept_id") (get (json/parse-string (:results (acl-search/search-for-acls context {:provider provider-id} true))) "items")))]
    (when-not (contains? (set (flatten (permissions-granted-by-provider-to-user sids provider-acls "CATALOG_ITEM_ACL"))) "create")
      {key-path [(format "User [%s] does not have permission to create catalog item targeting provider-id [%s]"
                          user provider-id)]})))

(defn- make-catalog-item-identity-validations
  "Returns a standard validation for an ACL catalog_item_identity field closed over the given context and ACL to be validated."
  [context acl save-flag]
  [catalog-item-identity-collection-or-granule-validation
   catalog-item-identity-collection-applicable-validation
   catalog-item-identity-granule-applicable-validation
   (when (= :create save-flag)
     #(validate-target-provider-grants-create context %1 %2))
   {:collection-identifier (v/when-present (make-collection-identifier-validation context acl))
    :granule-identifier (v/when-present granule-identifier-validation)}])

(defn validate-provider-exists
  "Validates that the acl provider exists."
  [context key-path acl]
  (let [provider-id (acls/acl->provider-id acl)]
    (when (and provider-id
               (not (some #{provider-id} (map :provider-id (mdb/get-providers context)))))
      {key-path [(msg/provider-does-not-exist provider-id)]})))

(defn validate-grantable-permissions
  "Checks if permissions requested are grantable for given target."
  [key-path acl]
  (let [identity-type (get-identity-type acl)
        target (:target (get acl identity-type))
        permissions-requested (mapcat :permissions (:group-permissions acl))
        grantable-permissions (get-in grantable-permission-mapping [identity-type target])
        ungrantable-permissions (remove (set grantable-permissions) permissions-requested)]
    (when (and (seq ungrantable-permissions) (seq (set grantable-permissions)))
      {key-path [(format "[%s] ACL cannot have [%s] permission for target [%s], only [%s] are grantable"
                         (name identity-type) (clojure.string/join ", " ungrantable-permissions)
                         target (clojure.string/join ", " grantable-permissions))]})))

(defn- make-acl-validations
  "Returns a sequence of validations closed over the given context for validating ACL records."
  [context acl save-flag]
  [#(validate-provider-exists context %1 %2)
   {:catalog-item-identity (v/when-present (make-catalog-item-identity-validations context acl save-flag))
    :single-instance-identity (v/when-present (make-single-instance-identity-validations context))}
   validate-grantable-permissions])

(defn validate-acl-save!
  "Throws service errors if ACL is invalid."
  [context acl save-flag]
  (v/validate! (make-acl-validations context acl save-flag) acl))
