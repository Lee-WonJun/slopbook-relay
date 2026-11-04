(ns slopbook.test-runner
  (:require [clojure.test :as test]
            [slopbook.architecture-test]
            [slopbook.client-accessibility-contract-test]
            [slopbook.identity.adapters.out.jwks-test]
            [slopbook.identity.adapters.out.sigil-raster-test]
            [slopbook.identity.adapters.out.turnstile-test]
            [slopbook.identity.application.accounts-test]
            [slopbook.identity.domain.anonymous-test]
            [slopbook.identity.domain.character-test]
            [slopbook.identity.domain.persona-test]
            [slopbook.i18n-contract-test]
            [slopbook.media.adapters.out.image-processor-test]
            [slopbook.platform.client-ip-test]
            [slopbook.platform.config-test]
            [slopbook.platform.db-test]
            [slopbook.qa-account-controls-contract-test]
            [slopbook.qa-choice-layout-contract-test]
            [slopbook.qa-comment-media-contract-test]
            [slopbook.qa-messages-width-contract-test]
            [slopbook.qa-reaction-vocabulary-contract-test]
            [slopbook.social.application.relationships-test]
            [slopbook.system-test]
            [slopbook.relay-test]
            [slopbook.tickets-test]
            [slopbook.transformation.adapters.out.openai-test]
            [slopbook.transformation.adapters.out.resilient-test]
            [slopbook.transformation.domain.circuit-breaker-test]
            [slopbook.transformation.domain.rewrite-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'slopbook.architecture-test
                                             'slopbook.client-accessibility-contract-test
                                             'slopbook.platform.db-test
                                             'slopbook.platform.client-ip-test
                                             'slopbook.platform.config-test
                                             'slopbook.qa-account-controls-contract-test
                                             'slopbook.qa-choice-layout-contract-test
                                             'slopbook.qa-comment-media-contract-test
                                             'slopbook.qa-messages-width-contract-test
                                             'slopbook.qa-reaction-vocabulary-contract-test
                                             'slopbook.identity.adapters.out.jwks-test
                                             'slopbook.identity.adapters.out.sigil-raster-test
                                             'slopbook.identity.adapters.out.turnstile-test
                                             'slopbook.identity.application.accounts-test
                                             'slopbook.identity.domain.anonymous-test
                                             'slopbook.identity.domain.character-test
                        'slopbook.identity.domain.persona-test
                        'slopbook.i18n-contract-test
                        'slopbook.media.adapters.out.image-processor-test
                                             'slopbook.social.application.relationships-test
                                             'slopbook.system-test
                                             'slopbook.relay-test
                                             'slopbook.transformation.adapters.out.openai-test
                                             'slopbook.transformation.adapters.out.resilient-test
                                             'slopbook.transformation.domain.circuit-breaker-test
                                             'slopbook.transformation.domain.rewrite-test
                                             'slopbook.tickets-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
