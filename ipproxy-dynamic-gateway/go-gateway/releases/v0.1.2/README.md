# tkgw-go-gateway v0.1.2

Go 版 Dynamic Proxy Gateway Linux 二进制包。

## 本版变更

- 更新 `resources/areaMapping.csv` 为新的地址映射文件。
- 继续支持 `INFATICA(16)`、`NETNUT(17)`、`liang(18)` 的国家、省、城市地址映射。
- 二进制逻辑与 v0.1.1 一致，本版本主要用于下发新的映射资源。

## 文件

- `tkgw-go-gateway-v0.1.2-linux-amd64.tar.gz`：Ubuntu x86_64 / AMD64 服务器使用。
- `tkgw-go-gateway-v0.1.2-linux-arm64.tar.gz`：Ubuntu ARM64 / AArch64 服务器使用。
- `SHA256SUMS`：校验和。

## Ubuntu 使用

大多数云服务器是 amd64：

```bash
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.2/tkgw-go-gateway-v0.1.2-linux-amd64.tar.gz
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.2/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
tar -xzf tkgw-go-gateway-v0.1.2-linux-amd64.tar.gz
cp config/dynamicProxy.example.yaml config/dynamicProxy.yaml
```

编辑 `config/dynamicProxy.yaml`，填入真实：

```yaml
gatewayHostname: "..."
endpoint: "https://api.gzyuyun.com"
token: "..."
```

启动：

```bash
chmod +x ./gateway-go
./gateway-go --config ./config/dynamicProxy.yaml
```

如果线上已有配置文件，只需要替换：

```text
gateway-go
resources/areaMapping.csv
```

生产部署建议参考包内 `docs/Operations.zh-CN.md`。
