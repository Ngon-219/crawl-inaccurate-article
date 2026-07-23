#!/bin/bash

set -e

echo "--- Stopping and cleaning Docker containers ---"
docker compose down -v

echo "--- Starting Docker containers ---"
docker compose up -d

sleep 5

echo "--- Building project with Maven ---"
mvn clean package -DskipTests

echo "--- Installing Playwright Chromium into the host cache (embedded browser) ---"
java -cp target/inaccurate-article-crawler-1.0-SNAPSHOT.jar \
  com.microsoft.playwright.CLI install chromium

echo "--- Seeding URLs ---"
java -cp target/inaccurate-article-crawler-1.0-SNAPSHOT.jar \
  crawlercommons.urlfrontier.client.Client PutURLs -f seeds.txt

echo "--- Running Storm topology ---"
storm local target/inaccurate-article-crawler-1.0-SNAPSHOT.jar \
  org.apache.storm.flux.Flux crawler.flux --local-ttl 3600