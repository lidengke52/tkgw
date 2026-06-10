# Go Dynamic Proxy Gateway 架构说明

本文档说明 Go 版动态代理网关的模块结构、请求路径、动态配置、会话分配、健康检查和流量统计策略。

## 总体架构

```text
Client
  |
  | SOCKS5 / HTTP Proxy / WhiteList Port
  v
+-----------------------------+
| Go Dynamic Proxy Gateway    |
|                             |
|  cmd/gateway                |
|   |                         |
|   +-- internal/socks        |
|   +-- internal/proxy        |
|   +-- internal/dynamic      |
|   +-- internal/session      |
|   +-- internal/health       |
|   +-- internal/stats        |
|   +-- internal/filter       |
|   +-- internal/resource     |
|                             |
+--------------+--------------+
               |
               | SOCKS5 auth + CONNECT
               v
        Upstream Supplier Proxy
```

核心路径：

1. 客户端连接 SOCKS5、HTTP 代理端口或白名单端口。
2. 网关认证用户，或通过白名单 IP + 端口映射出用户。
3. 解析用户名中的目标地区、sessionId、keeptime。
4. 从动态配置中获取用户可用供应商。
5. 根据 session 模式、健康状态和地区映射选择上游供应商。
6. 使用供应商认证模板生成上游 SOCKS5 账号。
7. 连接上游 SOCKS5 代理并发送 CONNECT。
8. 双向转发流量并统计 upLink/downLink。

## 入口协议

### SOCKS5

实现位置：[internal/socks/server.go](/Users/denker/Documents/tkgw-dt/ipproxy-dynamic-gateway/go-gateway/internal/socks/server.go)

流程：

1. 客户端发起 SOCKS5 greeting。
2. 网关要求 username/password 认证。
3. 网关解析用户名并校验动态用户配置。
4. 客户端发送 CONNECT 请求。
5. 网关分配上游供应商并建立 SOCKS5 转发连接。
6. 返回 SOCKS5 成功响应后开始双向 relay。

### HTTP 代理

实现位置：[internal/proxy/http.go](/Users/denker/Documents/tkgw-dt/ipproxy-dynamic-gateway/go-gateway/internal/proxy/http.go)

支持：

- `Proxy-Authorization: Basic ...`
- HTTP `CONNECT`
- 普通 HTTP 请求转发

HTTP `CONNECT` 建连成功后进入透明双向 relay。普通 HTTP 请求使用 Go 标准库请求写入上游连接，响应体流式复制回客户端。

### 白名单端口

实现位置：[cmd/gateway/main.go](/Users/denker/Documents/tkgw-dt/ipproxy-dynamic-gateway/go-gateway/cmd/gateway/main.go)

白名单端口范围由配置控制：

```yaml
whiteListPortRangeStart: 20000
whiteListPortRangeEnd: 22000
```

每个端口会监听一个 TCP listener。连接进来后读取首字节：

- `0x05`：按 SOCKS5 处理
- 其他：按 HTTP 代理处理

白名单模式不需要客户提供密码，网关根据来源 IP、访问端口和管理端下发的白名单配置确定用户和地区。

## 动态配置

实现位置：[internal/dynamic/store.go](/Users/denker/Documents/tkgw-dt/ipproxy-dynamic-gateway/go-gateway/internal/dynamic/store.go)

网关启动后定期拉取：

- 用户配置：`/api/admin/dynamic/getAccountConfig`
- 供应商配置：`/api/admin/dynamic/getAllSuppliers`
- 白名单配置：`/api/admin/agent/getWhiteConfig?gatewayHostname=...`

拉取间隔：

```yaml
configUpdateInterval: 180
```

配置快照通过 `atomic.Value` 替换，读路径不需要持有全局锁。刷新失败时保留旧配置继续运行。

## 用户名格式

一次一换：

```text
{authUser}-res-{country}
{authUser}-res-{country}-{state}
{authUser}-res-{country}-{state}-{city}
```

粘性 session：

```text
{authUser}-res-{country}-sid-{sessionId}-keeptime-{minutes}
{authUser}-res-{country}-{state}-sid-{sessionId}-keeptime-{minutes}
{authUser}-res-{country}-{state}-{city}-sid-{sessionId}-keeptime-{minutes}
```

说明：

- `sessionId` 必须是数字，长度小于 20。
- `keeptime` 单位是分钟，必须大于 0。
- 没有 `sid + keeptime` 时不会写入 session 缓存。

## 上游供应商分配

默认候选来自用户配置里的 `availableSupplier`。每个供应商可能包含多个 `availableGateway`。

选择步骤：

1. 枚举用户可用供应商和入口。
2. 根据供应商健康状态分成 healthy/all 两组。
3. 优先在 healthy 候选中选择。
4. 如果全部 unhealthy，则允许回退到 all，避免完全不可用。
5. 根据 session 模式决定随机还是确定性选择。

一次一换请求始终随机选择。粘性 session 根据 `sessionAffinityMode` 决定：

```yaml
sessionAffinityMode: "cache"          # 本机缓存模式
sessionAffinityMode: "deterministic" # 集群确定性模式
```

## 集群确定性 session

`deterministic` 模式使用 rendezvous hashing。

Hash 输入：

```text
authUser + sessionId + country + state + city + supplierId + endpoint
```

同一套动态配置下，不同网关实例会对同一个 session 选出同一个供应商和入口。因此 DNS/LB 把请求分到不同机器时，也能保持 session 稳定，不需要 Redis。

在该模式下，供应商模板里的 `{session}` 也会生成确定性 token，避免跨节点选择同一供应商但上游 session token 不一致。

供应商故障时，故障入口会从 healthy 候选中排除，下次会落到 rendezvous 排名中的下一个健康候选。

## Session 缓存

实现位置：[internal/session/store.go](/Users/denker/Documents/tkgw-dt/ipproxy-dynamic-gateway/go-gateway/internal/session/store.go)

配置：

```yaml
maxSessionSize: 50000
sessionExpireCheckInterval: 30
```

缓存 key：

```text
authUser + "_" + sessionId
```

缓存 TTL 为客户连接串中的 `keeptime`。过期会在读取时惰性删除，也会被后台定时扫描删除。

缓存达到 `maxSessionSize` 后会删除一个已有 entry 再写入新 entry，防止无限增长。当前实现不是严格 LRU。

## 融合池地区映射

Go 版兼容原项目 `areaMapping.csv`。供应商 ID 为 `16` 或 `17` 时进入融合池地区映射逻辑。

规则：

- `country=any` 时不做地区映射。
- 国家、省、城市按供应商支持粒度映射。
- 城市不支持时回退省；省不支持时回退国家。
- 查不到可用映射时返回失败。

## 供应商健康检查

实现位置：[internal/health/health.go](/Users/denker/Documents/tkgw-dt/ipproxy-dynamic-gateway/go-gateway/internal/health/health.go)

统计维度：

```text
supplierId + endpoint + country
```

健康检查是被动模式，不主动探测。真实转发失败时记录 failure，转发成功时记录 success。

熔断条件：

- 连续失败达到 `supplierHealthConsecutiveFailures`
- 或窗口内样本数达到 `supplierHealthMinSamples` 且失败率达到 `supplierHealthFailureRate`

冷却时间结束后进入恢复观察，成功次数足够后恢复 healthy。

## 流量统计

实现位置：[internal/stats/stats.go](/Users/denker/Documents/tkgw-dt/ipproxy-dynamic-gateway/go-gateway/internal/stats/stats.go)

按用户 `uid` 聚合：

- `upLink`：客户端到目标方向的字节数
- `downLink`：目标到客户端方向的字节数

SOCKS5、HTTP CONNECT 和白名单隧道按实际双向 relay 字节数统计。普通 HTTP 请求体按实际读取字节数统计，响应体按实际写回字节数统计。

上报接口：

```text
POST /api/admin/dynamic/trafficReport
```

上报失败会写入本地 JSONL spool 文件，下次启动或下一轮上报时优先补发。

## 资源观测

实现位置：[internal/resource/reporter.go](/Users/denker/Documents/tkgw-dt/ipproxy-dynamic-gateway/go-gateway/internal/resource/reporter.go)

定期输出：

- goroutine 数
- HeapAlloc
- HeapSys
- StackSys
- GC 次数
- 活跃连接数

配置：

```yaml
resourceUsageLogIntervalSec: 60
```

设置为 `0` 可关闭。

## 与 Java/Netty 版的主要差异

- Go 版使用 goroutine + `io.CopyBuffer` 转发，不使用 Netty pipeline。
- 普通 HTTP 响应流式转发，不做 Netty 式整包聚合。
- `workerThreads`、`writeBufferLowWaterMark` 等 Netty 参数只兼容读取，不按 Netty 语义生效。
- Go 版新增确定性 session 分配和供应商健康检查。
- Go 版流量上报失败会写本地 spool，减少统计丢失。
