# tkgw-go-gateway v0.1.5

Go 版 Dynamic Proxy Gateway Linux 二进制包。

## 本版变更

- 新增 `supplierWeights` 配置，默认 `16=50,17=50`。
- 供应商选择改为先按 supplier 权重选池，再在池内选择 gateway endpoint，避免 endpoint 数量影响供应商比例。
- sticky deterministic 模式支持加权确定性分配；同一套配置下集群内同 SID 仍会选到同一供应商和入口。
- 保留原有逻辑：`keeptime` 大于 30 分钟且用户可用供应商包含 16 时，仍优先选择 16。
- 包含最新 `areaMapping.csv`、监控文档和 v0.1.4 Prometheus metrics 能力。

## 权重配置

默认：

```yaml
supplierWeights: "16=50,17=50"
```

如果要 16 池 70%、17 池 30%：

```yaml
supplierWeights: "16=70,17=30"
```

注意：

- 集群内所有网关必须使用同一份 `supplierWeights`。
- 权重只影响新分配；已有本机缓存 session 不会立刻重排。
- 健康检查会先过滤不健康入口，再对剩余供应商按权重分配。
- sticky deterministic 模式下，比例按 SID 数量近似生效，不保证按字节流量精确分布。

## 文件

- `tkgw-go-gateway-v0.1.5-linux-amd64.tar.gz`：Ubuntu x86_64 / AMD64 服务器使用。
- `tkgw-go-gateway-v0.1.5-linux-arm64.tar.gz`：Ubuntu ARM64 / AArch64 服务器使用。
- `SHA256SUMS`：校验和。

## Ubuntu 使用

```bash
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.5/tkgw-go-gateway-v0.1.5-linux-amd64.tar.gz
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.5/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
tar -xzf tkgw-go-gateway-v0.1.5-linux-amd64.tar.gz
```

如果线上已有配置文件，至少需要替换：

```text
gateway-go
resources/areaMapping.csv
```

并在现有配置里增加：

```yaml
supplierWeights: "16=50,17=50"
```
