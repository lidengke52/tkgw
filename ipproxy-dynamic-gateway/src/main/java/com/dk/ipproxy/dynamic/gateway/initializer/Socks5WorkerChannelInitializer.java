package com.dk.ipproxy.dynamic.gateway.initializer;

import com.dk.ipproxy.dynamic.gateway.authenticator.BasicAuthenticator;
import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import com.dk.ipproxy.dynamic.gateway.config.GlobalConfigStore;
import com.dk.ipproxy.dynamic.gateway.handler.*;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

import java.util.concurrent.TimeUnit;

/**
 * Socks5 Channel初始化
 */
public class Socks5WorkerChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final Socks5InitialRequestHandler socks5InitialRequestHandler;
    private final Socks5PasswordAuthRequestHandler socks5PasswordAuthRequestHandler;
    private final Socks5CommandRequestHandler socks5CommandRequestHandler;
    private final TrafficStatsHandler trafficStatsHandler;
    private final ConnectionManageHandler connectManageHandler;

    public Socks5WorkerChannelInitializer(EventLoopGroup forwarders) {
        // 初始化线程安全的shared handler
        // Socks5初始化请求处理
        this.socks5InitialRequestHandler = new Socks5InitialRequestHandler();
        // Socks5账密验证请求处理
        BasicAuthenticator basicAuthenticator = new BasicAuthenticator();
        this.socks5PasswordAuthRequestHandler = new Socks5PasswordAuthRequestHandler(basicAuthenticator);
        // Socks5 Command请求处理
        this.socks5CommandRequestHandler = new Socks5CommandRequestHandler(forwarders);
        // 流量统计
        this.trafficStatsHandler = new TrafficStatsHandler();
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

        // 负责将输出的 Socks5Message 转为 ByteBuf
        pipeline.addLast(Socks5ServerEncoder.DEFAULT);

        // 初始化请求
        pipeline.addLast(Socks5InitialRequestDecoder.class.getName(), new Socks5InitialRequestDecoder());
        pipeline.addLast(Socks5InitialRequestHandler.class.getName(), socks5InitialRequestHandler);

        // 账密验证请求
        pipeline.addLast(Socks5PasswordAuthRequestDecoder.class.getName(), new Socks5PasswordAuthRequestDecoder());
        pipeline.addLast(Socks5PasswordAuthRequestHandler.class.getName(), socks5PasswordAuthRequestHandler);

        // 流量统计
        pipeline.addLast(TrafficStatsHandler.class.getName(), trafficStatsHandler);

        // 连接管理
        pipeline.addLast(ConnectionManageHandler.class.getName(), connectManageHandler);

        // Command请求
        pipeline.addLast(Socks5CommandRequestDecoder.class.getName(), new Socks5CommandRequestDecoder());
        pipeline.addLast(Socks5CommandRequestHandler.class.getName(), socks5CommandRequestHandler);
    }
}
