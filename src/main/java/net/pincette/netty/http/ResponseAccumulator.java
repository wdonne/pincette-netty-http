package net.pincette.netty.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.pincette.io.StreamConnector.copy;
import static net.pincette.netty.http.Util.asInputStream;
import static net.pincette.util.Util.tryToDoRethrow;

import io.netty.buffer.ByteBuf;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import net.pincette.rs.Accumulator;

/**
 * A subscriber that accumulates the complete response body.
 *
 * @author Werner Donné
 * @since 3.0
 */
public class ResponseAccumulator extends Accumulator<ByteBuf, InputStream> {
  public ResponseAccumulator() {
    super(buffers -> completedFuture(asInputStream(buffers)), ByteBuf::retain);
  }

  /**
   * This can be called only once.
   *
   * @return The body as an input stream.
   */
  public InputStream getBody() {
    return get().toCompletableFuture().join();
  }

  /**
   * This can be called only once. It assumes the body is encoded in UTF8.
   *
   * @return The body as a string.
   */
  public String getBodyAsString() {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    tryToDoRethrow(() -> copy(getBody(), out));

    return out.toString(UTF_8);
  }
}
