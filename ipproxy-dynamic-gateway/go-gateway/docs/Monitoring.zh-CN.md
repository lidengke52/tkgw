# 监控接入说明

Go 网关支持通过 Prometheus text format 暴露 `/metrics`，同时建议每台服务器安装 `node_exporter` 采集系统指标。

## 网关配置

默认 metrics 关闭：

```yaml
metricsEnabled: false
metricsHost: "127.0.0.1"
metricsPort: 19090
metricsPath: "/metrics"
```

单机本地 Prometheus 或本机 agent 采集：

```yaml
metricsEnabled: true
metricsHost: "127.0.0.1"
metricsPort: 19090
metricsPath: "/metrics"
```

Prometheus 从独立监控机拉取时：

```yaml
metricsEnabled: true
metricsHost: "0.0.0.0"
metricsPort: 19090
metricsPath: "/metrics"
```

如果暴露到 `0.0.0.0`，必须用安全组或防火墙只允许 Prometheus 服务器访问。

## 验证

网关启动后：

```bash
curl http://127.0.0.1:19090/metrics
curl http://127.0.0.1:19090/healthz
```

应能看到类似：

```text
gateway_active_connections 12
gateway_goroutines 234
gateway_dynamic_users 100
gateway_supplier_healthy{supplier="18",endpoint="brd.superproxy.io:22228",country="US"} 1
```

## Prometheus 配置示例

```yaml
scrape_configs:
  - job_name: "tkgw-gateway"
    metrics_path: /metrics
    static_configs:
      - targets:
          - "162.128.84.205:19090"
          - "162.128.85.111:19090"
        labels:
          cluster: "sg"
```

如果每台机器只监听 `127.0.0.1`，可以用 node_exporter textfile collector、agent 或反向代理转发；最简单是让 metrics 监听内网 IP，并用安全组限制来源。

## node_exporter

每台网关建议安装 node_exporter，用于服务器基础监控：

```text
CPU / load
内存 / swap
磁盘空间 / IO
网卡流量 / 错误包
TCP 连接数 / TIME_WAIT
文件句柄
systemd 服务状态
```

Prometheus 示例：

```yaml
scrape_configs:
  - job_name: "node"
    static_configs:
      - targets:
          - "162.128.84.205:9100"
          - "162.128.85.111:9100"
```

## 网关指标

当前 Go 网关输出：

| 指标 | 类型 | 说明 |
|------|------|------|
| `gateway_uptime_seconds` | gauge | 进程运行时间 |
| `gateway_goroutines` | gauge | goroutine 数 |
| `gateway_heap_alloc_bytes` | gauge | Go heap 当前分配 |
| `gateway_heap_sys_bytes` | gauge | Go heap 向系统申请 |
| `gateway_stack_sys_bytes` | gauge | goroutine stack 内存 |
| `gateway_gc_cycles_total` | counter | GC 次数 |
| `gateway_open_fds` | gauge | 当前打开 fd 数 |
| `gateway_active_connections` | gauge | 当前活跃代理连接 |
| `gateway_requests_total{uid,status}` | counter | 请求累计数 |
| `gateway_traffic_bytes_total{uid,direction}` | counter | 上下行累计流量 |
| `gateway_supplier_healthy{supplier,endpoint,country}` | gauge | 供应商健康状态，1=健康 |
| `gateway_supplier_health_samples{supplier,endpoint,country}` | gauge | 健康检查窗口样本数 |
| `gateway_supplier_health_failures{supplier,endpoint,country}` | gauge | 健康检查窗口失败数 |
| `gateway_supplier_consecutive_failures{supplier,endpoint,country}` | gauge | 连续失败数 |
| `gateway_dynamic_users` | gauge | 当前动态用户数 |
| `gateway_dynamic_suppliers` | gauge | 当前供应商数 |
| `gateway_dynamic_whitelist_ips` | gauge | 当前白名单 IP 数 |
| `gateway_dynamic_area_mappings` | gauge | 当前地址映射供应商数 |
| `gateway_traffic_spool_bytes` | gauge | 流量上报失败本地队列大小 |

## Grafana 面板建议

网关业务面板：

- 活跃连接数：`gateway_active_connections`
- 请求成功/失败：`rate(gateway_requests_total{status!="total"}[5m])`
- 失败率：`rate(gateway_requests_total{status="fail"}[5m]) / rate(gateway_requests_total{status="total"}[5m])`
- 上下行流量：`rate(gateway_traffic_bytes_total[5m])`
- 供应商健康：`gateway_supplier_healthy`
- 供应商失败窗口：`gateway_supplier_health_failures / gateway_supplier_health_samples`
- 流量上报堆积：`gateway_traffic_spool_bytes`

服务器面板使用 node_exporter 常规模板即可。

## 告警建议

基础告警：

```text
网关进程 /metrics 不可达
gateway_active_connections 突然归零或异常升高
gateway_traffic_spool_bytes 持续增长
gateway_supplier_healthy == 0 持续 1 分钟
请求失败率 > 5% 持续 5 分钟
open fd 使用率 > 80%
磁盘可用 < 15%
内存可用 < 10%
```

## 日志建议

线上默认保持：

```yaml
logDebug: false
```

排查 sticky 漂移时短期开启 `logDebug: true`。排查结束后关闭，避免日志量过大。

同时建议限制 journald：

```ini
SystemMaxUse=1G
SystemKeepFree=2G
RuntimeMaxUse=512M
MaxRetentionSec=7day
```
