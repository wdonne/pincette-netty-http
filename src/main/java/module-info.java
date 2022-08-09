module net.pincette.netty.http {
  requires io.netty.buffer;
  requires io.netty.codec;
  requires io.netty.codec.http;
  requires net.pincette.rs;
  requires net.pincette.common;
  requires io.netty.transport;
  requires io.netty.handler;
  requires io.netty.common;
  exports net.pincette.netty.http;
  opens net.pincette.netty.http;
}
