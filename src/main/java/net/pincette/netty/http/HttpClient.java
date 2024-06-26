package net.pincette.netty.http;

import static io.netty.channel.ChannelOption.AUTO_READ;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.EXPECTATION_FAILED;
import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.GONE;
import static io.netty.handler.codec.http.HttpResponseStatus.LENGTH_REQUIRED;
import static io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static io.netty.handler.codec.http.HttpResponseStatus.MOVED_PERMANENTLY;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import static io.netty.handler.codec.http.HttpResponseStatus.NO_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.PERMANENT_REDIRECT;
import static io.netty.handler.codec.http.HttpResponseStatus.PRECONDITION_FAILED;
import static io.netty.handler.codec.http.HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
import static io.netty.handler.codec.http.HttpResponseStatus.REQUEST_TIMEOUT;
import static io.netty.handler.codec.http.HttpResponseStatus.REQUEST_URI_TOO_LONG;
import static io.netty.handler.codec.http.HttpResponseStatus.RESET_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.SEE_OTHER;
import static io.netty.handler.codec.http.HttpResponseStatus.TEMPORARY_REDIRECT;
import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;
import static io.netty.handler.ssl.SslContextBuilder.forClient;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.pincette.netty.http.Util.simpleResponse;
import static net.pincette.rs.Serializer.dispatch;
import static net.pincette.rs.Util.devNull;
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
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import net.pincette.function.SideEffect;

/**
 * A simple HTTP client for Netty. It uses reactive streams with backpressure.
 *
 * @author Werner Donné
 * @since 1.1
 * @deprecated Use <code>java.net.http>HttpClient</code> instead.
 */
@Deprecated(since = "3.2.0", forRemoval = true)
public class HttpClient {
  private static final String HOST = "Host";
  private static final String HTTPS = "https";
  private static final String SSL = "ssl";

  final boolean followRedirects;
  final NioEventLoopGroup group = new NioEventLoopGroup();
  final SslContext sslContext;

  public HttpClient() {
    this(null, true);
  }

  private HttpClient(final SslContext sslContext, final boolean followRedirects) {
    this.sslContext = sslContext;
    this.followRedirects = followRedirects;
  }

  private static boolean bodyAbsent(final HttpResponseStatus status) {
    return NO_CONTENT == status
        || RESET_CONTENT == status
        || NOT_MODIFIED == status
        || NOT_FOUND == status
        || METHOD_NOT_ALLOWED == status
        || REQUEST_TIMEOUT == status
        || GONE == status
        || LENGTH_REQUIRED == status
        || PRECONDITION_FAILED == status
        || REQUEST_ENTITY_TOO_LARGE == status
        || REQUEST_URI_TOO_LONG == status
        || EXPECTATION_FAILED == status;
  }

  private static ChannelInitializer<SocketChannel> createPipeline(
      final String scheme,
      final String host,
      final int port,
      final SslContext sslContext,
      final CompletableFuture<HttpResponse> future,
      final Subscriber<? super ByteBuf> responseBody) {
    return new ChannelInitializer<>() {
      @Override
      public void initChannel(final SocketChannel ch) {
        final ChannelPipeline pipeline = ch.pipeline();

        if (HTTPS.equals(scheme)) {
          pipeline.addLast(SSL, sslHandler(host, port, ch, sslContext));
        }

        pipeline
            .addLast(new HttpClientCodec())
            .addLast(new HttpContentDecompressor())
            .addLast(new ChunkedWriteHandler())
            .addLast(new HttpHandler(future, responseBody));
      }
    };
  }

  private static int defaultPort(final URI uri) {
    return ofNullable(uri.getScheme()).map(scheme -> HTTPS.equals(scheme) ? 443 : 80).orElse(80);
  }

  private static String fullHost(final URI uri) {
    return uri.getHost()
        + Optional.of(uri.getPort()).filter(port -> port != -1).map(port -> ":" + port).orElse("");
  }

  private static int getPort(final URI uri) {
    return Optional.of(uri.getPort()).filter(port -> port != -1).orElseGet(() -> defaultPort(uri));
  }

  private static String getRedirectUri(final String uri, final HttpRequest request) {
    return tryToGetRethrow(() -> new URI(uri))
        .filter(URI::isAbsolute)
        .map(u -> uri)
        .orElseGet(() -> getUri(request).resolve(requireNonNull(uri)).toASCIIString());
  }

  private static URI getUri(final HttpRequest request) {
    return tryToGetRethrow(() -> new URI(request.uri()))
        .filter(URI::isAbsolute)
        .orElseThrow(() -> new IllegalArgumentException("The URI must be absolute."));
  }

  public static boolean hasBody(final HttpResponse response) {
    return (response.headers().contains(CONTENT_LENGTH)
            || response.headers().contains(TRANSFER_ENCODING))
        && !bodyAbsent(response.status());
  }

  private static boolean isRedirect(final HttpResponseStatus status) {
    return status == MOVED_PERMANENTLY
        || status == FOUND
        || status == SEE_OTHER
        || status == TEMPORARY_REDIRECT
        || status == PERMANENT_REDIRECT;
  }

  private static Redirectable newRedirectable(final Subscriber<? super ByteBuf> responseBody) {
    final Redirectable redirectable = new Redirectable();

    redirectable.subscribe(responseBody != null ? responseBody : devNull());

    return redirectable;
  }

  private static SslHandler sslHandler(
      final String host, final int port, final SocketChannel ch, final SslContext sslContext) {
    return (sslContext != null
            ? Optional.of(sslContext)
            : tryToGetRethrow(() -> forClient().build()))
        .map(context -> context.newHandler(ch.alloc(), host, port))
        .orElse(null);
  }

  private CompletionStage<HttpResponse> redirect(
      final FullHttpRequest request,
      final Subscriber<? super ByteBuf> responseBody,
      final HttpResponse response) {
    return ofNullable(response.headers().get(LOCATION))
        .map(
            location ->
                request(
                    request
                        .setUri(getRedirectUri(location, request))
                        .setMethod(response.status() == SEE_OTHER ? GET : request.method()),
                    responseBody))
        .orElseGet(() -> completedFuture(simpleResponse(BAD_REQUEST)));
  }

  public CompletionStage<HttpResponse> request(final FullHttpRequest request) {
    return request(request, null);
  }

  /**
   * The function completes when the response has arrived, before the response body is emitted.
   *
   * @param request the request object, which must not be <code>null</code>.
   * @param responseBody the response body subscriber. If it is <code>null</code> the response body
   *     will be consumed and discarded. The buffers are released as soon as the <code>onNext</code>
   *     method of the subscriber returns. If you still need them later, you have to make a copy.
   * @return The response object.
   * @since 1.1
   */
  public CompletionStage<HttpResponse> request(
      final FullHttpRequest request, final Subscriber<? super ByteBuf> responseBody) {
    final Redirectable redirectable =
        responseBody instanceof Redirectable red ? red : newRedirectable(responseBody);
    final URI uri = getUri(request);

    request.headers().add(ACCEPT_ENCODING, "gzip deflate").set(HOST, fullHost(uri));

    final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
    final ChannelFuture cf =
        new Bootstrap()
            .group(group)
            .option(AUTO_READ, false)
            .channel(NioSocketChannel.class)
            .handler(
                createPipeline(
                    uri.getScheme(), uri.getHost(), getPort(uri), sslContext, future, redirectable))
            .connect(uri.getHost(), getPort(uri));
    final FullHttpRequest copyForRedirect = request.copy();

    cf.addListener(
        f -> {
          if (!f.isSuccess()) {
            rethrow(f.cause());
          } else {
            cf.channel().writeAndFlush(request);
          }
        });

    return future
        .thenApply(
            response ->
                SideEffect.<HttpResponse>run(
                        () -> redirectable.shouldRedirect = shouldRedirect(response))
                    .andThenGet(() -> response))
        .thenComposeAsync(
            response ->
                shouldRedirect(response)
                    ? redirect(copyForRedirect, redirectable, response)
                    : completedFuture(response)
                        .thenApply(
                            resp ->
                                SideEffect.<HttpResponse>run(copyForRedirect::release)
                                    .andThenGet(() -> resp)));
  }

  private boolean shouldRedirect(final HttpResponse response) {
    return followRedirects && isRedirect(response.status());
  }

  /**
   * When set it will follow redirects.
   *
   * @param followRedirects the default is <code>true</code>.
   * @return A new client.
   */
  public HttpClient withFollowRedirects(final boolean followRedirects) {
    return new HttpClient(sslContext, followRedirects);
  }

  /**
   * Sets the context for SSL.
   *
   * @param sslContext when it is <code>null</code> a default context is created.
   * @return A new client.
   */
  public HttpClient withSslContext(final SslContext sslContext) {
    return new HttpClient(sslContext, followRedirects);
  }

  private static class HttpHandler extends ChannelInboundHandlerAdapter {
    private final CompletableFuture<HttpResponse> future;
    private final Subscriber<? super ByteBuf> responseBody;
    private ChannelConsumer channelConsumer;

    private HttpHandler(
        final CompletableFuture<HttpResponse> future,
        final Subscriber<? super ByteBuf> responseBody) {
      this.future = future;
      this.responseBody = responseBody;
    }

    @Override
    public void channelActive(final ChannelHandlerContext context) {
      channelConsumer = new ChannelConsumer(responseBody);
      channelConsumer.active(context);
    }

    @Override
    public void channelRead(final ChannelHandlerContext context, final Object message) {
      if (message instanceof HttpResponse response) {
        future.complete(response);

        if (!hasBody(response) && !isRedirect(response.status())) {
          responseBody.onComplete();
        }
      } else if (message instanceof LastHttpContent lastHttpContent) {
        if (message != EMPTY_LAST_CONTENT) {
          channelConsumer.read(lastHttpContent.content());
        }

        channelConsumer.complete();
      } else if (message instanceof HttpContent httpContent) {
        channelConsumer.read(httpContent.content());
      }
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext context) {
      channelConsumer.readCompleted(context);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
      rethrow(cause);
    }
  }

  private static class Redirectable implements Processor<ByteBuf, ByteBuf> {
    private long requested;
    private boolean shouldRedirect;
    private Subscriber<? super ByteBuf> subscriber;
    private Subscription subscription;

    private void more() {
      if (requested > 0 && subscription != null) {
        subscription.request(1);
      }
    }

    public void onComplete() {
      if (!shouldRedirect) {
        subscriber.onComplete();
      }
    }

    public void onError(final Throwable throwable) {
      subscriber.onError(throwable);
    }

    public void onNext(final ByteBuf item) {
      dispatch(
          () -> {
            --requested;
            subscriber.onNext(item);
            more();
          });
    }

    public void onSubscribe(final Subscription subscription) {
      this.subscription = subscription;
      more();
    }

    public void subscribe(final Subscriber<? super ByteBuf> subscriber) {
      this.subscriber = subscriber;
      subscriber.onSubscribe(new Backpressure());
    }

    private class Backpressure implements Subscription {
      public void cancel() {
        // Nothing to do.
      }

      public void request(final long number) {
        if (number <= 0) {
          throw new IllegalArgumentException("A request must be strictly positive.");
        }

        dispatch(
            () -> {
              requested += number;
              more();
            });
      }
    }
  }
}
