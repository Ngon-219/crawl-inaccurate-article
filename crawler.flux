name: "crawler"

includes:
  - resource: true
    file: "/crawler-default.yaml"
    override: false
  - resource: false
    file: "crawler-conf.yaml"
    override: true

spouts:
  - id: "spout"
    className: "org.apache.stormcrawler.urlfrontier.Spout"
    parallelism: 1

bolts:
  - id: "partitioner"
    className: "org.apache.stormcrawler.bolt.URLPartitionerBolt"
    parallelism: 1
  - id: "fetcher"
    className: "org.apache.stormcrawler.bolt.FetcherBolt"
    parallelism: 4
  - id: "sitemap"
    className: "org.apache.stormcrawler.bolt.SiteMapParserBolt"
    parallelism: 1
  - id: "parse"
    className: "org.apache.stormcrawler.bolt.JSoupParserBolt"
    parallelism: 2
  - id: "redirect"
    className: "org.apache.stormcrawler.protocol.playwright.bolt.JsRenderingRedirectionBolt"
    parallelism: 1
  - id: "index"
    className: "org.inaccurate.crawler.MongoIndexerBolt"       # đổi đúng package thật của bạn
    parallelism: 1
  - id: "status"
    className: "org.apache.stormcrawler.urlfrontier.StatusUpdaterBolt"
    parallelism: 1

streams:
  - from: "spout"
    to: "partitioner"
    grouping: { type: SHUFFLE }
  - from: "partitioner"
    to: "fetcher"
    grouping: { type: FIELDS, args: ["key"] }
  - from: "fetcher"
    to: "sitemap"
    grouping: { type: LOCAL_OR_SHUFFLE }
  - from: "sitemap"
    to: "parse"
    grouping: { type: LOCAL_OR_SHUFFLE }

  # parse -> redirect: JsRenderingRedirectionBolt reschedules CSR pages
  # (fetch.with=playwright) for a Playwright refetch, and passes everything
  # else straight through to the indexer.
  - from: "parse"
    to: "redirect"
    grouping: { type: LOCAL_OR_SHUFFLE }
  - from: "redirect"
    to: "index"
    grouping: { type: LOCAL_OR_SHUFFLE }

  - from: "fetcher"
    to: "status"
    grouping: { type: FIELDS, streamId: "status", args: ["url"] }
  - from: "sitemap"
    to: "status"
    grouping: { type: FIELDS, streamId: "status", args: ["url"] }
  - from: "parse"
    to: "status"
    grouping: { type: FIELDS, streamId: "status", args: ["url"] }

  # redirect reschedules CSR URLs via the status stream (Status.FETCHED)
  - from: "redirect"
    to: "status"
    grouping: { type: FIELDS, streamId: "status", args: ["url"] }

  - from: "index"
    to: "status"
    grouping: { type: FIELDS, streamId: "status", args: ["url"] }