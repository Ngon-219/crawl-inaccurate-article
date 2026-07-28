# Custom crawler image = Apache Storm base + our uber-JAR + an embedded
# Playwright Chromium baked into the image.
#
# It is used by:
#   * the supervisor pods   -> worker JVMs launch the baked Chromium in-process
#   * the submit Job        -> `storm jar ... Flux crawler.flux`
#   * the seed Job          -> URLFrontier client to inject seeds.txt
# (Nimbus and the UI use the stock storm:2.8.8 image — they need neither the JAR
# nor a browser.)
#
# BUILD FROM THE REPO ROOT so the build context can see target/ :
#   mvn -q clean package -DskipTests
#   docker build -f k8s/Dockerfile -t inaccurate-article-crawler:1.0 .
#
# WHY bake the browser in (vs. a remote browser service): StormCrawler holds one
# long-lived browser connection for the bolt's whole lifetime. Session-pooling
# proxies like browserless kill that connection on a per-job timeout
# (TargetClosedError). An embedded launch has no such timeout and guarantees the
# Chromium build matches the bundled Playwright driver version. See the notes in
# ../crawler-conf.yaml.

# Pin to the SAME Storm version as <storm.version> in pom.xml. storm-client is a
# 'provided' dependency, so the running cluster must supply matching classes.
FROM storm:2.8.8

USER root

# The uber-JAR (Flux topology + all StormCrawler modules + Playwright driver).
COPY target/inaccurate-article-crawler-1.0-SNAPSHOT.jar /crawler/app.jar

# Flux topology definition + seed list. crawler-conf.yaml is intentionally NOT
# baked: it is mounted from the crawler-config ConfigMap at submit time so config
# changes don't require an image rebuild.
COPY crawler.flux /crawler/crawler.flux
COPY seeds.txt    /crawler/seeds.txt

# Install Chromium + its OS libraries into a shared, world-readable location.
# --with-deps runs apt-get for the native libraries Chromium needs (fonts, X libs).
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
RUN java -cp /crawler/app.jar com.microsoft.playwright.CLI install --with-deps chromium \
    && chmod -R a+rx /opt/ms-playwright

WORKDIR /crawler
