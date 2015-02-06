package com.lemon.akhttp.stack;


import com.lemon.akhttp.Request;
import com.lemon.akhttp.Request.Method;
import com.lemon.akhttp.error.AuthFailureError;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * An {@link HttpStack} based on {@link HttpURLConnection}.
 */
public class HurlStack implements HttpStack {

    /**
     * An interface for transforming URLs before use.
     */
    public interface UrlRewriter {
        /**
         * Returns a URL to use instead of the provided one, or null to indicate
         * this URL should not be used at all.
         */
        public String rewriteUrl(String originalUrl);
    }

    private final UrlRewriter mUrlRewriter;
    private final SSLSocketFactory mSslSocketFactory;
    private final String mUserAgent;



    public HurlStack(String userAgent) {
        this(null, userAgent);
    }

    /**
     * @param urlRewriter Rewriter to use for request URLs
     */
    public HurlStack(UrlRewriter urlRewriter, String userAgent) {
        this(urlRewriter, null, userAgent);
    }

    /**
     * @param urlRewriter Rewriter to use for request URLs
     * @param sslSocketFactory SSL factory to use for HTTPS connections
     */
    public HurlStack(UrlRewriter urlRewriter, SSLSocketFactory sslSocketFactory, String userAgent) {
        mUrlRewriter = urlRewriter;
        mSslSocketFactory = sslSocketFactory;
        mUserAgent = userAgent;
    }



    @Override
    public HttpResponse performRequest(Request<?> request, Map<String, String> additionalHeaders) throws IOException, AuthFailureError {

        String url = request.getUrl();
        HashMap<String, String> map = new HashMap<String, String>();

        if (request.isShouldGzip()) {
            map.put(HEADER_ACCEPT_ENCODING, ENCODING_GZIP);
        }
        map.put(USER_AGENT, mUserAgent);

        map.putAll(request.getHeaders());
        map.putAll(additionalHeaders);
        if (mUrlRewriter != null) {
            String rewritten = mUrlRewriter.rewriteUrl(url);
            if (rewritten == null) {
                throw new IOException("URL blocked by rewriter: " + url);
            }
            url = rewritten;
        }
        URL parsedUrl = new URL(url);
        HttpURLConnection connection = openConnection(parsedUrl, request);

        for (String headerName : map.keySet()) {
            connection.addRequestProperty(headerName, map.get(headerName));
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == -1) {
            // -1 is returned by getResponseCode() if the response code could not be retrieved.
            // Signal to the caller that something was wrong with the connection.
            throw new IOException("Could not retrieve response code from HttpUrlConnection.");
        }

        // Initialize HttpResponse with data from the HttpURLConnection.
        ProtocolVersion protocolVersion = new ProtocolVersion("HTTP", 1, 1);
        StatusLine responseStatus = new BasicStatusLine(protocolVersion,
                connection.getResponseCode(), connection.getResponseMessage());
        BasicHttpResponse response = new BasicHttpResponse(responseStatus);
        response.setEntity(entityFromConnection(connection));
        for (Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if (header.getKey() != null) {
                Header h = new BasicHeader(header.getKey(), header.getValue().get(0));
                response.addHeader(h);
            }
        }
        return response;
    }

    /**
     * Initializes an {@link HttpEntity} from the given {@link HttpURLConnection}.
     * @param connection
     * @return an HttpEntity populated with data from <code>connection</code>.
     */
    private static HttpEntity entityFromConnection(HttpURLConnection connection) {
        BasicHttpEntity entity = new BasicHttpEntity();
        InputStream inputStream;
        try {
            inputStream = connection.getInputStream();
        } catch (IOException ioe) {
            inputStream = connection.getErrorStream();
        }
        entity.setContent(inputStream);
        entity.setContentLength(connection.getContentLength());
        entity.setContentEncoding(connection.getContentEncoding());
        entity.setContentType(connection.getContentType());

        return entity;
    }

    /**
     * Create an {@link HttpURLConnection} for the specified {@code url}.
     */
    protected HttpURLConnection createConnection(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }



    /**
     * Opens an {@link HttpURLConnection} with parameters.
     * @param url
     * @return an open connection
     * @throws IOException
     */
    private HttpURLConnection openConnection(URL url, Request<?> request) throws IOException {
        HttpURLConnection connection = createConnection(url);

        int timeoutMs = request.getTimeoutMs();
        connection.setConnectTimeout(CONNECTION_TIME_OUT_MS);
        connection.setReadTimeout(timeoutMs);
        connection.setUseCaches(false);
        connection.setDoInput(true);

        // use caller-provided custom SslSocketFactory, if any, for HTTPS
        if ("https".equals(url.getProtocol()) && mSslSocketFactory != null) {
            ((HttpsURLConnection)connection).setSSLSocketFactory(mSslSocketFactory);
        }

        return connection;
    }



    private static void setConnectionParametersForRequest(
            HttpURLConnection connection, Request<?> request) throws IOException, AuthFailureError {
        switch (request.getMethod()) {
            case Method.GET:
                // Not necessary to set the request method because connection defaults to GET but
                // being explicit here.
                connection.setRequestMethod("GET");
                break;
            case Method.DELETE:
                connection.setRequestMethod("DELETE");
                break;
            case Method.POST:
                connection.setRequestMethod("POST");
                addBodyIfExists(connection, request);
                break;
            case Method.PUT:
                connection.setRequestMethod("PUT");
                addBodyIfExists(connection, request);
                break;
            case Method.HEAD:
                connection.setRequestMethod("HEAD");
                break;
            case Method.OPTIONS:
                connection.setRequestMethod("OPTIONS");
                break;
            case Request.Method.TRACE:
                connection.setRequestMethod("TRACE");
                break;
            case Request.Method.PATCH:
                addBodyIfExists(connection, request);
                connection.setRequestMethod("PATCH");
                break;
            default:
                throw new IllegalStateException("Unknown method type.");
        }
    }


    private static void addBodyIfExists(HttpURLConnection connection, final Request<?> request)
            throws IOException, AuthFailureError {
        connection.setDoOutput(true);
        connection.addRequestProperty(HEADER_CONTENT_TYPE, request.getBodyContentType());

        byte[] body = request.getBody();
        if (body != null) {
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            out.write(body);
            out.close();
        }

//        if (request instanceof MultiPartRequest) {
//            final UploadMultipartEntity multipartEntity = ((MultiPartRequest<?>) request).getMultipartEntity();
//            // 防止所有文件写到内存中
//            connection.setFixedLengthStreamingMode((int)multipartEntity.getContentLength());
//            multipartEntity.writeTo(connection.getOutputStream());
//        } else {
//            byte[] body = request.getBody();
//            if (body != null) {
//                DataOutputStream out = new DataOutputStream(connection.getOutputStream());
//                out.write(body);
//                out.close();
//            }
//        }
    }




}