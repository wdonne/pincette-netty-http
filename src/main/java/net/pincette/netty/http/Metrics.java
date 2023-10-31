package net.pincette.netty.http;

import java.time.Duration;
import java.time.Instant;

/**
 * The metrics for one request.
 *
 * @param path the path of the URI.
 * @param method the HTTP method.
 * @param protocol the HTTP protocol version.
 * @param username the username, which can be <code>null</code>.
 * @param from the value of the From HTTP header.
 * @param statusCode the status code of the response.
 * @param requestBytes the number of bytes in the request body.
 * @param responseBytes the number of bytes in the response body.
 * @param timeOccurred the moment the request arrived.
 * @param timeTaken the time the request took.
 * @author Werner Donné
 * @since 3.1
 */
public record Metrics(
    String path,
    String method,
    String protocol,
    String username,
    String from,
    int statusCode,
    long requestBytes,
    long responseBytes,
    Instant timeOccurred,
    Duration timeTaken) {}
