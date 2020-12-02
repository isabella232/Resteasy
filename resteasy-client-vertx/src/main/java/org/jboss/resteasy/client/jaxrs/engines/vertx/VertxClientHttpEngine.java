package org.jboss.resteasy.client.jaxrs.engines.vertx;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import org.jboss.resteasy.client.jaxrs.engines.AsyncClientHttpEngine;
import org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.ResponseProcessingException;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import org.jboss.resteasy.client.jaxrs.internal.FinalizedClientResponse;
import org.jboss.resteasy.tracing.RESTEasyTracingLogger;
import org.jboss.resteasy.util.CaseInsensitiveMap;

public class VertxClientHttpEngine implements AsyncClientHttpEngine {

    /**
     * Client config property to set when a request timeout is needed.
     */
    public static final String REQUEST_TIMEOUT_MS = Vertx.class + "$RequestTimeout";

    private final Vertx vertx;
    private final HttpClient httpClient;

    public VertxClientHttpEngine() {
        this.vertx = Vertx.vertx();
        this.httpClient = vertx.createHttpClient();
    }

    public VertxClientHttpEngine(final Vertx vertx, final HttpClientOptions options) {
        this.vertx = vertx;
        this.httpClient = vertx.createHttpClient(options);
    }

    public VertxClientHttpEngine(final Vertx vertx) {
        this(vertx, new HttpClientOptions());
    }

    public VertxClientHttpEngine(final HttpClient client) {
        this.vertx = null;
        this.httpClient = client;
    }

    @Override
    public <T> Future<T> submit(final ClientInvocation request,
                                final boolean buffered,
                                final InvocationCallback<T> callback,
                                final ResultExtractor<T> extractor) {
        CompletableFuture<T> future = submit(request).thenCompose(response -> {
            CompletableFuture<T> tmp = new CompletableFuture<>();
            vertx.executeBlocking(promise -> {
                try {
                    T result = extractor.extractResult(response);
                    tmp.complete(result);
                } catch (Exception e) {
                    tmp.completeExceptionally(e);
                }
            }, ar -> {
                //
            });
            return tmp;
        });
        if (callback != null) {
            future = future.whenComplete((response, throwable) -> {
                if (throwable != null) {
                    callback.failed(throwable);
                } else {
                    callback.completed(response);
                }
            });
        }
        return future;
    }

    @Override
    public <T> CompletableFuture<T> submit(final ClientInvocation request,
                                           final boolean buffered,
                                           final ResultExtractor<T> extractor,
                                           final ExecutorService executorService) {
        return submit(request).thenCompose(response -> {
            CompletableFuture<T> tmp = new CompletableFuture<>();
            executorService.execute(() -> {
                try {
                    T result = extractor.extractResult(response);
                    tmp.complete(result);
                } catch (Exception e) {
                    tmp.completeExceptionally(e);
                }
            });
            return tmp;
        });
    }

    private CompletableFuture<ClientResponse> submit(final ClientInvocation request) {

        HttpMethod method;
        try {
            method = HttpMethod.valueOf(request.getMethod());
        } catch (IllegalArgumentException e) {
            method = HttpMethod.OTHER;
        }

        Object entity = request.getEntity();
        Buffer body;
        if (entity != null) {
            body = Buffer.buffer(requestContent(request));
        } else {
            body = null;
        }

        RequestOptions options = new RequestOptions();
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        request.getHeaders().asMap().forEach(headers::add);
        options.setHeaders(headers);
        if (body != null) {
            headers.set(HttpHeaders.CONTENT_LENGTH, "" + body.length());
        }
        options.addHeader(HttpHeaders.USER_AGENT.toString(), "Vertx");

        URI uri = request.getUri();
        options.setHost(uri.getHost());
        options.setPort(uri.getPort());
        options.setURI(uri.getRawPath());

        CompletableFuture<ClientResponse> future = new CompletableFuture<>();

        // Using this method is fine
        // This will go away with Vert.x 4
        @SuppressWarnings("deprecation")
        HttpClientRequest clientRequest = httpClient.request(method, options, response -> {
            future.complete(toRestEasyResponse(request.getClientConfiguration(), response));
        });
        if (method == HttpMethod.OTHER) {
            clientRequest.setRawMethod(request.getMethod());
        }

        Object timeout = request.getConfiguration().getProperty(REQUEST_TIMEOUT_MS);
        if (timeout != null) {
            long timeoutMs = unwrapTimeout(timeout);
            if (timeoutMs > 0) {
                clientRequest.setTimeout(timeoutMs);
            }
        }

        clientRequest.exceptionHandler(future::completeExceptionally);
        if (body != null) {
            clientRequest.end(body);
        } else {
            clientRequest.end();
        }

        return future;
    }

    private long unwrapTimeout(final Object timeout) {
        if (timeout instanceof Duration) {
            return ((Duration) timeout).toMillis();
        } else if (timeout instanceof Number) {
            return ((Number) timeout).longValue();
        } else if (timeout != null) {
            return Long.parseLong(timeout.toString());
        } else {
            return -1L;
        }
    }

    @Override
    public SSLContext getSslContext() {
        // Vertx does not allow to access the ssl-context from HttpClient API.
        throw new UnsupportedOperationException();
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
        // Vertx does not support HostnameVerifier API.
        throw new UnsupportedOperationException();
    }

    @Override
    public Response invoke(Invocation request) {
        final Future<ClientResponse> future = submit((ClientInvocation) request);

        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            throw clientException(e, null);
        } catch (ExecutionException e) {
            throw clientException(e.getCause(), null);
        }
    }

    @Override
    public void close() {
        if (vertx != null) {
            vertx.close();
        } else {
            httpClient.close();
        }
    }

    static RuntimeException clientException(Throwable ex, Response clientResponse) {
        RuntimeException ret;
        if (ex == null) {
            ret = new ProcessingException(new NullPointerException());
        } else if (ex instanceof WebApplicationException) {
            ret = (WebApplicationException) ex;
        } else if (ex instanceof ProcessingException) {
            ret = (ProcessingException) ex;
        } else if (clientResponse != null) {
            ret = new ResponseProcessingException(clientResponse, ex);
        } else {
            ret = new ProcessingException(ex);
        }
        return ret;
    }

    private static byte[] requestContent(ClientInvocation request)
    {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        request.getDelegatingOutputStream().setDelegate(baos);
        try {
            request.writeRequestBody(request.getEntityStream());
            baos.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write the request body!", e);
        }
    }


    private ClientResponse toRestEasyResponse(ClientConfiguration clientConfiguration,
                                               HttpClientResponse clientResponse) {


        InputStreamAdapter adapter = new InputStreamAdapter(clientResponse, 4 * 1024);

        class RestEasyClientResponse extends FinalizedClientResponse {

            private InputStream is;

            private RestEasyClientResponse(final ClientConfiguration configuration) {
                super(configuration, RESTEasyTracingLogger.empty());
                this.is = adapter;
            }

            @Override
            protected InputStream getInputStream() {
                return this.is;
            }

            @Override
            protected void setInputStream(InputStream inputStream) {
                this.is = inputStream;
            }

            @Override
            public void releaseConnection() throws IOException {
                this.releaseConnection(false);
            }

            @Override
            public void releaseConnection(boolean consumeInputStream) throws IOException {
                try {
                    if (is != null) {
                        if (consumeInputStream) {
                            while (is.available() > 0) {
                                is.read();
                            }
                        }
                        is.close();
                    }
                }
                catch (IOException e) {
                    // Swallowing because other ClientHttpEngine implementations are swallowing as well.
                    // What is better?  causing a potential leak with inputstream slowly or cause an unexpected
                    // and unhandled io error and potentially cause the service go down?
                    // log.warn("Exception while releasing the connection!", e);
                }
            }
        }
        ClientResponse restEasyClientResponse = new RestEasyClientResponse(clientConfiguration);
        restEasyClientResponse.setStatus(clientResponse.statusCode());
        CaseInsensitiveMap<String> restEasyHeaders = new CaseInsensitiveMap<>();
        clientResponse.headers().forEach(header -> restEasyHeaders.add(header.getKey(), header.getValue()));
        restEasyClientResponse.setHeaders(restEasyHeaders);
        return restEasyClientResponse;
    }
}