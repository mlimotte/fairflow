(ns fair.gsheets2
  (:require
    [clojure.tools.logging :as log]
    [clojure.core.memoize :as memo]
    [clojure.string :as string]
    [flatland.ordered.map :refer [ordered-map]]
    [google-apps-clj.credentials :as gcreds]
    [google-apps-clj.google-sheets-v4 :as gsheet]
    [camel-snake-kebab.core :as csk]
    [fair.flow.util.lang :as lang]
    [clj-time.core :as time])
  (:import
    com.google.api.services.sheets.v4.model.CellData))

;;; Extension to gsheets lib

(defn raw-cell-value
  [cell-data]
  (let [ev  (get cell-data "effectiveValue")
        uev (get cell-data "userEnteredValue")]
    (or ev uev)))

(defn cell->clj
  "Converts cell data with either a userEnteredValue (x)or effectiveValue to a clojure type.
  stringValue -> string
  numberValue -> double
  booleanValue -> boolean
  DATE -> date-time
  else ~ identity"
  ; Note: This fn is copied from lib `google-apps-clj`, in order to add support for booleans
  [cell-data]
  (let [ev            (get cell-data "effectiveValue")
        uev           (get cell-data "userEnteredValue")
        v             (or ev uev)
        string-val    (get v "stringValue")
        number-val    (get v "numberValue")
        bool-val      (get v "boolValue")
        number-format (get-in cell-data ["userEnteredFormat" "numberFormat" "type"])
        date?         (and (= "DATE" number-format) (some? number-val))
        currency?     (and (= "CURRENCY" number-format) (some? number-val))
        empty-cell?   (and (nil? ev) (nil? uev) (instance? CellData cell-data))]
    (when (and (some? ev)
               (some? uev))
      (throw (ex-info "Ambiguous cell data, contains both string effectiveValue and userEnteredValue"
                      {:cell-data cell-data})))
    (when (and (some? string-val)
               (some? number-val))
      (throw (ex-info "Ambiguous cell data value, contains both stringValue and numberValue"
                      {:cell-data cell-data})))
    (cond
      string-val
      string-val

      date?
      ;; https://developers.google.com/sheets/api/guides/concepts#datetime_serial_numbers
      (time/plus (time/date-time 1899 12 30) (time/days (long number-val)))

      currency?
      (bigdec number-val)

      number-val
      number-val

      (some? bool-val)
      bool-val

      empty-cell?
      nil

      :else
      cell-data)))

;;;

(defrecord SheetContext
  [service spreadsheet-id sheet-title sheet-id]
  Object
  (toString [_] (format "SheetContext[%s.../%s]" (subs spreadsheet-id 0 12) sheet-title)))

(defn login*
  "Login it to Google Sheets using the supplied JSON credentials file.
  To get credentials, see documentation at https://github.com/SparkFund/google-apps-clj.
  `creds-file` can be anything that can be handled by `clojure.java.io/input-stream`"
  [creds-file]
  (log/info "Logging into Google service.")
  (let [creds   (gcreds/credential-with-scopes
                  (gcreds/credential-from-json-stream creds-file)
                  (into gsheet/scopes [com.google.api.services.drive.DriveScopes/DRIVE]))
        service (gsheet/build-service creds)]
    service))

(def login (memo/lru login* {} :lru/threshold 5))

(defn find-sheet-id
  [ctx]
  (gsheet/find-sheet-id (:service ctx) (:spreadsheet-id ctx) (:sheet-title ctx)))

(defn make-context
  "Get a Sheets context from a URI of the form `gsheet://sheet-id/sheet-title`.
  Note that sheet title (the Tab name) IS case-sensitive."
  ([creds-file gsheet-uri]
   (let [uri         (java.net.URI. gsheet-uri)
         ; getAuthority is like getHost but more forgiving of chars that are illegal as a host
         sheet-id    (.getAuthority uri)
         sheet-title (-> uri .getPath (lang/safe-subs 1))]
     (make-context creds-file sheet-id sheet-title)))
  ([creds-file spreadsheet-id sheet-title]
   (log/info "Creating Sheets context for" spreadsheet-id sheet-title)
   (let [ctx*     {:service        (login creds-file)
                   :spreadsheet-id spreadsheet-id
                   :sheet-title    sheet-title}
         sheet-id (find-sheet-id ctx*)]
     (map->SheetContext (assoc ctx* :sheet-id sheet-id)))))

(defn get-cells-from-sheet
  [ctx cell-range]
  (->> (str (:sheet-title ctx) "!" cell-range)
       vector
       (gsheet/get-cells (:service ctx) (:spreadsheet-id ctx))
       ; first range
       first))

(defn empty-row?
  [row]
  (every? (comp nil? raw-cell-value) row))

(defn row-len
  [row]
  (->> row
       reverse
       (drop-while #(nil? (get % "effectiveValue")))
       count))

(let [A (int \A)]
  (defn col-indices
    ([]
     (col-indices 0))
    ([prefix]
     (concat
       (map #(str (if (pos? prefix) (char (+ A prefix -1)))
                  (char (+ A %)))
            (range 26))
       (lazy-seq (col-indices (inc prefix)))))))

(defn row-count
  "Get the row count, but will only see rows if there is some data in the first 4 columns."
  [ctx]
  (->> (get-cells-from-sheet ctx "A1:D")
       ; This is a vector of rows, we want to start dropping rows from the end:
       reverse
       (drop-while empty-row?)
       count))

(defn col-count
  "Get the column count, but will only see columns if there is data in the first 4 rows."
  [ctx]
  (->> (get-cells-from-sheet ctx "A1:4")
       (map row-len)
       (apply max)))

(defn read-all-data-cells
  "Return a Vector of Vectors for the row data.
  If `num-cols` is not specified, will attempt to guess based on first 4 rows of data."
  [ctx & [{:keys [num-cols max-rows]}]]
  (let [rows  (if max-rows max-rows (row-count ctx))
        cols  (or num-cols (col-count ctx))
        range (format "A1:%s%d" (nth (col-indices) (dec cols)) rows)]
    (log/info "Reading Gsheet cell range:" range)
    (get-cells-from-sheet ctx range)))

(defn read-all-data
  "Return a Vector of Vectors for the row data.
  If `num-cols` is not specified, will attempt to guess based on first 4 rows of data."
  [ctx & [options]]
  (->> (read-all-data-cells ctx options)
       (mapv (partial mapv cell->clj))))

(defn dups [coll]
  "If coll has any duplicates values, return a lazy-seq of those dupes."
  (for [[id freq] (frequencies coll)
        :when (> freq 1)]
    id))

(defn- assert-non-blank!
  [keywords]
  (if-let [blanks (seq (filter (comp string/blank? name) keywords))]
    (throw (ex-info "Blank headers were found, can not continue." {:blank-headers blanks}))
    keywords))

(defn- assert-unique!
  [keywords]
  (if-let [found (seq (dups keywords))]
    (throw (ex-info "Duplicate headers were found, can not continue." {:duplicate-headers found}))
    keywords))

(defn format-filter
  [text-format-name cell]
  (and (some? (raw-cell-value cell))
       (get-in cell ["userEnteredFormat" "textFormat" text-format-name])))

(defn get-headers
  [ctx]
  (->> (read-all-data-cells ctx {:max-rows 1})
       (map (partial map cell->clj))
       first
       assert-non-blank!
       (map string/lower-case)
       (map #(string/replace % " " "-"))
       (map keyword)
       assert-unique!
       (map (fn [idx col-header] [col-header idx]) (col-indices))
       ordered-map))

(defn all-records
  "Load all data as a Vector of Maps.

  Options:

    headers (List[Keyword]): The key names for the map. If supplied, should be
      in the same order as the columns. If not given, then the first row of the sheet,
      converted to kebab-case-keyword is used.

    num-cols (Integer): The number of columns to read. If not specified, will
      attempt to guess based on first 4 rows of data.

    row-filter ((List[Keywords], Vector[Cell]) -> any): Cell is a Google Apps cell
      object (see cell->clj). A fn of header keys and cells; only rows for which
      this function returns truthy will be returned in the result.

    skip-if-X-in (Keyword): If the named column contains an \"X\" (capital), the
      row is skipped. This functionality could be done with a `row-filter`, but
      it is a common case, so providing explicit support.
  "
  [ctx & [{:keys [headers skip-if-X-in num-cols row-filter] :as options}]]
  (let [all-data-cells (read-all-data-cells ctx {:num-cols num-cols})
        header-keys    (-> (or headers (keys (get-headers ctx)))
                           assert-non-blank!
                           assert-unique!)]
    (->> all-data-cells
         ; embed a row index into each record for tracking
         (map-indexed vector)
         (#(cond->> %
                    (not headers) rest
                    row-filter (filter (comp (partial row-filter header-keys) second))))
         (map (fn [[idx record]] [idx (mapv cell->clj record)]))
         (map (fn [[idx record]]
                (assoc (zipmap header-keys record) ::row-idx idx)))

         ; Allow skipping of additional header/comment/total rows
         (remove #(= (get % skip-if-X-in) "X"))
         doall)))
