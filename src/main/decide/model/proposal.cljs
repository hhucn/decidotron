(ns decide.model.proposal
  (:require [com.fulcrologic.fulcro.dom :as dom :refer [div form p button input label h2 h4 span small textarea br]]
            [com.fulcrologic.fulcro.dom.events :as evt]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.algorithms.merge :as mrg]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.guardrails.core :refer [>defn => | ? <-]]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [decide.model.argument :as arg]
            [decide.model.session :as session]
            [decide.util :as util]
            ["react-icons/io" :refer [IoMdMore IoIosCheckmarkCircleOutline IoIosCloseCircleOutline IoMdClose IoMdEye IoMdEyeOff]]
            ["bootstrap/js/dist/modal"]
            ["bootstrap/js/dist/collapse"]
            [com.fulcrologic.fulcro-css.css :as css]
            [goog.object :as gobj]
            ["jquery" :as $]))

(declare ProposalDetails ProposalList ProposalCollection)

(defn big-price-tag
  ([price budget]
   (big-price-tag price budget ""))
  ([price budget unit]
   (div :.price-tag-big
     (div (str price " " unit))
     (div "von " budget " " unit))))


(defmutation initial-load [{:keys [id]}]
  (action [{:keys [state app]}]
    (swap! state mrg/merge-component arg/Argumentation
      (comp/get-initial-state arg/Argumentation {:proposal/id id})
      :replace [:proposal/id id :>/argumentation])

    (df/load! app [:proposal/id id] ProposalDetails
      {:post-mutation        `dr/target-ready
       :post-mutation-params {:target [:proposal/id id]}})))


(defsc ProposalDetails [this {:keys          [argument/text >/argumentation]
                              :process/keys  [budget currency]
                              :proposal/keys [details cost]}]
  {:query         [:proposal/id
                   :argument/text
                   :proposal/details
                   :proposal/cost
                   :process/budget
                   :process/currency
                   {:>/argumentation (comp/get-query arg/Argumentation)}]
   :ident         :proposal/id
   :initial-state (fn [_] {:>/argumentation (comp/get-initial-state arg/Argumentation)})

   :route-segment ["proposal" :proposal/id]
   :will-enter    (fn [app {id :proposal/id}]
                    (let [id (uuid id)]
                      (dr/route-deferred [:proposal/id id]
                        #(comp/transact! app [(initial-load {:id id})]))))}
  (div :.container-md
    (div :.row.justify-content-between.m-4
      (h2 :.detail-card__header text)
      (big-price-tag cost budget currency))
    (p :.proposal__details details)
    (arg/ui-argumentation (comp/computed argumentation {:argumentation-root this}))))

(def ui-proposal-detail (comp/factory ProposalDetails {:keyfn (util/prefixed-keyfn :proposal-detail :proposal/id)}))

(>defn split-details [details]
  [string? => string?]
  (when (string? details)
    (some-> details (str/split #"\n\s*\n" 2) first)))

(defmutation set-vote [{:keys [proposal/id vote/utility]}]
  (action [{:keys [state]}]
    (swap! state update-in [:proposal/id id] assoc :vote/utility utility))
  (remote [{:keys [ast state] :as env}]
    (let [s      @state
          params (:params ast)]
      (-> env
        (m/with-params
          (assoc params :account/id (session/get-user-id-from-state s)))))))

(defn proposal-card [comp props]
  (let [{:proposal/keys [id details cost]
         :argument/keys [text]
         :process/keys  [currency]
         :keys          [vote/utility]} props
        logged-in? (session/get-logged-in? props)]
    (div :.proposal__card
      (div :.proposal__buttons.btn-group-toggle
        (button :.btn.btn-outline-success
          {:type     "radio"
           :title    "Zustimmen"
           :classes  [(when (pos? utility) "active")]
           :disabled (not logged-in?)
           :onClick  #(comp/transact! comp [(set-vote {:proposal/id  id
                                                       :vote/utility (if (pos? utility) 0 1)})])}
          (IoIosCheckmarkCircleOutline #js {:size "100%"}))
        (div :.spacer)
        (button :.btn.btn-outline-danger
          {:type     "radio"
           :title    "Ablehnen"
           :classes  [(when (neg? utility) "active")]
           :disabled (not logged-in?)
           :onClick  #(comp/transact! comp [(set-vote {:proposal/id  id
                                                       :vote/utility (if (neg? utility) 0 -1)})]
                        {:refresh [(comp/get-ident ProposalCollection nil)]})}
          (IoIosCloseCircleOutline #js {:size "100%"})))
      (div :.proposal__price
        (span :.proposal__price__text (str cost) currency))
      (button :.options.disabled.invisible
        {:title "Optionen"}
        (IoMdMore #js {:size "24px"}))
      (div :.proposal__content
        {:data-toggle  "modal"
         :data-target  (str "#modal-" id)
         :onMouseEnter #(df/load-field! comp :>/proposal-details {})}
        (dom/h6 :.proposal__title text)
        (div :.proposal__details (split-details details))))))

(def loading-spinner
  (div :.mt-5.d-flex.justify-content-center.align-items-center
    (div :.spinner-border {:role "status"}
      (span :.sr-only "Loading..."))))

(defn bottomsheet [id & children]
  (div :.modal.fade.bottom-sheet
    {:id (str "modal-" id)}
    (div :.spacer-frame)
    (div :.modal-dialog
      (div :.modal-content
        (div :.modal-body
          children)
        (button :.close
          {:style        {:position "absolute"
                          :top      "1rem"
                          :right    "1.8rem"}
           :data-dismiss "modal"} (IoMdClose))))))

(defsc ProposalCard [this {:keys          [>/proposal-details]
                           :proposal/keys [id] :as props}]
  {:query         [:proposal/id :argument/text :proposal/details :proposal/cost
                   :vote/utility
                   :process/currency
                   session/valid?-query
                   {:>/proposal-details (comp/get-query ProposalDetails)}]
   :ident         :proposal/id
   :initial-state (fn [_] {:process/currency   "€"
                           :vote/utility       0
                           :>/proposal-details (comp/initial-state ProposalDetails nil)})}
  (div :.proposal
    (proposal-card this props)
    (bottomsheet id
      (if proposal-details
        (ui-proposal-detail proposal-details)
        loading-spinner))))

(def ui-proposal-card (comp/factory ProposalCard {:keyfn (util/prefixed-keyfn :proposal-card :proposal/id)}))

(defn field [{:keys [label valid? error-message] :as props}]
  (let [input-props (-> props (assoc :name label) (dissoc :label :valid? :error-message))]
    (div :.form-group.field
      (label {:htmlFor label} label)
      (input input-props)
      (div :.ui.error.message {:classes [(when valid? "hidden")]}
        error-message))))

(defn- with-placeholder [value placeholder]
  (if (str/blank? value)
    placeholder
    value))

(defmutation new-proposal [params]
  (action [{:keys [state]}]
    (swap! state mrg/merge-component ProposalCard params :append [:all-proposals]))
  (remote [env]
    (m/returning env ProposalCard)))

(defsc EnterProposal [this {:keys [title cost summary]}
                      {:keys [close-modal]}]
  {:query         [:title :cost :summary fs/form-config-join]
   :initial-state (fn [_]
                    (fs/add-form-config EnterProposal
                      {:title   ""
                       :cost    ""
                       :summary ""}))
   :ident         (fn [] [:component/id :new-proposal])
   :form-fields   #{:title :cost :summary}}
  (let [title-max-length    100
        title-warn-length   (- title-max-length 10)
        summary-max-length  500
        summary-warn-length (- summary-max-length 20)

        short-summary       (split-details summary)]
    (div
      (div :.d-flex.justify-content-center.mb-3
        {:style {:pointerEvents "none"}}
        (proposal-card this
          {:argument/text    (with-placeholder title "Es sollte ein Wasserspender im Flur aufgestellt werden.")
           :proposal/cost    (with-placeholder cost "0")
           :proposal/details (with-placeholder short-summary "Ein Wasserspender sorgt dafür, dass alle Studenten und Mitarbeiter mehr trinken. Dies sorgt für ein gesünderes Leben.")
           :process/currency "€"}))
      (form :.p-md-3
        {:onSubmit (fn [e]
                     (evt/prevent-default! e)
                     (comp/transact! this [(new-proposal {:proposal/id      (tempid/tempid)
                                                          :proposal/cost    cost
                                                          :proposal/details summary
                                                          :argument/text    title})])
                     (close-modal))}
        (let [approaching-limit? (> (count title) title-warn-length)
              chars-exceeded?    (> (count title) title-max-length)]
          (div :.form-group
            (label "Titel")
            (input :.form-control
              {:placeholder "Es sollte ein Wasserspender im Flur aufgestellt werden."
               :value       title
               :required    true
               :onChange    #(m/set-string! this :title :event %)})
            (small :.form-text
              {:style   {:display (when-not approaching-limit? "none")}
               :classes [(when chars-exceeded? "text-danger")]}
              (str (count title) "/" title-max-length " Buchstaben")
              (when chars-exceeded? ". Bitte fassen Sie sich kurz!"))))

        (div :.form-group
          (label "Geschätzte Kosten")
          (input :.form-control
            {:type        "number"
             :value       cost
             :placeholder "1000"
             :min         "0"
             :step        "1"
             :required    true
             :onChange    #(m/set-string! this :cost :event %)}))

        (let [approaching-limit? (> (count title) summary-warn-length)
              chars-exceeded?    (> (count title) summary-max-length)]
          (div :.form-group
            (label "Details zum Vorschlag")
            (textarea :.form-control
              {:placeholder "Ein Wasserspender sorgt dafür, dass alle Studenten und Mitarbeiter mehr trinken. Dies sorgt für ein gesünderes Leben."
               :onChange    #(m/set-string! this :summary :event %)
               :value       summary})
            (small :.form-text
              {:style   {:display (when-not approaching-limit? "none")}
               :classes [(when chars-exceeded? "text-danger")]}
              (str (count title) "/" title-max-length " Buchstaben")
              (when chars-exceeded? ". Bitte beschränken Sie sich auf das Limit!"))))
        (button :.btn.btn-primary "Einreichen")))))

(def ui-new-proposal-form (comp/computed-factory EnterProposal))

(def not-logged-in-banner (div :.row.alert.alert-warning "Sie sind nicht eingeloggt. Sie können daher nichts hinzufügen."))

(defn new-proposal-modal [comp {:keys [new-proposal-form modal-ref]}]
  (div :.modal.fade
    {:ref modal-ref}
    (div :.modal-dialog.modal-lg
      (div :.modal-content
        (div :.modal-header
          (dom/h5 :.modal-title "Neuer Vorschlag")
          (button :.close
            {:data-dismiss "modal"
             :aria-label   "Close"}
            (span {:aria-hidden "true"} (IoMdClose))))
        (div :.modal-body
          (ui-new-proposal-form new-proposal-form {:close-modal #(m/toggle! comp :ui/show-new-proposal?)}))))))

(defsc ProposalCollection [this {:keys [all-proposals new-proposal-form ui/hide-declined?] :as props}]
  {:query              [{[:all-proposals '_] (comp/get-query ProposalCard)}
                        {:new-proposal-form (comp/get-query EnterProposal)}
                        :ui/show-new-proposal?
                        :ui/hide-declined?
                        session/valid?-query]
   :initial-state      (fn [_] {:all-proposals     []
                                :new-proposal-form (comp/get-initial-state EnterProposal)
                                :ui/hide-declined? false})
   :ident              (fn [] [:component/id :proposals])
   :route-segment      ["proposals"]
   :will-enter         (fn [app _]
                         (dr/route-deferred [:component/id :proposals]
                           #(df/load! app :all-proposals ProposalCard
                              {:without              #{:>/proposal-details}
                               :post-mutation        `dr/target-ready
                               :post-mutation-params {:target [:component/id :proposals]}})))
   :initLocalState     (fn [this _]
                         {:modal-ref (fn [r] (gobj/set this "modal" ($ r)))})
   :componentDidMount  (fn [this]
                         (when-let [modal (gobj/get this "modal")]
                           (.on modal "hide.bs.modal" #(m/set-value! this :ui/show-new-proposal? false))))
   :componentDidUpdate (fn [this _ _ _]
                         (when-let [modal (gobj/get this "modal")]
                           (.modal modal
                             (if (:ui/show-new-proposal? (comp/props this))
                               "show" "hide"))))
   :css                [[:.proposal-deck [:>* {:padding "5px"}]]]}
  (let [logged-in? (session/get-logged-in? props)
        {:keys [proposal-deck]} (css/get-classnames ProposalCollection)]
    (div :.container-md.py-2
      (when-not logged-in? not-logged-in-banner)
      (div :.row.btn-toolbar.justify-content-between.mb-3
        {:classes [(when-not logged-in? :.d-none)]}
        (div :.btn-group
          (button :.btn.btn-primary
            {:onClick #(m/toggle! this :ui/show-new-proposal?)}
            "Neuen Vorschlag hinzufügen"))

        (div :.btn-group
          (button :.btn.btn-sm
            {:classes [(if hide-declined? :.btn-secondary :.btn-light)]
             :title   "Bewege abgelehnte Vorschläge an das Ende"
             :style   {:minWidth "13em"}
             :onClick #(m/toggle! this :ui/hide-declined?)}
            (if hide-declined?
              (span (IoMdEyeOff) " Zeige Abgelehnte")
              (span (IoMdEye) " Verstecke Abgelehnte")))))

      (new-proposal-modal this {:new-proposal-form new-proposal-form
                                :modal-ref         (comp/get-state this :modal-ref)})

      (let [filtered-proposals (remove (comp neg? :vote/utility) all-proposals)
            proposals          (if hide-declined? filtered-proposals all-proposals)]
        (if-not (empty? proposals)
          (div :.row.card-deck.row-cols-1.row-cols-lg-2
            {:classes [proposal-deck]}
            (map ui-proposal-card proposals))

          (dom/div :.pt-3.text-muted.text-center
            (if (empty? all-proposals)
              (dom/h3 "Noch gibt es keine Vorschläge.")
              (dom/h4
                (dom/p "Sie haben alle Vorschläge abgelehnt und ausgeblendet.")
                (dom/button :.btn.btn-link
                  {:onClick #(m/toggle! this :ui/hide-declined?)}
                  "Einblenden")))
            (when-not logged-in?
              (comp/fragment
                (dom/hr)
                (dom/button :.btn.btn-link "Vorschlag hinzufügen")))))))))
