(ns slopbook.shared.domain.greeting)

(defn intent [language display-name]
  (case language
    "ko" (str "안녕하세요, " display-name "입니다. Slopbook에 가입했습니다. 잘 부탁드립니다.")
    "zh-Hans" (str "你好，我是" display-name "。我加入了 Slopbook，请多关照。")
    "ja" (str "こんにちは、" display-name "です。Slopbookに参加しました。よろしくお願いします。")
    (str "Hi, I'm " display-name ". I joined Slopbook. Nice to meet you.")))
