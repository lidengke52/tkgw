package com.dk.ipproxy.dynamic.gateway.cache;

import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import com.dk.ipproxy.dynamic.gateway.service.APIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 流量统计
 */
public class TrafficCounter {
    private static final Logger logger = LoggerFactory.getLogger(TrafficCounter.class);
    private static final Map<Integer, Long> sendTrafficMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> receiveTrafficMap = new ConcurrentHashMap<>();

    public static void init(GatewayConfig gatewayConfig) {
        APIService apiService = new APIService(
                gatewayConfig.getApiEndpoint(),
                gatewayConfig.getApiToken(),
                gatewayConfig.isReadLocalConfigFile(),
                gatewayConfig.getGatewayHostname(),
                gatewayConfig.getWhiteListPortRangeStart());
        ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(1);
        executorService.scheduleAtFixedRate(new ScheduleTrafficReport(gatewayConfig.getGatewayHostname(), apiService), gatewayConfig.getTrafficReportIntervalSec(), gatewayConfig.getTrafficReportIntervalSec(), TimeUnit.SECONDS);
        logger.info("finish init traffic counter");
    }

    /**
     * 用户上行流量
     */
    public static void addSentBytes(int uid, long bytes) {
        sendTrafficMap.put(uid, sendTrafficMap.getOrDefault(uid, 0L) + bytes);
    }

    /**
     * 用户下行流量
     */
    public static void addReceivedBytes(int uid, long bytes) {
        receiveTrafficMap.put(uid, receiveTrafficMap.getOrDefault(uid, 0L) + bytes);
    }

    /**
     * 重置用户流量统计
     */
    public static void resetUserTraffic(int uid) {
        sendTrafficMap.put(uid, 0L);
        receiveTrafficMap.put(uid, 0L);
    }

    /**
     * 定时流量上报
     */
    private static class ScheduleTrafficReport implements Runnable {
        private final APIService apiService;
        private final String hostname;

        public ScheduleTrafficReport(String hostname, APIService apiService) {
            this.apiService = apiService;
            this.hostname = hostname;
        }

        public void run() {
            logger.info("start report traffic");
            long totalUpLinkBytes = 0L;
            long totalDownLinkBytes = 0L;

            // 获取所有用户id
            Set<Integer> userIdSet = new HashSet<>();
            userIdSet.addAll(sendTrafficMap.keySet());
            userIdSet.addAll(receiveTrafficMap.keySet());

            // 逐个用户获取流量
            List<long[]> userTrafficDataList = new ArrayList<>();
            for (Integer userId : userIdSet) {
                long sendBytes = sendTrafficMap.getOrDefault(userId, 0L);
                long receiveBytes = receiveTrafficMap.getOrDefault(userId, 0L);
                totalUpLinkBytes += sendBytes;
                totalDownLinkBytes += receiveBytes;

                // 跳过空的不上报
                if (sendBytes + receiveBytes == 0) {
                    continue;
                }

                long[] trafficData = new long[3];
                trafficData[0] = userId;
                trafficData[1] = sendBytes;
                trafficData[2] = receiveBytes;
                userTrafficDataList.add(trafficData);

                // reset
                resetUserTraffic(userId);
            }

            if (totalDownLinkBytes + totalUpLinkBytes == 0) {
                logger.info("no traffic to report");
                return;
            }

            // API上报
            apiService.reportTraffic(hostname, totalUpLinkBytes, totalDownLinkBytes, userTrafficDataList);

            logger.info("traffic stats, total upLink bytes: {}, total downLink bytes: {}", totalUpLinkBytes, totalDownLinkBytes);
        }
     }
}
