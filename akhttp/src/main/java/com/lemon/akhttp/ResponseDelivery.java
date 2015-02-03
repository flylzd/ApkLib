package com.lemon.akhttp;


import com.lemon.akhttp.error.VolleyError;

public interface ResponseDelivery {

    /** Posts request finished callback for the given request. */
    public void postFinish(Request<?> request);

    /** Parses a response from the network or cache and delivers it. */
    public void postResponse(Request<?> request, Response<?> response);

    /**
     * Parses a response from the network or cache and delivers it. The provided
     * Runnable will be executed after delivery.
     */
    public void postResponse(Request<?> request, Response<?> response, Runnable runnable);

    /** Posts an error for the given request. */
    public void postError(Request<?> request, VolleyError error);

    /** Posts a cancel callback for the given request. */
    public void postCancel(Request<?> request);

    /** Posts starting execute callback for the given request. */
    public  void postPreExecute(Request<?> request);

    /** Posts cache used callback for the given request. */
    public void postUsedCache(Request<?> request);

    /** Posts networking callback for the given request. */
    public void postNetworking(Request<?> request);

    /** Posts request retry callback for the given request. */
    public void postRetry(Request<?> request);

    /** Posts file download progress stat. */
    public void postDownloadProgress(Request<?> request, long fileSize, long downloadedSize);
}
