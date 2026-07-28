!#/bin/bash

echo "mvn clean package"
mvn clean package

echo "build t inaccurate article crawler"
docker build -t inaccurate-article-crawler:1.0 .

echo "login to hub center"
docker login -u vtcc -p vtcc@2017 hub.vtcc.vn:8999


echo "get tag"
docker tag inaccurate-article-crawler:1.0 hub.vtcc.vn:8999/vtcc/ptdl/inaccurate-article-crawler:1.0

echo "pushing up"
docker push hub.vtcc.vn:8999/vtcc/ptdl/inaccurate-article-crawler:1.0

echo "end"