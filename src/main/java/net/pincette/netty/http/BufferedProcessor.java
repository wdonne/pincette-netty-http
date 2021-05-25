package net.pincette.netty.http;

import static io.netty.buffer.Unpooled.buffer;

import io.netty.buffer.ByteBuf;
import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class BufferedProcessor implements Processor<byte[], ByteBuf> {
  private final int capacity;
  private ByteBuf buf;
  private boolean error;
  private Subscriber<? super ByteBuf> subscriber;
  private Subscription subscription;

  public BufferedProcessor(final int capacity) {
    this.capacity = capacity;
    newBuffer();
  }

  private void newBuffer() {
    buf = buffer(capacity, capacity);
  }

  private void notifySubscriber() {
    subscriber.onSubscribe(new Backpressure());
  }

  public void onComplete() {
    if (!error && buf.readableBytes() > 0) {
      subscriber.onNext(buf);
      subscriber.onComplete();
    }
  }

  public void onError(final Throwable t) {
    error = true;

    if (subscriber != null) {
      subscriber.onError(t);
    }
  }

  public void onNext(final byte[] bytes) {
    if (bytes.length > buf.maxWritableBytes()) {
      final ByteBuf b = buf;

      newBuffer();
      buf.writeBytes(bytes);
      subscriber.onNext(b);
    } else {
      buf.writeBytes(bytes);
      subscription.request(1);
    }
  }

  public void onSubscribe(final Subscription subscription) {
    this.subscription = subscription;

    if (subscriber != null) {
      notifySubscriber();
    }
  }

  public void subscribe(final Subscriber<? super ByteBuf> subscriber) {
    this.subscriber = subscriber;

    if (subscriber != null && subscription != null) {
      notifySubscriber();
    }
  }

  private class Backpressure implements Subscription {
    public void cancel() {
      if (subscription != null) {
        subscription.cancel();
      }
    }

    public void request(final long number) {
      if (!error && number > 0 && subscription != null) {
        subscription.request(1);
      }
    }
  }
}
