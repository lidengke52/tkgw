# Go Dynamic Proxy Gateway 运维手册

本文档用于生产部署、灰度、监控和故障排查。

## 部署目录建议

```text
/opt/dynamic-proxy-gateway/
  gateway-go
  config/
    dynamicProxy.yaml
  resources/
    areaMapping.csv
    Country-only-cn-private.mmdb
  logs/
```

真实配置文件不要放进 Git。建议权限：

```bash
chmod 600 config/dynamicProxy.yaml
```

## systemd 示例

```ini
[Unit]
Description=Go Dynamic Proxy Gateway
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/dynamic-proxy-gateway
ExecStart=/opt/dynamic-proxy-gateway/gateway-go --config /opt/dynamic-proxy-gateway/config/dynamicProxy.yaml
Restart=always
RestartSec=3
LimitNOFILE=1048576

# 可选：根据机器 CPU 设置
# Environment=GOMAXPROCS=8

[Install]
WantedBy=multi-user.target
```

启动：

```bash
systemctl daemon-reload
systemctl enable dynamic-proxy-gateway
systemctl start dynamic-proxy-gateway
```

查看日志：

```bash
journalctl -u dynamic-proxy-gateway -f
```

## 上线前检查

1. Go 版本和二进制构建完成。
2. `dynamicProxy.yaml` 中 `gatewayHostname`、`endpoint`、`token` 正确。
3. 监听端口未被占用。
4. `ulimit -n` 足够。
5. 机器能访问管理端 API。
6. 机器能访问供应商 gateway。
7. `resources/areaMapping.csv` 和 mmdb 文件存在。
8. `trafficReportSpoolFile` 所在目录可写。

## 灰度替换 Java 版

推荐顺序：

1. 单独部署一台 Go 网关，不接生产流量。
2. 使用真实 token 拉取动态配置。
3. 用测试账号验证 SOCKS5、HTTP CONNECT、普通 HTTP、白名单端口。
4. 小流量切入 Go 网关。
5. 对比 Java 版成功率、延迟、CPU、内存、流量上报。
6. 扩大流量。
7. 保留 Java 版回滚入口一段时间。
8. 指标稳定后归档 Java 版。

## 集群部署建议

集群配置建议：

```yaml
sessionAffinityMode: "deterministic"
supplierRecoverAffectsExistingSession: false
trafficReportSpoolFile: "traffic-report-spool.jsonl"
```

说明：

- 不需要 Redis。
- DNS/LB 分到不同机器时，同一 sticky session 仍能算出同一供应商和入口。
- 每台机器独立做供应商健康检查。
- 每台机器独立上报自身流量，`hostname` 用于管理端识别来源。

如果管理端按 hostname 聚合，确保每台网关配置不同的 `gatewayHostname` 或符合现有管理端规则。

## 日志说明

常见日志：

```text
dynamic config refreshed users=... suppliers=... whitelist=... areaMappings=...
```

动态配置刷新成功。

```text
socks upstream connect failed target=... err=...
http connect upstream failed target=... err=...
```

连接上游供应商失败，会进入健康统计。

```text
traffic stats up=... down=... reportStatus=200
```

流量上报成功。

```text
traffic report failed: ...
traffic spool resend failed: ...
```

流量上报失败或补发失败。

```text
resource usage goroutines=... heapAlloc=... activeConnections=...
```

资源观测日志。

## 流量统计恢复

配置：

```yaml
trafficReportSpoolFile: "traffic-report-spool.jsonl"
```

行为：

- 定时上报失败时，本批次写入 spool 文件。
- 下一轮上报先补发 spool，再上报新批次。
- 进程重启后也会先补发 spool。
- 正常 SIGTERM/SIGINT 退出时会 flush 当前内存统计。

边界：

- 已写入 spool 的失败批次可恢复。
- 异常崩溃时，仍在内存里且尚未 flush 的极短窗口数据无法完全保证。
- 不建议每次计数同步落盘，会影响代理热路径性能。

## 供应商故障排查

如果某个供应商失败率高：

1. 查看上游连接失败日志。
2. 确认失败维度是哪个 `supplierId + endpoint + country`。
3. 检查供应商 gateway 是否可连。
4. 检查供应商账号模板是否正确。
5. 检查地区映射是否支持目标国家/省/城市。
6. 观察健康检查是否熔断并切换到其他供应商。

可临时放宽健康检查，减少误熔断：

```yaml
supplierHealthMinSamples: 50
supplierHealthFailureRate: 0.7
supplierHealthConsecutiveFailures: 10
```

也可加快切换：

```yaml
supplierHealthMinSamples: 10
supplierHealthFailureRate: 0.4
supplierHealthConsecutiveFailures: 3
```

## Sticky session 排查

客户反馈出口 IP 不稳定时：

1. 检查用户名是否包含 `sid` 和 `keeptime`。
2. 检查 `sessionId` 是否为数字且长度小于 20。
3. 检查 `keeptime` 是否大于 0。
4. 集群部署确认 `sessionAffinityMode: "deterministic"`。
5. 查看是否发生供应商熔断，熔断会导致 session 切到下一个健康候选。
6. 确认 `supplierRecoverAffectsExistingSession: false`。

排查期间可临时开启：

```yaml
logDebug: true
```

带 `sid` 的请求会输出 `sticky forward` 日志，字段包括：

```text
authUser sid keepTimeSec affinityMode cacheHit supplierId endpoint healthHealthy requestArea mappedArea forwardUser
```

如果同一个 sid 多次日志里的 `supplierId/endpoint/forwardUser` 一致，但出口 IP 仍然变化，问题更可能在上游供应商是否严格尊重 session token。排查结束后建议关闭 `logDebug`，避免日志量过大。

## 白名单端口排查

问题：白名单端口连不上。

检查：

1. 端口是否在配置范围内。
2. 端口是否监听成功。
3. 客户 IP 是否在管理端白名单。
4. 管理端白名单配置是否成功刷新。
5. 该端口映射的协议是否与客户使用协议一致。

## API 拉取失败

动态配置拉取失败时，网关会保留上一份成功配置继续运行。

排查：

1. `endpoint` 是否正确。
2. `token` 是否有效。
3. 机器 DNS 和网络是否可访问管理端。
4. 管理端 API 是否返回非 2xx。
5. `gatewayHostname` 是否匹配白名单配置。

## 回滚

Go 网关出现问题时：

1. 从 LB/DNS 下线 Go 网关。
2. 保留进程一段时间，让已有连接自然结束。
3. 若需要立刻停止，发送 SIGTERM，触发流量 flush。
4. 切回 Java 网关。
5. 保存 Go 网关日志、配置和 spool 文件用于排查。

## 安全注意事项

- 不要提交真实 `dynamicProxy.yaml`。
- 不要在日志中打印 token。
- 生产配置文件建议 `chmod 600`。
- `trafficReportSpoolFile` 包含用户 UID 和流量数据，建议放在受控目录。
- 对外只开放必要代理端口，调试端口不要暴露公网。
