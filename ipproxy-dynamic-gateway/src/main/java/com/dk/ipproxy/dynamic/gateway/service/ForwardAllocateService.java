package com.dk.ipproxy.dynamic.gateway.service;

import com.dk.ipproxy.dynamic.gateway.cache.SessionInfo;
import com.dk.ipproxy.dynamic.gateway.cache.SessionStateStore;
import com.dk.ipproxy.dynamic.gateway.config.AreaMappingConfig;
import com.dk.ipproxy.dynamic.gateway.config.GlobalConfigStore;
import com.dk.ipproxy.dynamic.gateway.config.SupplierConfig;
import com.dk.ipproxy.dynamic.gateway.config.UserConfig;
import com.dk.ipproxy.dynamic.gateway.constants.Country;
import com.dk.ipproxy.dynamic.gateway.constants.ProxyAuthFormatParts;
import com.dk.ipproxy.dynamic.gateway.constants.Supplier;
import com.dk.ipproxy.dynamic.gateway.context.ChannelContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 动态代理绑定分配服务
 */
public class ForwardAllocateService {
    private static final Logger logger = LoggerFactory.getLogger(ForwardAllocateService.class);

    public boolean getBindForwardUpstream(ChannelContext channelContext) {
        // 获取用户信息
        String authUser = channelContext.getAuthUser();
        UserConfig userConfig = GlobalConfigStore.value.getUserConfigMap().get(authUser);
        if (userConfig == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("get user config null, user: {}", authUser);
            }
            return false;
        }

        // 判断用户禁用流量
        if (!userConfig.isTrafficEnable()) {
            if (logger.isWarnEnabled()) {
                logger.warn("user traffic disabled, end connection, user: {}", authUser);
            }
            return false;
        }

        // 获取用户可用的供应商
        List<Integer> availableSupplier = userConfig.getAvailableSupplier();
        if (availableSupplier == null || availableSupplier.isEmpty()) {
            if (logger.isWarnEnabled()) {
                logger.warn("user no supplier available, user: {}", authUser);
            }
            return false;
        }

        // 获取指定的国家、省份、城市
        String country = channelContext.getForwardCountry();
        String state = channelContext.getForwardState();
        String city = channelContext.getForwardCity();

        // 生成真实连接串
        String sessionId = channelContext.getForwardSessionId();
        SessionInfo sessionInfo;
        boolean newAllocated = false;
        if (!StringUtils.isEmpty(sessionId)) {
            // 校验sessionId长度以及是否为数字
            if (sessionId.length() >= 20 || !StringUtils.isNumeric(sessionId)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("illegal sessionId, user: {}, sessionId: {}", authUser, sessionId);
                }
                return false;
            }

            // 校验KeepTime是否有效
            int sessionKeepTime = channelContext.getForwardSessionKeepTime();
            if (sessionKeepTime <= 0) {
                if (logger.isDebugEnabled()) {
                    logger.debug("illegal sessionKeepTime, user: {}, keepTime: {}", authUser, sessionKeepTime);
                }
                return false;
            }

            // 获取是否已存在绑定的Session信息
            sessionInfo = SessionStateStore.get(authUser, sessionId);
            if (sessionInfo == null) {
                // 如果没有缓存的Session信息，则生成粘性session链接串
                sessionInfo = allocateStickSession(availableSupplier, authUser, country, state, city, sessionId, sessionKeepTime);
                newAllocated = true;
            }
        } else {
            // 如果没有配置SessionID，那么生成一次一换链接串
            sessionInfo = allocateTempSession(availableSupplier, authUser, country, state, city);
            newAllocated = true;
        }

        // 成功从缓存获取或者分配的，设置到context中
        if (sessionInfo != null) {
            // 设置到cache
            if (newAllocated) {
                SessionStateStore.put(authUser, sessionId, sessionInfo);
            }

            // 设置到context
            channelContext.setBindForwardHostname(sessionInfo.forwardHostname);
            channelContext.setBindForwardPort(sessionInfo.forwardPort);
            channelContext.setBindForwardUsername(sessionInfo.forwardUser);
            channelContext.setBindForwardPass(sessionInfo.forwardPass);
            channelContext.setBindForwardKeepTime(sessionInfo.keepTimeSec);
            channelContext.setBindForwardTime(sessionInfo.bindTime);
            channelContext.setBindForwardSupplierId(sessionInfo.supplierId);
            return true;
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("allocate session failed, user: {}", authUser);
            }
            return false;
        }
    }

    /**
     * 分配粘性Session动态代理
     */
    private SessionInfo allocateStickSession(List<Integer> availableSupplier, String authUser, String country, String state, String city, String sessionId, int keepTime) {
        if (logger.isDebugEnabled()) {
            logger.debug("start allocate stick session, user: {}, country: {}, state: {}, city: {}, sessionId: {}, keepTime: {}", authUser, country, state, city, sessionId, keepTime);
        }

        // 随机挑选一个供应商
        int supplierId = availableSupplier.get(ThreadLocalRandom.current().nextInt(availableSupplier.size()));

        // 特殊逻辑：如果是融合池的，并且分钟数大于30min的，选择infatica
        if (Supplier.MergePool.ALL.contains(supplierId) && keepTime > 1800 && availableSupplier.contains(Supplier.MergePool.INFATICA)) {
            supplierId = Supplier.MergePool.INFATICA;
        }

        // 获取供应商详情
        SupplierConfig supplierConfig = GlobalConfigStore.value.getSupplierConfigMap().get(supplierId);
        if (supplierConfig == null) {
            if (logger.isWarnEnabled()) {
                logger.warn("get supplier info null, user: {}, supplierId: {}", authUser, supplierId);
            }
            return null;
        }

        // 转发接入点（随机选择一个）
        List<String> availableGatewayList = supplierConfig.getAvailableGateway();
        if (availableGatewayList == null || availableGatewayList.isEmpty()) {
            if (logger.isWarnEnabled()) {
                logger.warn("available gateway list is null, supplierId: {}", supplierConfig);
            }
            return null;
        }
        String endpoint  = availableGatewayList.get(ThreadLocalRandom.current().nextInt(availableGatewayList.size()));
        String[] endpointParts = endpoint.split(":");
        String host = endpointParts[0];
        int port = Integer.parseInt(endpointParts[1]);

        // 随机生成供应商的sessionID
        String forwardSessionId = String.valueOf(ThreadLocalRandom.current().nextInt(999999999));

        // 根据是否指定国家/省份/城市来选择对应的格式处理
        String authStr;
        if (Country.ANY.equalsIgnoreCase(country)) {
            // 任意国家
            authStr = supplierConfig.getStickSessionAuthFormat();
        } else {
            // 若供应商是融合池供应商，则还需要转换省份和城市
            if (Supplier.MergePool.ALL.contains(supplierId)) {
                AreaMappingConfig areaMappingConfig = GlobalConfigStore.value.getAreaMappingConfigMap().getOrDefault(supplierId, new AreaMappingConfig(supplierId));
                AreaMappingConfig.AreaInfo areaInfo = areaMappingConfig.getMapping(country, state, city);
                if (areaInfo == null) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("get area info null, country: {}, state: {}, city: {}", country, state, city);
                    }
                    return null;
                }
                state = areaInfo.state;
                city = areaInfo.city;
            }

            if (!StringUtils.isEmpty(state)) {
                if (!StringUtils.isEmpty(city)) {
                    // 指定国家、省份、城市
                    authStr = supplierConfig.getCityStickSessionAuthFormat();
                } else {
                    // 指定国家和省份，任意城市
                    authStr = supplierConfig.getStateStickSessionAuthFormat();
                }
            } else {
                // 指定国家，任意省份/城市
                if (Supplier.MergePool.ALL.contains(supplierId) && !StringUtils.isEmpty(city)) {
                    authStr = supplierConfig.getCityStickSessionAuthFormat();
                } else {
                    authStr = supplierConfig.getCountryStickSessionAuthFormat();
                }
            }
        }
        if (StringUtils.isEmpty(authStr) || !authStr.contains(":")) {
            // 可能运营后台设置了无效的格式
            if (logger.isWarnEnabled()) {
                logger.warn("illegal temp session format, value is empty, authStr: {}", authStr);
            }
            return null;
        }

        // 转换，处理国家大小写
        country = getSupplierMappingCountry(country, supplierConfig);

        // 替换格式的参数
        authStr = authStr
                .replace(ProxyAuthFormatParts.USER, supplierConfig.getAuthUser())
                .replace(ProxyAuthFormatParts.PASSWORD, supplierConfig.getAuthPass())
                .replace(ProxyAuthFormatParts.SESSION, forwardSessionId);
        if (!StringUtils.isEmpty(country)) {
                authStr = authStr.replace(ProxyAuthFormatParts.COUNTRY, country);
        }
        if (!StringUtils.isEmpty(state)) {
                authStr = authStr.replace(ProxyAuthFormatParts.STATE, state);
        }
        if (!StringUtils.isEmpty(city)) {
                authStr = authStr.replace(ProxyAuthFormatParts.CITY, city);
        }

        // 切分出账密
        String[] authStrParts = authStr.split(":");
        String user = authStrParts[0];
        String pass = authStrParts[1];

        // 返回
        SessionInfo sessionInfo = new SessionInfo();
        sessionInfo.forwardHostname = host;
        sessionInfo.forwardPort = port;
        sessionInfo.forwardUser = user;
        sessionInfo.forwardPass = pass;
        sessionInfo.supplierId = supplierId;
        sessionInfo.keepTimeSec = keepTime;
        sessionInfo.bindTime = (int) (System.currentTimeMillis() / 1000);
        return sessionInfo;
    }

    /**
     * 分配一次一换动态代理
     */
    private SessionInfo allocateTempSession(List<Integer> availableSupplier, String authUser, String country, String state, String city) {
        if (logger.isDebugEnabled()) {
            logger.debug("start allocate temp session, user: {}, country: {}, state: {}, city: {}", authUser, country, state, city);
        }

        // 随机挑选一个供应商
        int supplierId = availableSupplier.get(ThreadLocalRandom.current().nextInt(availableSupplier.size()));

        // 获取供应商详情
        SupplierConfig supplierConfig = GlobalConfigStore.value.getSupplierConfigMap().get(supplierId);
        if (supplierConfig == null) {
            if (logger.isWarnEnabled()) {
                logger.warn("get supplier info null, user: {}, supplierId: {}", authUser, supplierId);
            }
            return null;
        }

        // 转发接入点（随机选择一个）
        List<String> availableGatewayList = supplierConfig.getAvailableGateway();
        if (availableGatewayList == null || availableGatewayList.isEmpty()) {
            if (logger.isWarnEnabled()) {
                logger.warn("available gateway list is null, supplierId: {}", supplierConfig);
            }
            return null;
        }
        String endpoint  = availableGatewayList.get(ThreadLocalRandom.current().nextInt(availableGatewayList.size()));
        String[] endpointParts = endpoint.split(":");
        String host = endpointParts[0];
        int port = Integer.parseInt(endpointParts[1]);

        // 根据是否指定国家/省份/城市来选择对应的格式处理
        String authStr;
        if (Country.ANY.equalsIgnoreCase(country)) {
            // 任意国家
            authStr = supplierConfig.getTmpSessionAuthFormat();
        } else {
            // 若供应商是融合池供应商，则还需要转换省份和城市
            if (Supplier.MergePool.ALL.contains(supplierId)) {
                AreaMappingConfig areaMappingConfig = GlobalConfigStore.value.getAreaMappingConfigMap().getOrDefault(supplierId, new AreaMappingConfig(supplierId));
                AreaMappingConfig.AreaInfo areaInfo = areaMappingConfig.getMapping(country, state, city);
                if (areaInfo == null) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("get area info null, country: {}, state: {}, city: {}", country, state, city);
                    }
                    return null;
                }
                state = areaInfo.state;
                city = areaInfo.city;
            }

            if (!StringUtils.isEmpty(state)) {
                if (!StringUtils.isEmpty(city)) {
                    // 指定国家、省份、城市
                    authStr = supplierConfig.getCityTmpSessionAuthFormat();
                } else {
                    // 指定国家和省份，任意城市
                    authStr = supplierConfig.getStateTmpSessionAuthFormat();
                }
            } else {
                // 指定国家，任意省份/城市
                if (Supplier.MergePool.ALL.contains(supplierId) && !StringUtils.isEmpty(city)) {
                    authStr = supplierConfig.getCityTmpSessionAuthFormat();
                } else {
                    authStr = supplierConfig.getCountryTmpSessionAuthFormat();
                }
            }
        }
        if (StringUtils.isEmpty(authStr) || !authStr.contains(":")) {
            // 可能运营后台设置了无效的格式
            if (logger.isWarnEnabled()) {
                logger.warn("illegal temp session format, value is empty, authStr: {}", authStr);
            }
            return null;
        }

        // 转换，处理国家大小写
        country = getSupplierMappingCountry(country, supplierConfig);

        // 替换格式的参数
        authStr = authStr
                .replace(ProxyAuthFormatParts.USER, supplierConfig.getAuthUser())
                .replace(ProxyAuthFormatParts.PASSWORD, supplierConfig.getAuthPass());
        if (!StringUtils.isEmpty(country)) {
            authStr = authStr.replace(ProxyAuthFormatParts.COUNTRY, country);
        }
        if (!StringUtils.isEmpty(state)) {
            authStr = authStr.replace(ProxyAuthFormatParts.STATE, state);
        }
        if (!StringUtils.isEmpty(city)) {
            authStr = authStr.replace(ProxyAuthFormatParts.CITY, city);
        }

        // 切分出账密
        String[] authStrParts = authStr.split(":");
        String user = authStrParts[0];
        String pass = authStrParts[1];

        // 返回
        SessionInfo sessionInfo = new SessionInfo();
        sessionInfo.forwardHostname = host;
        sessionInfo.forwardPort = port;
        sessionInfo.forwardUser = user;
        sessionInfo.forwardPass = pass;
        sessionInfo.supplierId = supplierId;
        sessionInfo.keepTimeSec = 0;
        sessionInfo.bindTime = (int) (System.currentTimeMillis() / 1000);
        return sessionInfo;
    }

    /**
     * 获取传入国家代码映射的供应商配置中的国家代码
     */
    private String getSupplierMappingCountry(String country, SupplierConfig supplierConfig) {
        int areaCaseType = supplierConfig.getAreaCaseType();
        if (areaCaseType == 1) {
            return country.toUpperCase();
        } else if (areaCaseType == 0) {
            return country.toLowerCase();
        } else {
            return country;
        }
    }
}
