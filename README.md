# A Simple Netty HTTP Server

With this library you can run an HTTP server on top of
[Netty](https://netty.io). You just give it a function to handle the requests. The request body can be consumed as a reactive streams publisher or as an accumulated input stream. The response bodies are always reactive streams publishers. The handler functions are not supposed to do blocking calls.

Read more in the [API documentation](https://www.javadoc.io/doc/net.pincette/pincette-netty-http).