(ns slopbook.migration-test-runner
  (:require [clojure.test :as test]
            [slopbook.identity.adapters.out.postgres-accounts-integration-test]
            [slopbook.identity.adapters.out.postgres-characters-integration-test]
            [slopbook.messaging.adapters.out.postgres-messages-integration-test]
            [slopbook.media.adapters.out.postgres-media-integration-test]
            [slopbook.platform.migration-integration-test]
            [slopbook.publishing.adapters.out.postgres-comments-integration-test]
            [slopbook.publishing.adapters.out.postgres-posts-integration-test]
            [slopbook.publishing.application.profiles-integration-test]
            [slopbook.social.adapters.out.postgres-social-integration-test]
            [slopbook.transformation.adapters.out.postgres-runs-integration-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'slopbook.platform.migration-integration-test
                        'slopbook.identity.adapters.out.postgres-accounts-integration-test
                        'slopbook.identity.adapters.out.postgres-characters-integration-test
                        'slopbook.messaging.adapters.out.postgres-messages-integration-test
                        'slopbook.media.adapters.out.postgres-media-integration-test
                        'slopbook.publishing.adapters.out.postgres-comments-integration-test
                        'slopbook.publishing.adapters.out.postgres-posts-integration-test
                        'slopbook.publishing.application.profiles-integration-test
                        'slopbook.social.adapters.out.postgres-social-integration-test
                        'slopbook.transformation.adapters.out.postgres-runs-integration-test)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (System/exit 1))))
