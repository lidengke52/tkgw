# Go Dynamic Proxy Gateway 使用说明

本文档说明 Go 版网关的编译、启动、配置、账号格式和常见操作。

## 编译

进入 Go 版目录：

```bash
cd /Users/denker/Documents/tkgw-dt/ipproxy-dynamic-gateway/go-gateway
```

构建主网关：

```bash
go build -o gateway-go ./cmd/gateway
```

构建融合池城市测试工具：

```bash
go build -o geo-tester ./cmd/geo-tester
```

## 配置文件

复制示例配置：

```bash
cp ./config/dynamicProxy.example.yaml ./config/dynamicProxy.yaml
```

填写必填项：

```yaml
gatewayHostname: "your-gateway-hostname"
endpoint: "https://api.gzyuyun.com"
token: "your-token"
```

真实 token 不要提交到 Git。项目已忽略：

```text
config/dynamicProxy.yaml
```

## 启动

开发方式：

```bash
go run ./cmd/gateway --config ./config/dynamicProxy.yaml
```

生产方式：

```bash
./gateway-go --config ./config/dynamicProxy.yaml
```

配置缺少 `gatewayHostname`、`endpoint`、`token` 时会启动失败。

## 监听端口

固定代理端口：

```yaml
listenHost: "0.0.0.0"
listenSocks5Port: 29999
listenHttpPort: 28888
```

白名单端口范围：

```yaml
whiteListPortRangeStart: 20000
whiteListPortRangeEnd: 22000
```

白名单端口会逐个监听，端口范围越大，进程持有的 listener 越多。

## SOCKS5 使用

一次一换：

```bash
curl --socks5-hostname "username:password@gateway-host:29999" https://ipinfo.io/ip
```

用户名示例：

```text
sm929acehy4kf6z-res-us
```

粘性 session：

```text
sm929acehy4kf6z-res-us-sid-123456-keeptime-10
```

表示：

- 用户：`sm929acehy4kf6z`
- 国家：`us`
- sessionId：`123456`
- 缓存时间：10 分钟

## HTTP 代理使用

HTTP CONNECT：

```bash
curl -x "http://username:password@gateway-host:28888" https://ipinfo.io/ip
```

普通 HTTP：

```bash
curl -x "http://username:password@gateway-host:28888" http://example.com
```

HTTP 代理使用 Basic `Proxy-Authorization`。

## 地区格式

支持三种粒度：

```text
{user}-res-{country}
{user}-res-{country}-{state}
{user}-res-{country}-{state}-{city}
```

粘性 session 在地区后追加：

```text
-sid-{sessionId}-keeptime-{minutes}
```

`country=any` 表示随机地区。

## Session 模式

配置：

```yaml
sessionAffinityMode: "deterministic"
```

可选值：

| 值 | 说明 | 适用场景 |
|----|------|----------|
| `cache` | 首次随机选择，之后命中本机缓存 | 单机或 LB 有粘性 |
| `deterministic` | rendezvous hashing 确定性分配 | 集群 DNS/LB，无 Redis |

集群部署建议使用：

```yaml
sessionAffinityMode: "deterministic"
supplierRecoverAffectsExistingSession: false
```

这样供应商恢复后不会主动重排已有 session，减少出口 IP 来回变化。

## 供应商健康检查

配置：

```yaml
supplierHealthEnabled: true
supplierHealthMinSamples: 20
supplierHealthFailureRate: 0.5
supplierHealthConsecutiveFailures: 5
supplierHealthCooldownSec: 60
supplierHealthWindowSec: 60
```

说明：

- 真实转发失败会记录失败。
- 真实转发成功会记录成功。
- 失败率高或连续失败会熔断该供应商入口。
- 冷却结束后进入恢复观察。

## 流量上报

配置：

```yaml
trafficReportInterval: 30
trafficReportSpoolFile: "traffic-report-spool.jsonl"
```

网关每 30 秒增量上报一次。上报失败时写入本地 spool 文件，下一轮或重启后优先补发。

正常收到 SIGTERM/SIGINT 时，网关会立即 flush 当前内存统计。

## 本地模拟配置

开发调试可设置：

```yaml
localConfigFile: true
```

此时不走远程 API，使用代码内置 demo 配置。正式环境应设置为 `false`。

## 融合池城市测试工具

启动：

```bash
go run ./cmd/geo-tester
```

访问：

```text
http://127.0.0.1:8099
```

用途：

- 批量测试 SOCKS5 出口 IP
- 统计城市命中率
- 统计唯一 IP 数
- 导出 JSON/CSV

注意：免费定位接口有频率限制，不建议高并发压测定位 API。

## 常见问题

### 启动时报 gatewayHostname is required

检查配置文件是否包含：

```yaml
gatewayHostname: "..."
```

### 启动时报 endpoint is required 或 token is required

检查 API 配置：

```yaml
endpoint: "https://api.gzyuyun.com"
token: "..."
```

### 客户 sticky session 在集群下不稳定

确认配置：

```yaml
sessionAffinityMode: "deterministic"
```

如果使用 `cache` 模式，DNS/LB 把同一个 session 分到不同机器时，本机缓存不共享，出口可能变化。

### 供应商恢复后出口 IP 变化

确认配置：

```yaml
supplierRecoverAffectsExistingSession: false
```

默认不主动重排已有 session。

### 流量上报失败

检查日志中的：

```text
traffic report failed
traffic spool failed
traffic spool resend failed
```

如果启用了 `trafficReportSpoolFile`，失败批次会写入本地文件，后续补发。
