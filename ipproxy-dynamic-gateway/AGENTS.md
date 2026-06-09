# Dynamic Proxy Gateway

## Project Description

Dynamic Proxy Gateway is a dynamic proxy gateway that host as a socks5/http proxy service, and route the traffic to different upstream proxy based on the connect info and routing rules.


## Project Structure

```
ipproxy-dynamic-gateway/
├── src/main/java/com/dk/ipproxy/dynamic/gateway/
│   ├── GatewayMain.java                    # Application entry point
│   │
│   ├── authenticator/
│   │   └── BasicAuthenticator.java        # User credential validation & connection string parsing
│   │
│   ├── cache/
│   │   ├── ChannelStateStore.java         # Active connection channel management
│   │   ├── RequestCounter.java            # Request total/success/fail statistics
│   │   ├── ResourceUsageReporter.java     # Periodic heap/direct-memory/thread/connection usage logs with readable units and grouped layout
│   │   ├── SessionInfo.java                # Session data structure
│   │   ├── SessionStateStore.java         # Sticky session caching (Caffeine)
│   │   └── TrafficCounter.java            # Traffic usage statistics
│   │
│   ├── config/
│   │   ├── AreaMappingConfig.java         # Country/state/city mapping for MergePool suppliers
│   │   ├── GatewayConfig.java             # Gateway listening & timeout settings
│   │   ├── GlobalConfigStore.java         # Centralized config management & dynamic updates
│   │   ├── SupplierConfig.java           # Upstream proxy supplier configuration
│   │   ├── UserConfig.java               # User account configuration
│   │   └── WhiteListConfig.java          # Whitelist IP & port mapping config
│   │
│   ├── constants/
│   │   ├── CmdArgs.java                   # Command line argument definitions
│   │   ├── Country.java                   # Country code constants
│   │   ├── Protocol.java                  # Protocol type constants (HTTP/SOCKS5)
│   │   ├── ProxyAuthFormatParts.java     # Auth string format placeholders
│   │   └── Supplier.java                 # Supplier IDs (MergePool.INFATICA, MergePool.NETNUT)
│   │
│   ├── context/
│   │   ├── ChannelContext.java            # Per-channel context data (user, country, session)
│   │   └── ContextAttrKey.java           # Netty channel attribute keys
│   │
│   ├── filter/
│   │   ├── DomainFilter.java              # Domain blacklist filter
│   │   └── IPFilter.java                  # IP blacklist filter (placeholder)
│   │
│   ├── handler/
│   │   ├── ConnectionManageHandler.java   # Connection lifecycle management
│   │   ├── ForwardDownstreamChannelHandler.java  # Data forward: upstream → client
│   │   ├── ForwardUpstreamChannelHandler.java   # Data forward: client → upstream
│   │   ├── HttpConnectRequestHandler.java       # HTTP CONNECT tunnel setup before large-body aggregation
│   │   ├── HttpPasswordAuthRequestHandler.java  # HTTP basic auth handler
│   │   ├── HttpTrafficForwardHandler.java       # HTTP traffic relay handler
│   │   ├── IdleStateEventHandler.java           # Idle connection handler
│   │   ├── Socks5CommandRequestHandler.java     # SOCKS5 connect command handler
│   │   ├── Socks5InitialRequestHandler.java     # SOCKS5 protocol init handler
│   │   ├── Socks5NoAuthInitialRequestHandler.java  # SOCKS5 no-auth init (whitelist)
│   │   ├── Socks5PasswordAuthRequestHandler.java   # SOCKS5 password auth handler
│   │   └── TrafficStatsHandler.java              # Traffic statistics handler
│   │
│   ├── initializer/
│   │   ├── HttpWorkerChannelInitializer.java         # HTTP pipeline setup
│   │   ├── Socks5WorkerChannelInitializer.java       # SOCKS5 pipeline setup
│   │   └── WhiteListRoutingWorkerChannelInitializer.java  # WhiteList routing pipeline
│   │
│   ├── service/
│   │   ├── APIService.java              # Remote API client (fetch config, report traffic)
│   │   ├── ForwardAllocateService.java  # Upstream proxy allocation logic
│   │   └── ProxyService.java            # Main server bootstrap (SOCKS5/HTTP/WhiteList)
│   │
│   └── utils/
│       └── BitSetUtils.java             # BitSet utility for port range encoding
│
├── src/main/resources/
│   ├── Country-only-cn-private.mmdb     # MaxMind GeoIP database (CN private)
│   ├── areaMapping.csv                  # Country/state/city mapping data
│   ├── dynamicProxy-template.yaml       # Gateway startup config template for --init
│   ├── log4j2.properties                 # Log4j2 configuration
│   └── logback.xml                      # Logback configuration
│
├── docs/
│   ├── Architecture.md                   # Architecture documentation
│   └── Usage.md                          # Startup/config usage guide
│
├── pom.xml                              # Maven dependencies
├── README.md                            # Project readme
└── AGENTS.md                            # Agent guidelines (this file)
```

### Directory Overview

| Directory | Purpose |
|-----------|---------|
| `authenticator/` | User authentication and connection string parsing |
| `cache/` | Session and channel state management, traffic/request statistics |
| `config/` | Configuration data models and centralized config store |
| `constants/` | Enums and constants (protocols, suppliers, CLI args) |
| `context/` | Per-connection context data storage |
| `filter/` | Domain/IP blacklisting filters |
| `handler/` | Netty channel handlers for protocol processing and relay backpressure control |
| `initializer/` | Netty channel pipeline configuration |
| `service/` | Core business logic (proxy allocation, API client) |
| `utils/` | Utility functions |

### Key File Functions

**Entry Point**
- [GatewayMain.java](src/main/java/com/dk/ipproxy/dynamic/gateway/GatewayMain.java) - Application bootstrap, initializes config stores, starts ProxyService

**Core Services**
- [ProxyService.java](src/main/java/com/dk/ipproxy/dynamic/gateway/service/ProxyService.java) - Starts three server types: SOCKS5 (8088), HTTP (8089), WhiteList (20000-22000)
- [ForwardAllocateService.java](src/main/java/com/dk/ipproxy/dynamic/gateway/service/ForwardAllocateService.java) - Allocates upstream proxies based on user config and routing rules
- [APIService.java](src/main/java/com/dk/ipproxy/dynamic/gateway/service/APIService.java) - Remote API client for config sync and traffic reporting

**Authentication**
- [BasicAuthenticator.java](src/main/java/com/dk/ipproxy/dynamic/gateway/authenticator/BasicAuthenticator.java) - Validates credentials, parses structured username format

**Protocol Handlers**
- [Socks5InitialRequestHandler.java](src/main/java/com/dk/ipproxy/dynamic/gateway/handler/Socks5InitialRequestHandler.java) - SOCKS5 protocol handshake
- [Socks5PasswordAuthRequestHandler.java](src/main/java/com/dk/ipproxy/dynamic/gateway/handler/Socks5PasswordAuthRequestHandler.java) - SOCKS5 authentication
- [Socks5CommandRequestHandler.java](src/main/java/com/dk/ipproxy/dynamic/gateway/handler/Socks5CommandRequestHandler.java) - SOCKS5 connect command and upstream handshake failure diagnostics
- [HttpConnectRequestHandler.java](src/main/java/com/dk/ipproxy/dynamic/gateway/handler/HttpConnectRequestHandler.java) - HTTP CONNECT tunnel handler (pre-aggregator path)
- [HttpPasswordAuthRequestHandler.java](src/main/java/com/dk/ipproxy/dynamic/gateway/handler/HttpPasswordAuthRequestHandler.java) - HTTP Basic auth
- [HttpTrafficForwardHandler.java](src/main/java/com/dk/ipproxy/dynamic/gateway/handler/HttpTrafficForwardHandler.java) - HTTP traffic forwarding
- Relay forwarding uses `WRITE_BUFFER_WATER_MARK` + `AUTO_READ` based backpressure to pause reads when the peer channel is non-writable and resume when writable again
- HTTP pipeline handles `CONNECT` before the large `HttpObjectAggregator`, while normal HTTP requests continue to use the aggregator path
- Upstream client channels support dedicated `forwardReadTimeout` / `forwardWriteTimeout` and per-user concurrent forwarded connection limits
- SOCKS5, HTTP `CONNECT`, and normal HTTP handlers organize upstream channel setup as supplier DNS resolution, Bootstrap creation, pipeline initialization, and protocol-specific success/failure handling
- Supplier upstream proxy hostnames use JVM default DNS behavior by default; `disableSupplierDnsCache` makes domain suppliers resolve fresh before every forwarded request. This is separate from `dnsRemote`, which controls target-host DNS resolution.

**Channel Initializers**
- [Socks5WorkerChannelInitializer.java](src/main/java/com/dk/ipproxy/dynamic/gateway/initializer/Socks5WorkerChannelInitializer.java) - SOCKS5 channel pipeline
- [HttpWorkerChannelInitializer.java](src/main/java/com/dk/ipproxy/dynamic/gateway/initializer/HttpWorkerChannelInitializer.java) - HTTP channel pipeline
- [WhiteListRoutingWorkerChannelInitializer.java](src/main/java/com/dk/ipproxy/dynamic/gateway/initializer/WhiteListRoutingWorkerChannelInitializer.java) - Whitelist routing pipeline

**State Management**
- [SessionStateStore.java](src/main/java/com/dk/ipproxy/dynamic/gateway/cache/SessionStateStore.java) - Sticky session cache with TTL
- [ChannelStateStore.java](src/main/java/com/dk/ipproxy/dynamic/gateway/cache/ChannelStateStore.java) - Active connection tracking
- [RequestCounter.java](src/main/java/com/dk/ipproxy/dynamic/gateway/cache/RequestCounter.java) - Per-user request and success-count statistics
- [ResourceUsageReporter.java](src/main/java/com/dk/ipproxy/dynamic/gateway/cache/ResourceUsageReporter.java) - Periodic process-level resource usage logs (heap, direct memory, threads, inbound active channels) with human-readable memory units and grouped multi-line layout
- [GlobalConfigStore.java](src/main/java/com/dk/ipproxy/dynamic/gateway/config/GlobalConfigStore.java) - Centralized config with dynamic updates

## Project Dependencies

- Netty
- Spring Boot
- Java 11

## Documentation Guide
We adhere to a **Documentation-First** approach. The `docs/` directory serves as the single source of truth for all project designs, requirements, and technical guidelines.

### Documentation Update Policy
**CRITICAL: Always update Architecture.md and AGENTS.md and other document after every code change and necessary**
When making code changes, you MUST update the relevant documentation:
- Update `docs/Architecture.md` for user-facing changes (architecture, features)
- Keep documentation synchronized with the codebase at all times
- Ensure accuracy and timeliness of all documentation

### Documentation reference

- [architecture](docs/Architecture.md)
- [usage](docs/Usage.md)
- [performance-tuning](docs/PerformanceTuning.md)

## Development

### Code Guide
- You are an experienced Senior Java Developer.
- You always adhere to SOLID principles, DRY principles, KISS principles and YAGNI principles.
- add comments for the code when necessary.

## Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"
