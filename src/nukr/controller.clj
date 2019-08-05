(ns nukr.controller
  (:require 
   [io.pedestal.log :as log]
   [nukr.database :as database]
   [nukr.logic :as logic]
   [clojure.math.numeric-tower :as math]))

(defn to-profile-analytic-model
  [profile]
  {:id (:id profile)
   :name (:name profile)
   :email (:email profile)
   :visible (:visible profile)
   :createdat (:created-at profile)
   :updatedat (:updated-at profile)
   :connections (count (:connections profile))})

(defn add-profile!
  "Add a new profile to network.
   Input  model is: {:name string 
                     :email string 
                     :visible bool (optional, default true)};
   Output model is 
     - success: see to-profile-analytic-model function.
     - error: {:errors [(:network-over-capacity logic/core-error)]
                        --or--
                       [(:profile-email-exists logic/core-error)]
                        --or--
                       [(:profile-name-required logic/core-error)
                        --and/or--
                        [(:profile-email-required logic/core-error)
                         --or--
                         (:profile-invalid-email logic/core-error)]]}"
  [profile]
  ("nil")
  (log/debug :msg "add-profile! fired." :profile profile)
  (let [profile-or-error
        (logic/profile-check-preconditions
         profile
         (:profiles (database/read-all)))]
    (if (:errors profile-or-error)
      (do
        ;; Look for :network-over-capacity condition
        (if (some
             #(= (:network-over-capacity logic/core-error) %)
             (:errors profile-or-error))
          ;; then log warn if it happened
          (log/warn
           :msg "Network over capacity while trying to add profile."
           :profile profile)
          ;; log info for other kinds of precondition error
          (log/info
           :msg "add-profile! preconditions failed."
           :profile profile
           :result profile-or-error))
        ;; return errors
        profile-or-error)
      (let [new-profile (logic/new-profile profile)]
        (log/debug 
         :msg "add-profile! preconditions ok, saving the new profile."
         :new-profile new-profile)
        (database/save-profile! new-profile)
        (log/debug
         :msg "add-profile! successfully saved new-profile."
         :new-profile new-profile)        
        (to-profile-analytic-model new-profile)))))

(defn update-profile!
  "Update an existing profile.
   Input  model is 
     - profile-id: uuid
     - profile: {:name string 
                 :email string 
                 :visible bool (optional, default true)};
   Output model is 
     - success: see to-profile-analytic-model function.
     - error: {:errors [(:profile-not-found logic/core-error)]
                        --or--
                       [(:profile-email-exists logic/core-error)]
                        --or--
                       [(:profile-name-required logic/core-error)
                        --and/or--
                        [(:profile-email-required logic/core-error)
                         --or--
                         (:profile-invalid-email logic/core-error)]]}"
  [profile-id profile]
  (log/debug :msg "update-profile! fired."  
             :profile-id profile-id
             :profile profile)
  (let [old-profile (database/read-profile profile-id)]
    (if (nil? old-profile)
      {:errors [(:profile-not-found logic/core-error)]}
      (let [profile-or-error
            (logic/profile-check-preconditions
             profile
             (dissoc (:profiles (database/read-all)) profile-id))]
        (if (:errors profile-or-error)
          (do
            (log/info
             :msg "update-profile! preconditions failed."
             :profile-id profile-id
             :profile profile
             :result profile-or-error)
            ;; return errors
            profile-or-error)
          (let [new-profile (logic/update-profile old-profile profile)]
            (database/save-profile! new-profile)
            (log/debug
             :msg "update-profile! successfully updated profile."
             :new-profile new-profile)
            (to-profile-analytic-model new-profile)))))))

(defn connect-profiles!
  [profile1-id profile2-id]
  (let [preconditions
        (logic/connecting-check-preconditions
         (database/read-all)
         profile1-id
         profile2-id)]
    (if (:errors preconditions)
      preconditions
      (let [result
            (database/connect! logic/connect profile1-id profile2-id)]
        (to-profile-analytic-model (get (:profiles result) profile2-id))))))

(defn limit
  "If val greater than max, return max; 
   Returns val otherwise."
  [val max]
  (if (> val max)
    max
    val))

(defn try-parse-int 
  [val default-val]
  "Try to parse val as integer then returns it;
   If val is nil or not a valid integer then returns default-val."
  (let [provided-val 
        (if (nil? val) 
          ;; keep nil if nil provided
          nil 
          ;; non nil val was provided
          ;; so reading it
          (read-string val))]
    (if (integer? provided-val)
      provided-val
      default-val)))

(def paginate-items-per-page-max 50)
(def paginate-items-per-page-default 10)

(defn paginate 
  "Paginate a collection result. 
   Input  model is: 
    - paginate-params {:page int (optional, default 1)
                       :perpage int (optional, default 
                        paginate-items-per-page-default, 
                        limited to paginate-items-per-page-max)};
    - coll The collection;
   Output model is: {:total int (total items of collection)
                     :showing int (number of items in current page)
                     :page int (current page number, starting at 1)
                     :pages int (number of pages for the given perpage value)
                     :dropping int (number of dropping/skiping items)
                     :items '(collection) (collection's slice 
                                           for the given page value)}"
  [paginate-params coll]
  (let [items-per-page (-> paginate-params
                           :perpage
                           (try-parse-int paginate-items-per-page-default)
                           (limit paginate-items-per-page-max))
        current-page (try-parse-int (:page paginate-params) 1)
        total-items (count coll)
        total-pages (math/ceil (/ total-items items-per-page))
        dropping (* (dec current-page) items-per-page)
        items (->> coll
                   (drop dropping)
                   (take items-per-page))]
    {:total total-items
     :showing (count items)
     :page current-page
     :pages total-pages
     :dropping dropping
     :items items}))

(defn profile-sintetic-projection
  [profiles]
  (map #(select-keys % [:id :name]) profiles))

(defn get-profiles
  "Get network profile (paginated).
   Input  model is: {:page int (optional, default 1)
                     :perpage int (optional, default 
                      paginate-items-per-page-default, 
                      limited to paginate-items-per-page-max)};
   Output model is: {:total int (total items of collection)
                     :showing int (number of items in current page)
                     :page int (current page number, starting at 1)
                     :pages int (number of pages for the given perpage value)
                     :dropping int (number of dropping/skiping items)
                     :items '({:id uuid :name string})}"
  [paginate-params]
  (->> (-> (database/read-all)
           :profiles
           vals)
       profile-sintetic-projection
       (paginate paginate-params)))

(defn get-suggestions
  "Get connection suggestions for a given profile-id.
   Input  model is: 
    - profile-id uuid;
    - paginate-params {:page int (optional, default 1)
                       :perpage int (optional, default
                        paginate-items-per-page-default
                        limited to paginate-items-per-page-max)};
   Output model is: {:total int (total items of collection)
                     :showing int (number of items in current page)
                     :page int (current page number, starting at 1)
                     :pages int (number of pages for the given perpage value)
                     :dropping int (number of dropping/skiping items)
                     :items '({:id uuid :name string})}
                     --or--
                     {:errors '({:key profile-not-found})}"
  [profile-id paginate-params]
  (let [result (logic/get-suggestions (database/read-all) profile-id)]
    (if (:errors result)
      result
      (->> result
           profile-sintetic-projection
           (paginate paginate-params)))))

(defn get-profile-connections
  "Get connections for a given profile-id.
   Input  model is: 
    - profile-id uuid;
    - paginate-params {:page int (optional, default 1)
                       :perpage int (optional, default
                        paginate-items-per-page-default
                        limited to paginate-items-per-page-max)};
   Output model is: {:total int (total items of collection)
                     :showing int (number of items in current page)
                     :page int (current page number, starting at 1)
                     :pages int (number of pages for the given perpage value)
                     :dropping int (number of dropping/skiping items)
                     :items '({:id uuid :name string})}
                     --or--
                     {:errors '({:key profile-not-found})}"
  [profile-id paginate-params]
  (let [result (logic/get-profile-connections 
                (database/read-all) profile-id)]
    (if (:errors result)
      result
      (->> result
           profile-sintetic-projection
           (paginate paginate-params)))))

(defn get-profile-details
  "Get connections for a given profile-id.
   Input  model is: uuid;
   Output model is: {:id uuid
                     :name string
                     :email string
                     :visible bool
                     :create date
                     :connections int}
                    --or--
                    {:errors '({:key profile-not-found})}"
  [profile-id]
  (let [result (database/read-profile profile-id)]
    (if (nil? result)
      {:errors [(:profile-not-found logic/core-error)]}
      (to-profile-analytic-model result))))

(defn reset-database
  []
  (database/reset))

(defn dump-database 
  []
  (database/read-all))