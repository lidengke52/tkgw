# Architecture

## Overview

Dynamic Proxy Gateway is a dynamic proxy gateway that hosts as SOCKS5/HTTP proxy services and routes traffic to different upstream proxies based on connection info and routing rules.

## Architecture Diagram

```
                              ┌─────────────────────────────────────────────────────────────┐
                              │                    Dynamic Proxy Gateway                      │
                              │                                                             │
                              │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
                              │  │  SOCKS5     │  │   HTTP      │  │  WhiteList Routing  │  │
                              │  │  Service    │  │   Service   │  │  (Port Range)       │  │
                              │  │  :8088      │  │   :8089     │  │  20000-22000        │  │
                              │  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
                              │         │                │                     │            │
                              │         └────────────────┼─────────────────────┘            │
                              │                          │                               │
                              │              ┌───────────┴───────────┐                    │
                              │              │   ForwardAllocate     │                    │
                              │              │       Service         │                    │
                              │              └───────────┬───────────┘                    │
                              └──────────────────────────┼───────────────────────────────┘
                                                       │
                              ┌─────────────────────────┼───────────────────────────────┐
                              │                         │                               │
                              │         ┌───────────────┴───────────────┐                │
                              │         │      GlobalConfigStore        │                │
                              │         │  (SupplierConfig, UserConfig, │                │
                              │         │   WhiteListConfig, AreaMapping│                │
                              │         └───────────────┬───────────────┘                │
                              │                         │                                │
                              │                         ▼                                │
                              │         ┌───────────────────────────────┐                 │
                              │         │         APIService            │                 │
                              │         │   (Remote Config Fetch)      │                 │
                              │         └───────────────────────────────┘                 │
                              │                    │                                      │
                              └────────────────────┼──────────────────────────────────────┘
                                                  │
                                                  ▼
                                    ┌─────────────────────────┐
                                    │   Remote Admin API      │
                                    │  (User/Supplier/WhiteList)│
                                    └─────────────────────────┘
```

## Proxy Protocols

### 1. SOCKS5 Protocol Service

- **Default Port**: 8088 (configurable)
- **Authentication**: Username/Password (SOCKS5 Password Authentication)
- **Connection Flow**:
  1. Client sends SOCKS5 Initial Request
  2. Gateway responds with Password Authentication method
  3. Client sends Username/Password credentials
  4. Gateway validates credentials and parses connection string
  5. Client sends Connect Command
  6. Gateway establishes connection to upstream proxy

### 2. HTTP Protocol Service

- **Default Port**: 8089 (configurable)
- **Authentication**: Basic Authentication via Proxy-Authorization header
- **Connection Flow**:
  1. Client sends HTTP request with Basic Auth header
  2. Gateway validates credentials and parses connection string
  3. Gateway forwards request to upstream proxy

### 3. WhiteList Routing Service

- **Port Range**: 20000-22000 (configurable)
- **Authentication**: IP Whitelist (no password required)
- **Protocol Detection**: Auto-detect SOCKS5 or HTTP based on client connection
- **Connection Flow**:
  1. Client connects to whitelisted port
  2. Gateway identifies client IP and port
  3. Gateway determines protocol and upstream config from whitelist
  4. Connection established without user authentication

## Relay Backpressure Control

- SOCKS5 relay and HTTP `CONNECT` tunnel relay use paired forwarding handlers for bidirectional byte stream forwarding.
- Relay handlers write to the peer channel with `write()` and flush in `channelReadComplete()` to reduce flush frequency and syscall overhead.
- When the peer channel becomes non-writable, the current inbound channel disables `AUTO_READ` to stop pulling more bytes into Netty buffers.
- When the channel becomes writable again, the paired channel resumes `AUTO_READ` and triggers `read()` so traffic can continue.
- Server child channels and upstream client channels both apply explicit `WRITE_BUFFER_WATER_MARK` thresholds from YAML configuration to make backpressure behavior deterministic; the default water mark is tightened to `16KB / 32KB` for tunnel-heavy high-concurrency workloads.
- Upstream forwarding channels additionally attach dedicated `forwardReadTimeout` / `forwardWriteTimeout` handlers so slow upstreams do not retain buffers indefinitely after TCP connect succeeds.
- SOCKS5, HTTP `CONNECT`, and normal HTTP handlers split upstream channel setup into supplier DNS resolution, Bootstrap creation, pipeline initialization, and protocol-specific success/failure handling.

## User Authentication Logic

### Standard Authentication (SOCKS5/HTTP)

Users authenticate using a structured connection string in the username field:

**Username Format (Sticky Session)**:
```
{user}-res-{country}-{state}-{city}-sid-{sessionId}-keeptime-{minutes}
```
Example: `HSKFG-res-US-sid-34957934-keeptime-5`

**Username Format (Temporary Session)**:
```
{user}-res-{country}
```
Example: `HSKFG-res-US`

**Format Components**:
| Component | Description | Example |
|-----------|-------------|---------|
| user | User identifier | HSKFG |
| country | Target country code (lowercase) | us |
| state | Target state/province (optional) | ca |
| city | Target city (optional, for MergePool) | sanfrancisco |
| sessionId | Session identifier for sticky sessions (optional) | 34957934 |
| minutes | Session keep time in minutes (optional) | 5 |

**Session Types**:
- **Temporary Session (一次一换)**: No sessionId specified, each connection gets different upstream
- **Sticky Session (粘性会话)**: sessionId specified, same upstream for session duration

### Authentication Flow

```
Client                    Gateway                      GlobalConfigStore
  │                           │                              │
  │──Username/Password──────▶│                              │
  │                           │──Validate Credentials────────▶│
  │                           │◀──User Config────────────────│
  │                           │                              │
  │                           │──Parse Connection String────▶│
  │                           │   (country, state, city,     │
  │                           │    sessionId, keepTime)      │
  │                           │                              │
```

### Whitelist Authentication

For WhiteList Routing Service:
1. Client IP must be in configured whitelist
2. Port determines protocol (SOCKS5/HTTP) and upstream configuration
3. No password authentication required

## Traffic Routing Logic

### Routing Flow

```
Client Request
      │
      ▼
┌─────────────────┐
│ Authentication  │──▶ Validate user credentials
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Parse Connection│──▶ Extract: country, state, city, sessionId
│     String      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Get Available  │──▶ Get user's available suppliers list
│   Suppliers     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Select Random  │──▶ Random selection from available suppliers
│   Supplier      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Area Mapping?   │──▶ For MergePool (INFATICA/NETNUT):
│                 │    Convert standard area codes to supplier-specific codes
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Generate Auth   │──▶ Format: {user}_c_{country}_s_{session}_ttl_120m:{password}
│     String      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Select Gateway  │──▶ Random selection from supplier's available gateways
│   Endpoint      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Bind Session?   │──▶ Store session info in SessionStateStore
│                 │    (if sticky session)
└────────┬────────┘
         │
         ▼
   Upstream Proxy Connection
```

### Session Management

**SessionStateStore**:
- Uses Caffeine cache with maximum size limit
- Session expiration based on `keepTime` setting
- Key format: `{authUser}_{sessionId}`

**Session Binding**:
- For sticky sessions: Session info cached with expiration time
- For temporary sessions: No session caching, each connection gets new upstream

### Supported Suppliers

| Supplier ID | Name | Type |
|-------------|------|------|
| 16 | INFATICA | MergePool |
| 17 | NETNUT | MergePool |

**MergePool Suppliers**: Support area mapping for country/state/city conversion to supplier-specific codes.

## Remote API

### API Endpoints

All APIs use Bearer token authentication: `Authorization: Bearer {token}`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/admin/dynamic/getAccountConfig` | GET | Fetch user account configurations |
| `/api/admin/dynamic/getAllSuppliers` | GET | Fetch all supplier configurations |
| `/api/admin/agent/getWhiteConfig` | GET | Fetch whitelist IP configurations |
| `/api/admin/dynamic/trafficReport` | POST | Report traffic usage statistics |

### API Response Format

All APIs return JSON with standard format:
```json
{
  "code": 200,
  "data": { ... }
}
```

### Configuration Update

- **Update Interval**: Configurable (default 60 seconds)
- **Process**: Background thread periodically fetches updated configuration
- **Supported Configurations**:
  - User configurations (accounts, credentials, available suppliers)
  - Supplier configurations (gateways, auth formats, session settings)
  - Whitelist configurations (IPs, port mappings)
  - Area mapping configurations (for MergePool suppliers)

## Component Details

### Core Services

| Service | Description |
|---------|-------------|
| ProxyService | Main entry point, starts SOCKS5/HTTP/WhiteList servers with shared acceptor/worker/forwarder Netty event loop groups |
| APIService | Handles remote API communication |
| ForwardAllocateService | Allocates upstream proxy connections |

### Target DNS Resolution (Forward Path)

When the gateway forwards a client request to an upstream SOCKS5 proxy, the **target host** (destination the client wants to reach) can be resolved in two modes, controlled by `dnsRemote`:

| `dnsRemote` | Behavior | Equivalent |
|-------------|----------|------------|
| `false` (default) | Gateway resolves the target hostname locally, then sends IPv4/IPv6 in upstream SOCKS5 CONNECT | Local DNS |
| `true` | Gateway skips local DNS; sends hostname as SOCKS5 DOMAIN (ATYP=0x03) to upstream for resolution | `curl --socks5-hostname` |

Implementation: [`ForwardConnectUtils`](../src/main/java/com/dk/ipproxy/dynamic/gateway/utils/ForwardConnectUtils.java) uses `Bootstrap.disableResolver()` and `InetSocketAddress.createUnresolved()` when `dnsRemote` is enabled. Literal IP targets always use resolved socket addresses in both modes.

### Supplier DNS Resolution

Upstream proxy hostname (`bindForwardHostname`) is always resolved on the gateway so the gateway can open a TCP connection to the proxy server. This supplier hostname resolution is separate from target DNS (`dnsRemote`) and is controlled by `disableSupplierDnsCache`:

| `disableSupplierDnsCache` | Behavior |
|---------------------------|----------|
| `false` (default) | Use JVM default DNS behavior for supplier hostnames, including JVM/OS DNS cache policy |
| `true` | For domain supplier hostnames, resolve before every forwarded request with a no-cache Netty resolver; literal IP suppliers skip DNS |

When enabled, SOCKS5, normal HTTP, and HTTP CONNECT forwarding resolve the supplier hostname before creating the upstream `Socks5ProxyHandler`.

### Handlers

| Handler | Protocol | Description |
|---------|----------|-------------|
| Socks5InitialRequestHandler | SOCKS5 | Handles SOCKS5 initialization |
| Socks5PasswordAuthRequestHandler | SOCKS5 | Handles SOCKS5 password authentication |
| Socks5CommandRequestHandler | SOCKS5 | Handles SOCKS5 connect commands and logs detailed upstream proxy handshake failures |
| HttpPasswordAuthRequestHandler | HTTP | Handles HTTP Basic authentication |
| HttpTrafficForwardHandler | HTTP | Handles HTTP traffic forwarding |
| ForwardUpstreamChannelHandler | All | Forwards data from client to upstream |
| ForwardDownstreamChannelHandler | All | Forwards data from upstream to client |

### Channel Initializers

| Initializer | Description |
|-------------|-------------|
| Socks5WorkerChannelInitializer | Sets up SOCKS5 protocol pipeline |
| HttpWorkerChannelInitializer | Sets up HTTP protocol pipeline |
| WhiteListRoutingWorkerChannelInitializer | Sets up whitelist routing pipeline |

### Cache/State Stores

| Store | Description |
|-------|-------------|
| SessionStateStore | Caches sticky session information |
| ChannelStateStore | Manages active channel connections, session bindings, and authenticated user concurrent-connection accounting |
| TrafficCounter | Tracks traffic usage statistics |
| RequestCounter | Tracks per-user total requests and successful connections, and prints periodic stats logs |
| ResourceUsageReporter | Prints periodic process-level resource usage logs for heap, direct memory, threads, and inbound active channels with human-readable units and grouped multi-line formatting |

## Configuration

### Startup Modes

| Mode | Command | Description |
|------|---------|-------------|
| Init config | `java -jar gateway.jar --init` | Initialize `dynamicProxy.yaml` in current directory and print startup hint, then exit |
| YAML config startup | `java -jar gateway.jar --config ./dynamicProxy.yaml` | Load gateway static config from YAML file |
| Legacy CLI startup | `java -jar gateway.jar ...` | Backward-compatible startup via original command arguments when `--config` is not provided |

### Key Arguments

| Argument | Default | Description |
|----------|---------|-------------|
| `--init` | - | Initialize config file template and exit |
| `--config <path>` | - | YAML config path |
| `--gateway-hostname` | (required in legacy mode) | Gateway hostname domain |
| `--endpoint` | (required in legacy mode) | API endpoint |
| `--api-token` | (required in legacy mode) | API token |
| `--listen-host` | `0.0.0.0` | Listen host |
| `--listen-socks5-port` | `8088` | SOCKS5 listen port |
| `--listen-http-port` | `8089` | HTTP listen port |
| `--listen-thread` | `1` | Boss thread count for Netty |
| `--worker-threads` | `0` | Shared worker/forwarder thread count for Netty (0=auto) |
| `--conn-timeout` | `15000` | **Gateway listener** TCP connect timeout (ms), client→gateway |
| `--forward-conn-timeout` | `1500` | **Forward connection** TCP connect timeout (ms), gateway→upstream |
| `--dnsRemote` | (flag) | Resolve target host on upstream proxy (remote DNS); default is local resolution |
| `--disableSupplierDnsCache` | (flag) | Disable JVM DNS cache behavior for supplier upstream hostnames; domain suppliers are resolved before every forwarded request |
| `--read-timeout` | `30000` | **Gateway listener** read timeout (ms), client→gateway |
| `--write-timeout` | `30000` | **Gateway listener** write timeout (ms), gateway→client |
| `--forward-read-timeout` | `30000` | **Forward connection** read timeout (ms), gateway→upstream proxy |
| `--forward-write-timeout` | `30000` | **Forward connection** write timeout (ms), gateway→upstream proxy |
| `--read-idle-timeout` | `0` | **Gateway listener** read idle timeout (ms), 0=disable |
| `--write-idle-timeout` | `0` | **Gateway listener** write idle timeout (ms), 0=disable |
| `--http-request-header-max-size` | `32768` | HTTP request header max size (bytes); CONNECT is handled before large-body aggregation |
| `--http-object-aggregator-size` | `5242880` | HTTP aggregator max size (bytes) |
| `--write-buffer-low-water-mark` | `16384` | Relay low water mark (bytes) |
| `--write-buffer-high-water-mark` | `32768` | Relay high water mark (bytes) |
| `--user-max-concurrent-connections` | `500` | Maximum concurrent forwarded connections per authenticated user, 0=disable |
| `--white-list-port-range-start` | `20000` | WhiteList port range start |
| `--white-list-port-range-end` | `22000` | WhiteList port range end |
| `--config-update-interval` | `60` | Config update interval (seconds) |
| `resourceUsageLogIntervalSec` | `60` | Resource usage log interval (seconds), `0` disables periodic heap/direct-memory/thread/connection logs; enabled logs use grouped multi-line output and human-readable memory units |
| `--max-session-size` | `50000` | Maximum session cache size |
| `--session-expire-check-interval` | `30` | Session expire check interval (seconds) |
| `--log-debug` | (flag) | Enable debug logging |

> **Note**: Timeout parameters are divided into two categories:
> - **Gateway listener timeouts** (connTimeout, readTimeout, writeTimeout, readIdleTimeout, writeIdleTimeout): Control client ↔ gateway
> - **Forward connection timeouts** (forwardConnTimeout, forwardReadTimeout, forwardWriteTimeout): Control gateway ↔ upstream proxy
> - **Target DNS** (`dnsRemote`): See [Target DNS Resolution](#target-dns-resolution-forward-path) above
> - **Supplier DNS** (`disableSupplierDnsCache`): Controls gateway-side resolution of upstream proxy hostnames

### Performance Tuning

For high-throughput deployments, key parameters to tune:
- `workerThreads`: Shared Netty worker/forwarder threads (default 0 = 2*CPU cores)
- `backLog`: TCP accept backlog (`SO_BACKLOG`, default 128)
- `httpRequestHeaderMaxSize`: HTTP request head budget, including CONNECT
- `httpObjectAggregatorSize`: Max normal HTTP message aggregation size
- `writeBufferLowWaterMark` / `writeBufferHighWaterMark`: Relay direct-memory queue upper bound
- `userMaxConcurrentConnections`: Per-user forwarded connection cap
- `maxSessionSize`: Session cache capacity
- `resourceUsageLogIntervalSec`: Process-level resource usage log interval for heap, direct memory, threads, and inbound active channels

`ProxyService` starts SOCKS5, HTTP, and WhiteList listeners together, but the three services now share one boss group, one worker group, and one forwarder group. When `workerThreads > 0`, the configured value is applied to both the shared worker group and the shared forwarder group; when `workerThreads = 0`, both groups fall back to Netty's default thread count.

See [PerformanceTuning.md](docs/PerformanceTuning.md) for detailed guidance.

### References

- Detailed usage and full YAML field descriptions: `docs/Usage.md`
- Performance tuning guide: `docs/PerformanceTuning.md`
