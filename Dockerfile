FROM node:22-bookworm-slim AS node-runtime

FROM clojure:temurin-21-tools-deps AS application-build

WORKDIR /build
# Keep compilation bounded on the shared 1-OCPU, 6-GB deployment worker.
ENV JAVA_TOOL_OPTIONS="-Xmx768m -XX:MaxMetaspaceSize=256m"
ENV NODE_OPTIONS="--max-old-space-size=512"
COPY --from=node-runtime /usr/local/bin/node /usr/local/bin/node
COPY --from=node-runtime /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

COPY package.json package-lock.json shadow-cljs.edn deps.edn build.clj ./
COPY src ./src
COPY resources ./resources
COPY scripts/write-version.mjs ./scripts/write-version.mjs
ARG IF0_COMMIT
RUN npm ci --ignore-scripts \
    && npm run cljs:release \
    && npm run build:version \
    && clojure -P -M:run \
    && clojure -T:build uberjar

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl webp \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home slopbook

WORKDIR /app
COPY --from=application-build --chown=slopbook:slopbook \
  /build/target/slopbook-0.2.0-standalone.jar /app/slopbook.jar

ENV SLOPBOOK_PORT=8080
EXPOSE 8080

USER 10001
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD curl --fail --silent --max-time 2 http://127.0.0.1:8080/health/ready >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/slopbook.jar"]
