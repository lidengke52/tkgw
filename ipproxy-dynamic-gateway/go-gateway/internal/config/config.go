package config

import (
	"bufio"
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	GatewayHostname                   string
	ListenHost                        string
	ListenSocks5Port                  int
	ListenHTTPPort                    int
	ListenThreads                     int
	WorkerThreads                     int
	BackLog                           int
	ConnectTimeout                    time.Duration
	ForwardConnectTimeout             time.Duration
	ReadTimeout                       time.Duration
	WriteTimeout                      time.Duration
	ForwardReadTimeout                time.Duration
	ForwardWriteTimeout               time.Duration
	ReadIdleTimeout                   time.Duration
	WriteIdleTimeout                  time.Duration
	ConfigUpdateInterval              time.Duration
	Endpoint                          string
	Token                             string
	LogDebug                          bool
	LocalConfigFile                   bool
	MaxSessionSize                    int
	TrafficReportInterval             time.Duration
	ResourceUsageLogInterval          time.Duration
	SessionExpireCheckInterval        time.Duration
	SessionAffinityMode               string
	SupplierRecoverAffectsExisting    bool
	PreferenceEndpointArea            string
	LocalAreaMappingFile              string
	WhiteListPortRangeStart           int
	WhiteListPortRangeEnd             int
	HTTPObjectAggregatorSize          int64
	HTTPRequestHeaderMaxSize          int64
	WriteBufferLowWaterMark           int
	WriteBufferHighWaterMark          int
	UserMaxConcurrentConns            int
	DNSRemote                         bool
	DisableSupplierDNSCache           bool
	SupplierHealthEnabled             bool
	SupplierHealthMinSamples          int
	SupplierHealthFailureRate         float64
	SupplierHealthConsecutiveFailures int
	SupplierHealthCooldown            time.Duration
	SupplierHealthWindow              time.Duration
}

func Default() Config {
	return Config{
		ListenHost:                        "0.0.0.0",
		ListenSocks5Port:                  8088,
		ListenHTTPPort:                    8089,
		ListenThreads:                     1,
		BackLog:                           128,
		ConnectTimeout:                    15 * time.Second,
		ForwardConnectTimeout:             1500 * time.Millisecond,
		ReadTimeout:                       30 * time.Second,
		WriteTimeout:                      30 * time.Second,
		ForwardReadTimeout:                30 * time.Second,
		ForwardWriteTimeout:               30 * time.Second,
		ConfigUpdateInterval:              60 * time.Second,
		MaxSessionSize:                    50000,
		TrafficReportInterval:             30 * time.Second,
		ResourceUsageLogInterval:          60 * time.Second,
		SessionExpireCheckInterval:        30 * time.Second,
		SessionAffinityMode:               "cache",
		SupplierRecoverAffectsExisting:    false,
		WhiteListPortRangeStart:           20000,
		WhiteListPortRangeEnd:             22000,
		HTTPObjectAggregatorSize:          5 * 1024 * 1024,
		HTTPRequestHeaderMaxSize:          32 * 1024,
		WriteBufferLowWaterMark:           16 * 1024,
		WriteBufferHighWaterMark:          32 * 1024,
		UserMaxConcurrentConns:            500,
		SupplierHealthEnabled:             true,
		SupplierHealthMinSamples:          20,
		SupplierHealthFailureRate:         0.50,
		SupplierHealthConsecutiveFailures: 5,
		SupplierHealthCooldown:            60 * time.Second,
		SupplierHealthWindow:              time.Minute,
	}
}

func Load(args []string) (Config, error) {
	cfg := Default()
	fs := flag.NewFlagSet("gateway", flag.ContinueOnError)
	configPath := fs.String("config", "", "config file path")
	fs.StringVar(&cfg.GatewayHostname, "gatewayHostname", cfg.GatewayHostname, "gateway hostname")
	fs.StringVar(&cfg.Endpoint, "endpoint", cfg.Endpoint, "api endpoint")
	fs.StringVar(&cfg.Token, "token", cfg.Token, "api token")
	fs.StringVar(&cfg.ListenHost, "listenHost", cfg.ListenHost, "listen host")
	fs.IntVar(&cfg.ListenSocks5Port, "listenSocks5Port", cfg.ListenSocks5Port, "socks5 listen port")
	fs.IntVar(&cfg.ListenHTTPPort, "listenHttpPort", cfg.ListenHTTPPort, "http listen port")
	fs.BoolVar(&cfg.LocalConfigFile, "localConfigFile", cfg.LocalConfigFile, "use local demo config")
	fs.BoolVar(&cfg.LogDebug, "logDebug", cfg.LogDebug, "debug logging")
	fs.BoolVar(&cfg.DNSRemote, "dnsRemote", cfg.DNSRemote, "resolve target DNS on upstream proxy")
	fs.BoolVar(&cfg.DisableSupplierDNSCache, "disableSupplierDnsCache", cfg.DisableSupplierDNSCache, "disable supplier DNS cache")
	if err := fs.Parse(args); err != nil {
		return cfg, err
	}
	if *configPath != "" {
		values, err := readSimpleYAML(*configPath)
		if err != nil {
			return cfg, err
		}
		applyValues(&cfg, values)
	}
	if cfg.GatewayHostname == "" {
		return cfg, fmt.Errorf("gatewayHostname is required")
	}
	if cfg.Endpoint == "" {
		return cfg, fmt.Errorf("endpoint is required")
	}
	if cfg.Token == "" {
		return cfg, fmt.Errorf("token is required")
	}
	if cfg.WhiteListPortRangeEnd < cfg.WhiteListPortRangeStart {
		return cfg, fmt.Errorf("invalid white list port range")
	}
	return cfg, nil
}

func readSimpleYAML(path string) (map[string]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	values := make(map[string]string)
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") || !strings.Contains(line, ":") {
			continue
		}
		parts := strings.SplitN(line, ":", 2)
		key := strings.TrimSpace(parts[0])
		value := strings.TrimSpace(parts[1])
		if idx := strings.Index(value, " #"); idx >= 0 {
			value = strings.TrimSpace(value[:idx])
		}
		value = strings.Trim(value, `"'`)
		values[key] = value
	}
	return values, scanner.Err()
}

func applyValues(c *Config, v map[string]string) {
	setString := func(key string, dst *string) {
		if val, ok := v[key]; ok {
			*dst = val
		}
	}
	setBool := func(key string, dst *bool) {
		if val, ok := v[key]; ok {
			*dst = strings.EqualFold(val, "true")
		}
	}
	setInt := func(key string, dst *int) {
		if val, ok := v[key]; ok {
			if n, err := strconv.Atoi(val); err == nil {
				*dst = n
			}
		}
	}
	setInt64 := func(key string, dst *int64) {
		if val, ok := v[key]; ok {
			if n, err := strconv.ParseInt(val, 10, 64); err == nil {
				*dst = n
			}
		}
	}
	setFloat := func(key string, dst *float64) {
		if val, ok := v[key]; ok {
			if n, err := strconv.ParseFloat(val, 64); err == nil {
				*dst = n
			}
		}
	}
	setMillis := func(key string, dst *time.Duration) {
		if val, ok := v[key]; ok {
			if n, err := strconv.Atoi(val); err == nil {
				*dst = time.Duration(n) * time.Millisecond
			}
		}
	}
	setSeconds := func(key string, dst *time.Duration) {
		if val, ok := v[key]; ok {
			if n, err := strconv.Atoi(val); err == nil {
				*dst = time.Duration(n) * time.Second
			}
		}
	}
	setString("gatewayHostname", &c.GatewayHostname)
	setString("listenHost", &c.ListenHost)
	setString("endpoint", &c.Endpoint)
	setString("token", &c.Token)
	setString("preferenceEndpointArea", &c.PreferenceEndpointArea)
	setString("localAreaMappingFile", &c.LocalAreaMappingFile)
	setString("sessionAffinityMode", &c.SessionAffinityMode)
	setInt("listenSocks5Port", &c.ListenSocks5Port)
	setInt("listenHttpPort", &c.ListenHTTPPort)
	setInt("listenThread", &c.ListenThreads)
	setInt("workerThreads", &c.WorkerThreads)
	setInt("backLog", &c.BackLog)
	setInt("maxSessionSize", &c.MaxSessionSize)
	setInt("whiteListPortRangeStart", &c.WhiteListPortRangeStart)
	setInt("whiteListPortRangeEnd", &c.WhiteListPortRangeEnd)
	setInt("userMaxConcurrentConnections", &c.UserMaxConcurrentConns)
	setInt("writeBufferLowWaterMark", &c.WriteBufferLowWaterMark)
	setInt("writeBufferHighWaterMark", &c.WriteBufferHighWaterMark)
	setInt64("httpRequestHeaderMaxSize", &c.HTTPRequestHeaderMaxSize)
	setInt64("httpObjectAggregatorSize", &c.HTTPObjectAggregatorSize)
	setMillis("connTimeout", &c.ConnectTimeout)
	setMillis("forwardConnTimeout", &c.ForwardConnectTimeout)
	setMillis("readTimeout", &c.ReadTimeout)
	setMillis("writeTimeout", &c.WriteTimeout)
	setMillis("forwardReadTimeout", &c.ForwardReadTimeout)
	setMillis("forwardWriteTimeout", &c.ForwardWriteTimeout)
	setMillis("readIdleTimeout", &c.ReadIdleTimeout)
	setMillis("writeIdleTimeout", &c.WriteIdleTimeout)
	setSeconds("configUpdateInterval", &c.ConfigUpdateInterval)
	setSeconds("trafficReportInterval", &c.TrafficReportInterval)
	setSeconds("resourceUsageLogIntervalSec", &c.ResourceUsageLogInterval)
	setSeconds("sessionExpireCheckInterval", &c.SessionExpireCheckInterval)
	setBool("localConfigFile", &c.LocalConfigFile)
	setBool("logDebug", &c.LogDebug)
	setBool("dnsRemote", &c.DNSRemote)
	setBool("disableSupplierDnsCache", &c.DisableSupplierDNSCache)
	setBool("supplierHealthEnabled", &c.SupplierHealthEnabled)
	setBool("supplierRecoverAffectsExistingSession", &c.SupplierRecoverAffectsExisting)
	setInt("supplierHealthMinSamples", &c.SupplierHealthMinSamples)
	setInt("supplierHealthConsecutiveFailures", &c.SupplierHealthConsecutiveFailures)
	setFloat("supplierHealthFailureRate", &c.SupplierHealthFailureRate)
	setSeconds("supplierHealthCooldownSec", &c.SupplierHealthCooldown)
	setSeconds("supplierHealthWindowSec", &c.SupplierHealthWindow)
}

func (c Config) Addr(port int) string {
	return fmt.Sprintf("%s:%d", c.ListenHost, port)
}
