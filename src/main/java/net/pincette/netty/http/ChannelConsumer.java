package net.pincette.netty.http;

import static net.pincette.rs.Serializer.dispatch;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

class ChannelConsumer {
  private final Deque<ByteBuf> buffers = new ConcurrentLinkedDeque<>();
  private final List<ByteBuf> sent = new ArrayList<>();
  private boolean completed;
  private long requested;
  private boolean sentCompleted;
  private Subscriber<? super ByteBuf> subscriber;
  private Subscription subscription;

  ChannelConsumer() {}

  ChannelConsumer(final Subscriber<? super ByteBuf> subscriber) {
    subscribe(subscriber);
  }

  void active(final ChannelHandlerContext context) {
    subscription = new Backpressure(context);
    notifySubscriber();
    more(context);
  }

  void complete() {
    dispatch(() -> completed = true);
  }

  private void flush() {
    while (requested > 0 && !buffers.isEmpty()) {
      --requested;

      final ByteBuf buf = buffers.removeLast();

      sent.add(buf);
      subscriber.onNext(buf);
    }
  }

  private void more(final ChannelHandlerContext context) {
    if (!completed) {
      context.read();
    } else {
      sendCompleted();
    }
  }

  private void notifySubscriber() {
    if (subscription != null && subscriber != null) {
      subscriber.onSubscribe(subscription);
    }
  }

  void read(final ByteBuf buffer) {
    dispatch(
        () -> {
          buffers.addFirst(buffer);
          flush();
        });
  }

  void readCompleted(final ChannelHandlerContext context) {
    dispatch(
        () -> {
          context.flush();

          if (requested > 0) {
            more(context);
          }
        });
  }

  void releaseSent() {
    sent.forEach(ByteBuf::release);
    sent.clear();
  }

  private void sendCompleted() {
    if (!sentCompleted) {
      sentCompleted = true;
      subscriber.onComplete();
    }
  }

  void subscribe(final Subscriber<? super ByteBuf> subscriber) {
    this.subscriber = subscriber;
    notifySubscriber();
  }

  private class Backpressure implements Subscription {
    private final ChannelHandlerContext context;

    private Backpressure(final ChannelHandlerContext context) {
      this.context = context;
    }

    public void cancel() {
      // Nothing to do.
    }

    public void request(final long n) {
      dispatch(
          () -> {
            requested += n;
            releaseSent();
            flush();

            if (requested > 0) {
              more(context);
            }
          });
    }
  }
}
