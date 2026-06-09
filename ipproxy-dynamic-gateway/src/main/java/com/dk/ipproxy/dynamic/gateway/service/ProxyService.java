package com.dk.ipproxy.dynamic.gateway.service;

import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import com.dk.ipproxy.dynamic.gateway.config.GlobalConfigStore;
import com.dk.ipproxy.dynamic.gateway.initializer.HttpWorkerChannelInitializer;
import com.dk.ipproxy.dynamic.gateway.initializer.Socks5WorkerChannelInitializer;
import com.dk.ipproxy.dynamic.gateway.initializer.WhiteListRoutingWorkerChannelInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ProxyService {
    private static final Logger logger = LoggerFactory.getLogger(ProxyService.class);

    public ProxyService() {
    }

    public void start() throws Exception {
        // 获取配置
        GatewayConfig gatewayConfig = GlobalConfigStore.value.getGatewayConfig();

        // 三套服务共享同一组接收、工作和转发线程，减少线程与本地缓存的基线开销
        EventLoopGroup acceptors = new NioEventLoopGroup(gatewayConfig.getListenThreads());
        EventLoopGroup workers = createWorkerEventLoopGroup(gatewayConfig);
        EventLoopGroup forwarders = createWorkerEventLoopGroup(gatewayConfig);

        // 启动服务
        try {
            ChannelFuture socks5Future = startSocks5Server(gatewayConfig, acceptors, workers, forwarders);
            ChannelFuture httpFuture = startHttpServer(gatewayConfig, acceptors, workers, forwarders);
            List<Channel> whiteListChannels = startWhiteListRoutingServer(gatewayConfig, acceptors, workers, forwarders);

            socks5Future.channel().closeFuture().sync();
            httpFuture.channel().closeFuture().sync();
            for (Channel channel : whiteListChannels) {
                channel.closeFuture().sync();
            }
        } finally {
            forwarders.shutdownGracefully();
            workers.shutdownGracefully();
            acceptors.shutdownGracefully();
        }
    }

    private EventLoopGroup createWorkerEventLoopGroup(GatewayConfig gatewayConfig) {
        if (gatewayConfig.getWorkerThreads() > 0) {
            return new NioEventLoopGroup(gatewayConfig.getWorkerThreads());
        }
        return new NioEventLoopGroup();
    }

    /**
     * 固定端口，账密验证，动态代理，http协议
     */
    private ChannelFuture startHttpServer(GatewayConfig gatewayConfig, EventLoopGroup acceptors, EventLoopGroup workers, EventLoopGroup forwarders) throws Exception {
        logger.info("start http proxy service...");
        // 创建Http服务
        WriteBufferWaterMark writeBufferWaterMark = createWriteBufferWaterMark(gatewayConfig);
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(acceptors, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.SO_BACKLOG, gatewayConfig.getBackLog())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, gatewayConfig.getConnectTimeoutMillis())
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .childHandler(new HttpWorkerChannelInitializer(forwarders));

        // 启动服务
        logger.info("bind http proxy service on {}:{}", gatewayConfig.getListenHost(), gatewayConfig.getListenHttpPort());
        ChannelFuture future = bootstrap.bind(gatewayConfig.getListenHost(), gatewayConfig.getListenHttpPort()).sync();
        logger.info("start http proxy service successfully");
        return future;
    }

    /**
     * 固定端口，账密验证，动态代理，socks5协议
     */
    private ChannelFuture startSocks5Server(GatewayConfig gatewayConfig, EventLoopGroup acceptors, EventLoopGroup workers, EventLoopGroup forwarders) throws Exception {
        logger.info("start socks5 proxy service...");
        // 创建Socks5服务
        WriteBufferWaterMark writeBufferWaterMark = createWriteBufferWaterMark(gatewayConfig);
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(acceptors, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.SO_BACKLOG, gatewayConfig.getBackLog())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, gatewayConfig.getConnectTimeoutMillis())
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .childHandler(new Socks5WorkerChannelInitializer(forwarders));

        // 启动服务
        logger.info("bind socks5 proxy service on {}:{}", gatewayConfig.getListenHost(), gatewayConfig.getListenSocks5Port());
        ChannelFuture future = bootstrap.bind(gatewayConfig.getListenHost(), gatewayConfig.getListenSocks5Port()).sync();
        logger.info("start socks5 proxy service successfully");
        return future;
    }

    /**
     * 范围端口，白名单验证，动态代理，http&socks5双协议
     */
    private List<Channel> startWhiteListRoutingServer(GatewayConfig gatewayConfig, EventLoopGroup acceptors, EventLoopGroup workers, EventLoopGroup forwarders) throws Exception {
        logger.info("start whiteList routing service...");
        // 创建服务
        WriteBufferWaterMark writeBufferWaterMark = createWriteBufferWaterMark(gatewayConfig);
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(acceptors, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.SO_BACKLOG, gatewayConfig.getBackLog())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, gatewayConfig.getConnectTimeoutMillis())
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .childHandler(new WhiteListRoutingWorkerChannelInitializer(forwarders));

        // 启动服务
        List<Channel> channels = new ArrayList<>();
        for (int port = gatewayConfig.getWhiteListPortRangeStart(); port <= gatewayConfig.getWhiteListPortRangeEnd(); port++) {
            logger.info("bind whiteList routing service on {}:{}", gatewayConfig.getListenHost(), port);
            ChannelFuture future = bootstrap.bind(gatewayConfig.getListenHost(), port).sync();
            channels.add(future.channel());
        }
        logger.info("start whiteList routing service successfully");
        return channels;
    }

    private WriteBufferWaterMark createWriteBufferWaterMark(GatewayConfig gatewayConfig) {
        return new WriteBufferWaterMark(
                gatewayConfig.getWriteBufferLowWaterMark(),
                gatewayConfig.getWriteBufferHighWaterMark()
        );
    }
}
