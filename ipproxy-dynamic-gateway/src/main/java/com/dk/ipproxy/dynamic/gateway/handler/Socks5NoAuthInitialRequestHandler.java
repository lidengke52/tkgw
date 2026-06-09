package com.dk.ipproxy.dynamic.gateway.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v5.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Socks5无账密验证请求处理Handler
 */
@ChannelHandler.Sharable
public class Socks5NoAuthInitialRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5InitialRequest> {
    private static final Logger logger = LoggerFactory.getLogger(Socks5NoAuthInitialRequestHandler.class);

    @Override
    protected void channelRead0(ChannelHandlerContext context, DefaultSocks5InitialRequest initialRequest) {
        // 完成初始化请求解码后，从Pipeline中移除
        ChannelPipeline pipeline = context.pipeline();
        pipeline.remove(Socks5InitialRequestDecoder.class.getName());
        pipeline.remove(this);


        // 检查解码是否成功
        if (initialRequest.decoderResult().isFailure()) {
            if (logger.isWarnEnabled()) {
                logger.warn("initialRequest decode failed", initialRequest.decoderResult().cause());
            }
            context.close();
            return;
        }


        // 检查Socks5版本
        if (!initialRequest.version().equals(SocksVersion.SOCKS5)) {
            if (logger.isWarnEnabled()) {
                logger.warn("Unsupported SOCKS version: {}", initialRequest.version());
            }
            context.close();
            return;
        }

        // 核心初始化逻辑
        if (initialRequest.authMethods().contains(Socks5AuthMethod.NO_AUTH)) {
            if (logger.isDebugEnabled()) {
                logger.debug("socks5 initialize with NO_AUTH");
            }
            Socks5InitialResponse response = new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH);
            context.writeAndFlush(response);
        } else {
            if (logger.isWarnEnabled()) {
                logger.warn("Unsupported auth method. Closing connection.");
            }
            // 响应一个不可接受的认证方法，根据RFC应该是 0xFF
            Socks5InitialResponse response = new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED);
            context.writeAndFlush(response).addListener(future -> context.close());
        }
    }
}
