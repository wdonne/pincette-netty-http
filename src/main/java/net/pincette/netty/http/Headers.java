package net.pincette.netty.http;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * The combination of an HTTP request and response. A header handler is expressed in terms of it.
 *
 * @param request the given request.
 * @param response the given response.
 * @author Werner Donné
 * @since 3.1
 */
public record Headers(HttpRequest request, HttpResponse response) {}
