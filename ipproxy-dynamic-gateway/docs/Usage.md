# Usage Guide

## 启动模式

本项目支持三种启动方式：

1. `--init` 初始化配置文件（首次使用推荐）
2. `--config <path>` 从 YAML 配置文件启动（正式推荐）
3. 旧命令行参数模式（兼容保留）

## 首次初始化

在目标运行目录执行：

```bash
java -jar gateway.jar --init
```

行为说明：
- 若当前目录不存在 `dynamicProxy.yaml`，会根据内置模板自动创建。
- 若文件已存在，不会覆盖现有文件。
- 控制台会输出配置文件绝对路径，以及建议的正式启动命令。
- `--init` 仅初始化，不会启动网关服务。

## 配置文件启动

```bash
java -jar gateway.jar --config ./dynamicProxy.yaml
```

说明：
- 指定 `--config` 后，网关静态配置将从 YAML 读取。
- 未配置的字段自动使用默认值。
- `gatewayHostname`、`endpoint`、`token` 为必填项，缺失会启动失败。

## 兼容旧命令参数启动

未指定 `--config` 时，仍按原有命令参数解析：

```bash
java -jar gateway.jar \
  --gatewayHostname gateway.example.com \
  --endpoint https://api.admin.example.com \
  --token your-api-token \
  --listenSocks5Port 8088 \
  --listenHttpPort 8089 \
  --whiteListPortRangeStart 20000 \
  --whiteListPortRangeEnd 22000
```

## YAML 参数说明

| 参数 | 默认值 | 必填 | 说明 |
|------|--------|------|------|
| `gatewayHostname` | `""` | 是 | 网关主机名，用于白名单配置拉取与流量上报标识 |
| `listenHost` | `0.0.0.0` | 否 | 服务监听地址 |
| `listenSocks5Port` | `8088` | 否 | SOCKS5 服务端口 |
| `listenHttpPort` | `8089` | 否 | HTTP 代理服务端口 |
| `listenThread` | `1` | 否 | Netty boss 线程数 |
| `workerThreads` | `0` | 否 | 共享的 Netty worker / forwarder 线程数，`0` 表示由 Netty 自动设置 |
| `backLog` | `128` | 否 | TCP accept 连接队列长度（`SO_BACKLOG`） |
| `logDebug` | `false` | 否 | 是否开启 debug 日志 |
| `forwardConnTimeout` | `1500` | 否 | 到上游代理 TCP 建连超时（毫秒） |
| `forwardReadTimeout` | `30000` | 否 | 上游代理读超时（毫秒），`0` 表示关闭 |
| `forwardWriteTimeout` | `30000` | 否 | 上游代理写超时（毫秒），`0` 表示关闭 |
| `dnsRemote` | `false` | 否 | 目标地址 DNS：`false` 网关本机解析（默认）；`true` 由上游 SOCKS5 代理解析（等同 `curl --socks5-hostname`） |
| `disableSupplierDnsCache` | `false` | 否 | 供应商上游代理 hostname DNS：`false` 使用 JVM 默认 DNS 行为（默认）；`true` 供应商为域名时每次转发重新解析 |
| `connTimeout` | `15000` | 否 | 网关连接超时（毫秒） |
| `readTimeout` | `30000` | 否 | 网关读超时（毫秒） |
| `writeTimeout` | `30000` | 否 | 网关写超时（毫秒） |
| `httpRequestHeaderMaxSize` | `32768` | 否 | HTTP 请求头最大尺寸（字节），同时约束 `CONNECT` 头部大小 |
| `httpObjectAggregatorSize` | `5242880` | 否 | 普通 HTTP 消息聚合器最大尺寸（字节）；`CONNECT` 不走该聚合器 |
| `writeBufferLowWaterMark` | `16384` | 否 | 写缓冲低水位（字节），低于该值后恢复 relay 读取 |
| `writeBufferHighWaterMark` | `32768` | 否 | 写缓冲高水位（字节），高于该值后暂停 relay 读取 |
| `userMaxConcurrentConnections` | `500` | 否 | 单个认证用户最大并发转发连接数，`0` 表示关闭限制 |
| `endpoint` | `""` | 是 | 管理端 API 地址 |
| `token` | `""` | 是 | 管理端 API Token |
| `configUpdateInterval` | `60` | 否 | 动态配置拉取间隔（秒） |
| `trafficReportInterval` | `30` | 否 | 流量上报间隔（秒） |
| `resourceUsageLogIntervalSec` | `60` | 否 | 资源使用观测日志间隔（秒）；输出堆内存、堆外 direct buffer、Netty direct memory、线程数和接入侧活动连接数；`0` 表示关闭 |
| `localConfigFile` | `false` | 否 | 是否使用本地模拟配置替代远程 API |
| `maxSessionSize` | `50000` | 否 | 粘性会话缓存上限 |
| `sessionExpireCheckInterval` | `30` | 否 | 会话过期检查间隔（秒） |
| `whiteListPortRangeStart` | `20000` | 否 | 白名单路由端口起始值 |
| `whiteListPortRangeEnd` | `22000` | 否 | 白名单路由端口结束值 |
| `preferenceEndpointArea` | 未设置 | 否 | 可选：偏好出口地区；不设置则不生效 |
| `localAreaMappingFile` | 未设置 | 否 | 可选：本地区域映射文件路径；不设置使用内置映射 |

## 资源观测日志

- 默认情况下，网关每 `60` 秒输出一条 `resource usage stats` 日志。
- 日志按 `heap`、`direct buffer pool`、`netty`、`threads`、`channels` 分组多行输出，便于直接查看。
- 日志包含：
  - JVM 堆内存 `used/committed/max`，使用 `KB/MB/GB` 等更易读单位展示，并保留原始字节数
  - JDK direct buffer pool 的 `count/used/capacity`
  - Netty pooled direct memory 使用量
  - JVM `live/daemon/peak` 线程数
  - 接入侧活动连接数
- 若不希望输出该日志，可将 `resourceUsageLogIntervalSec` 设置为 `0`。

## 常见问题

1. 启动时报 `gateway hostname is required`、`endpoint is required`、`token is required`
- 检查 YAML 中是否填写了 `gatewayHostname`、`endpoint`、`token`。

2. 启动时报 `config file not exists`
- 检查 `--config` 的路径是否正确，建议使用绝对路径。

3. `--init` 后服务没有启动
- 这是预期行为；`--init` 仅用于生成配置文件，请使用输出的 `--config` 命令正式启动。
