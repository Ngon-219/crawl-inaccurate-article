# Kubernetes deployment — StormCrawler (inaccurate-article-crawler)

This directory contains a complete Kubernetes deployment of the crawler and every
service it depends on, plus the reasoning behind each choice. Read the
[architecture](#1-architecture) and the
[StatefulSet vs Deployment](#3-why-statefulset-vs-why-deployment) sections first —
they explain *why* the manifests look the way they do.

---

## 1. Architecture

The crawler is an **Apache Storm topology** (defined by Flux in
[`../crawler.flux`](../crawler.flux)) that runs on a small Storm cluster and reads
from / writes to two data services.

```
                         ┌──────────────────────────────────────────┐
                         │                Storm cluster              │
                         │                                           │
   seeds.txt ──► seed    │   ┌─────────┐        ┌──────────────┐     │
                Job ─────┼─► │ Nimbus  │◄──────►│  Supervisor  │     │
                         │   │ (master)│  ZK    │  (workers +  │     │
   submit Job ──────────┼─► │         │        │  Playwright) │     │
   (storm jar Flux)      │   └────┬────┘        └──────┬───────┘     │
                         │        │ Thrift             │ worker JVMs │
                         │   ┌────▼────┐                │            │
                         │   │Storm UI │                │            │
                         │   └─────────┘                │            │
                         └──────────┼───────────────────┼───────────┘
                                    │                    │
              Zookeeper ◄───────────┘                    │
             (coordination)                              │
                                       ┌─────────────────┼──────────────┐
                                       │                 │              │
                                 ┌─────▼──────┐   ┌───────▼──────┐  (fetch web)
                                 │URLFrontier │   │   MongoDB    │
                                 │(crawl queue│   │(crawled docs)│
                                 │  RocksDB)  │   │              │
                                 └────────────┘   └──────────────┘
```

| Component      | Kind        | Image                              | Role |
|----------------|-------------|------------------------------------|------|
| Zookeeper      | StatefulSet | `zookeeper:3.9.3`                  | Storm coordination (leader election, assignments, heartbeats) |
| Nimbus         | StatefulSet | `storm:2.8.8`                      | Storm master; stores topology JAR + config |
| Supervisor     | Deployment  | `inaccurate-article-crawler:1.0`   | Runs worker JVMs **and the embedded Playwright Chromium** |
| Storm UI       | Deployment  | `storm:2.8.8`                      | Read-only dashboard |
| URLFrontier    | StatefulSet | `crawlercommons/url-frontier:2.4`  | The crawl frontier (which URLs, what state) — RocksDB on disk |
| MongoDB        | StatefulSet | `mongo:7`                          | Document sink written by `MongoIndexerBolt` |
| seed-urls      | Job         | `inaccurate-article-crawler:1.0`   | Injects `seeds.txt` into URLFrontier (run once) |
| submit-topology| Job         | `inaccurate-article-crawler:1.0`   | `storm jar ... Flux crawler.flux` (run once) |

### Dataflow (matches `crawler.flux`)
`spout` (pulls URLs from URLFrontier) → `partitioner` → `fetcher` → `sitemap` →
`parse` (JSoup; a `JsRenderingDetector` parse-filter flags client-side-rendered
pages with `render=playwright`) → `redirect` (reschedules CSR pages for a
Playwright render) → `index` (`MongoIndexerBolt` → MongoDB) → `status`
(`StatusUpdaterBolt` writes outcomes back to URLFrontier).

---

## 2. TL;DR — deploy it

```bash
# 0) from the repo root
cd ..

# 1) build the uber-JAR (produces target/inaccurate-article-crawler-1.0-SNAPSHOT.jar)
mvn -q clean package -DskipTests

# 2) build the custom worker/submitter image (Storm + baked Playwright Chromium).
#    Build context MUST be the repo root so it can see target/ .
docker build -f k8s/Dockerfile -t inaccurate-article-crawler:1.0 .

# 3) make the image reachable by the cluster:
#    - kind:      kind load docker-image inaccurate-article-crawler:1.0
#    - minikube:  minikube image load inaccurate-article-crawler:1.0
#    - real cluster: docker tag + docker push to your registry, then set the
#      image in k8s/kustomization.yaml (images: newName/newTag).

# 4) deploy everything
kubectl apply -k k8s/

# 5) watch it come up
kubectl -n crawler get pods -w
```

Bring-up ordering is handled for you: the numeric filename prefixes encode intent,
and the two Jobs use init-containers that **block until their dependency
(URLFrontier / Nimbus) answers on its port**, so `kubectl apply -k` is safe even
though everything is created at once.

### Verify

```bash
# topology is running?
kubectl -n crawler logs job/submit-topology

# open the Storm UI (then browse http://localhost:8080)
kubectl -n crawler port-forward svc/storm-ui 8080:8080

# documents landing in Mongo?
kubectl -n crawler exec -it mongodb-0 -- \
  mongosh -u admin -p password123 --authenticationDatabase admin \
  --eval 'db.getSiblingDB("crawler_db").articles.countDocuments()'
```

---

## 3. Why StatefulSet vs. why Deployment

This is the core design decision, so here is the rule and then each component
against it.

> **Use a StatefulSet when a pod needs a *stable identity* — a durable disk that
> follows it across restarts, and/or a fixed network name other things dial
> directly. Use a Deployment when pods are *fungible*: interchangeable, no
> per-pod disk, addressed as a pool.**

A StatefulSet gives three things a Deployment does not:
1. **Stable pod names & DNS** — `nimbus-0.nimbus.crawler.svc...` instead of a
   random `nimbus-6d9c...`.
2. **Stable per-pod storage** — each ordinal keeps *its own* PVC across
   reschedules (`volumeClaimTemplates`).
3. **Ordered, controlled rollout** — pods start/stop `0,1,2…`, which matters for
   quorum systems.

| Component | Choice | Why |
|-----------|--------|-----|
| **Zookeeper** | StatefulSet | Persists the transaction log + snapshots (must survive restart). Ensemble members are addressed individually and need a pinned `myid`↔volume mapping. Classic StatefulSet workload. |
| **Nimbus** | StatefulSet | Stores the **submitted topology JAR + serialized config** under `storm.local.dir`; supervisors download code *from Nimbus*. If Nimbus restarts onto an empty disk it can't serve that code and the topology breaks → needs a durable PVC. Also must be reachable at the fixed `nimbus.seeds` address. |
| **URLFrontier** | StatefulSet | **The crawl's memory.** RocksDB on disk records every URL's state. Lose it → the crawler re-crawls everything or stalls. The topology config points at a fixed hostname. Durable disk + stable name = StatefulSet. |
| **MongoDB** | StatefulSet | It's a database. The crawled corpus must persist; a single RWO volume must attach to exactly one stable pod. |
| **Supervisor** | **Deployment** | Stateless/fungible. `storm.local.dir` here is a *scratch cache* — a supervisor re-downloads the JAR from Nimbus on boot, so nothing needs persisting (`emptyDir`). Nimbus discovers supervisors dynamically via Zookeeper heartbeats; nothing dials a specific one. We want to scale/replace them freely. |
| **Storm UI** | **Deployment** | Stateless read-only view of Nimbus. No disk, no fixed identity, fronted by a normal load-balanced Service. |
| **seed / submit** | **Job** | Run-once actions that must run to completion and stop — not services. A Deployment would wrongly keep restarting them. |

### "Couldn't I just run the supervisor as a StatefulSet too?"
You *could*, and some charts do. But it buys nothing here: supervisors hold no
durable state and are never addressed by identity, so a StatefulSet's ordering
and per-pod PVCs are pure overhead. A Deployment matches their fungible nature
and makes `kubectl scale deployment/supervisor` the natural scaling knob.

---

## 4. Why the other choices

### 4.1 Config as data (ConfigMaps), not baked into the image
- **`storm-config`** (`02-storm-config.yaml`) → mounted at `/conf/storm.yaml` on
  every Storm daemon. One source of truth for `nimbus.seeds`,
  `storm.zookeeper.servers`, worker slots.
- **`crawler-config`** (`03-crawler-config.yaml`) → the K8s copy of
  `../crawler-conf.yaml`, mounted into the **submit Job**. Flux reads it at submit
  time and ships the merged config to the workers.

The JAR is immutable and identical across dev / compose / K8s; only the config
changes per environment. That's why the two hostnames that differ from local dev
live in the ConfigMap, not the image:
- `urlfrontier.host` → `urlfrontier-0.urlfrontier.crawler.svc.cluster.local`
- `mongodb.uri` → the MongoDB service (also injectable via the `MONGO_URI` env).

> **Keep them in sync:** `03-crawler-config.yaml` is a copy of
> `../crawler-conf.yaml` with three marked `# K8S:` changes. If you edit the
> crawler config, mirror it here.

### 4.2 The one required code change
`MongoIndexerBolt` used to hardcode `mongodb://admin:password123@localhost:27017/`.
That can't work in-cluster. It now resolves the URI in this order (no rebuild
needed to move between environments):
1. Storm config key `mongodb.uri` (from `crawler-config`)
2. env var `MONGO_URI` (from the `mongo-credentials` Secret, set on the supervisor
   — **worker JVMs inherit the supervisor's environment**)
3. `localhost` default (for a bare IDE run)

`mongodb.database` / `mongodb.collection` are configurable the same way (default
`crawler_db` / `articles`).

### 4.3 Secrets
MongoDB credentials live in a Secret (`01-secrets.yaml`), consumed both by the
MongoDB pod (root user bootstrap) and the supervisor (`MONGO_URI`). The committed
values are demo-only — see the header of that file for generating real ones.

### 4.4 Playwright Chromium baked into the image
CSR pages are rendered by an **embedded** headless Chromium *inside the worker
JVM* — not a separate browser service. StormCrawler holds one long-lived browser
connection for the bolt's lifetime, which session-pooling proxies (browserless)
break with per-job timeouts. So the browser is baked into the worker image
(`k8s/Dockerfile`, `playwright install --with-deps chromium`) at
`/opt/ms-playwright`, and the supervisor sets `PLAYWRIGHT_BROWSERS_PATH` to point
at it. `/dev/shm` is backed by a memory `emptyDir` because Chromium's default
shared-memory size is too small in containers.

### 4.5 Resource sizing
The heavy pod is the **supervisor**: 2 worker slots × `-Xmx2g` (from
`topology.worker.childopts`) **plus** a memory-hungry Chromium. It requests 3Gi
and is limited to 6Gi — do not lower the limit or the OOM killer will reap
workers mid-render. Everything else is modest (see each manifest).

### 4.6 Services are (mostly) headless
Zookeeper, Nimbus, URLFrontier and MongoDB use `clusterIP: None` (headless) so
that DNS resolves to the actual pod, giving the stable `-0` name their clients
are configured with. Storm UI uses a normal ClusterIP because it's a load-balanced
stateless endpoint (reach it via `port-forward`, or add an Ingress).

---

## 5. Day-2 operations

### Scale the crawl
Throughput is set by the topology, not the pod count:
1. In `../crawler-conf.yaml` **and** `03-crawler-config.yaml`, raise
   `topology.workers` (e.g. `2`) and bump bolt `parallelism` in `../crawler.flux`
   (especially `fetcher`).
2. Ensure enough worker slots: add supervisor replicas
   (`kubectl -n crawler scale deployment/supervisor --replicas=2`) and/or add
   ports to `supervisor.slots.ports` in `02-storm-config.yaml`.
3. Redeploy the topology (below).

### Redeploy topology code/config
`storm` refuses to submit a topology whose name already exists, so kill first:
```bash
kubectl -n crawler exec deploy/supervisor -- storm kill crawler -w 30 || true
kubectl -n crawler delete job submit-topology --ignore-not-found
kubectl apply -k k8s/          # recreates the submit Job -> resubmits
```
(If you changed the JAR, rebuild + reload/push the image first.)

### Add more seed URLs
Edit `seeds.txt` in `03-crawler-config.yaml`, then:
```bash
kubectl apply -k k8s/
kubectl -n crawler delete job seed-urls --ignore-not-found
kubectl apply -k k8s/          # re-runs the seed Job (URLFrontier de-dupes)
```

### Inspect the frontier
```bash
kubectl -n crawler exec deploy/supervisor -- \
  java -cp /crawler/app.jar crawlercommons.urlfrontier.client.Client \
  --host urlfrontier GetStats
```

### High availability (production)
- **Zookeeper**: `replicas: 3`, set `ZOO_STANDALONE_ENABLED=false`, and list all
  three in `storm.zookeeper.servers`.
- **Nimbus**: `replicas: 2+` (Storm elects a leader via ZK); list all seeds in
  `nimbus.seeds`.
- **MongoDB**: use the MongoDB Community Operator for a real replica set instead
  of the single-node StatefulSet here.
- **Storage**: set an explicit `storageClassName` on the `volumeClaimTemplates`
  for your platform (they use the cluster default here).

---

## 6. File map

| File | Purpose |
|------|---------|
| `Dockerfile` | Worker/submitter image: Storm + uber-JAR + Playwright Chromium |
| `kustomization.yaml` | Applies all manifests in order; pins the image tag |
| `00-namespace.yaml` | `crawler` namespace |
| `01-secrets.yaml` | MongoDB credentials + `MONGO_URI` |
| `02-storm-config.yaml` | `storm.yaml` for all daemons |
| `03-crawler-config.yaml` | K8s copy of `crawler-conf.yaml` + `seeds.txt` |
| `10-zookeeper.yaml` | Zookeeper StatefulSet + headless Service |
| `11-mongodb.yaml` | MongoDB StatefulSet + headless Service |
| `12-urlfrontier.yaml` | URLFrontier StatefulSet + headless Service |
| `20-nimbus.yaml` | Nimbus StatefulSet + headless Service |
| `21-supervisor.yaml` | Supervisor Deployment (workers + Chromium) |
| `22-storm-ui.yaml` | Storm UI Deployment + Service |
| `30-seed-job.yaml` | Seed-injection Job |
| `31-submit-topology-job.yaml` | Topology-submission Job |

---

## 7. Troubleshooting

| Symptom | Likely cause / fix |
|---------|--------------------|
| `submit-topology` Job CrashLoopBackOff | Nimbus not ready, or `storm.version` in the image ≠ cluster. Check `kubectl -n crawler logs job/submit-topology`. |
| Workers OOMKilled | Supervisor memory limit too low for Chromium; raise it in `21-supervisor.yaml`. |
| No docs in Mongo | Check worker logs (`kubectl -n crawler logs deploy/supervisor`); verify `MONGO_URI` and that the seed Job succeeded. |
| Topology submits but nothing crawls | Frontier empty — did `seed-urls` run? `... Client --host urlfrontier ListURLs`. |
| `ImagePullBackOff` on supervisor/jobs | Custom image not loaded into the cluster (step 3 of §2). |
| Playwright `TargetClosedError` | Don't point at a remote browser pool; the embedded Chromium must be baked in (it is, via the Dockerfile). |
| Storm version mismatch errors | Align `FROM storm:X` (Dockerfile), the `storm:X` tags (nimbus/ui), and `<storm.version>` in `pom.xml`. |
```
