# Go 版 Dynamic Proxy Gateway

这是对 Java/Netty 版动态代理网关的 Go 重写实现。目标是后续只维护这套 Go 版；原 Java 目录可以在迁移期作为历史参考，等压测和线上验证完成后再归档或删除。

## 已实现能力

- SOCKS5 网关，支持用户名/密码认证。
- HTTP 代理网关，支持 `Proxy-Authorization: Basic ...` 认证。
- HTTP `CONNECT` 隧道代理。
- 普通 HTTP 请求转发，采用流式响应转发，避免原 Java 版整包聚合响应。
- 白名单端口路由，支持同一端口按首字节分流 HTTP/SOCKS5。
- 动态拉取用户、供应商、白名单配置。
- 上游 SOCKS5 代理认证与 CONNECT 建连。
- 粘性 session 缓存，且只缓存真正的粘性 session，避免“一次一换”连接被错误写入缓存。
- 单用户并发连接数限制。
- 基于原子计数器的请求数、流量统计与定时上报。
- 兼容原 Java 版白名单 bitset 端口格式：gzip + base64。
- 兼容原 Java 版 `Country-only-cn-private.mmdb` 客户端 IP Geo 过滤。
- 兼容融合池 `areaMapping.csv` 国家/省/城市映射。
- 资源使用观测日志：堆内存、goroutine、GC 次数、活跃连接数。
- 供应商被动健康检查：按供应商、入口、国家统计失败率，自动熔断并恢复。
- 集群友好的确定性 session 分配：支持 rendezvous hashing，无需 Redis。
- 默认本地解析目标域名；配置 `dnsRemote: true` 后由上游 SOCKS5 代理解析目标域名。

## 启动方式

开发运行：

```bash
go run ./cmd/gateway --config ../src/main/resources/dynamicProxy-template.yaml
```

生产构建：

```bash
go build -o gateway-go ./cmd/gateway
./gateway-go --config ./dynamicProxy.yaml
```

## 文档

- [架构说明](./docs/Architecture.zh-CN.md)
- [使用说明](./docs/Usage.zh-CN.md)
- [性能调优指南](./docs/PerformanceTuning.zh-CN.md)
- [运维手册](./docs/Operations.zh-CN.md)

## 融合池城市定位测试页

如果需要批量验证 SOCKS5 出口 IP 的城市定位，可以启动内置测试页：

```bash
go run ./cmd/geo-tester
```

然后打开 `http://127.0.0.1:8099`，填入类似 `host:port@username:password` 的 SOCKS5 连接串，默认会测试 1000 次并以 Delhi 作为期望城市。页面也支持维护多个测试池，每行按 `池名称=host:port@username:password` 填写，保存后可以在同一个页面下拉选择不同池测试。测试器会通过代理请求 `ip-api.com` 或 `ipinfo.io`，统计成功数、失败数、城市命中数、唯一 IP 数、城市分布和错误分布，并支持导出 JSON/CSV。

注意：`ip-api.com` 免费接口有频率限制，页面默认把请求间隔设为 1400ms，约等于 45 次/分钟；如果改成更高并发或更低间隔，可能会被定位接口限流。`ipinfo.io` 可填写 token 以提升额度。

## 配置兼容

Go 版会读取原 Java 版 `dynamicProxy.yaml` 中的常用简单键值配置，例如：

- `gatewayHostname`
- `listenHost`
- `listenSocks5Port`
- `listenHttpPort`
- `endpoint`
- `token`
- `configUpdateInterval`
- `trafficReportInterval`
- `maxSessionSize`
- `whiteListPortRangeStart`
- `whiteListPortRangeEnd`
- `userMaxConcurrentConnections`
- `dnsRemote`
- `localConfigFile`

Go 标准库没有 Netty 的 pipeline、write water mark、boss/worker event loop 这些一一对应概念，所以 `backLog`、`writeBufferLowWaterMark`、`writeBufferHighWaterMark`、`workerThreads` 等参数不会按 Netty 语义生效。

## 单 Go 维护说明

当前 Go 版已经覆盖原 Java 网关的核心运行能力：

- 固定端口 SOCKS5 代理。
- 固定端口 HTTP 代理。
- 白名单端口动态路由。
- 动态配置拉取。
- 上游供应商分配。
- 粘性 session。
- 流量/请求统计。
- IP Geo 过滤。
- 融合池区域映射。

不需要长期维护 Java+Go 两套业务逻辑。迁移期建议先保留 Java 目录作为对照，确认 Go 版压测和线上灰度没问题后，可以把 Java 源码归档或移除。

仍需要注意的差异：

- Go 版不会保留 Netty 专属参数语义，例如 write water mark、boss/worker event loop。
- 日志文本不会逐字等同 Java 版，但关键事件和错误路径都有记录。
- 上线前仍需要按生产流量做压测，确认超时、连接数和系统 ulimit 设置。

## 供应商健康检查

Go 版会基于真实请求结果做被动健康检查，不主动探测供应商。

统计维度：

```text
supplierId + endpoint + country
```

默认规则：

- 连续失败达到 `supplierHealthConsecutiveFailures`，熔断该供应商入口。
- 最近窗口样本数达到 `supplierHealthMinSamples`，且失败率达到 `supplierHealthFailureRate`，熔断该供应商入口。
- 熔断持续 `supplierHealthCooldownSec` 秒，之后进入半开恢复。
- 新 session 和一次一换请求会优先选择 healthy 供应商。
- 已存在的粘性 session 不主动切换；如果连接失败，会删除该 session 缓存，让下一次请求重新分配。

## 集群 session 分配

`sessionAffinityMode` 支持两种模式：

- `cache`：首次随机选择供应商和入口，然后写入本机 session 缓存。适合单机，或负载均衡已经能保证同一个客户/session 命中同一台网关。
- `deterministic`：使用 rendezvous hashing，根据 `authUser + sessionId + country/state/city` 在所有健康候选中确定性选择供应商和入口。不同网关实例只要动态配置一致，就会得到相同结果，不需要 Redis。

在 `deterministic` 模式下，Go 版还会把上游认证模板里的 `{session}` 生成成确定性 token。这样跨节点不只是选到同一个供应商，也会让上游代理看到同一个 session 标识。

供应商故障时，该供应商入口会从健康候选中排除；相关 session 下次会落到 rendezvous 排名里的下一个健康候选。供应商恢复后不会主动重排已存在 session，避免出口 IP 来回漂移。

## 流量统计可靠性

流量按用户 `uid` 聚合，上报字段为 `upLink` 和 `downLink`。SOCKS5、HTTP CONNECT、白名单隧道都按实际双向转发字节数统计；普通 HTTP 请求体也按实际读取字节数统计，支持 chunked 或未知长度 body。

`trafficReportInterval` 控制定时上报周期。上报失败时，Go 版会把本批次写入 `trafficReportSpoolFile` 指定的本地 JSONL 队列，下一轮上报或进程重启后优先补发。

收到 SIGTERM/SIGINT 正常退出时，进程会立即 flush 当前内存统计，尽量避免丢失最后一个上报周期的数据。异常崩溃、机器断电时，已经写入 spool 文件的失败批次可以恢复；仍停留在内存里且尚未 flush 的极短窗口数据无法完全保证，除非把每次字节计数都做同步落盘，这会明显拖慢代理热路径。

## 推荐验证顺序

1. 先运行 `go fmt ./...` 和 `go build ./cmd/gateway`。
2. 使用 `localConfigFile: true` 做本地启动验证。
3. 用真实 API 配置拉取用户、供应商、白名单。
4. 分别压测 SOCKS5、HTTP CONNECT、白名单 SOCKS5。
5. 和 Java 版对比：连接成功率、P95/P99 建连耗时、吞吐、RSS、CPU、GC。

## 设计取舍

Go 版把热路径写得更直接：

- 每条连接完成认证后直接分配上游。
- 直接向供应商 SOCKS5 发起认证和 CONNECT。
- 成功后用 pooled buffer 做双向 `io.CopyBuffer`。
- 统计使用原子计数器，定时 `Swap(0)` 上报。

这比 Java/Netty 版少了大量 pipeline handler 动态增删、匿名 handler、非原子 map 计数和整包 HTTP 聚合，更适合后续压测优化。
