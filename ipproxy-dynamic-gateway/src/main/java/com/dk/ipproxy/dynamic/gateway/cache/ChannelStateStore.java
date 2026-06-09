package com.dk.ipproxy.dynamic.gateway.cache;

import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 连接Channel状态存储
 */
public class ChannelStateStore {
    private static final Logger logger = LoggerFactory.getLogger(ChannelStateStore.class);
    private static final ChannelGroup allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private static final Map<ChannelId, String> channelSessionMap = new ConcurrentHashMap<>();
    private static final Map<ChannelId, String> channelUserMap = new ConcurrentHashMap<>();
    private static final Map<String, Set<ChannelId>> userChannelMap = new ConcurrentHashMap<>();

    /**
     * 初始化Channel状态存储
     */
    public static void init(GatewayConfig gatewayConfig) {
        ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(1);
        executorService.scheduleAtFixedRate(new ChannelStateCheckTask(), 0, gatewayConfig.getSessionExpireCheckIntervalSec(), TimeUnit.SECONDS);
        logger.info("finish init channel state store");
    }

    /**
     * 添加Channel到ChannelGroup
     */
    public static void addChannel(Channel channel) {
        allChannels.add(channel);
    }

    /**
     * 添加Channel与Session的绑定信息
     */
    public static  void addChannelBindInfo(Channel channel, String authUser, String sessionId) {
        channelSessionMap.put(channel.id(), String.format("%s:%s", authUser, sessionId));
    }

    /**
     * 绑定用户与连接，支持keep-alive场景幂等复用
     */
    public static boolean tryBindUserChannel(Channel channel, String authUser, int limit) {
        if (channel == null || authUser == null || authUser.isEmpty()) {
            return true;
        }
        ChannelId channelId = channel.id();
        String boundUser = channelUserMap.get(channelId);
        if (authUser.equals(boundUser)) {
            return true;
        }
        if (boundUser != null) {
            return false;
        }

        Set<ChannelId> userChannels = userChannelMap.computeIfAbsent(authUser, key -> ConcurrentHashMap.newKeySet());
        synchronized (userChannels) {
            boundUser = channelUserMap.get(channelId);
            if (authUser.equals(boundUser)) {
                return true;
            }
            if (boundUser != null) {
                return false;
            }
            if (limit > 0 && userChannels.size() >= limit) {
                return false;
            }
            userChannels.add(channelId);
            channelUserMap.put(channelId, authUser);
            return true;
        }
    }

    /**
     * 从ChannelGroup中移除Channel
     */
    public static void removeChannel(Channel channel) {
        ChannelId channelId = channel.id();
        channelSessionMap.remove(channelId);

        String authUser = channelUserMap.remove(channelId);
        if (authUser != null) {
            Set<ChannelId> userChannels = userChannelMap.get(authUser);
            if (userChannels != null) {
                synchronized (userChannels) {
                    userChannels.remove(channelId);
                    if (userChannels.isEmpty()) {
                        userChannelMap.remove(authUser, userChannels);
                    }
                }
            }
        }

        allChannels.remove(channel);
    }

    /**
     * 当前接入侧活动连接总数
     */
    public static int getActiveChannelCount() {
        return allChannels.size();
    }

    /**
     * 获取Channel与Session的绑定信息
     */
    public static SessionInfo getChannelSessionInfo(ChannelId channelId) {
        String sessionId = channelSessionMap.get(channelId);
        if (sessionId == null) {
            return null;
        }

        String[] sessionParts = sessionId.split(":");
        if (sessionParts.length != 2) {
            return null;
        }

        return SessionStateStore.get(sessionParts[0], sessionParts[1]);
    }

    /**
     * Channel状态检查任务
     */
    private static class ChannelStateCheckTask implements Runnable {

        @Override
        public void run() {
            try {
                logger.info("start check channel state, current channel cnt: {}", allChannels.size());
                int emptyChannelCnt = 0;
                int expiredChannelCnt = 0;
                for (Channel channel : allChannels) {
                    ChannelId channelId = channel.id();
                    // 获取绑定的session信息，如果没有的话，则放入到待观察的列表中，超过时长直接移除掉
                    SessionInfo sessionInfo = getChannelSessionInfo(channelId);
                    if (sessionInfo != null) {
                        if (checkSessionExpire(sessionInfo)) {
                            // 删除session记录
                            SessionStateStore.remove(sessionInfo.authUser, sessionInfo.sessionId);
                            // 关闭连接
                            expiredChannelCnt++;
                            channel.close();
                            logger.info("close expire session channel, channelId: {}, expireTime: {}", channelId, sessionInfo.bindTime + sessionInfo.keepTimeSec);
                        }
                    } else {
                        emptyChannelCnt++;
                    }
                }
                logger.info("finish check channel state, expireChannel cnt: {}, expireChannel cnt: {}", expiredChannelCnt, emptyChannelCnt);
            } catch (Exception e) {
                logger.error("error in channel state check task", e);
            }
        }

        /**
         * 检查Session是否过期
         */
        private boolean checkSessionExpire(SessionInfo sessionInfo) {
            // session首次绑定时间
            int sessionStartTime = sessionInfo.bindTime;
            // session有效时长
            int sessionKeepTime = sessionInfo.keepTimeSec;
            // 当前时间
            int currentTime = (int) (System.currentTimeMillis() / 1000);
            logger.info("currentTime: {}, bindTime: {}, sessionKeepTime: {}", currentTime, sessionStartTime, sessionKeepTime);
            // 如果当前时间大于session首次绑定时间加上有效时长，则表示session过期
            return currentTime > sessionStartTime + sessionKeepTime;
        }
    }
}
