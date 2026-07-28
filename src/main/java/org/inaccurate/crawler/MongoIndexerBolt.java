package org.inaccurate.crawler;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.persistence.Status;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.MongoWriteException;
import org.bson.Document;
import java.util.Map;

public class MongoIndexerBolt extends BaseRichBolt {
    private OutputCollector collector;
    private MongoClient mongoClient;
    private MongoCollection<Document> collection;

    @Override
    public void prepare(Map<String, Object> stormConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;

        // Connection URI resolution order (so the same jar runs in local dev,
        // docker-compose and Kubernetes without a rebuild):
        //   1. Storm config key "mongodb.uri"  (injected via crawler-conf.yaml)
        //   2. environment variable MONGO_URI  (injected via the pod spec/Secret)
        //   3. localhost default               (bare-metal / IDE run)
        String uri = null;
        Object confUri = stormConf.get("mongodb.uri");
        if (confUri != null && !confUri.toString().isBlank()) {
            uri = confUri.toString();
        } else if (System.getenv("MONGO_URI") != null && !System.getenv("MONGO_URI").isBlank()) {
            uri = System.getenv("MONGO_URI");
        } else {
            uri = "mongodb://admin:password123@localhost:27017/";
        }

        String dbName = stormConf.getOrDefault("mongodb.database", "crawler_db").toString();
        String collName = stormConf.getOrDefault("mongodb.collection", "articles").toString();

        this.mongoClient = MongoClients.create(uri);

        MongoDatabase database = mongoClient.getDatabase(dbName);
        this.collection = database.getCollection(collName);

        IndexOptions indexOptions = new IndexOptions().unique(true);
        this.collection.createIndex(new Document("url", 1), indexOptions);
    }

    @Override
    public void execute(Tuple tuple) {
        String url = tuple.getStringByField("url");
        Metadata metadata = (Metadata) tuple.getValueByField("metadata");

        try {
            String text = tuple.getStringByField("text");
            String title = metadata.getFirstValue("parse.title");

            Document doc = new Document("url", url)
                    .append("title", title != null ? title : "No Title")
                    .append("content", text)
                    .append("crawled_at", System.currentTimeMillis());

            collection.insertOne(doc);

            // Báo cho StatusUpdaterBolt biết URL đã index xong (bắt buộc,
            // nếu không URLFrontier sẽ không bao giờ biết URL này đã FETCHED).
            // AbstractStatusUpdaterBolt.execute() đọc field "status" bắt buộc
            // phải có kiểu org.apache.stormcrawler.persistence.Status.
            collector.emit("status", tuple, new Values(url, Status.FETCHED, metadata));
            collector.ack(tuple);

        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                // Duplicate key (URL đã tồn tại) - vẫn coi là thành công,
                // vẫn phải báo status để URLFrontier không giữ URL ở trạng thái treo
                collector.emit("status", tuple, new Values(url, Status.FETCHED, metadata));
                collector.ack(tuple);
            } else {
                collector.fail(tuple);
            }
        } catch (Exception e) {
            collector.fail(tuple);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declareStream("status", new Fields("url", "status", "metadata"));
    }

    @Override
    public void cleanup() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}