package com.lemon.akhttp.toolbox;

import android.os.SystemClock;
import android.webkit.URLUtil;

import com.lemon.akhttp.Network;
import com.lemon.akhttp.NetworkResponse;
import com.lemon.akhttp.Request;
import com.lemon.akhttp.ResponseDelivery;
import com.lemon.akhttp.RetryPolicy;
import com.lemon.akhttp.VolleyLog;
import com.lemon.akhttp.cache.Cache;
import com.lemon.akhttp.error.AuthFailureError;
import com.lemon.akhttp.error.NetworkError;
import com.lemon.akhttp.error.NoConnectionError;
import com.lemon.akhttp.error.ServerError;
import com.lemon.akhttp.error.TimeoutError;
import com.lemon.akhttp.error.VolleyError;
import com.lemon.akhttp.stack.HttpStack;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.impl.cookie.DateUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A network performing Netroid requests over an {@link HttpStack}.
 */
public class BasicNetwork implements Network {


    protected static final boolean DEBUG = VolleyLog.DEBUG;

    private static int SLOW_REQUEST_THRESHOLD_MS = 3000;

    private static int DEFAULT_POOL_SIZE = 4096;

    protected final HttpStack mHttpStack;

//    protected final ByteArrayPool mPool;

    /**
     * Request delivery mechanism.
     */
    private ResponseDelivery mDelivery;

    /**
     * @param httpStack HTTP stack to be used
     */
    public BasicNetwork(HttpStack httpStack) {
        // If a pool isn't passed in, then build a small default pool that will give us a lot of
        // benefit and not use too much memory.
        this(httpStack, DEFAULT_POOL_SIZE);
    }

    /**
     * @param httpStack HTTP stack to be used
     * @param pool      a buffer pool that improves GC performance in copy operations
     */
    public BasicNetwork(HttpStack httpStack, int bytePoolSize) {
        ByteArrayPool.init(bytePoolSize);
        mHttpStack = httpStack;
//        mPool = pool;
    }


    @Override
    public void setDelivery(ResponseDelivery delivery) {
        mDelivery = delivery;
    }

    @Override
    public NetworkResponse performRequest(Request<?> request) throws VolleyError {

        // Determine if request had non-http perform.
//        NetworkResponse networkResponse = request.perform();
//        if (networkResponse != null) return networkResponse;


        long requestStart = SystemClock.elapsedRealtime();
        while (true) {
            HttpResponse httpResponse = null;
            byte[] responseContents = null;
            Map<String, String> responseHeaders = Collections.emptyMap();

            try {

                if (!URLUtil.isNetworkUrl(request.getUrl())) {
                    return new NetworkResponse(responseContents);
                }

                // Gather headers.
                Map<String, String> headers = new HashMap<String, String>();
                addCacheHeaders(headers, request.getCacheEntry());

                // prepare to perform this request, normally is reset the request headers.
                request.prepare();

                httpResponse = mHttpStack.performRequest(request, headers);

                StatusLine statusLine = httpResponse.getStatusLine();
                int statusCode = statusLine.getStatusCode();

                responseHeaders = convertHeaders(httpResponse.getAllHeaders());
                // Handle cache validation.
                if (statusCode == HttpStatus.SC_NOT_MODIFIED) {

                    Cache.Entry entry = request.getCacheEntry();
                    if (entry == null) {
                        return new NetworkResponse(HttpStatus.SC_NOT_MODIFIED, null,
                                responseHeaders, true,
                                SystemClock.elapsedRealtime() - requestStart);
                    }

                    // A HTTP 304 response does not have all header fields. We
                    // have to use the header fields from the cache entry plus
                    // the new ones from the response.
                    // http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
                    entry.responseHeaders.putAll(responseHeaders);
                    return new NetworkResponse(HttpStatus.SC_NOT_MODIFIED, entry.data,
                            entry.responseHeaders, true,
                            SystemClock.elapsedRealtime() - requestStart);
                }

                // Handle moved resources
                if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY || statusCode == HttpStatus.SC_MOVED_TEMPORARILY) {
                    String newUrl = responseHeaders.get("Location");
                    request.setRedirectUrl(newUrl);
                }

                responseContents = request.handleResponse(httpResponse, mDelivery);

                // if the request is slow, log it.
                long requestLifetime = SystemClock.elapsedRealtime() - requestStart;
                logSlowRequests(requestLifetime, request, responseContents, statusLine);

                if (statusCode < 200 || statusCode > 299) {
                    throw new IOException();
                }
                return new NetworkResponse(statusCode, responseContents, responseHeaders, false,
                        SystemClock.elapsedRealtime() - requestStart);
            } catch (SocketTimeoutException e) {
                attemptRetryOnException("socket", request, new TimeoutError());
            } catch (ConnectTimeoutException e) {
                attemptRetryOnException("connection", request, new TimeoutError());
            } catch (MalformedURLException e) {
                throw new RuntimeException("Bad URL " + request.getUrl(), e);
            } catch (IOException e) {
                int statusCode = 0;
                NetworkResponse networkResponse = null;
                if (httpResponse != null) {
                    statusCode = httpResponse.getStatusLine().getStatusCode();
                } else {
                    throw new NoConnectionError(e);
                }
                if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY ||
                        statusCode == HttpStatus.SC_MOVED_TEMPORARILY) {
                    VolleyLog.e("Request at %s has been redirected to %s", request.getOriginUrl(), request.getUrl());
                } else {
                    VolleyLog.e("Unexpected response code %d for %s", statusCode, request.getUrl());
                }
                if (responseContents != null) {
                    networkResponse = new NetworkResponse(statusCode, responseContents,
                            responseHeaders, false);
                    if (statusCode == HttpStatus.SC_UNAUTHORIZED ||
                            statusCode == HttpStatus.SC_FORBIDDEN) {
                        attemptRetryOnException("auth",
                                request, new AuthFailureError(networkResponse));
                    } else if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY ||
                            statusCode == HttpStatus.SC_MOVED_TEMPORARILY) {
                        attemptRetryOnException("redirect",
                                request, new AuthFailureError(networkResponse));
                    } else {
                        // TODO: Only throw ServerError for 5xx status codes.
                        throw new ServerError(networkResponse);
                    }
                } else {
                    throw new NetworkError(networkResponse);
                }
            } finally {
                try {
                    if (httpResponse != null && httpResponse.getEntity() != null) {
                        httpResponse.getEntity().getContent().close();
                    }
                } catch (IllegalStateException e) {
                } catch (IOException e) {
                }
            }
        }
    }


    private void addCacheHeaders(Map<String, String> headers, Cache.Entry entry) {
        // If there's no cache entry, we're done.
        if (entry == null) {
            return;
        }

        if (entry.etag != null) {
            headers.put("If-None-Match", entry.etag);
        }

        if (entry.serverDate > 0) {
            Date refTime = new Date(entry.serverDate);
            headers.put("If-Modified-Since", DateUtils.formatDate(refTime));
        }
    }

    /**
     * Logs requests that took over SLOW_REQUEST_THRESHOLD_MS to complete.
     */
    private void logSlowRequests(long requestLifetime, Request<?> request,
                                 byte[] responseContents, StatusLine statusLine) {
        if (DEBUG || requestLifetime > SLOW_REQUEST_THRESHOLD_MS) {
            VolleyLog.d("HTTP response for request=<%s> [lifetime=%d], [size=%s], " +
                            "[rc=%d], [retryCount=%s]", request, requestLifetime,
                    responseContents != null ? responseContents.length : "null",
                    statusLine.getStatusCode(), request.getRetryPolicy().getCurrentRetryCount());
        }
    }


    /**
     * Attempts to prepare the request for a retry. If there are no more attempts remaining in the
     * request's retry policy, a timeout exception is thrown.
     *
     * @param request The request to use.
     */
    private void attemptRetryOnException(String logPrefix, Request<?> request, VolleyError exception) throws VolleyError {
        RetryPolicy retryPolicy = request.getRetryPolicy();
        int oldTimeout = request.getTimeoutMs();

        try {
            retryPolicy.retry(exception);
        } catch (VolleyError e) {
            request.addMarker(String.format("%s-timeout-giveup [timeout=%s]", logPrefix, oldTimeout));
            throw e;
        }

        request.addMarker(String.format("%s-retry [timeout=%s]", logPrefix, oldTimeout));
        mDelivery.postRetry(request);
    }

    protected void logError(String what, String url, long start) {
        long now = SystemClock.elapsedRealtime();
        VolleyLog.v("HTTP ERROR(%s) %d ms to fetch %s", what, (now - start), url);
    }


    /**
     * Converts Headers[] to Map<String, String>.
     */
    protected static Map<String, String> convertHeaders(Header[] headers) {
        Map<String, String> result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < headers.length; i++) {
            result.put(headers[i].getName(), headers[i].getValue());
        }
        return result;
    }


}
