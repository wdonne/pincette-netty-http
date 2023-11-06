package net.pincette.netty.http;

import static io.netty.buffer.Unpooled.buffer;
import static java.lang.Math.min;
import static java.util.Arrays.copyOfRange;
import static net.pincette.util.Collections.list;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.pincette.rs.Buffered;

/**
 * Buffers bytes before writing them to the Netty buffer.
 *
 * @author Werner Donné
 * @since 1.2
 */
public class BufferedProcessor extends Buffered<byte[], ByteBuf> {
  private final int capacity;
  private ByteBuf buffer;

  /**
   * Create a byte buffer.
   *
   * @param capacity the maximum capacity of the buffer.
   */
  public BufferedProcessor(final int capacity) {
    super(1);
    this.capacity = capacity;
    buffer = newBuffer();
  }

  private void consume(final byte[] value, final int offset, final List<ByteBuf> result) {
    final int remaining = value.length - offset - buffer.writableBytes();

    buffer.writeBytes(
        copyOfRange(value, offset, offset + min(value.length - offset, buffer.writableBytes())));

    if (buffer.writableBytes() == 0) {
      result.add(buffer);
      buffer = newBuffer();

      if (remaining > 0) {
        consume(value, value.length - remaining, result);
      }
    }
  }

  @Override
  protected void last() {
    if (buffer.writerIndex() > 0) {
      addValues(list(buffer));
      emit();
    }
  }

  private ByteBuf newBuffer() {
    return buffer(capacity, capacity);
  }

  @Override
  protected boolean onNextAction(final byte[] value) {
    final List<ByteBuf> result = new ArrayList<>();

    consume(value, 0, result);

    if (!result.isEmpty()) {
      addValues(result);
      emit();

      return true;
    }

    return false;
  }
}
