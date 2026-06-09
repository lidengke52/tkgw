package com.dk.ipproxy.dynamic.gateway.initializer;

import com.dk.ipproxy.dynamic.gateway.config.GatewayConfig;
import com.dk.ipproxy.dynamic.gateway.config.GlobalConfigStore;
import com.dk.ipproxy.dynamic.gateway.config.UserConfig;
import com.dk.ipproxy.dynamic.gateway.config.WhiteListConfig;
import com.dk.ipproxy.dynamic.gateway.constants.Protocol;
import com.dk.ipproxy.dynamic.gateway.context.ChannelContext;
import com.dk.ipproxy.dynamic.gateway.context.ContextAttrKey;
import com.dk.ipproxy.dynamic.gateway.handler.*;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 白名单&多协议路由Channel
 * <br/>
 * 白名单下无需进行账密验证
 */
public class WhiteListRoutingWorkerChannelInitializer extends ChannelInitializer<SocketChannel>  {
    private static final Logger logger = LoggerFactory.getLogger(WhiteListRoutingWorkerChannelInitializer.class);

    private final EventLoopGroup forwarders;
    private final TrafficStatsHandler trafficStatsHandler;
    private final ConnectionManageHandler connectManageHandler;
    private final HttpTrafficForwardHandler httpTrafficForwardHandler;
    private final Socks5NoAuthInitialRequestHandler socks5NoAuthInitialRequestHandler;
    private final Socks5CommandRequestHandler socks5CommandRequestHandler;

    public WhiteListRoutingWorkerChannelInitializer(EventLoopGroup forwarders) {
        this.forwarders = forwarders;
        // 流量统计处理器
        this.trafficStatsHandler = new TrafficStatsHandler();
        // 连接管理
        this.connectManageHandler = new ConnectionManageHandler();

        // Http命令请求处理器
        this.httpTrafficForwardHandler = new HttpTrafficForwardHandler(forwarders);

        // Socks5初始化请求处理
        this.socks5NoAuthInitialRequestHandler = new Socks5NoAuthInitialRequestHandler();
        // Socks5 Command请求处理
        this.socks5CommandRequestHandler = new Socks5CommandRequestHandler(forwarders);
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) {
        // 获取本地绑定的端口
        int bindPort = socketChannel.localAddress().getPort();
        // 获取客户端的IP，IP+监听端口可以唯一确定一个代理连接以及协议
        InetSocketAddress remoteAddress = socketChannel.remoteAddress();
        String clientIP = remoteAddress.getAddress().getHostAddress();
        int clientPort = remoteAddress.getPort();

        // 获取Gateway配置
        GatewayConfig gatewayConfig = GlobalConfigStore.value.getGatewayConfig();

        // 根据客户端的endpoint判断协议以及proxy的upstream
        if (logger.isDebugEnabled()) {
            logger.debug("start judge protocol by client endpoint, {}:{} <--> 0.0.0.0:{}", clientIP, clientPort, bindPort);
        }

        // 获取白名单配置
        Map<String, WhiteListConfig> whiteListConfigMap = GlobalConfigStore.value.getWhiteListConfigMap();
        if (whiteListConfigMap == null || whiteListConfigMap.isEmpty()) {
            socketChannel.close();
            return;
        }
        WhiteListConfig whiteListConfig = whiteListConfigMap.get(clientIP);
        if (whiteListConfig == null) {
            socketChannel.close();
            return;
        }

        // 获取用户配置
        int uid = whiteListConfig.getUid();
        UserConfig userConfig = GlobalConfigStore.value.getUserConfigMapForUid().get(uid);
        if (userConfig == null) {
            socketChannel.close();
            return;
        }

        String chooseProtocol = chooseProtocol(whiteListConfig, bindPort);
        if (Protocol.HTTP.equals(chooseProtocol)) {
            if (logger.isDebugEnabled()) {
                logger.debug("choose protocol success, protocol: {}, {}:{} <--> 0.0.0.0:{}", chooseProtocol, clientIP, clientPort, bindPort);
            }

            // 初始化 pipeline
            ChannelPipeline pipeline = socketChannel.pipeline();

            // 空闲超时
            pipeline.addLast(new IdleStateHandler(gatewayConfig.getReadIdleTimeoutMillis(), gatewayConfig.getWriteIdleTimeoutMillis(), 0));
            pipeline.addLast(new IdleStateEventHandler());

            // 读写超时
            pipeline.addLast(new ReadTimeoutHandler(gatewayConfig.getReadTimeoutMillis(), TimeUnit.MILLISECONDS));
            pipeline.addLast(new WriteTimeoutHandler(gatewayConfig.getWriteTimeoutMillis(), TimeUnit.MILLISECONDS));

            // http编码器
            pipeline.addLast(HttpServerCodec.class.getName(), new HttpServerCodec(4096, gatewayConfig.getHttpRequestHeaderMaxSize(), 8192));

            // 流量统计
            pipeline.addLast(TrafficStatsHandler.class.getName(), trafficStatsHandler);

            // 连接管理
            pipeline.addLast(ConnectionManageHandler.class.getName(), connectManageHandler);

            // CONNECT请求优先于普通HTTP聚合处理
            pipeline.addLast(HttpConnectRequestHandler.class.getName(), new HttpConnectRequestHandler(forwarders));
            pipeline.addLast(HttpObjectAggregator.class.getName(), new HttpObjectAggregator(gatewayConfig.getHttpObjectAggregatorSize()));

            // 普通HTTP请求
            pipeline.addLast(HttpTrafficForwardHandler.class.getName(), httpTrafficForwardHandler);

        } else if (Protocol.SOCKS5.equals(chooseProtocol)) {
            if (logger.isDebugEnabled()) {
                logger.debug("choose protocol success, protocol: {}, {}:{} <--> 0.0.0.0:{}", chooseProtocol, clientIP, clientPort, bindPort);
            }

            // 初始化 pipeline
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
            pipeline.addLast(Socks5NoAuthInitialRequestHandler.class.getName(), socks5NoAuthInitialRequestHandler);

            // 流量统计
            pipeline.addLast(TrafficStatsHandler.class.getName(), trafficStatsHandler);

            // 连接管理
            pipeline.addLast(ConnectionManageHandler.class.getName(), connectManageHandler);

            // Command请求
            pipeline.addLast(Socks5CommandRequestDecoder.class.getName(), new Socks5CommandRequestDecoder());
            pipeline.addLast(Socks5CommandRequestHandler.class.getName(), socks5CommandRequestHandler);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("choose protocol failed, close connection, {}:{} <--> 0.0.0.0:{}", clientIP, clientPort, bindPort);
            }
            socketChannel.close();
            return;
        }

        // 设置upstream连接信息到context中
        setUpstreamContext(socketChannel, userConfig, whiteListConfig, bindPort);
    }

    private String chooseProtocol(WhiteListConfig whiteListConfig, int bindPort) {
        // 映射端口
        WhiteListConfig.PortDynamicProxyInfo portDynamicProxyInfo = whiteListConfig.getPortDynamicProxyInfoMap().get(bindPort);
        if (portDynamicProxyInfo == null) {
            return null;
        }

        // 获取协议
        return portDynamicProxyInfo.getProtocol();
    }

    private void setUpstreamContext(SocketChannel channel, UserConfig userConfig, WhiteListConfig whiteListConfig, int bindPort) {
        // 获取upstream国家信息
        WhiteListConfig.PortDynamicProxyInfo portDynamicProxyInfo = whiteListConfig.getPortDynamicProxyInfoMap().get(bindPort);
        String area = portDynamicProxyInfo.getArea();
        String region = portDynamicProxyInfo.getRegion();
        String city = portDynamicProxyInfo.getCity();
        int keepMinutes = portDynamicProxyInfo.getMinutes();

        // 设置context信息
        ChannelContext channelContext = new ChannelContext();
        // 用户账密信息，通过uid获取对应的authUser和authPass，用于后面的upstream代理分配
        channelContext.setRawConnectUser(":");
        channelContext.setUid(userConfig.getUserId());
        channelContext.setAuthUser(userConfig.getAuthUser());
        channelContext.setAuthPass(userConfig.getAuthPass());
        // upstream国家信息
        channelContext.setForwardCountry(area);
        channelContext.setForwardState(region);
        channelContext.setForwardCity(city);
        // 控制一次一换还是session
        if (keepMinutes <= 0) {
            // 一次一换
            channelContext.setForwardSessionId(null);
        } else {
            // 设置session过期时间
            // sessionID，端口作为session
            channelContext.setForwardSessionId(String.valueOf(bindPort));
            channelContext.setForwardSessionKeepTime(keepMinutes * 60);
        }
        channel.attr(ContextAttrKey.CHANNEL_FORWARD_CONTEXT).setIfAbsent(channelContext);
    }
}
