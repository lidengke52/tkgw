package com.dk.ipproxy.dynamic.gateway.initializer;

import com.dk.ipproxy.dynamic.gateway.authenticator.BasicAuthenticator;
import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import com.dk.ipproxy.dynamic.gateway.config.GlobalConfigStore;
import com.dk.ipproxy.dynamic.gateway.handler.*;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

import java.util.concurrent.TimeUnit;

/**
 * Http Channel初始化
 */
public class HttpWorkerChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final EventLoopGroup forwarders;
    private final HttpPasswordAuthRequestHandler httpPasswordAuthRequestHandler;
    private final TrafficStatsHandler trafficStatsHandler;
    private final HttpTrafficForwardHandler httpTrafficForwardHandler;
    private final ConnectionManageHandler connectManageHandler;

    public HttpWorkerChannelInitializer(EventLoopGroup forwarders) {
        this.forwarders = forwarders;
        // http密码验证处理器
        BasicAuthenticator basicAuthenticator = new BasicAuthenticator();
        // 账密验证处理器
        this.httpPasswordAuthRequestHandler = new HttpPasswordAuthRequestHandler(basicAuthenticator);
        // 流量统计处理器
        this.trafficStatsHandler = new TrafficStatsHandler();
        // Http命令请求处理器
        this.httpTrafficForwardHandler = new HttpTrafficForwardHandler(forwarders);
        // 连接管理
        this.connectManageHandler = new ConnectionManageHandler();
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) {
        // 获取Gateway配置
        GatewayConfig gatewayConfig = GlobalConfigStore.value.getGatewayConfig();

        // 设置Inbound和Outbound的pipeline
        ChannelPipeline pipeline = socketChannel.pipeline();

        // 空闲超时
        pipeline.addLast(new IdleStateHandler(gatewayConfig.getReadIdleTimeoutMillis(), gatewayConfig.getWriteIdleTimeoutMillis(), 0));
        pipeline.addLast(new IdleStateEventHandler());

        // 读写超时
        pipeline.addLast(new ReadTimeoutHandler(gatewayConfig.getReadTimeoutMillis(), TimeUnit.MILLISECONDS));
        pipeline.addLast(new WriteTimeoutHandler(gatewayConfig.getWriteTimeoutMillis(), TimeUnit.MILLISECONDS));

        // http编码器
        pipeline.addLast(HttpServerCodec.class.getName(), new HttpServerCodec(4096, gatewayConfig.getHttpRequestHeaderMaxSize(), 8192));

        // 账密验证
        pipeline.addLast(HttpPasswordAuthRequestHandler.class.getName(), httpPasswordAuthRequestHandler);

        // 流量统计
        pipeline.addLast(TrafficStatsHandler.class.getName(), trafficStatsHandler);

        // 连接管理
        pipeline.addLast(ConnectionManageHandler.class.getName(), connectManageHandler);

        // CONNECT请求优先于普通HTTP聚合处理
        pipeline.addLast(HttpConnectRequestHandler.class.getName(), new HttpConnectRequestHandler(forwarders));
        pipeline.addLast(HttpObjectAggregator.class.getName(), new HttpObjectAggregator(gatewayConfig.getHttpObjectAggregatorSize()));

        // 普通HTTP请求
        pipeline.addLast(HttpTrafficForwardHandler.class.getName(), httpTrafficForwardHandler);
    }
}
