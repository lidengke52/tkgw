package com.dk.ipproxy.dynamic.gateway.cache;

import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Session状态存储
 */
public class SessionStateStore {
    private static final Logger logger = LoggerFactory.getLogger(SessionStateStore.class);
    private static Cache<String, SessionInfo> stateStore;

    /**
     * 初始化
     */
    public static void init(GatewayConfig gatewayConfig) {
        stateStore = Caffeine.newBuilder()
                .maximumSize(gatewayConfig.getMaxSessionSize())
                .expireAfter(new StateExpirePolicy())
                .build();
        logger.info("finish init session state store");
    }

    /**
     * 获取Session状态
     */
    public static SessionInfo get(String authUser, String sessionId) {
        return stateStore.getIfPresent(getStateKey(authUser, sessionId));
    }

    /**
     * 添加Session状态
     */
    public static void put(String authUser, String sessionId, SessionInfo sessionInfo) {
        if (logger.isDebugEnabled()) {
            logger.debug("put session state, sessionId: {}, keepTime: {} sec", sessionId, sessionInfo.keepTimeSec);
        }
        stateStore.put(getStateKey(authUser, sessionId), sessionInfo);
    }

    /**
     * 移除Session状态
     */
    public static void remove(String authUser, String sessionId) {
        if (logger.isDebugEnabled()) {
            logger.debug("remove session state, sessionId: {}", sessionId);
        }
        stateStore.invalidate(getStateKey(authUser, sessionId));
    }

    private static String getStateKey(String authUser, String sessionId) {
        return authUser + "_" + sessionId;
    }

    /**
     * State过期规则
     */
    private static class StateExpirePolicy implements Expiry<String, SessionInfo> {
        @Override
        public long expireAfterCreate(@NonNull String key, @NonNull SessionInfo sessionState, long currentTime) {
            // 保留keepTime长度时间
            return TimeUnit.SECONDS.toNanos(sessionState.keepTimeSec);
        }

        @Override
        public long expireAfterUpdate(@NonNull String key, @NonNull SessionInfo sessionState, long currentTime, @NonNegative long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(@NonNull String key, @NonNull SessionInfo sessionState, long currentTime, @NonNegative long currentDuration) {
            return currentDuration;
        }
    }
}
