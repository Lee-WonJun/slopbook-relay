(ns slopbook.client.media)

(defn snapshot-files
  "Copies a live browser FileList before its input value is cleared."
  [file-list]
  (vec (array-seq file-list)))

(defn selection-announcement
  "Returns the current selection warning, clearing any stale prior announcement
  when every candidate is valid."
  [candidates valid? invalid-message]
  (if (some #(not (valid? %)) candidates)
    invalid-message
    ""))
