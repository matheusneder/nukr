(ns nukr.controller
  (:require [io.pedestal.log :as log]
            [nukr.database :as database]
            [nukr.logic :as logic]))

(defn add-profile!
  [add-profile-dto]
  (let [profile-model (logic/new-profile add-profile-dto)]
    (database/add-profile! profile-model)
    profile-model))

(defn connect-profiles!
  [profile1-id profile2-id]
  (database/connect! logic/connect profile1-id profile2-id))

(defn get-connection-suggestions
  [profile-id]
  (logic/get-suggestions (database/read-all) profile-id))

(defn reset-database
  []
  (database/reset))

(defn dump-database 
  []
  (database/read-all))