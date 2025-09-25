package net.pincette.netty.http;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.channel.ChannelOption.AUTO_READ;
import static io.netty.channel.ChannelOption.SO_BACKLOG;
import static io.netty.channel.ChannelOption.SO_KEEPALIVE;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;
import static io.netty.handler.codec.http.HttpUtil.setKeepAlive;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.ofNullable;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Logger.getLogger;
import static net.pincette.rs.Serializer.dispatch;
import static net.pincette.util.Util.getStackTrace;
import static net.pincette.util.Util.tryToDoRethrow;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
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
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.logging.Logger;

/**
 * A simple HTTP server on top of Netty.
 *
 * @author Werner Donné
 * @since 1.0
 */
public class HttpServer implements Closeable {
  private static final Logger LOGGER = getLogger("net.pincette.netty.http");

  private final EventLoopGroup masterGroup = new NioEventLoopGroup();
  private final EventLoopGroup slaveGroup = new NioEventLoopGroup();
  private final ChannelFuture channel;

  /**
   * Sets up an HTTP server.
   *
   * @param port the port to bind to.
   * @param requestHandler the function to which requests are passed.
   * @since 1.0
   */
  public HttpServer(final int port, final RequestHandler requestHandler) {
    channel = createChannel(masterGroup, slaveGroup, port, requestHandler);
  }

  /**
   * Sets up an HTTP server.
   *
   * @param port the port to bind to.
   * @param requestHandler the function to which requests are passed.
   * @since 1.0
   */
  public HttpServer(final int port, final RequestHandlerAccumulated requestHandler) {
    this(port, accumulate(requestHandler));
  }

  public static RequestHandler accumulate(final RequestHandlerAccumulated requestHandler) {
    return (request, requestBody, response) -> {
      final RequestAccumulator accumulator =
          new RequestAccumulator(request, response, requestHandler);

      requestBody.subscribe(accumulator);

      return accumulator.get();
    };
  }

  private static ChannelHandler childInitializer(final RequestHandler requestHandler) {
    return new ChannelInitializer<SocketChannel>() {
      @Override
      public void initChannel(final SocketChannel ch) {
        ch.pipeline()
            .addLast(new HttpServerCodec())
            .addLast(new HttpServerExpectContinueHandler())
            .addLast(new HttpContentCompressor())
            .addLast(new HttpContentDecompressor())
            .addLast(new HttpHandler(requestHandler));
      }
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
        .childOption(AUTO_READ, false)
        .childOption(SO_KEEPALIVE, true)
        .childHandler(childInitializer(requestHandler))
        .option(SO_BACKLOG, 128)
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
   * Starts the server and returns a future that completes when the server completes. The result
   * value indicates success.
   *
   * @return The completion stage.
   * @since 3.0
   */
  public CompletionStage<Boolean> run() {
    final CompletableFuture<Boolean> future = new CompletableFuture<>();

    channel.channel().closeFuture().addListener(f -> future.complete(f.isSuccess()));

    return future;
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
    private ChannelConsumer channelConsumer;

    private HttpHandler(final RequestHandler requestHandler) {
      this.requestHandler = requestHandler;
    }

    private static void internalServerError(
        final ChannelHandlerContext context, final Throwable cause) {
      LOGGER.log(SEVERE, cause.getMessage(), cause);
      context.writeAndFlush(
          new DefaultFullHttpResponse(
              HTTP_1_1, INTERNAL_SERVER_ERROR, wrappedBuffer(getStackTrace(cause).getBytes())));
    }

    @Override
    public void channelActive(final ChannelHandlerContext context) {
      channelConsumer = new ChannelConsumer();
      channelConsumer.active(context);
    }

    @Override
    public void channelRead(final ChannelHandlerContext context, final Object message) {
      switch (message) {
        case HttpRequest httpRequest -> handleRequest(context, httpRequest);
        case LastHttpContent lastHttpContent -> {
          if (message != EMPTY_LAST_CONTENT) {
            channelConsumer.read(lastHttpContent.content());
          }

          channelConsumer.complete();
        }
        case HttpContent httpContent -> channelConsumer.read(httpContent.content());
        default -> tryToDoRethrow(() -> super.channelRead(context, message));
      }
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext context) {
      channelConsumer.readCompleted(context);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
      internalServerError(context, cause);
    }

    private void handleRequest(final ChannelHandlerContext context, final HttpRequest request) {
      final boolean keepAlive = isKeepAlive(request);
      final HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);

      if (keepAlive) {
        setKeepAlive(response, true);
      }

      requestHandler
          .apply(request, channelConsumer::subscribe, response)
          .thenAccept(
              body ->
                  ofNullable(body)
                      .orElseGet(net.pincette.rs.Util::empty)
                      .subscribe(new ResponseStreamer(response, context)))
          .exceptionally(
              t -> {
                internalServerError(context, t);
                context.channel().close();
                return null;
              });
    }
  }

  private static class ResponseStreamer implements Subscriber<ByteBuf> {
    private final ChannelHandlerContext context;
    private final HttpResponse response;
    private boolean responseFlushed;
    private Subscription subscription;

    private ResponseStreamer(final HttpResponse response, final ChannelHandlerContext context) {
      this.response = response;
      this.context = context;
    }

    private void flushResponse() {
      if (!responseFlushed) {
        context.writeAndFlush(response);
        responseFlushed = true;
      }
    }

    public void onComplete() {
      dispatch(
          () -> {
            flushResponse();
            context
                .writeAndFlush(new DefaultLastHttpContent())
                .addListener(f -> context.channel().close());
          });
    }

    public void onError(final Throwable t) {
      dispatch(
          () -> {
            response.setStatus(INTERNAL_SERVER_ERROR);
            response.headers().set(CONTENT_TYPE, "text/plain");
            flushResponse();
            context.writeAndFlush(
                new DefaultHttpContent(wrappedBuffer(getStackTrace(t).getBytes(UTF_8))));
            onComplete();
          });
    }

    public void onNext(final ByteBuf buffer) {
      if (context.channel().isActive()) {
        dispatch(
            () -> {
              flushResponse();
              context.writeAndFlush(new DefaultHttpContent(buffer));
              subscription.request(1);
            });
      } else {
        subscription.cancel();
      }
    }

    public void onSubscribe(final Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }
  }
}
