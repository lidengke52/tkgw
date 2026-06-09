package com.dk.ipproxy.dynamic.gateway.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.dk.ipproxy.dynamic.gateway.constants.CmdArgs;
import com.dk.ipproxy.dynamic.gateway.constants.Supplier;
import com.dk.ipproxy.dynamic.gateway.service.APIService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 全局配置，包括静态配置以及动态配置
 * 动态配置定时更新
 */
public class GlobalConfigStore {
    private static final Logger logger = LoggerFactory.getLogger(GlobalConfigStore.class);
    private static final String DEFAULT_CONFIG_FILE_NAME = "dynamicProxy.yaml";
    private static final String TEMPLATE_CONFIG_FILE_NAME = "dynamicProxy-template.yaml";

    public static final GlobalConfigStore value;
    static {
        value = new GlobalConfigStore();
    }

    /** 动态网关配置参数 **/
    private GatewayConfig gatewayConfig;
    /** 供应商配置，如账密、连接串 **/
    private Map<Integer, SupplierConfig> supplierConfigMap = new ConcurrentHashMap<>();
    /** 用户配置，如账密、支持供应商 **/
    private Map<String, UserConfig> userConfigMap = new ConcurrentHashMap<>();
    private Map<Integer, UserConfig> userConfigMapForUid = new ConcurrentHashMap<>();
    /** 融合池地区映射配置 **/
    private Map<Integer, AreaMappingConfig> areaMappingConfigMap = new ConcurrentHashMap<>();
    /** 白名单配置 */
    private Map<String, WhiteListConfig> whiteListConfigMap = new ConcurrentHashMap<>();

    /**
     * 静态配置
     */
    public boolean setupGatewayStaticConfig(String[] cmdArgs) {
        CommandLineParser cmdParser = new DefaultParser();
        Options cmdOptions = createCliOption();
        try {
            CommandLine cmd = cmdParser.parse(cmdOptions, cmdArgs);
            if (cmd.hasOption(CmdArgs.INIT)) {
                return handleInitCommand();
            }
            if (cmd.hasOption(CmdArgs.CONFIG)) {
                return setupGatewayStaticConfigFromYaml(cmd.getOptionValue(CmdArgs.CONFIG));
            }
            return setupGatewayStaticConfigFromCmd(cmd);
        } catch (Exception e) {
            logger.error("failed to parse cmd args", e);
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("Dynamic Gateway", cmdOptions);
            return false;
        }
    }

    /**
     * 定义命令行参数
     */
    private Options createCliOption() {
        Options options = new Options();
        options.addOption(null, CmdArgs.INIT, false, "initialize dynamicProxy.yaml in current directory");
        options.addOption(null, CmdArgs.CONFIG, true, "gateway config yaml file path");
        options.addOption(null, CmdArgs.GATEWAY_HOSTNAME, true, "gateway hostname domain");
        options.addOption(null, CmdArgs.ENDPOINT, true, "api endpoint");
        options.addOption(null, CmdArgs.API_TOKEN, true, "api token");
        options.addOption(null, CmdArgs.LISTEN_HOST, true, "listen host");
        options.addOption(null, CmdArgs.LISTEN_SOCKS5_PORT, true, "listen socks5 port");
        options.addOption(null, CmdArgs.LISTEN_HTTP_PORT, true, "listen http port");
        options.addOption(null, CmdArgs.LISTEN_THREAD, true, "listen thread");
        options.addOption(null, CmdArgs.FORWARD_CONNECT_TIMEOUT, true, "forward connection timeout(millis)");
        options.addOption(null, CmdArgs.CONN_TIMEOUT, true, "gateway connect timeout(millis)");
        options.addOption(null, CmdArgs.READ_TIMEOUT, true, "gateway read timeout(millis)");
        options.addOption(null, CmdArgs.WRITE_TIMEOUT, true, "gateway write timeout(millis)");
        options.addOption(null, CmdArgs.FORWARD_READ_TIMEOUT, true, "forward read timeout(millis)");
        options.addOption(null, CmdArgs.FORWARD_WRITE_TIMEOUT, true, "forward write timeout(millis)");
        options.addOption(null, CmdArgs.CONFIG_UPDATE_INTERVAL, true, "config update interval(secs)");
        options.addOption(null, CmdArgs.LOCAL_CONFIG_FILE, false, "read local config file");
        options.addOption(null, CmdArgs.LOG_DEBUG, false, "enable debug log");
        options.addOption(null, CmdArgs.MAX_SESSION_SIZE, true, "maximum size of session cache");
        options.addOption(null, CmdArgs.TRAFFIC_REPORT_INTERVAL, true, "traffic report interval(secs)");
        options.addOption(null, CmdArgs.RESOURCE_USAGE_LOG_INTERVAL, true, "resource usage log interval(secs), 0 to disable");
        options.addOption(null, CmdArgs.SESSION_EXPIRE_CHECK_INTERVAL, true, "session check interval(secs)");
        options.addOption(null, CmdArgs.PREFERENCE_ENDPOINT_AREA, true, "preference endpoint area");
        options.addOption(null, CmdArgs.LOCAL_AREA_MAPPING_FILE, true, "local area mapping file");
        options.addOption(null, CmdArgs.WHITE_LIST_PORT_RANGE_START, true, "white list port range start");
        options.addOption(null, CmdArgs.WHITE_LIST_PORT_RANGE_END, true, "white list port range end");
        options.addOption(null, CmdArgs.WORKER_THREADS, true, "worker threads for NIO event loop");
        options.addOption(null, CmdArgs.BACK_LOG, true, "TCP accept backlog (SO_BACKLOG)");
        options.addOption(null, CmdArgs.HTTP_OBJECT_AGGREGATOR_SIZE, true, "http object aggregator max size (bytes)");
        options.addOption(null, CmdArgs.HTTP_REQUEST_HEADER_MAX_SIZE, true, "http request header max size (bytes)");
        options.addOption(null, CmdArgs.READ_IDLE_TIMEOUT, true, "read idle timeout (millis)");
        options.addOption(null, CmdArgs.WRITE_IDLE_TIMEOUT, true, "write idle timeout (millis)");
        options.addOption(null, CmdArgs.USER_MAX_CONCURRENT_CONNECTIONS, true, "per-user maximum concurrent forwarded connections");
        options.addOption(null, CmdArgs.DNS_REMOTE, false, "resolve target host on upstream proxy (remote DNS, like curl --socks5-hostname)");
        options.addOption(null, CmdArgs.DISABLE_SUPPLIER_DNS_CACHE, false, "disable JVM DNS cache for upstream supplier hostnames");
        return options;
    }

    private boolean handleInitCommand() {
        Path configFilePath = Paths.get(System.getProperty("user.dir"), DEFAULT_CONFIG_FILE_NAME).toAbsolutePath().normalize();
        try {
            if (Files.exists(configFilePath)) {
                logger.info("config file already exists: {}", configFilePath);
            } else {
                ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                try (InputStream templateInputStream = classLoader.getResourceAsStream(TEMPLATE_CONFIG_FILE_NAME)) {
                    if (templateInputStream == null) {
                        logger.error("config template not found in classpath: {}", TEMPLATE_CONFIG_FILE_NAME);
                        return false;
                    }
                    Files.copy(templateInputStream, configFilePath, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("config file initialized: {}", configFilePath);
                }
            }
            logger.info("config file path: {}", configFilePath);
            logger.info("startup command: java -jar gateway.jar --config \"{}\"", configFilePath);
            logger.info("init finished, gateway will not start in init mode");
            return false;
        } catch (Exception e) {
            logger.error("failed to initialize config file", e);
            return false;
        }
    }

    private boolean setupGatewayStaticConfigFromYaml(String configFilePath) {
        if (StringUtils.isBlank(configFilePath)) {
            logger.error("config file path is required when --config is specified");
            return false;
        }

        Path configPath = Paths.get(configFilePath).toAbsolutePath().normalize();
        if (!Files.exists(configPath)) {
            logger.error("config file not exists: {}", configPath);
            return false;
        }

        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> yamlConfig;
            try (InputStream yamlInputStream = Files.newInputStream(configPath)) {
                yamlConfig = yamlMapper.readValue(yamlInputStream, new TypeReference<Map<String, Object>>() {});
            }
            if (yamlConfig == null) {
                yamlConfig = Collections.emptyMap();
            }
            return setupGatewayConfig(
                    getStringValue(yamlConfig, CmdArgs.GATEWAY_HOSTNAME, ""),
                    getStringValue(yamlConfig, CmdArgs.LISTEN_HOST, "0.0.0.0"),
                    getIntValue(yamlConfig, CmdArgs.LISTEN_SOCKS5_PORT, 8088),
                    getIntValue(yamlConfig, CmdArgs.LISTEN_HTTP_PORT, 8089),
                    getIntValue(yamlConfig, CmdArgs.LISTEN_THREAD, 1),
                    getIntValue(yamlConfig, CmdArgs.FORWARD_CONNECT_TIMEOUT, 1500),
                    getIntValue(yamlConfig, CmdArgs.CONN_TIMEOUT, 15000),
                    getIntValue(yamlConfig, CmdArgs.READ_TIMEOUT, 30000),
                    getIntValue(yamlConfig, CmdArgs.WRITE_TIMEOUT, 30000),
                    getIntValue(yamlConfig, CmdArgs.FORWARD_READ_TIMEOUT, 30000),
                    getIntValue(yamlConfig, CmdArgs.FORWARD_WRITE_TIMEOUT, 30000),
                    getIntValue(yamlConfig, CmdArgs.CONFIG_UPDATE_INTERVAL, 60),
                    getStringValue(yamlConfig, CmdArgs.ENDPOINT, ""),
                    getStringValue(yamlConfig, CmdArgs.API_TOKEN, ""),
                    getBooleanValue(yamlConfig, CmdArgs.LOCAL_CONFIG_FILE, false),
                    getBooleanValue(yamlConfig, CmdArgs.LOG_DEBUG, false) ? "DEBUG" : "INFO",
                    getIntValue(yamlConfig, CmdArgs.MAX_SESSION_SIZE, 50000),
                    getIntValue(yamlConfig, CmdArgs.TRAFFIC_REPORT_INTERVAL, 30),
                    getIntValue(yamlConfig, CmdArgs.RESOURCE_USAGE_LOG_INTERVAL, 60),
                    getIntValue(yamlConfig, CmdArgs.SESSION_EXPIRE_CHECK_INTERVAL, 30),
                    getStringValue(yamlConfig, CmdArgs.PREFERENCE_ENDPOINT_AREA, ""),
                    getStringValue(yamlConfig, CmdArgs.LOCAL_AREA_MAPPING_FILE, ""),
                    getIntValue(yamlConfig, CmdArgs.WHITE_LIST_PORT_RANGE_START, 20000),
                    getIntValue(yamlConfig, CmdArgs.WHITE_LIST_PORT_RANGE_END, 22000),
                    getIntValue(yamlConfig, CmdArgs.WORKER_THREADS, 0),
                    getIntValue(yamlConfig, CmdArgs.BACK_LOG, 128),
                    getIntValue(yamlConfig, CmdArgs.HTTP_OBJECT_AGGREGATOR_SIZE, 5 * 1024 * 1024),
                    getIntValue(yamlConfig, CmdArgs.HTTP_REQUEST_HEADER_MAX_SIZE, 32 * 1024),
                    getIntValue(yamlConfig, CmdArgs.WRITE_BUFFER_LOW_WATER_MARK, 16 * 1024),
                    getIntValue(yamlConfig, CmdArgs.WRITE_BUFFER_HIGH_WATER_MARK, 32 * 1024),
                    getIntValue(yamlConfig, CmdArgs.READ_IDLE_TIMEOUT, 0),
                    getIntValue(yamlConfig, CmdArgs.WRITE_IDLE_TIMEOUT, 0),
                    getIntValue(yamlConfig, CmdArgs.USER_MAX_CONCURRENT_CONNECTIONS, 500),
                    getBooleanValue(yamlConfig, CmdArgs.DNS_REMOTE, false),
                    getBooleanValue(yamlConfig, CmdArgs.DISABLE_SUPPLIER_DNS_CACHE, false)
            );
        } catch (Exception e) {
            logger.error("failed to read yaml config file: {}", configPath, e);
            return false;
        }
    }

    private boolean setupGatewayStaticConfigFromCmd(CommandLine cmd) {
        String gatewayHostname;
        if (!cmd.hasOption(CmdArgs.GATEWAY_HOSTNAME)) {
            logger.error("gateway hostname is required");
            return false;
        }
        gatewayHostname = cmd.getOptionValue(CmdArgs.GATEWAY_HOSTNAME);

        String endpoint;
        if (!cmd.hasOption(CmdArgs.ENDPOINT)) {
            logger.error("endpoint is required");
            return false;
        }
        endpoint = cmd.getOptionValue(CmdArgs.ENDPOINT);

        String token;
        if (!cmd.hasOption(CmdArgs.API_TOKEN)) {
            logger.error("token is required");
            return false;
        }
        token = cmd.getOptionValue(CmdArgs.API_TOKEN);

        return setupGatewayConfig(
                gatewayHostname,
                cmd.hasOption(CmdArgs.LISTEN_HOST) ? cmd.getOptionValue(CmdArgs.LISTEN_HOST) : "0.0.0.0",
                cmd.hasOption(CmdArgs.LISTEN_SOCKS5_PORT) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.LISTEN_SOCKS5_PORT)) : 8088,
                cmd.hasOption(CmdArgs.LISTEN_HTTP_PORT) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.LISTEN_HTTP_PORT)) : 8089,
                cmd.hasOption(CmdArgs.LISTEN_THREAD) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.LISTEN_THREAD)) : 1,
                cmd.hasOption(CmdArgs.FORWARD_CONNECT_TIMEOUT) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.FORWARD_CONNECT_TIMEOUT)) : 1500,
                cmd.hasOption(CmdArgs.CONN_TIMEOUT) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.CONN_TIMEOUT)) : 15000,
                cmd.hasOption(CmdArgs.READ_TIMEOUT) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.READ_TIMEOUT)) : 30000,
                cmd.hasOption(CmdArgs.WRITE_TIMEOUT) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.WRITE_TIMEOUT)) : 30000,
                cmd.hasOption(CmdArgs.FORWARD_READ_TIMEOUT) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.FORWARD_READ_TIMEOUT)) : 30000,
                cmd.hasOption(CmdArgs.FORWARD_WRITE_TIMEOUT) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.FORWARD_WRITE_TIMEOUT)) : 30000,
                cmd.hasOption(CmdArgs.CONFIG_UPDATE_INTERVAL) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.CONFIG_UPDATE_INTERVAL)) : 60,
                endpoint,
                token,
                cmd.hasOption(CmdArgs.LOCAL_CONFIG_FILE),
                cmd.hasOption(CmdArgs.LOG_DEBUG) ? "DEBUG" : "INFO",
                cmd.hasOption(CmdArgs.MAX_SESSION_SIZE) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.MAX_SESSION_SIZE)) : 50000,
                cmd.hasOption(CmdArgs.TRAFFIC_REPORT_INTERVAL) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.TRAFFIC_REPORT_INTERVAL)) : 30,
                cmd.hasOption(CmdArgs.RESOURCE_USAGE_LOG_INTERVAL) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.RESOURCE_USAGE_LOG_INTERVAL)) : 60,
                cmd.hasOption(CmdArgs.SESSION_EXPIRE_CHECK_INTERVAL) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.SESSION_EXPIRE_CHECK_INTERVAL)) : 30,
                cmd.hasOption(CmdArgs.PREFERENCE_ENDPOINT_AREA) ? cmd.getOptionValue(CmdArgs.PREFERENCE_ENDPOINT_AREA) : "",
                cmd.hasOption(CmdArgs.LOCAL_AREA_MAPPING_FILE) ? cmd.getOptionValue(CmdArgs.LOCAL_AREA_MAPPING_FILE) : "",
                cmd.hasOption(CmdArgs.WHITE_LIST_PORT_RANGE_START) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.WHITE_LIST_PORT_RANGE_START)) : 20000,
                cmd.hasOption(CmdArgs.WHITE_LIST_PORT_RANGE_END) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.WHITE_LIST_PORT_RANGE_END)) : 22000,
                cmd.hasOption(CmdArgs.WORKER_THREADS) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.WORKER_THREADS)) : 0,
                cmd.hasOption(CmdArgs.BACK_LOG) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.BACK_LOG)) : 128,
                cmd.hasOption(CmdArgs.HTTP_OBJECT_AGGREGATOR_SIZE) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.HTTP_OBJECT_AGGREGATOR_SIZE)) : 5 * 1024 * 1024,
                cmd.hasOption(CmdArgs.HTTP_REQUEST_HEADER_MAX_SIZE) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.HTTP_REQUEST_HEADER_MAX_SIZE)) : 32 * 1024,
                cmd.hasOption(CmdArgs.WRITE_BUFFER_LOW_WATER_MARK) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.WRITE_BUFFER_LOW_WATER_MARK)) : 16 * 1024,
                cmd.hasOption(CmdArgs.WRITE_BUFFER_HIGH_WATER_MARK) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.WRITE_BUFFER_HIGH_WATER_MARK)) : 32 * 1024,
                cmd.hasOption(CmdArgs.READ_IDLE_TIMEOUT) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.READ_IDLE_TIMEOUT)) : 0,
                cmd.hasOption(CmdArgs.WRITE_IDLE_TIMEOUT) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.WRITE_IDLE_TIMEOUT)) : 0,
                cmd.hasOption(CmdArgs.USER_MAX_CONCURRENT_CONNECTIONS) ? Integer.parseInt(cmd.getOptionValue(CmdArgs.USER_MAX_CONCURRENT_CONNECTIONS)) : 500,
                cmd.hasOption(CmdArgs.DNS_REMOTE),
                cmd.hasOption(CmdArgs.DISABLE_SUPPLIER_DNS_CACHE)
        );
    }

    private boolean setupGatewayConfig(
            String gatewayHostname,
            String listenHost,
            int listenSocks5Port,
            int listenHttpPort,
            int listenThread,
            int forwardConnTimeoutMillis,
            int connectTimeoutMillis,
            int readTimeoutMillis,
            int writeTimeoutMillis,
            int forwardReadTimeoutMillis,
            int forwardWriteTimeoutMillis,
            int configUpdateIntervalSec,
            String endpoint,
            String token,
            boolean readLocalConfigFile,
            String logLevel,
            int maxSessionSize,
            int trafficReportIntervalSec,
            int resourceUsageLogIntervalSec,
            int sessionExpireCheckIntervalSec,
            String preferenceEndpointArea,
            String localAreaMappingFile,
            int whiteListPortRangeStart,
            int whiteListPortRangeEnd,
            int workerThreads,
            int backLog,
            int httpObjectAggregatorSize,
            int httpRequestHeaderMaxSize,
            int writeBufferLowWaterMark,
            int writeBufferHighWaterMark,
            int readIdleTimeout,
            int writeIdleTimeout,
            int userMaxConcurrentConnections,
            boolean dnsRemote,
            boolean disableSupplierDnsCache) {
        if (StringUtils.isBlank(gatewayHostname)) {
            logger.error("gateway hostname is required");
            return false;
        }
        if (StringUtils.isBlank(endpoint)) {
            logger.error("endpoint is required");
            return false;
        }
        if (StringUtils.isBlank(token)) {
            logger.error("token is required");
            return false;
        }
        if (writeBufferLowWaterMark < 0 || writeBufferHighWaterMark <= 0 || writeBufferLowWaterMark >= writeBufferHighWaterMark) {
            logger.error("invalid write buffer water mark config, lowWaterMark: {}, highWaterMark: {}", writeBufferLowWaterMark, writeBufferHighWaterMark);
            return false;
        }
        if ((forwardReadTimeoutMillis < 0) || (forwardWriteTimeoutMillis < 0)) {
            logger.error("invalid forward timeout config, forwardReadTimeout: {}, forwardWriteTimeout: {}", forwardReadTimeoutMillis, forwardWriteTimeoutMillis);
            return false;
        }
        if (httpRequestHeaderMaxSize <= 0) {
            logger.error("invalid http request header max size: {}", httpRequestHeaderMaxSize);
            return false;
        }
        if (userMaxConcurrentConnections < 0) {
            logger.error("invalid user max concurrent connections: {}", userMaxConcurrentConnections);
            return false;
        }
        if (resourceUsageLogIntervalSec < 0) {
            logger.error("invalid resource usage log interval: {}", resourceUsageLogIntervalSec);
            return false;
        }
        if (backLog <= 0) {
            logger.error("invalid backLog: {}", backLog);
            return false;
        }

        logger.info("loading gateway configuration...");
        this.gatewayConfig = new GatewayConfig();
        gatewayConfig.setGatewayHostname(gatewayHostname);
        gatewayConfig.setListenHost(listenHost);
        gatewayConfig.setListenSocks5Port(listenSocks5Port);
        gatewayConfig.setListenHttpPort(listenHttpPort);
        gatewayConfig.setListenThreads(listenThread);
        gatewayConfig.setWorkerThreads(workerThreads);
        gatewayConfig.setBackLog(backLog);
        gatewayConfig.setHttpObjectAggregatorSize(httpObjectAggregatorSize);
        gatewayConfig.setReadIdleTimeoutMillis(readIdleTimeout);
        gatewayConfig.setWriteIdleTimeoutMillis(writeIdleTimeout);
        gatewayConfig.setForwardConnectTimeoutMillis(forwardConnTimeoutMillis);
        gatewayConfig.setConnectTimeoutMillis(connectTimeoutMillis);
        gatewayConfig.setReadTimeoutMillis(readTimeoutMillis);
        gatewayConfig.setWriteTimeoutMillis(writeTimeoutMillis);
        gatewayConfig.setForwardReadTimeoutMillis(forwardReadTimeoutMillis);
        gatewayConfig.setForwardWriteTimeoutMillis(forwardWriteTimeoutMillis);
        gatewayConfig.setConfigUpdateIntervalSec(configUpdateIntervalSec);
        gatewayConfig.setApiEndpoint(endpoint);
        gatewayConfig.setApiToken(token);
        gatewayConfig.setReadLocalConfigFile(readLocalConfigFile);
        gatewayConfig.setLoggerLevel(logLevel);
        gatewayConfig.setMaxSessionSize(maxSessionSize);
        gatewayConfig.setTrafficReportIntervalSec(trafficReportIntervalSec);
        gatewayConfig.setResourceUsageLogIntervalSec(resourceUsageLogIntervalSec);
        gatewayConfig.setSessionExpireCheckIntervalSec(sessionExpireCheckIntervalSec);
        gatewayConfig.setPreferenceEndpointArea(preferenceEndpointArea);
        gatewayConfig.setLocalAreaMappingFile(localAreaMappingFile);
        gatewayConfig.setWhiteListPortRangeStart(whiteListPortRangeStart);
        gatewayConfig.setWhiteListPortRangeEnd(whiteListPortRangeEnd);
        gatewayConfig.setHttpRequestHeaderMaxSize(httpRequestHeaderMaxSize);
        gatewayConfig.setWriteBufferLowWaterMark(writeBufferLowWaterMark);
        gatewayConfig.setWriteBufferHighWaterMark(writeBufferHighWaterMark);
        gatewayConfig.setUserMaxConcurrentConnections(userMaxConcurrentConnections);
        gatewayConfig.setDnsRemote(dnsRemote);
        gatewayConfig.setDisableSupplierDnsCache(disableSupplierDnsCache);
        logger.info("target DNS resolution mode: {}", dnsRemote ? "remote (upstream)" : "local (gateway)");
        logger.info("supplier DNS cache: {}", disableSupplierDnsCache ? "disabled" : "JVM default");
        logger.info("finish setup gateway configuration");
        logger.info("config: {}", gatewayConfig);
        return true;
    }

    private String getStringValue(Map<String, Object> source, String key, String defaultValue) {
        Object value = source.get(key);
        if (value == null) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    private int getIntValue(Map<String, Object> source, String key, int defaultValue) {
        Object value = source.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private boolean getBooleanValue(Map<String, Object> source, String key, boolean defaultValue) {
        Object value = source.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 动态配置
     */
    public void setupGatewayDynamicConfig() throws Exception {
        if (gatewayConfig == null) {
            logger.error("gateway static config is null");
            throw new Exception("gateway static config not init yet");
        }
        // 初始化API服务
        APIService apiService = new APIService(
                gatewayConfig.getApiEndpoint(),
                gatewayConfig.getApiToken(),
                gatewayConfig.isReadLocalConfigFile(),
                gatewayConfig.getGatewayHostname(),
                gatewayConfig.whiteListPortRangeStart);
        // 初始化更新服务，并首次执行
        this.supplierConfigMap = new ConcurrentHashMap<>();
        this.userConfigMap = new ConcurrentHashMap<>();
        this.userConfigMapForUid = new ConcurrentHashMap<>();
        this.areaMappingConfigMap = new ConcurrentHashMap<>();
        this.whiteListConfigMap = new ConcurrentHashMap<>();
        Runnable scheduleFetchJob = new ScheduleUpdateDynamicConfig(
                apiService,
                supplierConfigMap,
                userConfigMap,
                userConfigMapForUid,
                areaMappingConfigMap,
                whiteListConfigMap,
                gatewayConfig.preferenceEndpointArea);
        scheduleFetchJob.run();
        // 启动定时任务
        ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(1);
        executorService.scheduleAtFixedRate(scheduleFetchJob, gatewayConfig.getConfigUpdateIntervalSec(), gatewayConfig.getConfigUpdateIntervalSec(), TimeUnit.SECONDS);
        logger.info("finish setup dynamic config loader");
    }

    /**
     * 设置日志级别
     */
    public void setLoggerLevel() {
        String logLevel = this.gatewayConfig.getLoggerLevel();
        if ("DEBUG".equalsIgnoreCase(logLevel)) {
            // set logger level for logback
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.getLogger("com.dk.ipproxy.dynamic.gateway").setLevel(Level.DEBUG);
            logger.info("set log level to DEBUG");
        }
        logger.info("finish setup logger level");
    }

    public GatewayConfig getGatewayConfig() {
        return this.gatewayConfig;
    }

    public Map<String, UserConfig> getUserConfigMap() {
        return this.userConfigMap;
    }

    public Map<Integer, UserConfig> getUserConfigMapForUid() {
        return this.userConfigMapForUid;
    }

    public Map<String, WhiteListConfig> getWhiteListConfigMap() {
        return this.whiteListConfigMap;
    }

    public Map<Integer, SupplierConfig> getSupplierConfigMap() {
        return this.supplierConfigMap;
    }

    public Map<Integer, AreaMappingConfig> getAreaMappingConfigMap() {
        return this.areaMappingConfigMap;
    }

    /**
     * 定时配置更新任务
     */
    private static class ScheduleUpdateDynamicConfig implements Runnable {
        private final APIService apiService;

        /** 供应商配置，如账密、连接串 **/
        private final Map<Integer, SupplierConfig> supplierConfigMap;
        /** 用户配置，如账密、支持供应商 **/
        private final Map<String, UserConfig> userConfigMap;
        private final Map<Integer, UserConfig> userConfigMapForUid;
        /** 融合池地区映射配置 **/
        private final Map<Integer, AreaMappingConfig> areaMappingConfigMap;
        /** 白名单配置 **/
        private final Map<String, WhiteListConfig> whiteListConfigMap;
        private final String preferenceEndpointArea;

        public ScheduleUpdateDynamicConfig(
                APIService apiService,
                Map<Integer, SupplierConfig> supplierConfigMap,
                Map<String, UserConfig> userConfigMap,
                Map<Integer, UserConfig> userConfigMapForUid,
                Map<Integer, AreaMappingConfig> areaMappingConfigMap,
                Map<String, WhiteListConfig> whiteListConfigMap,
                String preferenceEndpointArea) {
            this.apiService = apiService;
            this.supplierConfigMap = supplierConfigMap;
            this.userConfigMap = userConfigMap;
            this.userConfigMapForUid = userConfigMapForUid;
            this.areaMappingConfigMap = areaMappingConfigMap;
            this.whiteListConfigMap = whiteListConfigMap;
            this.preferenceEndpointArea = preferenceEndpointArea;
        }

        private void setupSupplierPreferenceEndpoint(SupplierConfig supplierConfig) {
            if (supplierConfig == null) {
                return;
            }
            List<String> preferenceEndpointList = new ArrayList<>();
            for (String endpoint : supplierConfig.getAvailableGateway()) {
                String[] endpointParts = endpoint.split(":");
                if (endpointParts.length == 2) {
                    // 默认没有设置地区的
                    preferenceEndpointList.add(endpoint);
                } else if (endpointParts.length == 3) {
                    // 带地区的则匹配
                    if (StringUtils.isEmpty(preferenceEndpointArea) || endpoint.startsWith(preferenceEndpointArea)) {
                        String newEndpoint = String.format("%s:%s", endpointParts[1], endpointParts[2]);
                        preferenceEndpointList.add(newEndpoint);
                    }
                }
            }
            supplierConfig.setAvailableGateway(preferenceEndpointList);
            logger.info("set supplier preference available gateway, gatewayId: {}, new endpoint list: {}", supplierConfig.getSupplierId(), preferenceEndpointList);
        }

        @Override
        public void run() {
            try {
                logger.info("start update dynamic config...");

                // 获取更新供应商配置
                List<SupplierConfig> supplierConfigList = apiService.getSupplierConfigLIst();
                if (supplierConfigList != null && !supplierConfigList.isEmpty()) {
                    Set<Integer> currentAvailableSupplierIdSet = new HashSet<>();
                    for (SupplierConfig supplierConfig : supplierConfigList) {
                        int supplierId = supplierConfig.getSupplierId();
                        if (!supplierConfigMap.containsKey(supplierId)) {
                            logger.info("add new supplier config, supplierId: {}, type: {}",
                                    supplierConfig.getSupplierId(),
                                    Supplier.MergePool.ALL.contains(supplierConfig.getSupplierId()) ? "MergePool" : "NormalPool");
                        }
                        // 设置偏好endpoint
                        setupSupplierPreferenceEndpoint(supplierConfig);

                        // 更新
                        supplierConfigMap.put(supplierId, supplierConfig);
                        currentAvailableSupplierIdSet.add(supplierId);
                    }

                    for (Integer supplierId : supplierConfigMap.keySet()) {
                        if (!currentAvailableSupplierIdSet.contains(supplierId)) {
                            supplierConfigMap.remove(supplierId);
                            logger.info("remove supplier config, because not available remote");
                        }
                    }
                }

                // 获取更新用户配置
                List<UserConfig> userConfigList = apiService.getUserConfigList();
                if (userConfigList != null && !userConfigList.isEmpty()) {
                    Set<String> currentAvailableUserSet = new HashSet<>();
                    for (UserConfig userConfig : userConfigList) {
                        String authUser = userConfig.getAuthUser();
                        if (!userConfigMap.containsKey(authUser)) {
                            logger.info("add new user config, userId: {}", userConfig.getUserId());
                        }
                        userConfigMap.put(authUser, userConfig);
                        userConfigMapForUid.put(userConfig.getUserId(), userConfig);
                        currentAvailableUserSet.add(authUser);
                    }

                    for (String authUser : userConfigMap.keySet()) {
                        if (!currentAvailableUserSet.contains(authUser)) {
                            userConfigMap.remove(authUser);
                            userConfigMapForUid.remove(userConfigMap.get(authUser).getUserId());
                            logger.info("remove user config, because not available remote");
                        }
                    }
                }

                // 获取动态白名单列表
                List<WhiteListConfig> whiteListConfigList = apiService.getWhiteListConfigList();
                if (whiteListConfigList != null && !whiteListConfigList.isEmpty()) {
                    for (WhiteListConfig whiteListConfig : whiteListConfigList) {
                        String clientIp = whiteListConfig.getIp();
                        if (!whiteListConfigMap.containsKey(clientIp)) {
                            logger.info("add new white list config, ip: {}", whiteListConfig.getIp());
                        }
                        whiteListConfigMap.put(clientIp, whiteListConfig);
                    }

                    for (String ip : whiteListConfigMap.keySet()) {
                        if (!whiteListConfigList.contains(whiteListConfigMap.get(ip))) {
                            whiteListConfigMap.remove(ip);
                            logger.info("remove white list config, because not available remote, ip: {}", ip);
                        }
                    }
                }

                // 更新融合池地区映射
                List<AreaMappingConfig> areaMappingConfigList = apiService.getAreaMappingConfigList();
                for (AreaMappingConfig areaMappingConfig : areaMappingConfigList) {
                    this.areaMappingConfigMap.put(areaMappingConfig.supplierId, areaMappingConfig);
                }

                logger.info("finish update dynamic config...");
            } catch (Exception e) {
                logger.error("update dynamic config failed", e);
            }
        }
    }
}
