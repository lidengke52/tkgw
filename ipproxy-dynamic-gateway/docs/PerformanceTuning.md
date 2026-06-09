# 性能调优指南

本文档用于指导运维人员根据实际部署环境对 Dynamic Proxy Gateway 进行性能调优。

## 目录

- [性能调优参数一览](#性能调优参数一览)
- [网络相关参数](#网络相关参数)
- [线程模型参数](#线程模型参数)
- [缓存与会话参数](#缓存与会话参数)
- [超时配置参数](#超时配置参数)
- [HTTP 协议参数](#http-协议参数)
- [调优建议](#调优建议)

---

## 性能调优参数一览

| 参数名 | 默认值 | 单位 | 配置位置 | 说明 |
|--------|--------|------|----------|------|
| `listenThread` | 1 | 线程数 | YAML/命令行 | Netty Boss 线程数 |
| `workerThreads` | 0 | 线程数 | YAML/命令行 | 共享的 Netty Worker / Forwarder 线程数，0表示自动 |
| `backLog` | 128 | 连接数 | YAML/命令行 | TCP 连接队列长度 |
| `connTimeout` | 15000 | 毫秒 | YAML/命令行 | **网关监听端口** TCP 建连超时（客户端→网关） |
| `forwardConnTimeout` | 1500 | 毫秒 | YAML/命令行 | **转发连接** TCP 建连超时（网关→上游代理） |
| `readTimeout` | 30000 | 毫秒 | YAML/命令行 | **网关监听端口** 读超时（客户端→网关） |
| `writeTimeout` | 30000 | 毫秒 | YAML/命令行 | **网关监听端口** 写超时（网关→客户端） |
| `readIdleTimeout` | 0 | 毫秒 | YAML/命令行 | **网关监听端口** 读空闲超时（客户端→网关），0表示不检测 |
| `writeIdleTimeout` | 0 | 毫秒 | YAML/命令行 | **网关监听端口** 写空闲超时（网关→客户端），0表示不检测 |
| `maxSessionSize` | 50000 | 会话数 | YAML/命令行 | 粘性会话缓存最大数量 |
| `sessionExpireCheckInterval` | 30 | 秒 | YAML/命令行 | 会话过期检查间隔 |
| `resourceUsageLogIntervalSec` | 60 | 秒 | YAML/命令行 | 资源使用观测日志间隔，0 表示关闭 |
| `httpObjectAggregatorSize` | 5242880 | 字节 | YAML/命令行 | HTTP 消息聚合器最大尺寸 |
| `writeBufferLowWaterMark` | 32768 | 字节 | YAML | 写缓冲低水位，低于该值后恢复读取 |
| `writeBufferHighWaterMark` | 65536 | 字节 | YAML | 写缓冲高水位，高于该值后触发背压暂停读取 |

---

## 网络相关参数

### backLog

**位置**: YAML 配置 `backLog` / 命令行 `--backLog`

**说明**: TCP ServerSocketChannel 的 `SO_BACKLOG` 参数，控制已完成三次握手但未完成 accept 的连接队列长度。

**默认值**: 128

**调优建议**:
- 低并发场景: 128
- 高并发场景: 512~1024
- 需要根据上游代理的响应速度和并发连接数进行调整

### writeBufferLowWaterMark / writeBufferHighWaterMark

**位置**: YAML 配置 `writeBufferLowWaterMark` / `writeBufferHighWaterMark`

**说明**:
- 作用于 server child channel 和 upstream client channel 的 `WRITE_BUFFER_WATER_MARK`
- 当待发送数据超过高水位时，relay handler 会暂停对端 `AUTO_READ`
- 当待发送数据回落到低水位以下时，relay handler 会恢复对端读取

**默认值**:
- `writeBufferLowWaterMark`: 32768 字节
- `writeBufferHighWaterMark`: 65536 字节

**调优建议**:
- 慢消费者较多或内存敏感场景优先保持默认值或适当减小
- 吞吐更高且链路稳定的场景可逐步增大，但应配合压测与 RSS 监控
- 必须满足 `0 <= low < high`，否则启动会失败

---

## 线程模型参数

### listenThread (Boss 线程)

**位置**: YAML 配置 `listenThread`

**说明**: Netty Boss 线程组线程数，用于接受客户端连接。

**默认值**: 1

**调优建议**:
- 大多数场景下 1 个 Boss 线程足够
- 如果使用多端口绑定（如 Whitelist 路由服务），可能需要增加

### workerThreads (Worker 线程)

**位置**: YAML 配置 `workerThreads`

**说明**: 共享的 Netty Worker / Forwarder 线程组线程数，用于处理入站 IO 事件、协议处理和出站转发连接。

**默认值**: 0（表示由 Netty 自动设置，约为 2 * CPU cores）

**调优建议**:
- 默认值适合大多数场景
- 三类服务共享同一组 worker / forwarder 线程，能减少线程栈和 Netty 线程本地缓存带来的基线内存占用
- CPU 密集型场景可考虑增大
- IO 密集型场景可考虑增大到 2-4 倍 CPU cores
- 过大的线程数会增加上下文切换开销

---

## 缓存与会话参数

### maxSessionSize

**位置**: YAML 配置 `maxSessionSize`

**说明**: 粘性会话缓存的最大数量，使用 Caffeine Cache 实现。

**默认值**: 50000

**调优建议**:
- 根据实际用户数和会话保持时间调整
- 内存受限时可适当减小
- 高流量场景下可适当增大

### sessionExpireCheckInterval

**位置**: YAML 配置 `sessionExpireCheckInterval`

**说明**: 会话过期检查的间隔时间。

**默认值**: 30 秒

**调优建议**:
- 值越小检查越频繁，CPU 消耗略高
- 值过大会导致过期会话占用缓存时间延长
- 建议与 `sessionKeepTime` 配合调整

---

## 超时配置参数

> **重要说明**: 超时参数分为两类：
> - **网关监听端口超时**: 控制客户端 ↔ 网关之间的超时
> - **转发连接超时**: 控制网关 ↔ 上游代理之间的超时
>
> 这两组超时相互独立，分别处理不同方向的流量。

### 网关监听端口超时

#### connTimeout

**位置**: YAML 配置 `connTimeout`

**说明**: 客户端连接到网关的 TCP 建连超时时间。

**默认值**: 15000ms

#### readTimeout / writeTimeout

**位置**: YAML 配置 `readTimeout` / `writeTimeout`

**说明**:
- `readTimeout`: **客户端 → 网关** 通道的读取超时时间
- `writeTimeout`: **网关 → 客户端** 通道的写入超时时间

**默认值**: 30000ms

**调优建议**:
- 根据业务请求的预期响应时间调整
- 避免设置过大导致问题发现延迟

#### readIdleTimeout / writeIdleTimeout

**位置**: YAML 配置 `readIdleTimeout` / `writeIdleTimeout`

**说明**:
- `readIdleTimeout`: **客户端 → 网关** 读空闲超时，0 表示不检测
- `writeIdleTimeout`: **网关 → 客户端** 写空闲超时，0 表示不检测

**默认值**: 0（不检测）

**调优建议**:
- 设置后可以及时发现不活跃连接
- 建议设置为正常请求周期的 2-3 倍
- 与上游代理的 Keep-Alive 时间配合

### 转发连接超时

#### forwardConnTimeout

**位置**: YAML 配置 `forwardConnTimeout`

**说明**: 网关连接到上游代理的 TCP 建连超时时间。

**默认值**: 1500ms

**调优建议**:
- 如果上游代理响应慢，适当增大此值
- 高并发场景下，避免设置过大导致资源占用

---

## HTTP 协议参数

### httpObjectAggregatorSize

**位置**: YAML 配置 `httpObjectAggregatorSize`

**说明**: HTTP 消息聚合器的最大尺寸，用于聚合 HTTP 请求/响应的多个 chunk。

**默认值**: 5242880 字节（5MB）

**调优建议**:
- 根据 HTTP 请求体大小调整
- 上传大文件场景下需要增大
- 减小此值可以限制内存占用
- 建议值: 1MB - 10MB

---

## 调优建议

### 超时配置对照表

| 参数 | 方向 | 典型值 | 场景 |
|------|------|--------|------|
| `connTimeout` | 客户端 → 网关 | 10000-20000ms | 建连阶段 |
| `readTimeout` | 客户端 → 网关 | 20000-60000ms | 读取请求/响应 |
| `writeTimeout` | 网关 → 客户端 | 20000-60000ms | 发送响应 |
| `readIdleTimeout` | 客户端 → 网关 | 0（不检测）或 300000ms | 长连接保活 |
| `writeIdleTimeout` | 网关 → 客户端 | 0（不检测）或 300000ms | 长连接保活 |
| `forwardConnTimeout` | 网关 → 上游 | 1000-3000ms | 建连阶段 |

### 高并发场景配置示例

```yaml
# 高并发场景配置
listenThread: 2
workerThreads: 16
backLog: 512
connTimeout: 10000
forwardConnTimeout: 3000
readTimeout: 20000
writeTimeout: 20000
readIdleTimeout: 0
writeIdleTimeout: 0
maxSessionSize: 100000
sessionExpireCheckInterval: 15
httpObjectAggregatorSize: 8388608
writeBufferLowWaterMark: 32768
writeBufferHighWaterMark: 65536
```

### 内存受限场景配置示例

```yaml
# 内存受限场景配置
workerThreads: 4
maxSessionSize: 10000
sessionExpireCheckInterval: 60
httpObjectAggregatorSize: 1048576
writeBufferLowWaterMark: 16384
writeBufferHighWaterMark: 32768
```

### 低延迟场景配置示例

```yaml
# 低延迟场景配置
workerThreads: 8
forwardConnTimeout: 1000
readTimeout: 15000
writeTimeout: 15000
httpObjectAggregatorSize: 1048576
writeBufferLowWaterMark: 32768
writeBufferHighWaterMark: 131072
```

### 调优检查清单

1. **监控指标**: 在调整参数前，确保有监控以下指标:
   - CPU 使用率
   - 内存使用率
   - 连接数
   - 请求延迟
   - 错误率

2. **逐步调整**: 每次只调整 1-2 个参数，观察效果

3. **记录基准**: 调整前记录当前性能指标，作为对比基准

4. **压力测试**: 生产环境调整前，建议在测试环境进行压力测试验证

## 资源观测日志使用建议

- 推荐保持 `resourceUsageLogIntervalSec: 60`，用最低成本持续观察资源趋势。
- 重点关注以下字段：
  - `heapUsedBytes` / `heapCommittedBytes` / `heapMaxBytes`：判断堆是否持续逼近上限。
  - `directBufferUsedBytes` 与 `nettyDirectUsedBytes`：判断堆外 direct memory 是否持续增长。
  - `liveThreads` / `peakThreads`：判断线程数是否异常抬升。
  - `activeChannels`：观察接入侧活动连接数与压测负载是否匹配。
- 若是极度安静的生产环境，且已有外部观测手段，可将 `resourceUsageLogIntervalSec` 调整为更大值，或设置为 `0` 关闭。
