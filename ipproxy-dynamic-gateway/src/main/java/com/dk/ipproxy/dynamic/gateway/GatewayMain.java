package com.dk.ipproxy.dynamic.gateway;

import com.dk.ipproxy.dynamic.gateway.cache.ChannelStateStore;
import com.dk.ipproxy.dynamic.gateway.cache.RequestCounter;
import com.dk.ipproxy.dynamic.gateway.cache.ResourceUsageReporter;
import com.dk.ipproxy.dynamic.gateway.cache.SessionStateStore;
import com.dk.ipproxy.dynamic.gateway.cache.TrafficCounter;
import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import com.dk.ipproxy.dynamic.gateway.config.GlobalConfigStore;
import com.dk.ipproxy.dynamic.gateway.filter.DomainFilter;
import com.dk.ipproxy.dynamic.gateway.filter.IPFilter;
import com.dk.ipproxy.dynamic.gateway.service.ProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatewayMain {
    private static final Logger logger = LoggerFactory.getLogger(GatewayMain.class);

    private void start(String[] cmdArgs) throws Exception {
        logger.info("starting gateway...");

        // 初始化配置Store
        GlobalConfigStore configStore = GlobalConfigStore.value;
        if (!configStore.setupGatewayStaticConfig(cmdArgs)) {
            logger.info("gateway exited before startup, please check logs for details");
            return;
        }
        GatewayConfig gatewayConfig = configStore.getGatewayConfig();
        configStore.setLoggerLevel();
        configStore.setupGatewayDynamicConfig();

        // 初始化Filter
        IPFilter.init();
        DomainFilter.init();

        // 初始化Session状态Store
        SessionStateStore.init(gatewayConfig);

        // 初始化Channel状态Store
        ChannelStateStore.init(gatewayConfig);

        // 初始化流量统计Counter
        TrafficCounter.init(gatewayConfig);
        // 初始化请求统计Counter
        RequestCounter.init(gatewayConfig);
        // 初始化资源使用观测
        ResourceUsageReporter.init(gatewayConfig);

        // 新增一个shutdown hooker
        Runtime.getRuntime().addShutdownHook(new Thread(() -> logger.info("shutting down gateway successfully")));

        // 启动代理转发服务
        ProxyService proxyService = new ProxyService();
        proxyService.start();
    }

    public static void main(String[] args) throws Exception {
        new GatewayMain().start(args);
    }
}
