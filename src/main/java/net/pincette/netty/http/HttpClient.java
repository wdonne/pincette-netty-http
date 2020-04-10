package net.pincette.netty.http;

import static io.netty.handler.ssl.SslContextBuilder.forClient;
import static java.lang.Integer.parseInt;
import static net.pincette.util.Pair.pair;
import static net.pincette.util.Util.rethrow;
import static net.pincette.util.Util.tryToGetRethrow;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.pincette.rs.LambdaSubscriber;
import net.pincette.rs.NopSubscription;
import net.pincette.util.Pair;
import org.reactivestreams.Subscriber;

/**
 * A simple HTTP client for Netty.
 *
 * @author Werner Donn\u00e9
 * @since 1.1
 */
public class HttpClient {
  private static final String HOST = "Host";
  private static final String HTTP = "http";
  private static final String HTTPS = "https";

  final NioEventLoopGroup group = new NioEventLoopGroup();

  private static ChannelInitializer<SocketChannel> createPipeline(
      final String scheme,
      final String host,
      final int port,
      final CompletableFuture<HttpResponse> future,
      final Subscriber<ByteBuf> responseBody) {
    return new ChannelInitializer<SocketChannel>() {
      @Override
      public void initChannel(final SocketChannel ch) {
        final ChannelPipeline pipeline = ch.pipeline();

        if (HTTPS.equals(scheme)) {
          pipeline.addLast(
              "ssl",
              tryToGetRethrow(() -> forClient().build())
                  .map(context -> context.newHandler(ch.alloc(), host, port))
                  .orElse(null));
        }

        pipeline
            .addLast(new HttpClientCodec())
            .addLast(new ChunkedWriteHandler())
            .addLast(
                new HttpHandler(
                    future, responseBody != null ? responseBody : new LambdaSubscriber<>(b -> {})));
      }
    };
  }

  private static int defaultPort(final URI uri) {
    return Optional.ofNullable(uri.getScheme())
        .map(scheme -> HTTPS.equals(scheme) ? 443 : 80)
        .orElse(80);
  }

  private static String getHost(final HttpRequest request) {
    return Optional.ofNullable(request.headers().get(HOST))
        .map(HttpClient::splitHost)
        .map(pair -> pair.first)
        .orElse(null);
  }

  private static int getPort(final HttpRequest request) {
    return Optional.ofNullable(request.headers().get(HOST))
        .map(HttpClient::splitHost)
        .map(pair -> pair.second)
        .orElseGet(
            () ->
                tryToGetRethrow(() -> new URI(request.uri()))
                    .map(uri -> uri.getPort() != -1 ? uri.getPort() : defaultPort(uri))
                    .orElse(-1));
  }

  private static String getScheme(final HttpRequest request) {
    return tryToGetRethrow(() -> new URI(request.uri())).map(URI::getScheme).orElse(HTTP);
  }

  private static void setHost(final FullHttpRequest request) {
    tryToGetRethrow(() -> new URI(request.uri()))
        .filter(uri -> uri.getHost() != null)
        .ifPresent(
            uri ->
                request
                    .setUri(uri.getPath())
                    .headers()
                    .add(HOST, uri.getHost() + (uri.getPort() != -1 ? (":" + uri.getPort()) : "")));
  }

  private static Pair<String, Integer> splitHost(final String host) {
    return Optional.of(host.split(":"))
        .map(parts -> pair(parts[0], parts.length > 1 ? parseInt(parts[1]) : null))
        .orElse(null);
  }
  /**
   * The function completes when the response has arrived, before the response body is emitted.
   *
   * @param request the request object, which must not be <code>null</code>.
   * @param responseBody the response body subscriber. If it is <code>null</code> the response body
   *     will be consumed and discarded.
   * @return The response object.
   * @since 1.1
   */
  public CompletionStage<HttpResponse> request(
      final FullHttpRequest request, final Subscriber<ByteBuf> responseBody) {
    final int port = getPort(request);
    final String scheme = getScheme(request);

    setHost(request);

    final String host = getHost(request);
    final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
    final ChannelFuture cf =
        new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(createPipeline(scheme, host, port, future, responseBody))
            .connect(host, port);

    cf.addListener(
        f -> {
          if (!f.isSuccess()) {
            rethrow(f.cause());
          } else {
            cf.channel().writeAndFlush(request);
          }
        });

    return future;
  }

  private static class HttpHandler extends ChannelInboundHandlerAdapter {
    private final CompletableFuture<HttpResponse> future;
    private final Subscriber<ByteBuf> responseBody;

    private HttpHandler(
        final CompletableFuture<HttpResponse> future, final Subscriber<ByteBuf> responseBody) {
      this.future = future;
      this.responseBody = responseBody;
      responseBody.onSubscribe(new NopSubscription());
    }

    @Override
    public void channelRead(final ChannelHandlerContext context, final Object message) {
      if (message instanceof HttpResponse) {
        future.complete((HttpResponse) message);
      } else if (message instanceof LastHttpContent) {
        responseBody.onNext(((LastHttpContent) message).content());
        responseBody.onComplete();
      } else if (message instanceof HttpContent) {
        responseBody.onNext(((HttpContent) message).content());
      }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
      rethrow(cause);
    }
  }
}
