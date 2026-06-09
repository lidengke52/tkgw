package com.dk.ipproxy.dynamic.gateway.service;

import com.dk.ipproxy.dynamic.gateway.config.*;
import com.dk.ipproxy.dynamic.gateway.constants.Protocol;
import com.dk.ipproxy.dynamic.gateway.constants.Supplier;
import com.dk.ipproxy.dynamic.gateway.utils.BitSetUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class APIService {
    private static final Logger logger = LoggerFactory.getLogger(APIService.class);
    private final String endpoint;
    private final String token;
    private final OkHttpClient httpClient;
    private final ObjectMapper jsonObjMapper;
    private final boolean readLocalFile;
    private final String gatewayHostname;
    private final int whiteListPortRangeStart;

    public APIService(String endpoint, String token, boolean isReadLocalFile, String gatewayHostname, int whiteListPortRangeStart) {
        this.endpoint = endpoint;
        this.token = token;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.jsonObjMapper = new ObjectMapper();
        this.readLocalFile = isReadLocalFile;
        this.gatewayHostname = gatewayHostname;
        this.whiteListPortRangeStart = whiteListPortRangeStart;
    }

    /**
     * 从本地文件读取用户配置
     * TODO
     */
    private List<UserConfig> getLocalUserConfigList() {
        List<UserConfig> userConfigList = new ArrayList<>();
        UserConfig userConfig = new UserConfig();
        userConfig.setUserId(1);
        userConfig.setAuthUser("IPTNSJSN");
        userConfig.setAuthPass("V219Lce1");
        userConfig.setTrafficEnable(true);
        userConfig.setAvailableSupplier(Collections.singletonList(16));
        userConfigList.add(userConfig);
        return userConfigList;
    }

    /**
     * 从本地文件读取供应商配置
     * TODO
     */
    private List<SupplierConfig> getLocalSupplierConfigList() {
        List<SupplierConfig> supplierConfigList = new ArrayList<>();
        SupplierConfig supplierConfig = new SupplierConfig();
        supplierConfig.setSupplierId(16);
        supplierConfig.setAuthUser("dfdfd");
        supplierConfig.setAuthPass("ggdd");
        supplierConfig.setSessionMinKeepTime(5);
        supplierConfig.setSessionMaxKeepTime(60);
        supplierConfig.setAvailableGateway(Arrays.asList("us:gate.smartproxy.com:7000", "gate.smartproxy.com:7000", "gate.smartproxy.com:7000"));
        supplierConfig.setTmpSessionAuthFormat("user-{user}:{password}");
        supplierConfig.setStickSessionAuthFormat("{user}_s_{session}_ttl_120m:{password}");
        supplierConfig.setCountryTmpSessionAuthFormat("user-{user}-country-{country}:{password}");
        supplierConfig.setCountryStickSessionAuthFormat("{user}_c_{country}_s_{session}_ttl_120m:{password}");
        supplierConfig.setStateTmpSessionAuthFormat("user-{user}-country-{country}-{state}:{password}");
        supplierConfig.setStateStickSessionAuthFormat("{user}_c_{country}_s_{session}_ttl_120m:{password}");
        supplierConfig.setCityTmpSessionAuthFormat("user-{user}-country-{country}-{state}-{city}:{password}");
        supplierConfig.setCityStickSessionAuthFormat("{user}_c_{country}_city_{city}_s_{session}_ttl_120m:{password}");
        supplierConfigList.add(supplierConfig);
        return supplierConfigList;
    }

    /**
     * 从本地文件读取白名单列表
     */
    private List<WhiteListConfig> getLocalWhiteListConfigList() {
        List<WhiteListConfig> whiteListConfigList = new ArrayList<>();
        WhiteListConfig whiteListConfig = new WhiteListConfig();
        whiteListConfig.setIp("172.19.74.130");
        whiteListConfig.setUid(1);
        WhiteListConfig.PortDynamicProxyInfo portInfo = new WhiteListConfig.PortDynamicProxyInfo();
        portInfo.setProtocol(Protocol.HTTP);
        portInfo.setArea("AD");
        portInfo.setRegion("Canillo");
        portInfo.setCity("Canillo");
        Map<Integer, WhiteListConfig.PortDynamicProxyInfo> portDynamicProxyInfoMap = new HashMap<>();
        portDynamicProxyInfoMap.put(20000, portInfo);
        portDynamicProxyInfoMap.put(20001, portInfo);
        whiteListConfig.setPortDynamicProxyInfoMap(portDynamicProxyInfoMap);
        whiteListConfigList.add(whiteListConfig);
        return whiteListConfigList;
    }

    /**
     * 获取客户列表
     */
    public List<UserConfig> getUserConfigList() {
        if (this.readLocalFile) {
            logger.info("get userConfig from local file");
            return getLocalUserConfigList();
        }
        List<UserConfig> userConfigList = new ArrayList<>();
        try {
            Request request = new Request.Builder()
                    .url(this.endpoint + "/api/admin/dynamic/getAccountConfig")
                    .addHeader("Authorization", String.format("Bearer %s", this.token))
                    .build();

            try (Response response = this.httpClient.newCall(request).execute()) {
                int responseCode = response.code();
                if (responseCode != 200) {
                    logger.error("failed to get user config list, code: {}", responseCode);
                    return userConfigList;
                }

                // 解析响应内容
                if (response.body() == null) {
                    logger.error("failed to get user config list, response body is null");
                    return userConfigList;
                }
                String bodyStr = response.body().string();
                JsonNode bodyObj = jsonObjMapper.readTree(bodyStr);
                int bodyCode = bodyObj.get("code").intValue();
                if (bodyCode != 200) {
                    logger.error("failed to get user config list, code: {}", bodyCode);
                    return userConfigList;
                }
                JsonNode dataObj = bodyObj.get("data");
                for (JsonNode userObj : dataObj) {
                    int userId = userObj.get("uid").asInt();
                    boolean trafficEnable = !userObj.get("trafficEnable").asBoolean();
                    String authUser = userObj.get("authUser").asText();
                    String authPass = userObj.get("authPass").asText();
                    List<Integer> availableSupplier = new ArrayList<>();
                    for (JsonNode availableSupplierObj : userObj.get("availableSupplier")) {
                        availableSupplier.add(availableSupplierObj.asInt());
                    }

                    // 没有启用的就跳过
                    if (!trafficEnable) {
                        continue;
                    }

                    // set
                    UserConfig userConfig = new UserConfig();
                    userConfig.setUserId(userId);
                    userConfig.setAuthUser(authUser);
                    userConfig.setAuthPass(authPass);
                    userConfig.setTrafficEnable(trafficEnable);
                    userConfig.setAvailableSupplier(availableSupplier);
                    userConfigList.add(userConfig);
                }
            }
        } catch (Exception e) {
            logger.error("failed to get user config list", e);
        }
        return userConfigList;
    }

    /**
     * 获取供应商列表
     */
    public List<SupplierConfig> getSupplierConfigLIst() {
        if (this.readLocalFile) {
            logger.info("get supplierConfig from local file");
            return getLocalSupplierConfigList();
        }
        List<SupplierConfig> supplierConfigList = new ArrayList<>();
        try {
            Request request = new Request.Builder()
                    .url(this.endpoint + "/api/admin/dynamic/getAllSuppliers")
                    .addHeader("Authorization", String.format("Bearer %s", this.token))
                    .build();

            try (Response response = this.httpClient.newCall(request).execute()) {
                int responseCode = response.code();
                if (responseCode != 200) {
                    logger.error("failed to get supplier config list, code: {}", responseCode);
                    return supplierConfigList;
                }

                // 解析响应内容
                if (response.body() == null) {
                    logger.error("failed to get supplier config list, response body is null");
                    return supplierConfigList;
                }
                String bodyStr = response.body().string();
                JsonNode bodyObj = jsonObjMapper.readTree(bodyStr);
                int bodyCode = bodyObj.get("code").intValue();
                if (bodyCode != 200) {
                    logger.error("failed to get supplier config list, code: {}", bodyCode);
                    return supplierConfigList;
                }
                JsonNode dataObj = bodyObj.get("data");
                for (JsonNode supplierObj : dataObj) {
                    logger.info("fetch supplier config: {}", supplierObj);
                    int supplierId = supplierObj.get("supplierId").asInt();
                    JsonNode authObj = supplierObj.get("auth");
                    String user = authObj.get("user").asText();
                    String pass = authObj.get("pass").asText();
                    JsonNode sessionKeepTimeObj = supplierObj.get("sessionKeepTime");
                    int minKeepTime = sessionKeepTimeObj.get("min").asInt();
                    int maxKeepTime = sessionKeepTimeObj.get("max").asInt();
                    List<String> availableGateway = new ArrayList<>();
                    for (JsonNode availableGatewayObj : supplierObj.get("availableGateway")) {
                        availableGateway.add(availableGatewayObj.asText());
                    }
                    String tmpSessionAuthFormat = supplierObj.get("authFormatOnce").asText();
                    String stickSessionAuthFormat = supplierObj.get("authFormat").asText();
                    String countryTmpSessionAuthFormat = supplierObj.get("authFormatAreaOnce").asText();
                    String countryStickSessionAuthFormat = supplierObj.get("authFormatArea").asText();
                    String stateTmpSessionAuthFormat = supplierObj.get("authFormatRegionOnce").asText();
                    String stateStickSessionAuthFormat = supplierObj.get("authFormatRegion").asText();
                    String cityTmpSessionAuthFormat = supplierObj.get("authFormatCityOnce").asText();
                    String cityStickSessionAuthFormat = supplierObj.get("authFormatCity").asText();
                    int areaCaseType = supplierObj.get("areaCaseType").asInt();

                    // set
                    SupplierConfig supplierConfig = new SupplierConfig();
                    supplierConfig.setSupplierId(supplierId);
                    supplierConfig.setAuthUser(user);
                    supplierConfig.setAuthPass(pass);
                    supplierConfig.setSessionMinKeepTime(minKeepTime);
                    supplierConfig.setSessionMaxKeepTime(maxKeepTime);
                    supplierConfig.setAvailableGateway(availableGateway);
                    supplierConfig.setAreaCaseType(areaCaseType);
                    supplierConfig.setTmpSessionAuthFormat(tmpSessionAuthFormat);
                    supplierConfig.setStickSessionAuthFormat(stickSessionAuthFormat);
                    supplierConfig.setCountryTmpSessionAuthFormat(countryTmpSessionAuthFormat);
                    supplierConfig.setCountryStickSessionAuthFormat(countryStickSessionAuthFormat);
                    supplierConfig.setStateTmpSessionAuthFormat(stateTmpSessionAuthFormat);
                    supplierConfig.setStateStickSessionAuthFormat(stateStickSessionAuthFormat);
                    supplierConfig.setCityTmpSessionAuthFormat(cityTmpSessionAuthFormat);
                    supplierConfig.setCityStickSessionAuthFormat(cityStickSessionAuthFormat);
                    supplierConfigList.add(supplierConfig);
                }
            }
        } catch (Exception e) {
            logger.error("failed to get supplier config", e);
        }
        return supplierConfigList;
    }

    /**
     * 获取白名单列表
     */
    public List<WhiteListConfig> getWhiteListConfigList() {
        if (this.readLocalFile) {
            logger.info("get whiteListConfig from local file");
            return getLocalWhiteListConfigList();
        }
        List<WhiteListConfig> whiteListConfigList = new ArrayList<>();
        try {
            Request request = new Request.Builder()
                    .url(this.endpoint + "/api/admin/agent/getWhiteConfig?gatewayHostname=" + this.gatewayHostname)
                    .addHeader("Authorization", String.format("Bearer %s", this.token))
                    .build();

            try (Response response = this.httpClient.newCall(request).execute()) {
                int responseCode = response.code();
                if (responseCode != 200) {
                    logger.error("failed to get whiteList config, code: {}", responseCode);
                    return whiteListConfigList;
                }

                // 解析响应内容
                if (response.body() == null) {
                    logger.error("failed to get whiteList config, response body is null");
                    return whiteListConfigList;
                }
                String bodyStr = response.body().string();
                JsonNode bodyObj = jsonObjMapper.readTree(bodyStr);
                int bodyCode = bodyObj.get("code").intValue();
                if (bodyCode != 200) {
                    logger.error("failed to get whiteList config, code: {}", bodyCode);
                    return whiteListConfigList;
                }
                JsonNode dataObj = bodyObj.get("data");
                for (JsonNode whiteListObj : dataObj) {
                    int uid = whiteListObj.get("uid").asInt();
                    JsonNode whiteIpObjList = whiteListObj.get("whiteIps");

                    // 获取白名单链接
                    Map<Integer, WhiteListConfig.PortDynamicProxyInfo> portDynamicProxyInfoMap = new HashMap<>();
                    JsonNode links = whiteListObj.get("links");
                    for (JsonNode link : links) {
                        String area = link.get("area").asText();
                        String region = link.get("region").asText();
                        String city = link.get("city").asText();
                        String bitsetPort = link.get("bitsetPort").asText();
                        int protocolVal = link.get("protocol").asInt();
                        int minutes = link.get("minutes").asInt();

                        // 解析端口数组
                        int[] portList = BitSetUtils.base64ToPorts(this.whiteListPortRangeStart, bitsetPort);
                        if (portList == null) {
                            continue;
                        }
                        for (int port : portList) {
                            WhiteListConfig.PortDynamicProxyInfo portDynamicProxyInfo = new WhiteListConfig.PortDynamicProxyInfo();
                            portDynamicProxyInfo.setArea(area);
                            portDynamicProxyInfo.setRegion(region);
                            portDynamicProxyInfo.setCity(city);
                            portDynamicProxyInfo.setProtocol(protocolVal == 1 ? Protocol.SOCKS5 : Protocol.HTTP);
                            portDynamicProxyInfo.setMinutes(minutes);
                            portDynamicProxyInfoMap.put(port, portDynamicProxyInfo);
                        }
                    }
                    for (JsonNode ipObj : whiteIpObjList) {
                        String whiteListIP = ipObj.asText();
                        WhiteListConfig whiteListConfig = new WhiteListConfig();
                        whiteListConfig.setIp(whiteListIP);
                        whiteListConfig.setUid(uid);
                        whiteListConfig.setPortDynamicProxyInfoMap(portDynamicProxyInfoMap);
                        whiteListConfigList.add(whiteListConfig);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("failed to get whiteList config", e);
        }
        return whiteListConfigList;
    }

    /**
     * 获取区域映射列表
     */
    public List<AreaMappingConfig> getAreaMappingConfigList() {
        List<String> localData;
        // 如果配置了本地文件路径，则先读取本地文件，否则读取内置的文件
        GatewayConfig gatewayConfig = GlobalConfigStore.value.getGatewayConfig();
        String localAreaMappingFilePath = gatewayConfig.getLocalAreaMappingFile();
        if (StringUtils.isEmpty(localAreaMappingFilePath)) {
            logger.info("local area mapping file not configured, try to read embedding conf file");
            localData = getAreaMappingConfigListReadEmbedding();
        } else {
            logger.info("local area mapping file configured, try to read local conf file: {}", localAreaMappingFilePath);
            localData = getAreaMappingConfigListReadLocalFile(localAreaMappingFilePath);
            if (localData == null || localData.isEmpty()) {
                logger.warn("area mapping file read failed, try to read embedding conf file");
                localData = getAreaMappingConfigListReadEmbedding();
            }
        }

        if (localData == null || localData.isEmpty()) {
            logger.error("do not have any available mapping file");
            return new ArrayList<>();
        }

        // 解析配置文件
        List<AreaMappingConfig> areaMappingConfigList = new ArrayList<>();
        AreaMappingConfig infaticaAreaMapping = new AreaMappingConfig(Supplier.MergePool.INFATICA);
        AreaMappingConfig netnutAreaMapping = new AreaMappingConfig(Supplier.MergePool.NETNUT);
        for (String line : localData) {
            String[] lineArr = line.split(",");
            if (lineArr.length != 16) {
                continue;
            }
            if (!StringUtils.isNumeric(lineArr[0])) {
                continue;
            }
            // 映射支持情况
            boolean infaticaCountrySupport = lineArr[10].equals("1");
            boolean  infaticaStateSupport = lineArr[11].equals("1");
            boolean infaticaCitySupport = lineArr[12].equals("1");
            boolean netnutCountrySupport = lineArr[13].equals("1");
            boolean netnutStateSupport = lineArr[14].equals("1");
            boolean netnutCitySupport = lineArr[15].equals("1");
            // 标准代码
            String standardCountryCode = lineArr[1];
            String standardStateCode = lineArr[2];
            String standardCityCode = lineArr[3];
            // infatica
            String infaticaCountry = infaticaCountrySupport ? lineArr[4] : null;
            String infaticaState = infaticaStateSupport ? lineArr[5] : null;
            String infaticaCity = infaticaCitySupport ? lineArr[6] : null;
            // netnut
            String netnutCountry = netnutCountrySupport ? lineArr[7] : null;
            String netnutState = netnutStateSupport ? lineArr[8] : null;
            String netnutCity = netnutCitySupport ? lineArr[9] : null;
            // 国家映射
            infaticaAreaMapping.addMapping(standardCountryCode, null, null, infaticaCountry, null, null);
            netnutAreaMapping.addMapping(standardCountryCode, null, null, netnutCountry, null, null);
            // 州映射
            infaticaAreaMapping.addMapping(standardCountryCode, standardStateCode, null, infaticaCountry, infaticaState, null);
            netnutAreaMapping.addMapping(standardCountryCode, standardStateCode, null, netnutCountry, netnutState, null);
            // 城市映射
            infaticaAreaMapping.addMapping(standardCountryCode, standardStateCode, standardCityCode, infaticaCountry, infaticaState, infaticaCity);
            netnutAreaMapping.addMapping(standardCountryCode, standardStateCode, standardCityCode, netnutCountry, netnutState, netnutCity);
        }
        logger.info("finish load mapping config, infaticaSize: {}, netnutSize: {}", infaticaAreaMapping.getMappingStoreSize(), netnutAreaMapping.getMappingStoreSize());
        areaMappingConfigList.add(infaticaAreaMapping);
        areaMappingConfigList.add(netnutAreaMapping);
        return areaMappingConfigList;
    }

    private List<String> getAreaMappingConfigListReadEmbedding() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream("areaMapping.csv")) {
            if (inputStream == null) {
                logger.error("Embedding area mapping config file 'areaMapping.csv' not found in classpath.");
                throw new IOException("Embedding area mapping config file 'areaMapping.csv' not found in classpath.");
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                List<String> lines = reader.lines().collect(Collectors.toList());
                logger.info("Successfully read {} lines from 'areaMapping.csv'.", lines.size());
                return lines;
            }
        } catch (IOException e) {
            logger.error("Failed to read 'areaMapping.csv' from classpath.", e);
            return null;
        }
    }

    private List<String> getAreaMappingConfigListReadLocalFile(String localAreaMappingFilePath) {
        Path path = Paths.get(localAreaMappingFilePath);
        if (!Files.exists(path)) {
            logger.error("configured local area mapping file not exists");
            return null;
        }

        // 读取文件内容
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
            logger.info("read total {} lines", lines.size());
        } catch (Exception e) {
            logger.error("failed to read local area mapping file", e);
            return null;
        }
        return lines;
    }

    /**
     * 流量上报
     */
    public void reportTraffic(String hostname, long totalUpLinkBytes, long totalDownLinkBytes, List<long[]> userTrafficData) {
        if (this.readLocalFile) {
            return;
        }
        try {
            // 构建上报对象
            Map<String, Object> reportData = new HashMap<>();
            reportData.put("hostname", hostname);
            Map<String, Long> totalStats = new HashMap<>();
            totalStats.put("upLink", totalUpLinkBytes);
            totalStats.put("downLink", totalDownLinkBytes);
            reportData.put("totalTraffic", totalStats);
            List<Map<String, Long>> userStatsList = new ArrayList<>();
            for (long[] userTraffic : userTrafficData) {
                Map<String, Long> userStats = new HashMap<>();
                userStats.put("id", userTraffic[0]);
                userStats.put("upLink", userTraffic[1]);
                userStats.put("downLink", userTraffic[2]);
                userStatsList.add(userStats);
            }
            reportData.put("userTraffic", userStatsList);
            String reportDataJson = new ObjectMapper().writeValueAsString(reportData);

            RequestBody body = RequestBody.create(reportDataJson, MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(this.endpoint + "/api/admin/dynamic/trafficReport")
                    .addHeader("Authorization", String.format("Bearer %s", this.token))
                    .post(body)
                    .build();

            try (Response response = this.httpClient.newCall(request).execute()) {
                int responseCode = response.code();
                if (responseCode != 200) {
                    logger.error("failed to get supplier config list, code: {}", responseCode);
                } else {
                    logger.info("report traffic success");
                }
            }
        } catch (Exception e) {
            logger.error("failed to report traffic", e);
        }
    }
}
