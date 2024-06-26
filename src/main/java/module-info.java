module net.pincette.netty.http {
  requires io.netty.buffer;
  requires io.netty.codec;
  requires io.netty.codec.http;
  requires net.pincette.rs;
  requires net.pincette.common;
  requires io.netty.transport;
  requires io.netty.handler;
  requires io.netty.common;
  requires net.pincette.jwt;
  requires com.auth0.jwt;
  requires net.pincette.json;
  requires java.json;
  requires java.logging;
  requires java.net.http;

  exports net.pincette.netty.http;

  opens net.pincette.netty.http;
}
