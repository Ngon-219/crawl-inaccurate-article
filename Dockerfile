FROM hub.vtcc.vn:8989/storm:2.8.8

WORKDIR /apache-storm

COPY target/*.jar crawler.jar

COPY crawler.flux crawler.flux
COPY crawler-conf.yaml crawler-conf.yaml

COPY src/main/resources/urlfilters.json               src/main/resources/urlfilters.json
COPY src/main/resources/parsefilters.json              src/main/resources/parsefilters.json
COPY src/main/resources/jsoupfilters.json              src/main/resources/jsoupfilters.json
COPY src/main/resources/default-regex-filters.txt      src/main/resources/default-regex-filters.txt
COPY src/main/resources/default-regex-normalizers.xml  src/main/resources/default-regex-normalizers.xml
COPY src/main/resources/playwright-actions.json        src/main/resources/playwright-actions.json

COPY src/main/resources/proxies.txt src/main/resources/proxies.txt