package com.dk.ipproxy.dynamic.gateway.config;

public class GatewayConfig {
    private String gatewayHostname;
    private String listenHost;
    private int listenSocks5Port;
    private int listenHttpPort;
    private int listenThreads;
    private int workerThreads;
    private int backLog;
    private int connectTimeoutMillis;
    private int forwardConnectTimeoutMillis;
    private int readTimeoutMillis;
    private int writeTimeoutMillis;
    private int forwardReadTimeoutMillis;
    private int forwardWriteTimeoutMillis;
    private int readIdleTimeoutMillis;
    private int writeIdleTimeoutMillis;
    private int httpObjectAggregatorSize;
    private int httpRequestHeaderMaxSize;
    private int writeBufferLowWaterMark;
    private int writeBufferHighWaterMark;
    private int userMaxConcurrentConnections;
    private String apiEndpoint;
    private String apiToken;
    private int configUpdateIntervalSec;
    public boolean readLocalConfigFile;
    private String loggerLevel;
    public int maxSessionSize;
    public int trafficReportIntervalSec;
    private int resourceUsageLogIntervalSec;
    public int sessionExpireCheckIntervalSec;
    public String preferenceEndpointArea;
    public String localAreaMappingFile;
    public int whiteListPortRangeStart;
    public int whiteListPortRangeEnd;
    private boolean dnsRemote;
    private boolean disableSupplierDnsCache;

    public int getWhiteListPortRangeStart() {
        return whiteListPortRangeStart;
    }

    public void setWhiteListPortRangeStart(int whiteListPortRangeStart) {
        this.whiteListPortRangeStart = whiteListPortRangeStart;
    }

    public int getWhiteListPortRangeEnd() {
        return whiteListPortRangeEnd;
    }

    public void setWhiteListPortRangeEnd(int whiteListPortRangeEnd) {
        this.whiteListPortRangeEnd = whiteListPortRangeEnd;
    }

    public String getLocalAreaMappingFile() {
        return localAreaMappingFile;
    }

    public void setLocalAreaMappingFile(String localAreaMappingFile) {
        this.localAreaMappingFile = localAreaMappingFile;
    }

    public int getListenHttpPort() {
        return listenHttpPort;
    }

    public void setListenHttpPort(int listenHttpPort) {
        this.listenHttpPort = listenHttpPort;
    }

    public String getPreferenceEndpointArea() {
        return preferenceEndpointArea;
    }

    public void setPreferenceEndpointArea(String preferenceEndpointArea) {
        this.preferenceEndpointArea = preferenceEndpointArea;
    }

    public int getSessionExpireCheckIntervalSec() {
        return sessionExpireCheckIntervalSec;
    }

    public void setSessionExpireCheckIntervalSec(int sessionExpireCheckIntervalSec) {
        this.sessionExpireCheckIntervalSec = sessionExpireCheckIntervalSec;
    }

    public String getGatewayHostname() {
        return gatewayHostname;
    }

    public void setGatewayHostname(String gatewayHostname) {
        this.gatewayHostname = gatewayHostname;
    }

    public int getTrafficReportIntervalSec() {
        return trafficReportIntervalSec;
    }

    public void setTrafficReportIntervalSec(int trafficReportIntervalSec) {
        this.trafficReportIntervalSec = trafficReportIntervalSec;
    }

    public int getResourceUsageLogIntervalSec() {
        return resourceUsageLogIntervalSec;
    }

    public void setResourceUsageLogIntervalSec(int resourceUsageLogIntervalSec) {
        this.resourceUsageLogIntervalSec = resourceUsageLogIntervalSec;
    }

    public int getMaxSessionSize() {
        return maxSessionSize;
    }

    public void setMaxSessionSize(int maxSessionSize) {
        this.maxSessionSize = maxSessionSize;
    }

    public String getLoggerLevel() {
        return loggerLevel;
    }

    public void setLoggerLevel(String loggerLevel) {
        this.loggerLevel = loggerLevel;
    }

    public boolean isReadLocalConfigFile() {
        return readLocalConfigFile;
    }

    public void setReadLocalConfigFile(boolean readLocalConfigFile) {
        this.readLocalConfigFile = readLocalConfigFile;
    }

    public int getConfigUpdateIntervalSec() {
        return configUpdateIntervalSec;
    }

    public void setConfigUpdateIntervalSec(int configUpdateIntervalSec) {
        this.configUpdateIntervalSec = configUpdateIntervalSec;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public int getReadIdleTimeoutMillis() {
        return readIdleTimeoutMillis;
    }

    public void setReadIdleTimeoutMillis(int readIdleTimeoutMillis) {
        this.readIdleTimeoutMillis = readIdleTimeoutMillis;
    }

    public int getWriteIdleTimeoutMillis() {
        return writeIdleTimeoutMillis;
    }

    public void setWriteIdleTimeoutMillis(int writeIdleTimeoutMillis) {
        this.writeIdleTimeoutMillis = writeIdleTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public int getWriteTimeoutMillis() {
        return writeTimeoutMillis;
    }

    public void setWriteTimeoutMillis(int writeTimeoutMillis) {
        this.writeTimeoutMillis = writeTimeoutMillis;
    }

    public int getForwardReadTimeoutMillis() {
        return forwardReadTimeoutMillis;
    }

    public void setForwardReadTimeoutMillis(int forwardReadTimeoutMillis) {
        this.forwardReadTimeoutMillis = forwardReadTimeoutMillis;
    }

    public int getForwardWriteTimeoutMillis() {
        return forwardWriteTimeoutMillis;
    }

    public void setForwardWriteTimeoutMillis(int forwardWriteTimeoutMillis) {
        this.forwardWriteTimeoutMillis = forwardWriteTimeoutMillis;
    }

    public int getForwardConnectTimeoutMillis() {
        return forwardConnectTimeoutMillis;
    }

    public void setForwardConnectTimeoutMillis(int forwardConnectTimeoutMillis) {
        this.forwardConnectTimeoutMillis = forwardConnectTimeoutMillis;
    }

    public String getListenHost() {
        return listenHost;
    }

    public void setListenHost(String listenHost) {
        this.listenHost = listenHost;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getBackLog() {
        return backLog;
    }

    public void setBackLog(int backLog) {
        this.backLog = backLog;
    }

    public int getListenThreads() {
        return listenThreads;
    }

    public void setListenThreads(int listenThreads) {
        this.listenThreads = listenThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getHttpObjectAggregatorSize() {
        return httpObjectAggregatorSize;
    }

    public void setHttpObjectAggregatorSize(int httpObjectAggregatorSize) {
        this.httpObjectAggregatorSize = httpObjectAggregatorSize;
    }

    public int getHttpRequestHeaderMaxSize() {
        return httpRequestHeaderMaxSize;
    }

    public void setHttpRequestHeaderMaxSize(int httpRequestHeaderMaxSize) {
        this.httpRequestHeaderMaxSize = httpRequestHeaderMaxSize;
    }

    public int getWriteBufferLowWaterMark() {
        return writeBufferLowWaterMark;
    }

    public void setWriteBufferLowWaterMark(int writeBufferLowWaterMark) {
        this.writeBufferLowWaterMark = writeBufferLowWaterMark;
    }

    public int getWriteBufferHighWaterMark() {
        return writeBufferHighWaterMark;
    }

    public void setWriteBufferHighWaterMark(int writeBufferHighWaterMark) {
        this.writeBufferHighWaterMark = writeBufferHighWaterMark;
    }

    public int getUserMaxConcurrentConnections() {
        return userMaxConcurrentConnections;
    }

    public void setUserMaxConcurrentConnections(int userMaxConcurrentConnections) {
        this.userMaxConcurrentConnections = userMaxConcurrentConnections;
    }

    public int getListenSocks5Port() {
        return listenSocks5Port;
    }

    public void setListenSocks5Port(int listenSocks5Port) {
        this.listenSocks5Port = listenSocks5Port;
    }

    public boolean isDnsRemote() {
        return dnsRemote;
    }

    public void setDnsRemote(boolean dnsRemote) {
        this.dnsRemote = dnsRemote;
    }

    public boolean isDisableSupplierDnsCache() {
        return disableSupplierDnsCache;
    }

    public void setDisableSupplierDnsCache(boolean disableSupplierDnsCache) {
        this.disableSupplierDnsCache = disableSupplierDnsCache;
    }

    @Override
    public String toString() {
        return "GatewayConfig{" +
                "listenHost='" + listenHost + '\'' +
                ", listenSocks5Port=" + listenSocks5Port +
                ", listenThreads=" + listenThreads +
                ", workerThreads=" + workerThreads +
                ", backLog=" + backLog +
                ", connectTimeoutMillis=" + connectTimeoutMillis +
                ", forwardConnectTimeoutMillis=" + forwardConnectTimeoutMillis +
                ", readTimeoutMillis=" + readTimeoutMillis +
                ", writeTimeoutMillis=" + writeTimeoutMillis +
                ", forwardReadTimeoutMillis=" + forwardReadTimeoutMillis +
                ", forwardWriteTimeoutMillis=" + forwardWriteTimeoutMillis +
                ", readIdleTimeoutMillis=" + readIdleTimeoutMillis +
                ", writeIdleTimeoutMillis=" + writeIdleTimeoutMillis +
                ", httpObjectAggregatorSize=" + httpObjectAggregatorSize +
                ", httpRequestHeaderMaxSize=" + httpRequestHeaderMaxSize +
                ", writeBufferLowWaterMark=" + writeBufferLowWaterMark +
                ", writeBufferHighWaterMark=" + writeBufferHighWaterMark +
                ", userMaxConcurrentConnections=" + userMaxConcurrentConnections +
                ", apiEndpoint='" + apiEndpoint + '\'' +
                ", apiToken='" + apiToken + '\'' +
                ", configUpdateIntervalSec=" + configUpdateIntervalSec +
                ", readLocalConfigFile=" + readLocalConfigFile +
                ", loggerLevel='" + loggerLevel + '\'' +
                ", maxSessionSize=" + maxSessionSize +
                ", resourceUsageLogIntervalSec=" + resourceUsageLogIntervalSec +
                ", dnsRemote=" + dnsRemote +
                ", disableSupplierDnsCache=" + disableSupplierDnsCache +
                '}';
    }
}
