# tkgw-go-gateway v0.1.4

Go 版 Dynamic Proxy Gateway Linux 二进制包。

## 本版变更

- 新增 Prometheus `/metrics` 端点。
- 新增 `docs/Monitoring.zh-CN.md` 监控接入文档。
- 包含 v0.1.3 sticky debug 日志和最新 `areaMapping.csv`。

## Metrics 配置

默认关闭：

```yaml
metricsEnabled: false
metricsHost: "127.0.0.1"
metricsPort: 19090
metricsPath: "/metrics"
```

开启本机采集：

```yaml
metricsEnabled: true
metricsHost: "127.0.0.1"
metricsPort: 19090
metricsPath: "/metrics"
```

独立 Prometheus 服务器采集时可监听内网：

```yaml
metricsEnabled: true
metricsHost: "0.0.0.0"
metricsPort: 19090
metricsPath: "/metrics"
```

暴露到 `0.0.0.0` 时必须用安全组或防火墙限制来源。

## 文件

- `tkgw-go-gateway-v0.1.4-linux-amd64.tar.gz`：Ubuntu x86_64 / AMD64 服务器使用。
- `tkgw-go-gateway-v0.1.4-linux-arm64.tar.gz`：Ubuntu ARM64 / AArch64 服务器使用。
- `SHA256SUMS`：校验和。

## Ubuntu 使用

```bash
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.4/tkgw-go-gateway-v0.1.4-linux-amd64.tar.gz
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.4/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
tar -xzf tkgw-go-gateway-v0.1.4-linux-amd64.tar.gz
```

如果线上已有配置文件，只需要替换：

```text
gateway-go
resources/areaMapping.csv
```

并在现有配置里按需增加 metrics 配置。
