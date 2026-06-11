# tkgw-go-gateway v0.1.3

Go 版 Dynamic Proxy Gateway Linux 二进制包。

## 本版变更

- 新增 sticky session 诊断日志。
- `logDebug: true` 时，带 `sid` 的请求会输出 `sticky forward` 日志。
- 日志包含 `authUser`、`sid`、`cacheHit`、`supplierId`、`endpoint`、`healthHealthy`、`requestArea`、`mappedArea`、`forwardUser`。
- 不打印上游密码。
- 包含 v0.1.2 的最新 `areaMapping.csv`。

## 文件

- `tkgw-go-gateway-v0.1.3-linux-amd64.tar.gz`：Ubuntu x86_64 / AMD64 服务器使用。
- `tkgw-go-gateway-v0.1.3-linux-arm64.tar.gz`：Ubuntu ARM64 / AArch64 服务器使用。
- `SHA256SUMS`：校验和。

## Ubuntu 使用

大多数云服务器是 amd64：

```bash
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.3/tkgw-go-gateway-v0.1.3-linux-amd64.tar.gz
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.3/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
tar -xzf tkgw-go-gateway-v0.1.3-linux-amd64.tar.gz
```

如果线上已有配置文件，只需要替换：

```text
gateway-go
resources/areaMapping.csv
```

排查 sticky 漂移时临时开启：

```yaml
logDebug: true
```

排查结束后建议关闭，避免日志量过大。
