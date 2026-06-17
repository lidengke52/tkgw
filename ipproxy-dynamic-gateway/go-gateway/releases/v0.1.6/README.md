# tkgw-go-gateway v0.1.6

Go 版 Dynamic Proxy Gateway Linux 二进制包。

## 本版变更

- 修复 supplier 18（liang / 亮数据）地址映射空值覆盖问题。
- 当 `areaMapping.csv` 中某行标记 `*_support=1` 但映射字段为空时，不再写入该级映射，也不会覆盖已存在的有效国家/省/城市映射。
- 修复后 supplier 18 的 `res-in` 会生成带 `country-IN` 的上游账号串，避免因为映射成空值而落到供应商默认出口。

## 验证

- `go test ./...` 通过。
- 已在 SG-01 临时验证：`supplierId=18` 的 debug 日志从 `mappedArea=//` 变为 `mappedArea=IN//`，`forwardUser` 从不带国家参数变为包含 `country-IN`。

## 文件

- `tkgw-go-gateway-v0.1.6-linux-amd64.tar.gz`：Ubuntu x86_64 / AMD64 服务器使用。
- `tkgw-go-gateway-v0.1.6-linux-arm64.tar.gz`：Ubuntu ARM64 / AArch64 服务器使用。
- `SHA256SUMS`：校验和。

## Ubuntu 使用

```bash
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.6/tkgw-go-gateway-v0.1.6-linux-amd64.tar.gz
wget https://raw.githubusercontent.com/lidengke52/tkgw/master/ipproxy-dynamic-gateway/go-gateway/releases/v0.1.6/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
tar -xzf tkgw-go-gateway-v0.1.6-linux-amd64.tar.gz
```

如果线上已有配置文件，至少需要替换：

```text
gateway-go
resources/areaMapping.csv
```
