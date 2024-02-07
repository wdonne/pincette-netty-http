package net.pincette.netty.http;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.FROM;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.lang.String.valueOf;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.between;
import static java.time.Instant.now;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static net.pincette.netty.http.JWTVerifier.getBearerToken;
import static net.pincette.rs.Chain.with;
import static net.pincette.rs.DequePublisher.dequePublisher;
import static net.pincette.rs.LambdaSubscriber.lambdaSubscriber;
import static net.pincette.rs.Util.tap;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import net.pincette.function.SideEffect;
import net.pincette.rs.DequePublisher;
import net.pincette.util.State;

/**
 * Some utilities.
 *
 * @author Werner Donn\u00e9
 * @since 3.0
 */
public class Util {
  private Util() {}

  public static InputStream asInputStream(final Stream<ByteBuf> buffers) {
    return new ByteBufInputStream(buffers.toList());
  }

  private static Subscriber<ByteBuf> bodyCounter(final LongConsumer count) {
    final State<Long> total = new State<>(0L);

    return lambdaSubscriber(
        v -> total.set(total.get() + v.readableBytes()),
        () -> count.accept(total.get()),
        t -> count.accept(total.get()));
  }

  private static Metrics createMetrics(
      final HttpRequest request,
      final HttpResponse response,
      final long requestBytes,
      final long responseBytes,
      final Instant start) {
    return new Metrics(
        request.uri(),
        request.method().name(),
        request.protocolVersion().text(),
        getUsername(request).orElse(null),
        request.headers().get(FROM),
        response.status().code(),
        requestBytes,
        responseBytes,
        start,
        between(start, now()));
  }

  private static Optional<String> getUsername(final HttpRequest request) {
    return getBearerToken(request).map(JWT::decode).map(DecodedJWT::getSubject);
  }

  public static FullHttpRequest setBody(
      final FullHttpRequest request, final String contentType, final byte[] body) {
    request.headers().add("Content-Type", contentType).add("Content-Length", valueOf(body.length));

    return request.replace(wrappedBuffer(body));
  }

  public static FullHttpRequest setBody(
      final FullHttpRequest request, final String contentType, final String body) {
    return setBody(request, contentType, body.getBytes(UTF_8));
  }

  public static FullHttpRequest setUrlEncodedFormData(
      final FullHttpRequest request, final Map<String, String> formData) {
    return setBody(request, "application/x-www-form-urlencoded", urlEncodedFormData(formData));
  }

  public static HttpResponse simpleResponse(final HttpResponseStatus status) {
    return new DefaultFullHttpResponse(HTTP_1_1, status);
  }

  public static CompletionStage<Publisher<ByteBuf>> simpleResponse(
      final HttpResponse response,
      final HttpResponseStatus status,
      final Publisher<ByteBuf> responseBody) {
    return simpleResponse(response, status, null, responseBody);
  }

  public static CompletionStage<Publisher<ByteBuf>> simpleResponse(
      final HttpResponse response,
      final HttpResponseStatus status,
      final String contentType,
      final Publisher<ByteBuf> responseBody) {
    return SideEffect.<CompletionStage<Publisher<ByteBuf>>>run(
            () -> {
              response.setStatus(status);

              if (contentType != null) {
                response.headers().set(CONTENT_TYPE, contentType);
              }
            })
        .andThenGet(() -> completedFuture(responseBody));
  }

  public static String urlEncodedFormData(final Map<String, String> formData) {
    return formData.entrySet().stream()
        .map(e -> e.getKey() + "=" + encode(e.getValue(), UTF_8))
        .collect(joining("&"));
  }

  public static RequestHandler wrapMetrics(
      final RequestHandler handler, final Subscriber<Metrics> collector) {
    final DequePublisher<Metrics> publisher = dequePublisher();

    publisher.subscribe(collector);

    return (request, requestBody, response) -> {
      final State<Long> requestBytes = new State<>(0L);
      final Instant start = now();

      return handler
          .apply(
              request, with(requestBody).map(tap(bodyCounter(requestBytes::set))).get(), response)
          .thenApply(
              responseBody ->
                  with(responseBody)
                      .map(
                          tap(
                              bodyCounter(
                                  bytes ->
                                      publisher
                                          .getDeque()
                                          .addFirst(
                                              createMetrics(
                                                  request,
                                                  response,
                                                  requestBytes.get(),
                                                  bytes,
                                                  start)))))
                      .get());
    };
  }
}
