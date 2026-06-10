# tkgw-go-gateway v0.1.0

Go 版 Dynamic Proxy Gateway 首个 Linux 二进制包。

## 文件

- `tkgw-go-gateway-v0.1.0-linux-amd64.tar.gz`：Ubuntu x86_64 / AMD64 服务器使用。
- `tkgw-go-gateway-v0.1.0-linux-arm64.tar.gz`：Ubuntu ARM64 / AArch64 服务器使用。
- `SHA256SUMS`：校验和。

## Ubuntu 使用

大多数云服务器是 amd64：

```bash
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.0/tkgw-go-gateway-v0.1.0-linux-amd64.tar.gz
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.0/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
tar -xzf tkgw-go-gateway-v0.1.0-linux-amd64.tar.gz
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

生产部署建议参考包内 `docs/Operations.zh-CN.md`。
