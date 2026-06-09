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
 * Socks5初始化请求处理Handler
 */
@ChannelHandler.Sharable
public class Socks5InitialRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5InitialRequest> {
    private static final Logger logger = LoggerFactory.getLogger(Socks5InitialRequestHandler.class);

    @Override
    protected void channelRead0(ChannelHandlerContext context, DefaultSocks5InitialRequest initialRequest) {
        // 完成初始化请求解码后，从Pipeline中移除
        ChannelPipeline pipeline = context.pipeline();
        pipeline.remove(Socks5InitialRequestDecoder.class.getName());
        pipeline.remove(this);

        if (initialRequest.decoderResult().isFailure()) {
            if (logger.isDebugEnabled()) {
                logger.debug("initialRequest decode failed");
            }
            context.fireChannelRead(initialRequest);
        } else {
            if (initialRequest.version().equals(SocksVersion.SOCKS5)){
                if (logger.isDebugEnabled()) {
                    logger.debug("socks5 initialize with AUTH");
                }
                Socks5InitialResponse response = new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD);
                context.writeAndFlush(response);
            } else {
                if (logger.isWarnEnabled()) {
                    SocksVersion version = initialRequest.version();
                    logger.warn(String.format("unsupported version: %s(%d)", version.name(), version.byteValue()));
                }
                context.fireChannelRead(initialRequest);
            }
        }
    }
}
