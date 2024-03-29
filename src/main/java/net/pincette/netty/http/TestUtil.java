package net.pincette.netty.http;

import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static java.nio.channels.Channels.newChannel;
import static java.util.Optional.ofNullable;
import static net.pincette.netty.http.Util.simpleResponse;
import static net.pincette.rs.Chain.with;
import static net.pincette.rs.ReadableByteChannelPublisher.readableByteChannel;
import static net.pincette.util.Util.tryToGetRethrow;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.function.UnaryOperator;

public class TestUtil {
  private TestUtil() {}

  private static String path(final String uri) {
    return tryToGetRethrow(() -> new URI(uri).getPath()).orElse(null);
  }

  /**
   * Returns a request handler that serves Java resources using the path in the request URI.
   *
   * @return The request handler function.
   */
  public static RequestHandlerAccumulated resourceHandler() {
    return resourceHandler(null);
  }

  /**
   * Returns a request handler that serves Java resources using the path in the request URI.
   *
   * @param contentType an optional function that derives the content type of the resource from the
   *     URI path.
   * @return The request handler function.
   */
  public static RequestHandlerAccumulated resourceHandler(final UnaryOperator<String> contentType) {
    return resourceHandler(contentType, 0xffff);
  }

  /**
   * Returns a request handler that serves Java resources using the path in the request URI.
   *
   * @param contentType an optional function that derives the content type of the resource from the
   *     URI path.
   * @param bufferSize the size of the byte buffer in the response stream.
   * @return The request handler function.
   */
  public static RequestHandlerAccumulated resourceHandler(
      final UnaryOperator<String> contentType, final int bufferSize) {
    return (request, requestBody, response) ->
        ofNullable(TestUtil.class.getResourceAsStream(path(request.uri())))
            .map(
                in ->
                    simpleResponse(
                        response,
                        OK,
                        contentType != null ? contentType.apply(path(request.uri())) : null,
                        with(readableByteChannel(newChannel(in)))
                            .map(TestUtil::toBytes)
                            .map(new BufferedProcessor(bufferSize))
                            .get()))
            .orElseGet(() -> simpleResponse(response, NOT_FOUND, null));
  }

  public static byte[] toBytes(final ByteBuffer buffer) {
    final byte[] buf = new byte[buffer.limit() - buffer.position()];

    buffer.get(buf);

    return buf;
  }
}
