(function () {
  "use strict";

  var messages = {
    en: "Opening Slopbook…",
    ko: "Slopbook 여는 중…",
    "zh-Hans": "正在打开 Slopbook…",
    ja: "Slopbook を開いています…"
  };

  function normalize(value) {
    var locale = String(value || "").replace("_", "-").toLowerCase();
    if (locale.indexOf("ko") === 0) return "ko";
    if (locale.indexOf("ja") === 0) return "ja";
    if (locale.indexOf("zh") === 0) return "zh-Hans";
    return "en";
  }

  var stored;
  try {
    stored = window.localStorage.getItem("slopbook.locale");
  } catch (_) {
    stored = null;
  }

  var locale = normalize(stored || window.navigator.language);
  document.documentElement.lang = locale;
  document.documentElement.dir = "ltr";

  var boot = document.querySelector(".boot-screen");
  if (boot) boot.textContent = messages[locale];
}());
