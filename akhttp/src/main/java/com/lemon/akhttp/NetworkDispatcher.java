package com.lemon.akhttp;

import android.annotation.TargetApi;
import android.net.TrafficStats;
import android.os.*;
import android.os.Process;

import com.lemon.akhttp.cache.Cache;
import com.lemon.akhttp.error.VolleyError;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing network dispatch from a queue of requests.
 * <p/>
 * Requests added to the specified queue are processed from the network via a
 * specified {@link android.net.Network} interface. Responses are committed to cache, if
 * eligible, using a specified {@link com.lemon.akhttp.cache.Cache} interface. Valid responses and
 * errors are posted back to the caller via a {@link ResponseDelivery}.
 */
public class NetworkDispatcher extends Thread {

    /**
     * The queue of requests to service.
     */
    private final BlockingQueue<Request<?>> mQueue;
    /**
     * The network interface for processing requests.
     */
    private final Network mNetwork;
    /**
     * The cache to write to.
     */
    private final Cache mCache;
    /**
     * For posting responses and errors.
     */
    private final ResponseDelivery mDelivery;
    /**
     * Used for telling us to die.
     */
    private volatile boolean mQuit = false;

    private volatile boolean mPause = false;
    private Object mPauseLock = new Object();


    /**
     * Creates a new network dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     *
     * @param queue    Queue of incoming requests for triage
     * @param network  Network interface to use for performing requests
     * @param cache    Cache interface to use for writing responses to cache
     * @param delivery Delivery interface to use for posting responses
     */
    public NetworkDispatcher(BlockingQueue<Request<?>> queue,
                             Network network, Cache cache,
                             ResponseDelivery delivery) {
        mQueue = queue;
        mNetwork = network;
        mCache = cache;
        mDelivery = delivery;
    }


    /**
     * Forces this dispatcher to quit immediately.  If any requests are still in
     * the queue, they are not guaranteed to be processed.
     */
    public void quit() {
        resumeTask();
        mQuit = true;
        interrupt();
    }

    public void resumeTask() {
        mPause = false;
        synchronized (mPauseLock) {
            mPauseLock.notifyAll();
        }
    }

    public void pauseTask() {
        mPause = true;
    }


    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void addTrafficStatsTag(Request<?> request) {
        // Tag the request (if API >= 14)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            TrafficStats.setThreadStatsTag(request.getTrafficStatsTag());
        }
    }


    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            Request<?> request;
            try {

                if (mPause) {
                    synchronized (mPauseLock) {
                        mPauseLock.wait();
                    }
                }
                // Take a request from the queue.
                request = mQueue.take();
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }

            try {
                request.addMarker("network-queue-take");
                mDelivery.postPreExecute(request);

                // If the request was cancelled already, do not perform the
                // network request.
                if (request.isCanceled()) {
                    request.finish("network-discard-cancelled");
                    mDelivery.postCancel(request);
                    mDelivery.postFinish(request);
                    continue;
                }
                addTrafficStatsTag(request);

                // Perform the network request.
                NetworkResponse networkResponse = mNetwork.performRequest(request);
                request.addMarker("network-http-complete");

                // If the server returned 304 AND we delivered a response already,
                // we're done -- don't deliver a second identical response.
                if (networkResponse.notModified && request.hasHadResponseDelivered()) {
                    request.finish("not-modified");
                    continue;
                }

                // Parse the response here on the worker thread.
                Response<?> response = request.parseNetworkResponse(networkResponse);
                request.addMarker("network-parse-complete");

                // Write to cache if applicable.
                // TODO: Only update cache metadata instead of entire record for 304s.
                if (request.shouldCache() && response.cacheEntry != null) {
                    mCache.put(request.getCacheKey(), response.cacheEntry);
                    request.addMarker("network-cache-written");
                }

                // Post the response back.
                request.markDelivered();
                mDelivery.postResponse(request, response);
            } catch (VolleyError volleyError) {
                parseAndDeliverNetworkError(request, volleyError);
            } catch (Exception e) {
                VolleyLog.e(e, "Unhandled exception %s", e.toString());
                mDelivery.postError(request, new VolleyError(e));
            }

        }
    }

    private void parseAndDeliverNetworkError(Request<?> request, VolleyError error) {
        error = request.parseNetworkError(error);
        mDelivery.postError(request, error);
    }
}
