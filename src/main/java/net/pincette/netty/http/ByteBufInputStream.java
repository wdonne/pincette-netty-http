package net.pincette.netty.http;

import static java.lang.Math.min;
import static net.pincette.util.Pair.pair;

import io.netty.buffer.ByteBuf;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.pincette.function.SideEffect;

/**
 * Creates an input stream from a list of buffers. It may happen that buffers are repeated. However,
 * their readability status will make sure content is not read more than once.
 *
 * @author Werner Donné
 * @since 1.0
 */
class ByteBufInputStream extends InputStream {
  private final List<ByteBuf> buffers;

  ByteBufInputStream(final List<ByteBuf> buffers) {
    this.buffers = new ArrayList<>(buffers);
  }

  private void checkCurrentBuffer() {
    if (!buffers.isEmpty() && !buffers.getFirst().isReadable()) {
      buffers.removeFirst().release();
    }
  }

  @Override
  public int read() {
    final byte[] b = new byte[1];

    return read(b, 0, b.length) == -1 ? -1 : (255 & b[0]);
  }

  @Override
  public int read(final byte[] b, final int off, final int len) {
    return readBuffer(b, off, len).orElse(-1);
  }

  private Optional<Integer> readBuffer(final byte[] b, final int off, final int len) {
    return Optional.of(buffers)
        .filter(buf -> !buf.isEmpty())
        .map(List::getFirst)
        .map(buf -> pair(buf, min(len, buf.readableBytes())))
        .map(
            pair ->
                SideEffect.<Integer>run(
                        () -> {
                          pair.first.readBytes(b, off, pair.second);
                          checkCurrentBuffer();
                        })
                    .andThenGet(() -> pair.second));
  }
}
