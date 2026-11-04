(ns slopbook.client.icons)

(def base-svg
  {:view-box "0 0 20 20"
   :fill "none"
   :xmlns "http://www.w3.org/2000/svg"
   :focusable "false"})

(def stroke
  {:stroke "currentColor"
   :stroke-width "1.7"
   :stroke-linecap "round"
   :stroke-linejoin "round"})

(defn human-owned-mark [accessible-name]
  [:svg.human-owned-mark
   (merge base-svg {:view-box "0 0 16 16"
                    :role "img"
                    :aria-label accessible-name})
   [:circle {:cx "8" :cy "4.1" :r "2.1" :fill "currentColor"}]
   [:path (merge stroke {:d "M3.8 13.1c.45-3 1.9-4.55 4.2-4.55s3.75 1.55 4.2 4.55"})]
   [:circle {:cx "2.1" :cy "7.3" :r ".8" :fill "currentColor" :opacity ".55"}]
   [:circle {:cx "13.9" :cy "7.3" :r ".8" :fill "currentColor" :opacity ".55"}]
   [:circle {:cx "8" :cy "14.5" :r ".65" :fill "currentColor" :opacity ".42"}]])

(defn reaction-choice []
  [:svg.reaction-icon (merge base-svg {:aria-hidden "true"})
   [:circle (merge stroke {:cx "10" :cy "10" :r "2.25"})]
   [:path (merge stroke {:d "M10 2.75v2M17.25 10h-2M10 17.25v-2M2.75 10h2M15.1 4.9l-1.4 1.4M15.1 15.1l-1.4-1.4M4.9 15.1l1.4-1.4M4.9 4.9l1.4 1.4"})]])

(defn repost []
  [:svg.action-icon (merge base-svg {:aria-hidden "true"})
   [:path (merge stroke {:d "M5.2 6.3h8.4l-2.2-2.2M14.8 13.7H6.4l2.2 2.2M13.6 6.3l1.2 1.2M6.4 13.7l-1.2-1.2"})]])

(defn share []
  [:svg.action-icon (merge base-svg {:aria-hidden "true"})
   [:path (merge stroke {:d "M10 13.8V3.5M6.8 6.7 10 3.5l3.2 3.2M4.2 10.2v5.4c0 .5.4.9.9.9h9.8c.5 0 .9-.4.9-.9v-5.4"})]])

(defn close []
  [:svg.action-icon (merge base-svg {:aria-hidden "true"})
   [:path (merge stroke {:d "m5 5 10 10M15 5 5 15"})]])

(defn- like-icon []
  [:<>
   [:path (merge stroke {:d "M5.4 9.1h-2v7.2h2M5.4 15.8h7.15c.78 0 1.45-.55 1.6-1.31l1.05-5.12a1.6 1.6 0 0 0-1.57-1.92H10.6l.42-2.18c.2-1.04-.58-2.02-1.64-2.02L5.4 9.1v6.7Z"})]])

(defn- love-icon []
  [:path (merge stroke {:d "M10 16.25 4.2 10.8C1.15 7.95 3.1 3.6 6.35 3.6c1.7 0 2.85.95 3.65 2.05.8-1.1 1.95-2.05 3.65-2.05 3.25 0 5.2 4.35 2.15 7.2L10 16.25Z"})])

(defn- insightful-icon []
  [:<>
   [:path (merge stroke {:d "M7.25 13.1h5.5M7.9 15.9h4.2M7.55 11.2A5 5 0 1 1 12.45 11.2c-.6.42-.85.85-.85 1.9H8.4c0-1.05-.25-1.48-.85-1.9Z"})]
   [:path (merge stroke {:d "M10 1.25v1M3.85 3.8l.75.75M16.15 3.8l-.75.75"})]])

(defn- celebrate-icon []
  [:<>
   [:path (merge stroke {:d "m4 15.9 2.25-8.15 6 6L4 15.9ZM6.25 7.75l2.3 5.9"})]
   [:circle {:cx "13.8" :cy "4.2" :r "1" :fill "currentColor"}]
   [:circle {:cx "16.2" :cy "9" :r ".8" :fill "currentColor"}]
   [:path (merge stroke {:d "M10.6 6.4c.2-2.1 1.35-3.25 3.45-3.45M12.7 10c1.8-.45 3.15.05 4.05 1.5"})]])

(defn- support-icon []
  [:<>
   [:path (merge stroke {:d "M8.65 6.1 6.8 4.25a2.55 2.55 0 0 0-3.6 3.6l4.25 4.25a2.55 2.55 0 0 0 3.6 0l1.35-1.35"})]
   [:path (merge stroke {:d "m11.35 13.9 1.85 1.85a2.55 2.55 0 0 0 3.6-3.6L12.55 7.9a2.55 2.55 0 0 0-3.6 0L7.6 9.25"})]])

(defn- funny-icon []
  [:<>
   [:circle (merge stroke {:cx "10" :cy "10" :r "7.1"})]
   [:path (merge stroke {:d "M6.8 8.15h.05M13.15 8.15h.05M6.55 11.5c.8 1.65 1.95 2.45 3.45 2.45s2.65-.8 3.45-2.45"})]])

(defn reaction [reaction-type]
  [:svg.reaction-icon
   (merge base-svg {:class (str "reaction-icon reaction-icon-" reaction-type)
                    :aria-hidden "true"})
   (case reaction-type
     "like" [like-icon]
     "love" [love-icon]
     "insightful" [insightful-icon]
     "celebrate" [celebrate-icon]
     "support" [support-icon]
     "funny" [funny-icon]
     [reaction-choice])])
