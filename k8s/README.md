# StormCrawler trên Kubernetes — cấu hình High Availability

## Tổng quan mức HA của từng thành phần

| Thành phần | Replica | Chịu được | Cơ chế |
|---|---|---|---|
| Zookeeper | 3 | mất 1 node | Quorum 2/3, anti-affinity `required` |
| Nimbus | 2 | mất 1 node | Leader election qua ZK + replication jar (`topology.min.replication.count: 2`) |
| Supervisor | 3 | mất 1 node | Nimbus tái phân bổ task sang supervisor còn sống |
| Storm UI | 2 | mất 1 node | Stateless, Service load-balance |

Tất cả đều có **PodDisruptionBudget `maxUnavailable: 1`** để k8s không evict quá 1 pod cùng lúc khi drain node.

## Thứ tự apply

```bash
kubectl apply -f 00-namespace.yaml
kubectl apply -f 02-storm-conf-configmap.yaml
kubectl apply -f 01-zookeeper.yaml

# CHỜ zookeeper đủ quorum (1 leader + 2 follower) rồi mới sang bước sau
kubectl rollout status statefulset/zookeeper -n stormcrawler --timeout=300s

kubectl apply -f 03-nimbus.yaml
kubectl apply -f 04-supervisor.yaml
kubectl apply -f 05-ui.yaml
kubectl get pods -n stormcrawler -w
```

## Build & push image topology

```bash
cd /path/to/inaccurate-article-crawler
mvn clean package

docker build -t inaccurate-article-crawler:1.0 .
docker login hub.vtcc.vn:8999
docker tag inaccurate-article-crawler:1.0 hub.vtcc.vn:8999/vtcc/group-name/inaccurate-article-crawler:1.0
docker push hub.vtcc.vn:8999/vtcc/group-name/inaccurate-article-crawler:1.0
```

Nếu registry cần credential, tạo secret và bỏ comment `imagePullSecrets` trong `06-submit-job.yaml`:

```bash
kubectl create secret docker-registry regcred \
  --docker-server=hub.vtcc.vn:8999 \
  --docker-username=<user> \
  --docker-password=<pass> \
  -n stormcrawler
```

Rồi submit:

```bash
kubectl apply -f 07-submit-job.yaml
kubectl logs -n stormcrawler job/submit-stormcrawler-topology -f
```

## Kiểm tra HA đã hoạt động đúng chưa

**1. Zookeeper — phải có đúng 1 leader, 2 follower**
```bash
for i in 0 1 2; do
  echo -n "zookeeper-$i: "
  kubectl exec -n stormcrawler zookeeper-$i -- sh -c "echo stat | nc localhost 2181" | grep Mode
done
```

**2. Nimbus — phải có 1 Leader, 1 Not a Leader**
```bash
kubectl exec -n stormcrawler nimbus-0 -- storm list
# hoặc xem mục "Nimbus Summary" trên Storm UI
```

**3. Pod phải nằm trên các node KHÁC NHAU**
```bash
kubectl get pods -n stormcrawler -o wide | awk '{print $1, $7}'
```
Nếu thấy 2 pod cùng role (VD nimbus-0 và nimbus-1) chung 1 node → anti-affinity chưa ăn, kiểm tra lại cluster có đủ node không.

**4. Test failover thật — xoá leader và xem cluster có tự phục hồi**
```bash
# Xoá 1 zookeeper, cluster phải vẫn hoạt động
kubectl delete pod zookeeper-0 -n stormcrawler
kubectl get pods -n stormcrawler -w

# Xoá nimbus leader, nimbus còn lại phải lên thay
kubectl delete pod nimbus-0 -n stormcrawler
```
Trong lúc đó topology **vẫn phải tiếp tục crawl** — kiểm tra qua Storm UI xem số tuple emitted có tăng không.

## Yêu cầu tối thiểu của cluster

⚠️ Cấu hình này cần **ít nhất 3 worker node** trong K8s cluster. Lý do: Zookeeper và Nimbus dùng anti-affinity `requiredDuringScheduling` — nếu cluster chỉ có 1-2 node, pod thứ 3 sẽ kẹt **Pending** vĩnh viễn.

Nếu cluster chỉ có 1-2 node (môi trường dev), sửa `requiredDuringSchedulingIgnoredDuringExecution` → `preferredDuringSchedulingIgnoredDuringExecution` trong `01-zookeeper.yaml` và `03-nimbus.yaml`. Nhưng lúc đó **không còn là HA thật** — chỉ chạy được thôi.

Tổng resource tối thiểu (theo request đang set):
- Zookeeper: 3 × (250m CPU, 512Mi RAM)
- Nimbus: 2 × (500m CPU, 1Gi RAM)
- Supervisor: 3 × (1 CPU, 9Gi RAM) ← nặng nhất
- UI: 2 × (200m CPU, 512Mi RAM)

→ Khoảng **5 CPU / 32Gi RAM** request. Giảm `supervisor.slots.ports` xuống 2 slot và `worker.childopts` xuống `-Xmx1024m` nếu cluster nhỏ hơn.

## Những gì HA này KHÔNG bảo vệ được

- **Mất 2/3 Zookeeper cùng lúc** → mất quorum → cả cluster Storm dừng. Muốn chịu được 2 node chết phải dùng ensemble 5 node.
- **Mất cả 2 Nimbus** → topology đang chạy vẫn tiếp tục, nhưng worker chết sẽ không được hồi phục và không submit/kill topology được.
- **Storage backend** (Mongo/OpenSearch/URLFrontier) chưa có trong bộ manifest này — cần HA riêng cho chúng, nếu không đó vẫn là single point of failure của toàn hệ thống.
- **Mất dữ liệu tuple đang xử lý**: đã giảm thiểu bằng `topology.acker.executors: 2` (tuple fail sẽ được replay), nhưng cần đảm bảo Spout của bạn hỗ trợ replay đúng.
