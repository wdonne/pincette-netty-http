package net.pincette.netty.http;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.lang.String.valueOf;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;
import java.util.stream.Stream;
import net.pincette.function.SideEffect;

/**
 * Some utilities.
 *
 * @author Werner Donn\u00e9
 * @since 3.0
 */
public class Util {
  private Util() {}

  public static InputStream asInputStream(final Stream<ByteBuf> buffers) {
    return new ByteBufInputStream(buffers.collect(toList()));
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
}
