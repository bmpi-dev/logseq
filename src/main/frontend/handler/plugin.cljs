(ns frontend.handler.plugin
  (:require [promesa.core :as p]
            [rum.core :as rum]
            [frontend.util :as util]
            [frontend.fs :as fs]
            [frontend.handler.notification :as notifications]
            [frontend.state :as state]
            [medley.core :as md]
            [electron.ipc :as ipc]
            [cljs-bean.core :as bean]
            [clojure.string :as string]))

(defonce lsp-enabled? (util/electron?))

;; state handlers
(defn register-plugin
  [pl]
  (swap! state/state update-in [:plugin/installed-plugins] assoc (keyword (:id pl)) pl))

(defn unregister-plugin
  [id]
  (js/LSPluginCore.unregister id))

(defn host-mounted!
  []
  (and lsp-enabled? (js/LSPluginCore.hostMounted)))

(defn register-plugin-slash-command
  [id [cmd actions]]
  (if-let [id (keyword id)]
    (when (contains? (:plugin/installed-plugins @state/state) id)
      (do (swap! state/state update-in [:plugin/installed-commands id]
                 (fnil merge {}) (hash-map cmd (mapv #(conj % {:pid id}) actions)))
          true))))

(defn unregister-plugin-slash-command
  [id]
  (swap! state/state md/dissoc-in [:plugin/installed-commands (keyword id)]))

(defn update-plugin-settings
  [id settings]
  (swap! state/state update-in [:plugin/installed-plugins id] assoc :settings settings))

(defn open-readme!
  [url display]
  (when url
    (-> (p/let [content (js/api.load_plugin_readme url)]
          (state/set-state! :plugin/active-readme content)
          (state/set-modal! display))
        (p/catch #(notifications/show! "No README file." :warn)))))

(defn load-unpacked-plugin
  []
  (if util/electron?
    (p/let [path (ipc/ipc "openDialogSync")]
      (when-not (:plugin/selected-unpacked-pkg @state/state)
        (state/set-state! :plugin/selected-unpacked-pkg path)))))

(defn reset-unpacked-state
  []
  (state/set-state! :plugin/selected-unpacked-pkg nil))

(defn hook-plugin
  [tag type payload plugin-id]
  (when lsp-enabled?
    (js-invoke js/LSPluginCore
               (str "hook" (string/capitalize (name tag)))
               (name type)
               (bean/->js payload)
               (if (keyword? plugin-id) (name plugin-id) plugin-id))))

(defn hook-plugin-app
  ([type payload] (hook-plugin-app type payload nil))
  ([type payload plugin-id] (hook-plugin :app type payload plugin-id)))

(defn hook-plugin-editor
  ([type payload] (hook-plugin-editor type payload nil))
  ([type payload plugin-id] (hook-plugin :editor type payload plugin-id)))

(defn get-ls-dotdir-root
  []
  (ipc/ipc "getLogseqDotDirRoot"))

(defn- get-user-default-plugins
  []
  (p/catch
   (p/let [files ^js (ipc/ipc "getUserDefaultPlugins")
           files (js->clj files)]
     (map #(hash-map :url %) files))
   (fn [e]
     (js/console.error e))))

;; components
(rum/defc lsp-indicator < rum/reactive
  []
  (let [text (state/sub :plugin/indicator-text)]
    (if (= text "END")
      [:span]
      [:div
       {:style
        {:width           "100%"
         :height          "100vh"
         :display         "flex"
         :align-items     "center"
         :justify-content "center"}}
       [:span
        {:style
         {:color     "#aaa"
          :font-size "58px"}} (or text "Loading ...")]])))

;; demo plugin configures

(defonce
  a-themes-provider
  {:url "/Users/charlie/Desktop/examples-0-themes"})

(defn init-plugins
  [callback]

  (let [el (js/document.createElement "div")]
    (.appendChild js/document.body el)
    (rum/mount
     (lsp-indicator) el))

  (state/set-state! :plugin/indicator-text "⏳")

  (p/then
   (p/let [root (get-ls-dotdir-root)
           _ (.setupPluginCore js/LSPlugin (bean/->js {:localUserConfigRoot root}))
           _ (doto js/LSPluginCore
               (.on "registered"
                    (fn [^js pl]
                      (register-plugin
                       (bean/->clj (.parse js/JSON (.stringify js/JSON pl))))))

               (.on "unregistered" #((fn [pid]
                                       (let [pid (keyword pid)]
                                         ;; plugins
                                         (swap! state/state util/dissoc-in [:plugin/installed-plugins (keyword pid)])
                                         ;; commands
                                         (unregister-plugin-slash-command pid)))))

               (.on "theme-changed" (fn [^js themes]
                                      (swap! state/state assoc :plugin/installed-themes
                                             (vec (mapcat (fn [[_ vs]] (bean/->clj vs)) (bean/->clj themes))))))

               (.on "theme-selected" (fn [^js opts]
                                       (let [opts (bean/->clj opts)
                                             url (:url opts)
                                             mode (:mode opts)]
                                         (when mode (state/set-theme! mode))
                                         (state/set-state! :plugin/selected-theme url))))

               (.on "settings-changed" (fn [id ^js settings]
                                         (let [id (keyword id)]
                                           (when (and settings
                                                      (contains? (:plugin/installed-plugins @state/state) id))
                                             (update-plugin-settings id (bean/->clj settings)))))))

           default-plugins (get-user-default-plugins)

           _ (.register js/LSPluginCore (bean/->js (if (seq default-plugins) default-plugins [])) true)])
   #(do
      (state/set-state! :plugin/indicator-text "END")
      (callback))))

(defn setup!
  "setup plugin core handler"
  [callback]
  (if (not lsp-enabled?)
    (callback)
    (init-plugins callback)))
