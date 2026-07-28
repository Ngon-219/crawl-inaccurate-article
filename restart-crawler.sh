#!/bin/bash

set -e

export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

echo "--- Stopping and cleaning Docker containers ---"
docker compose down -v

echo "--- Starting Docker containers ---"
docker compose up -d

sleep 5

echo "delete cache playwirht"
rm -rf /tmp/playwright-java-*

echo "--- Building project with Maven ---"
mvn clean package -DskipTests

#echo "--- Installing Playwright Chromium into the host cache (embedded browser) ---"
#java -cp target/inaccurate-article-crawler-1.0-SNAPSHOT.jar \
#  com.microsoft.playwright.CLI install chromium

echo "--- Seeding URLs ---"
java -cp target/inaccurate-article-crawler-1.0-SNAPSHOT.jar \
  crawlercommons.urlfrontier.client.Client PutURLs -f seeds.txt

echo "--- Running Storm topology ---"
storm local target/inaccurate-article-crawler-1.0-SNAPSHOT.jar \
org.apache.storm.flux.Flux crawler.flux --local-ttl 3600 2>&1 | tee crawl_output.log