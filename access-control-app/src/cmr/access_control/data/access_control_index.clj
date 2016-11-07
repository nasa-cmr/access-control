(ns cmr.access-control.data.access-control-index
  "Performs search and indexing of access control data."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [cmr.access-control.data.acls :as acls]
   [cmr.common-app.services.search.elastic-search-index :as esi]
   [cmr.common-app.services.search.query-to-elastic :as q2e]
   [cmr.common.log :refer [info debug]]
   [cmr.common.services.errors :as errors]
   [cmr.common.util :as util :refer [defn-timed]]
   [cmr.elastic-utils.index-util :as m :refer [defmapping defnestedmapping]]
   [cmr.transmit.metadata-db :as mdb-legacy]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Groups

(def group-index-name
  "The name of the index in elastic search."
  "groups")

(def group-type-name
  "The name of the mapping type within the cubby elasticsearch index."
  "access-group")

(defmapping ^:private group-mappings group-type-name
  "Defines the field mappings and type options for indexing groups in elasticsearch."
  {:concept-id (m/stored m/string-field-mapping)
   :revision-id (m/stored m/int-field-mapping)

   :name (m/stored m/string-field-mapping)
   :name.lowercase m/string-field-mapping

   :provider-id (m/stored m/string-field-mapping)
   :provider-id.lowercase m/string-field-mapping

   :description (m/not-indexed (m/stored m/string-field-mapping))

   :legacy-guid (m/stored m/string-field-mapping)
   :legacy-guid.lowercase m/string-field-mapping

   :members (m/stored m/string-field-mapping)
   :members.lowercase m/string-field-mapping

   ;; Member count is returned in the group response. The list of members is returned separately so
   ;; we don't store the members in the elastic index. If members end up being stored at some point
   ;; we can get rid of this field.
   :member-count (m/stored (m/not-indexed m/int-field-mapping))})

(def ^:private group-index-settings
  "Defines the elasticsearch index settings."
  {:number_of_shards 3,
   :number_of_replicas 1,
   :refresh_interval "1s"})

(defn- group-concept-map->elastic-doc
  "Converts a concept map containing an access group into the elasticsearch document to index."
  [concept-map]
  (let [group (edn/read-string (:metadata concept-map))]
    (-> group
        (merge (select-keys concept-map [:concept-id :revision-id]))
        (assoc :name.lowercase (util/safe-lowercase (:name group))
               :provider-id.lowercase (util/safe-lowercase (:provider-id group))
               :members (:members group)
               :members.lowercase (map str/lower-case (:members group))
               :legacy-guid.lowercase (util/safe-lowercase (:legacy-guid group))
               :member-count (count (:members group))))))

(defn index-group
  "Indexes an access control group."
  [context concept-map]
  (info "Indexing group concept:" (pr-str concept-map))
  (let [elastic-doc (group-concept-map->elastic-doc concept-map)
        {:keys [concept-id revision-id]} concept-map
        elastic-store (esi/context->search-index context)]
    (m/save-elastic-doc
      elastic-store group-index-name group-type-name concept-id elastic-doc revision-id
      {:ignore-conflict? true
       ;; This option makes indexing synchronous by forcing a refresh of the index before returning.
       :refresh? true})))

(defn-timed reindex-groups
  "Fetches and indexes all groups"
  [context]
  (info "Reindexing all groups")
  (doseq [group-batch (mdb-legacy/find-in-batches context :access-group 100 {:latest true})
          group group-batch]
    (index-group context group))
  (info "Reindexing all groups complete"))

(defn unindex-group
  "Removes group from index by concept ID."
  [context concept-id]
  (m/delete-by-id (esi/context->search-index context)
                  group-index-name
                  group-type-name
                  concept-id))

(defn unindex-groups-by-provider
  "Unindexes all access groups owned by provider-id."
  [context provider-id]
  (info "Unindexing all groups for" provider-id)
  (m/delete-by-query (esi/context->search-index context)
                     group-index-name
                     group-type-name
                     ;; only :provider-id.lowercase is indexed, so to find the access group by
                     ;; provider-id we need to compare the lowercased version
                     {:term {:provider-id.lowercase (.toLowerCase provider-id)}}))

(defmethod q2e/concept-type->field-mappings :access-group
  [_]
  {:provider :provider-id})

(defmethod q2e/field->lowercase-field-mappings :access-group
  [_]
  {:provider "provider-id.lowercase"
   :member "members.lowercase"})

(defmethod esi/concept-type->index-info :access-group
  [context _ _]
  {:index-name group-index-name
   :type-name group-type-name})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ACLs

(def ^:private acl-index-name
  "The name of the index in elastic search."
  "acls")

(def ^:private acl-type-name
  "The name of the mapping type within the cubby elasticsearch index."
  "acl")

(defnestedmapping group-permission-field-mapping
  "Defines mappings for group permission."
  {:permitted-group m/string-field-mapping
   :permitted-group.lowercase m/string-field-mapping
   :permission m/string-field-mapping
   :permission.lowercase m/string-field-mapping})

(defmapping ^:private acl-mappings acl-type-name
  "Defines the field mappings and type options for indexing acls in elasticsearch."
  {:concept-id (m/stored m/string-field-mapping)
   :revision-id (m/stored m/int-field-mapping)

   :collection-identifier m/bool-field-mapping
   :collection-access-value-min m/int-field-mapping
   :collection-access-value-max m/int-field-mapping
   :collection-access-value-include-undefined-value m/bool-field-mapping

   :permitted-group (m/stored m/string-field-mapping)
   :permitted-group.lowercase m/string-field-mapping

   :group-permission group-permission-field-mapping

   :legacy-guid (m/stored m/string-field-mapping)
   :legacy-guid.lowercase m/string-field-mapping

   ;; target-provider-id indexes the provider id of the provider-identity or
   ;; catalog-item-identity field of an acl, if present
   :target-provider-id (m/stored m/string-field-mapping)
   :target-provider-id.lowercase m/string-field-mapping

   ;; The name of the ACL for returning in the references response.
   ;; This will be the catalog item identity name or a string containing
   ;; "<identity type> - <target>". For example "System - PROVIDER"
   :display-name (m/stored m/string-field-mapping)
   :identity-type (m/stored m/string-field-mapping)
   ;; Store the full ACL metadata for quick retrieval.
   :acl-gzip-b64 (m/stored (m/not-indexed m/string-field-mapping))})

(def ^:private acl-index-settings
  "Defines the elasticsearch index settings."
  {:number_of_shards 3,
   :number_of_replicas 1,
   :refresh_interval "1s"})

(defn acl->display-name
  "Returns the display name to index with the ACL. This will be the catalog item identity name or a
  string containing \"<identity type> - <target>\". For example \"System - PROVIDER\""
  [acl]
  (let [{:keys [system-identity provider-identity single-instance-identity catalog-item-identity]} acl]
    (cond
      system-identity          (str "System - " (:target system-identity))
      ;; We index the display name for a single instance identity using "Group" because they're only for
      ;; groups currently. We use the group concept id here instead of the name. We could support
      ;; indexing the group name with the ACL but then if the group name changes we'd have to
      ;; locate and reindex the related acls. We'll do it this way for now and file a new issue
      ;; if this feature is desired.
      single-instance-identity (str "Group - " (:target-id single-instance-identity))
      provider-identity        (format "Provider - %s - %s"
                                       (:provider-id provider-identity)
                                       (:target provider-identity))
      catalog-item-identity    (:name catalog-item-identity)
      :else                    (errors/internal-error!
                                 (str "ACL was missing identity " (pr-str acl))))))

(defn acl->identity-type
  "Returns the identity type to index with the ACL."
  [acl]
  (cond
    (:system-identity acl)          "System"
    (:single-instance-identity acl) "Group"
    (:provider-identity acl)        "Provider"
    (:catalog-item-identity acl)    "Catalog Item"
    :else                    (errors/internal-error!
                               (str "ACL was missing identity " (pr-str acl)))))

(defn acl->permitted-groups
  "Returns the permitted groups of the ACL, which is a list of group ids or user types referenced
  in the group permissions of the ACL."
  [acl]
  (map #(or (:user-type %) (:group-id %)) (:group-permissions acl)))

(defn- acl-group-permission->elastic-doc
  "Converts a map containing a group permission map to an elasticsearch document to index
  as a nested document field of an acl."
  [group-permission]
  (let [{:keys [group-id user-type permissions]} group-permission
        gid (or group-id user-type)]
    {:permitted-group gid
     :permitted-group.lowercase (str/lower-case gid)
     :permission permissions
     :permission.lowercase (map str/lower-case permissions)}))

(defn- acl-concept-map->elastic-doc
  "Converts a concept map containing an acl into the elasticsearch document to index."
  [concept-map]
  (let [acl (edn/read-string (:metadata concept-map))
        permitted-groups (acl->permitted-groups acl)
        provider-id (acls/acl->provider-id acl)]
    (merge
      (when-let [av (:access-value (:collection-identifier (:catalog-item-identity acl)))]
        {:collection-access-value-max (:max-value av)
         :collection-access-value-min (:min-value av)
         :collection-access-value-include-undefined-value (:include-undefined-value av)
         :collection-identifier true})
      (when (and
              (not (:collection-identifier (:catalog-item-identity acl)))
              (not (:granule-identifier (:catalog-item-identity acl)))
              (:catalog-item-identity acl))
        {:collection-identifier false})
      (assoc (select-keys concept-map [:concept-id :revision-id])
             :display-name (acl->display-name acl)
             :identity-type (acl->identity-type acl)
             :permitted-group permitted-groups
             :permitted-group.lowercase (map str/lower-case permitted-groups)
             :group-permission (map acl-group-permission->elastic-doc (:group-permissions acl))
             :target-provider-id provider-id
             :target-provider-id.lowercase (util/safe-lowercase provider-id)
             :acl-gzip-b64 (util/string->gzip-base64 (:metadata concept-map))
             :legacy-guid (:legacy-guid acl)
             :legacy-guid.lowercase (when-let [legacy-guid (:legacy-guid acl)]
                                      (str/lower-case legacy-guid))))))

(defn index-acl
  "Indexes ACL concept map."
  [context concept-map]
  (info "Indexing ACL concept:" (pr-str concept-map))
  (let [elastic-doc (acl-concept-map->elastic-doc concept-map)
        {:keys [concept-id revision-id]} concept-map
        elastic-store (esi/context->search-index context)]
    (m/save-elastic-doc
      elastic-store acl-index-name acl-type-name concept-id elastic-doc revision-id
      {:ignore-conflict? true})))

(defn unindex-acl
  "Removes ACL from index by concept ID."
  [context concept-id]
  (m/delete-by-id (esi/context->search-index context)
                  acl-index-name
                  acl-type-name
                  concept-id))

(defmethod esi/concept-type->index-info :acl
  [context _ _]
  {:index-name acl-index-name
   :type-name acl-type-name})

(defmethod q2e/concept-type->field-mappings :acl
  [_]
  {:provider :target-provider-id})

(defmethod q2e/field->lowercase-field-mappings :acl
  [_]
  {:provider "target-provider-id.lowercase"})

(defn unindex-acls-by-provider
  "Removes all ACLs granting permissions to the specified provider ID from the index."
  [context provider-id]
  (m/delete-by-query (esi/context->search-index context)
                     acl-index-name
                     acl-type-name
                     {:term {:target-provider-id.lowercase (str/lower-case provider-id)}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Common public functions

(defn create-index-or-update-mappings
  "Creates the index needed in Elasticsearch for data storage"
  [elastic-store]
  (m/create-index-or-update-mappings
    group-index-name group-index-settings group-type-name group-mappings elastic-store)
  (m/create-index-or-update-mappings
    acl-index-name acl-index-settings acl-type-name acl-mappings elastic-store))

(defn reset
  "Deletes all data from the index"
  [elastic-store]
  (m/reset group-index-name group-index-settings group-type-name group-mappings elastic-store)
  (m/reset acl-index-name acl-index-settings acl-type-name acl-mappings elastic-store))
