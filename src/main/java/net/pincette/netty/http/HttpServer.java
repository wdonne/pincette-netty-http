package net.pincette.netty.http;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.channel.ChannelOption.SO_BACKLOG;
import static io.netty.channel.ChannelOption.SO_KEEPALIVE;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static net.pincette.util.Util.rethrow;
import static net.pincette.util.Util.tryToDoRethrow;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.io.Closeable;
import net.pincette.function.SideEffect;
import net.pincette.rs.NopSubscription;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A simple HTTP server on top of Netty.
 *
 * @author Werner Donn\u00e9
 * @since 1.0
 */
public class HttpServer implements Closeable {
  private final EventLoopGroup masterGroup = new NioEventLoopGroup();
  private final EventLoopGroup slaveGroup = new NioEventLoopGroup();
  private final ChannelFuture channel;

  /**
   * Sets up an HTTP server.
   *
   * @param port the port to bind to.
   * @param requestHandler the function to which requests are passed. The request body publisher
   *     doesn't support back pressure.
   * @since 1.0
   */
  public HttpServer(final int port, final RequestHandler requestHandler) {
    channel = createChannel(masterGroup, slaveGroup, port, requestHandler);
  }

  /**
   * Sets up an HTTP server.
   *
   * @param port the port to bind to.
   * @param requestHandler the function to which requests are passed. The request body publisher
   *     doesn't support back pressure.
   * @since 1.0
   */
  public HttpServer(final int port, final RequestHandlerAccumulated requestHandler) {
    this(port, accumulate(requestHandler));
  }

  private static RequestHandler accumulate(final RequestHandlerAccumulated requestHandler) {
    return (request, requestBody, response) -> {
      final Accumulator accumulator = new Accumulator(request, response, requestHandler);

      requestBody.subscribe(accumulator);

      return accumulator.get();
    };
  }

  private static ChannelFuture createChannel(
      final EventLoopGroup masterGroup,
      final EventLoopGroup slaveGroup,
      final int port,
      final RequestHandler requestHandler) {
    return new ServerBootstrap()
        .group(masterGroup, slaveGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              public void initChannel(final SocketChannel ch) {
                ch.pipeline()
                    .addLast("codec", new HttpServerCodec())
                    .addLast("request", new HttpHandler(requestHandler))
                    .addLast(new HttpResponseEncoder())
                    .addLast(new HttpContentCompressor());
              }
            })
        .option(SO_BACKLOG, 128)
        .childOption(SO_KEEPALIVE, true)
        .bind(port);
  }

  /**
   * Shuts down the server.
   *
   * @since 1.0
   */
  public void close() {
    slaveGroup.shutdownGracefully();
    masterGroup.shutdownGracefully();

    if (channel != null) {
      tryToDoRethrow(() -> channel.channel().closeFuture().sync());
    }
  }

  /**
   * Starts the server, which will block.
   *
   * @since 1.0
   */
  public void start() {
    tryToDoRethrow(() -> channel.channel().closeFuture().sync());
  }

  private static class HttpHandler extends ChunkedWriteHandler {
    private final RequestHandler requestHandler;
    private Subscriber<? super ByteBuf> subscriber;

    private HttpHandler(final RequestHandler requestHandler) {
      this.requestHandler = requestHandler;
    }

    private static void internalServerError(
        final ChannelHandlerContext context, final Throwable cause) {
      context.writeAndFlush(
          new DefaultFullHttpResponse(
              HTTP_1_1, INTERNAL_SERVER_ERROR, copiedBuffer(cause.getMessage().getBytes())));
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
      if (message instanceof HttpRequest) {
        handleRequest(context, (HttpRequest) message);
      } else if (message instanceof LastHttpContent) {
        if (subscriber != null) {
          subscriber.onNext(((LastHttpContent) message).content());
          subscriber.onComplete();
        }
      } else if (message instanceof HttpContent) {
        if (subscriber != null) {
          subscriber.onNext(((HttpContent) message).content());
        }
      } else {
        tryToDoRethrow(() -> super.channelRead(context, message));
      }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
      ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      internalServerError(context, cause);
    }

    private void handleRequest(final ChannelHandlerContext context, final HttpRequest request) {
      final boolean keepAlive = isKeepAlive(request);
      final HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);

      if (keepAlive) {
        response.headers().set(CONNECTION, KEEP_ALIVE);
      }

      response.headers().set("Transfer-Encoding", "chunked");

      requestHandler
          .apply(
              request,
              s -> {
                this.subscriber = s;
                s.onSubscribe(new NopSubscription());
              },
              response)
          .thenAccept(
              body ->
                  SideEffect.<Publisher<ByteBuf>>run(() -> context.writeAndFlush(response))
                      .andThenGet(() -> body)
                      .subscribe(new ResponseStreamer(context, keepAlive)));
    }
  }

  private static class ResponseStreamer implements Subscriber<ByteBuf> {
    private final ChannelHandlerContext context;
    private final boolean keepAlive;
    private Subscription subscription;

    private ResponseStreamer(final ChannelHandlerContext context, final boolean keepAlive) {
      this.context = context;
      this.keepAlive = keepAlive;
    }

    public void onComplete() {
      context
          .writeAndFlush(new DefaultLastHttpContent())
          .addListener(
              keepAlive ? (f -> context.channel().flush()) : (f -> context.channel().close()));
    }

    public void onError(final Throwable t) {
      rethrow(t);
    }

    public void onNext(final ByteBuf buffer) {
      context.writeAndFlush(new DefaultHttpContent(buffer));
      subscription.request(1);
    }

    public void onSubscribe(final Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }
  }
}
