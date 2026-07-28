#!/bin/bash
set -e

RESPONSE=$(curl -s -X POST http://localhost:4444/wd/hub/session \
  -H "Content-Type: application/json" \
  -d '{"capabilities":{"alwaysMatch":{"browserName":"chrome","goog:chromeOptions":{"args":["--headless=new","--no-sandbox","--disable-dev-shm-usage"]}}}}')

SESSION_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['value']['sessionId'])")
echo "Session ID: $SESSION_ID"

curl -s -X POST "http://localhost:4444/wd/hub/session/$SESSION_ID/url" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://dichvucong.gov.vn/"}'

echo "Đang chờ 20s để trang render..."
sleep 20

echo "--- DOM sau khi chờ 20s ---"
curl -s "http://localhost:4444/wd/hub/session/$SESSION_ID/source" | head -c 3000

curl -s -X DELETE "http://localhost:4444/wd/hub/session/$SESSION_ID"
echo "--- Đã đóng session ---"