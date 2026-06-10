# Go Dynamic Proxy Gateway

This is the Go rewrite of the Java/Netty dynamic proxy gateway. The intended maintenance target is this Go implementation; the Java tree can be kept only as a historical reference during migration.

## Implemented

- SOCKS5 gateway with username/password auth.
- HTTP proxy gateway with Basic `Proxy-Authorization`.
- HTTP `CONNECT` tunnel proxying.
- Ordinary HTTP request forwarding without full response aggregation.
- White-list routing ports with HTTP/SOCKS5 first-byte dispatch.
- Dynamic user, supplier, and white-list config refresh through the existing admin API.
- Sticky session cache that only stores real sticky sessions.
- Per-user concurrent connection limiting.
- Atomic traffic and request counters with periodic reporting.
- Upstream SOCKS5 auth and CONNECT handshake.
- GeoIP mmdb client IP filtering compatible with the Java gateway behavior.
- Merge-pool area mapping using `resources/areaMapping.csv`.
- Resource usage reporting for heap, goroutines, GC, and active connections.
- Passive supplier health checks with circuit breaking and recovery.
- Local target DNS by default, remote target DNS with `dnsRemote: true`.

## Run

```bash
go run ./cmd/gateway --config ../src/main/resources/dynamicProxy-template.yaml
```

For production:

```bash
go build -o gateway-go ./cmd/gateway
./gateway-go --config ./dynamicProxy.yaml
```

## Merge-pool Geo Tester

To verify SOCKS5 exit IP geolocation in bulk, run:

```bash
go run ./cmd/geo-tester
```

Open `http://127.0.0.1:8099`, enter a SOCKS5 string in the `host:port@username:password` format, and run the default 1000 checks. The page can also store multiple test pools in the browser, one per line as `pool name=host:port@username:password`, so different pools can be selected from the same page. The tester requests `ip-api.com` or `ipinfo.io` through the proxy and summarizes successes, failures, expected city matches, unique IPs, city distribution, and error distribution. Results can be exported as JSON or CSV.

`ip-api.com` free usage is rate-limited, so the UI defaults to a 1400ms interval, roughly 45 requests per minute. Use an IPinfo token if you need higher quota through `ipinfo.io`.

## Notes

- The Go implementation uses the standard library plus `github.com/oschwald/geoip2-golang/v2` for the MaxMind mmdb file.
- `backLog`, low/high write water marks, and Netty-specific boss/worker knobs are not 1:1 concepts in Go.
- The project has been formatted and verified with `go build ./cmd/gateway`.
