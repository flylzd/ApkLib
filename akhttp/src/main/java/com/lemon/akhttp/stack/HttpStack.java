package com.lemon.akhttp.stack;

import com.lemon.akhttp.Request;
import com.lemon.akhttp.error.AuthFailureError;

import org.apache.http.HttpResponse;

import java.io.IOException;
import java.util.Map;

/**
 * An HTTP stack abstraction.
 */
public interface HttpStack {

    String HEADER_CONTENT_TYPE = "Content-Type";
    String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
    String USER_AGENT = "User-Agent";
    String ENCODING_GZIP = "gzip";
    int CONNECTION_TIME_OUT_MS = 15000;

    /**
     * Performs an HTTP request with the given parameters.
     *
     * <p>A GET request is sent if request.getPostBody() == null. A POST request is sent otherwise,
     * and the Content-Type header is set to request.getPostBodyContentType().</p>
     *
     * @param request the request to perform
     * @param additionalHeaders additional headers to be sent together with
     *         {@link Request#getHeaders()}
     * @return the HTTP response
     */
    public HttpResponse performRequest(Request<?> request, Map<String, String> additionalHeaders)
            throws IOException, AuthFailureError;
}
