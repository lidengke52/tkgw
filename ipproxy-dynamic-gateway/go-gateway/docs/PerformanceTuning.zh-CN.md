# Go Dynamic Proxy Gateway 性能调优指南

本文档面向生产部署和压测调优。Go 版不是 Netty 模型，因此不要把 Java 版线程参数和水位参数直接等价理解。

## 关键指标

建议压测和线上观察这些指标：

- 连接成功率
- 上游建连耗时 P95/P99
- 客户端请求成功率
- CPU 使用率
- RSS 内存
- goroutine 数
- GC 次数和停顿
- 活跃连接数
- 供应商熔断次数
- traffic spool 文件是否持续增长

## 系统参数

### 文件描述符

代理网关是连接密集型服务。单个客户端连接通常会对应一个上游连接，所以文件描述符至少按两倍连接数估算。

建议：

```bash
ulimit -n 1048576
```

systemd 中配置：

```ini
LimitNOFILE=1048576
```

### 本地端口

高并发短连接时要关注本机临时端口和 TIME_WAIT。

Linux 可检查：

```bash
sysctl net.ipv4.ip_local_port_range
sysctl net.ipv4.tcp_tw_reuse
```

是否调整需要结合内核版本、部署方式和安全策略。

## 监听配置

```yaml
listenHost: "0.0.0.0"
listenSocks5Port: 29999
listenHttpPort: 28888
whiteListPortRangeStart: 20000
whiteListPortRangeEnd: 22000
```

白名单端口范围会逐个监听。如果不需要很多白名单端口，建议缩小范围，减少 listener 数量和端口占用。

`backLog` Go 版目前兼容读取，但不会按 Java/Netty 版方式显式设置。

## 超时配置

```yaml
readTimeout: 30000
writeTimeout: 30000
forwardConnTimeout: 15000
forwardReadTimeout: 30000
forwardWriteTimeout: 30000
readIdleTimeout: 0
writeIdleTimeout: 0
```

建议：

- 上游供应商稳定时，可以适当降低 `forwardConnTimeout`，让坏供应商更快失败并触发健康检查。
- 下载或长连接场景较多时，不要把 `forwardReadTimeout` 设置过低。
- HTTP 服务的 idle timeout 会取 `readIdleTimeout` 和 `writeIdleTimeout` 中较大的值。

## 并发限制

```yaml
userMaxConcurrentConnections: 0
```

`0` 表示关闭限制。

如果出现单个用户打满连接、影响其他用户，可设置为：

```yaml
userMaxConcurrentConnections: 500
```

该限制按认证用户 `authUser` 维度生效。

## Session 缓存

```yaml
maxSessionSize: 50000
sessionExpireCheckInterval: 30
```

说明：

- 只缓存带 `sid + keeptime` 的粘性 session。
- TTL 由客户连接串中的 `keeptime` 决定。
- 缓存达到上限后会删除一个已有 entry，防止无限增长。

调优建议：

- 高粘性 session 用户多时可增大 `maxSessionSize`。
- 内存敏感环境可减小该值。
- 如果客户恶意或误用大量唯一 sessionId，缓存不会无限涨，但会频繁淘汰，sticky 效果下降。

## 集群 session 模式

集群建议：

```yaml
sessionAffinityMode: "deterministic"
supplierRecoverAffectsExistingSession: false
```

原因：

- 不依赖 Redis，避免每次请求多一次网络 IO。
- DNS/LB 把请求分到不同机器时，仍能确定性选出同一供应商和入口。
- 供应商恢复后不主动重排旧 session，减少出口 IP 波动。

单机或 LB 已经做粘性时可使用：

```yaml
sessionAffinityMode: "cache"
```

## 供应商健康检查

```yaml
supplierHealthEnabled: true
supplierHealthMinSamples: 20
supplierHealthFailureRate: 0.5
supplierHealthConsecutiveFailures: 5
supplierHealthCooldownSec: 60
supplierHealthWindowSec: 60
```

调优建议：

- 想更快切走坏供应商：降低 `supplierHealthConsecutiveFailures` 或 `supplierHealthMinSamples`。
- 想减少误熔断：提高 `supplierHealthMinSamples` 或 `supplierHealthFailureRate`。
- 上游偶发波动多时，适当增大 `supplierHealthWindowSec`。
- 供应商恢复慢时，适当增大 `supplierHealthCooldownSec`。

注意：健康检查是本机被动统计。集群中每台机器会独立学习供应商健康状态。

## 流量统计

```yaml
trafficReportInterval: 30
trafficReportSpoolFile: "traffic-report-spool.jsonl"
```

调优建议：

- 周期越短，管理端压力越高，但重启时内存窗口越小。
- 周期越长，管理端压力越低，但正常退出前未 flush 的内存窗口更大。
- 建议保持 30 秒，除非管理端或计费策略另有要求。

上报失败会写 spool 文件。若发现该文件持续增长，说明管理端 API 长时间不可用或 token/endpoint 配置异常。

## HTTP 参数

```yaml
httpRequestHeaderMaxSize: 32768
httpObjectAggregatorSize: 5242880
```

Go 版普通 HTTP 路径是流式转发，不做 Netty 式整包聚合。`httpObjectAggregatorSize` 目前主要用于兼容配置语义，不是普通 HTTP 响应内存上限。

`httpRequestHeaderMaxSize` 会用于 Go HTTP server 的 `MaxHeaderBytes`。

## DNS 策略

```yaml
dnsRemote: true
disableSupplierDnsCache: false
```

`dnsRemote: true` 表示目标域名交给上游 SOCKS5 代理解析，等价于 `curl --socks5-hostname` 的行为。

供应商 gateway 域名解析使用 Go/系统 resolver。`disableSupplierDnsCache` 当前兼容读取，不做 JVM DNS 缓存语义。

## Go 运行时建议

通常不需要设置 `GOMAXPROCS`，Go 会根据 CPU 自动设置。

如果容器 CPU quota 和 Go 版本行为不一致，可显式设置：

```bash
export GOMAXPROCS=8
```

建议在压测时开启 pprof 或至少采集：

- goroutine profile
- heap profile
- CPU profile

当前代码还未内置 pprof HTTP 端口，生产接入前可以按需补一个仅内网可访问的 debug 端口。

## 压测建议

分阶段压测：

1. SOCKS5 一次一换。
2. SOCKS5 粘性 session。
3. HTTP CONNECT。
4. 普通 HTTP。
5. 白名单 SOCKS5。
6. 白名单 HTTP。
7. 供应商故障注入。
8. 管理端上报失败注入。

对比 Java 版：

- 成功率
- P95/P99 建连耗时
- CPU
- RSS
- GC
- 上游失败恢复时间
- sticky session 出口稳定性

## Java/Netty 参数兼容说明

这些参数 Go 版会读取，但不按 Netty 语义生效：

```yaml
listenThread
workerThreads
backLog
writeBufferLowWaterMark
writeBufferHighWaterMark
```

保留它们是为了让旧配置文件尽量可直接启动。真正影响 Go 版性能的主要是系统 fd、超时、并发限制、session 模式、健康检查和上报周期。
