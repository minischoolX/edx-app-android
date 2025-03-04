package org.edx.mobile.view.custom;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import org.edx.mobile.core.EdxDefaultModule;
import org.edx.mobile.http.HttpStatus;
import org.edx.mobile.logger.Logger;
import org.edx.mobile.model.AjaxCallData;
import org.edx.mobile.module.analytics.AnalyticsRegistry;
import org.edx.mobile.util.BrowserUtil;
import org.edx.mobile.util.Config;
import org.edx.mobile.util.ConfigUtil;
import org.edx.mobile.util.FileUtil;
import org.edx.mobile.util.NetworkUtil;
import org.edx.mobile.util.links.WebViewLink;
import org.edx.mobile.view.custom.cache.offline.Destroyable;
import org.edx.mobile.view.custom.cache.FastOpenApi;
import org.edx.mobile.view.custom.cache.config.CacheConfig;
import org.edx.mobile.view.custom.cache.config.FastCacheMode;
import org.edx.mobile.view.custom.cache.offline.ResourceInterceptor;
import org.edx.mobile.view.custom.cache.WebViewCache;
import org.edx.mobile.view.custom.cache.WebViewCacheImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.android.qualifiers.ActivityContext;

/**
 * Created by rohan on 2/2/15.
 * <p/>
 * This class represents a custom {@link android.webkit.WebViewClient}.
 * This class is responsible for setting up a given {@link android.webkit.WebView}, assign itself
 * as a {@link android.webkit.WebViewClient} delegate and to intercept URLs being loaded.
 * Depending on the form of URL, this client may forward URL back to the app.
 * <p/>
 * This implementation detects host of the first URL being loaded. Further, if any URL intercepted has a different host
 * than the current one, then treats it as an external link and may open in external browser.
 */
@Singleton
public class URLInterceptorWebViewClient extends WebViewClient implements FastOpenApi, Destroyable {

    private final Logger logger = new Logger(URLInterceptorWebViewClient.class);
    private final FragmentActivity activity;
    private final CompletionCallback completionCallback;
    private final boolean interceptAjaxRequest;
    private ActionListener actionListener;
    private IPageStatusListener pageStatusListener;
    private String hostForThisPage = null;
    private boolean ajaxInterceptorEmbed = false;
    Config config;
    AnalyticsRegistry analyticsRegistry;

    private static final String SCHEME_HTTP = "http";
    private static final String SCHEME_HTTPS = "https";
    private static final String METHOD_GET = "GET";
    private WebViewCache mWebViewCache;
    private final int mWebViewCacheMode;
    private final String mUserAgent;

    /**
     * Tells if the page loading has been finished or not.
     */
    private boolean loadingFinished = false;
    /**
     * Tells if the currently loading url is the initial page url requested to load.
     */
    private boolean loadingInitialUrl = true;

    /*
    To help a few views (like Announcements) to treat every link as external link and open in
    external web browser.
     */
    private boolean isAllLinksExternal = false;
    /**
     * List of hosts whose links will be considered as internal links and will be opened within the
     * app rather than redirecting them into external browser.
     */
    private Set<String> internalLinkHosts = new HashSet<>();

    private ValueCallback<Uri[]> filePathCallback;

    @Inject
    public URLInterceptorWebViewClient(@ActivityContext FragmentActivity activity, WebView webView,
                                       boolean interceptAjaxRequest,
                                       CompletionCallback completionCallback) {
        this.activity = activity;
        this.config = EntryPointAccessors.fromApplication(activity.getApplicationContext(),
                EdxDefaultModule.ProviderEntryPoint.class).getEnvironment().getConfig();
        this.analyticsRegistry = EntryPointAccessors.fromApplication(activity.getApplicationContext(),
                EdxDefaultModule.ProviderEntryPoint.class).getEnvironment().getAnalyticsRegistry();
        this.interceptAjaxRequest = interceptAjaxRequest;
        this.completionCallback = completionCallback;
        setupWebView(webView);
        WebSettings settings = webView.getSettings();
        mWebViewCacheMode = settings.getCacheMode();
        mUserAgent = settings.getUserAgentString();
        mWebViewCache = new WebViewCacheImpl(webView.getContext());

    }

    /**
     * Sets action listener for this client. Use this method to get callbacks
     * of actions as declared in {@link ActionListener}.
     *
     * @param actionListener
     */
    public void setActionListener(ActionListener actionListener) {
        this.actionListener = actionListener;
    }

    /**
     * Gives page status callbacks like page loading started, finished or error.
     *
     * @param pageStatusListener
     */
    public void setPageStatusListener(IPageStatusListener pageStatusListener) {
        this.pageStatusListener = pageStatusListener;
    }

    /**
     * Sets up the WeView, applies minimal required settings and
     * sets this class itself as WebViewClient.
     *
     * @param webView
     */
    private void setupWebView(WebView webView) {
        webView.setWebViewClient(this);
        //We need to hide the loading progress if the Page starts rendering.
        webView.setWebChromeClient(new WebChromeClient() {
            public void onProgressChanged(WebView view, int progress) {
                if (progress < 100) {
                    loadingFinished = false;
                }
                if (pageStatusListener != null) {
                    pageStatusListener.onPageLoadProgressChanged(view, progress);
                }
                if (interceptAjaxRequest && progress > 30 && !ajaxInterceptorEmbed) {
                    // setup native callback to intercept the ajax requests.
                    try {
                        String nativeAjaxCallbackJS = FileUtil.loadTextFileFromAssets(activity, "js/nativeAjaxCallback.js");
                        view.loadUrl(nativeAjaxCallbackJS);
                        ajaxInterceptorEmbed = true;
                    } catch (IOException e) {
                        logger.error(e);
                    }
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                URLInterceptorWebViewClient.this.filePathCallback = filePathCallback;
                FileUtil.chooseFiles(activity, fileChooserParams.getAcceptTypes());
                return true;
            }
        });
        if (interceptAjaxRequest) {
            webView.addJavascriptInterface(new AjaxNativeCallback(completionCallback), "nativeAjaxCallback");
        }
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);

        loadingFinished = false;

        // hold on the host of this page, just once
        if (this.hostForThisPage == null && url != null) {
            this.hostForThisPage = Uri.parse(url).getHost();
        }

        if (pageStatusListener != null) {
            pageStatusListener.onPageStarted();
        }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        loadingInitialUrl = false;
        loadingFinished = true;
        // Page loading has finished.
        if (pageStatusListener != null) {
            pageStatusListener.onPageFinished();
        }
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        if (pageStatusListener != null) {
            pageStatusListener.onPageLoadError(view, errorCode, description, failingUrl);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        super.onReceivedHttpError(view, request, errorResponse);
        if (pageStatusListener != null) {
            pageStatusListener.onPageLoadError(view, request, errorResponse,
                    request.getUrl().toString().equals(view.getUrl()));
        }
    }

    /**
     * Method is usable for Android 6.0 and below otherwise app didn't get the control when a URL is
     * about to be loaded in the current WebView.
     * Ref: https://developer.android.com/reference/android/webkit/WebViewClient#shouldOverrideUrlLoading(android.webkit.WebView,%20java.lang.String)
     */
    @Deprecated
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        //view.loadurl(url);
        //return true;
        return shouldOverrideUrlLoadingWrapper(view, url);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        //view.loadurl(request.getUrl().toString());
        //return true;
        return shouldOverrideUrlLoadingWrapper(view, request.getUrl().toString());
    }

    private boolean shouldOverrideUrlLoadingWrapper(WebView view, String url) {
        if (actionListener == null) {
            logger.warn("you have not set IActionLister to this WebViewClient, " +
                    "you might miss some event");
        }
        logger.debug("loading: " + url);
        if (parseRecognizedLinkAndCallListener(url)) {
            // we handled this URL
            view.loadUrl(url);
            return true;
            //return true;
        } else if (loadingInitialUrl && !loadingFinished) {
            // Server has redirected the initial url to other hosting url, in this case no need to
            // redirect the user to external browser.
            // For more details see LEARNER-6596
            // Return false means the current WebView handles the url.
            view.loadUrl(url);
            return true;
            //return false;
        } else if (isInternalLink(url)) {
            view.loadUrl(url);
            return true;
            //return false;
        } else if (isAllLinksExternal || isExternalLink(url)) {
            // open URL in external web browser
            // return true means the host application handles the url
            // this should open the URL in the browser with user's confirmation
            BrowserUtil.showOpenInBrowserDialog(activity, config.getPlatformName(), url, analyticsRegistry, true);
            return true;
        } else {
            // return false means the current WebView handles the url.
            view.loadUrl(url);
            return true;
            //return false;
        }
    }

    public void setAllLinksAsExternal(boolean isAllLinksExternal) {
        this.isAllLinksExternal = isAllLinksExternal;
    }

    /**
     * Add a host whose links will be considered as internal links and will be loaded within the
     * app rather than redirecting them into external browser.
     *
     * @param internalLinkHost host which needs to add in the list
     */
    public void addInternalLinkHost(@NonNull String internalLinkHost) {
        this.internalLinkHosts.add(internalLinkHost);
    }

    /**
     * Check either the url should be loaded within app or should be redirected to external browser.
     *
     * @param url which needs be checked
     * @return true if url is considered internal, false otherwise
     */
    private boolean isInternalLink(@NonNull String url) {
        for (String internalLinkHost : internalLinkHosts) {
            if (url.startsWith(internalLinkHost)) {
                return true;
            }
        }
        return false;
    }

    public void setLoadingInitialUrl(boolean isLoadingInitialUrl) {
        this.loadingInitialUrl = isLoadingInitialUrl;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (completionCallback != null &&
                AjaxCallData.isCompletionRequest(new AjaxCallData(HttpStatus.OK, request.getUrl().toString(), ""))) {
            completionCallback.blockCompletionHandler(true);
        }
        Context context = view.getContext().getApplicationContext();

        // suppress external links on ZeroRated network
        String url = request.getUrl().toString();
        if (isExternalLink(url)
                && !ConfigUtil.Companion.isWhiteListedURL(url, config)
                && NetworkUtil.isOnZeroRatedNetwork(context, config)
                && NetworkUtil.isConnectedMobile(context)) {
            return new WebResourceResponse("text/html", StandardCharsets.UTF_8.name(), null);
        }
        return onIntercept(view, request);
        //return super.shouldInterceptRequest(view, request);
    }

    private WebResourceResponse onIntercept(WebView view, WebResourceRequest request) {
        //if (mDelegate != null) {
        //    WebResourceResponse response = mDelegate.shouldInterceptRequest(view, request);
        //    if (response != null) {
        //        return response;
        ///    }
        //}
        return loadFromWebViewCache(request);
    }

    private WebResourceResponse loadFromWebViewCache(WebResourceRequest request) {
        String scheme = request.getUrl().getScheme().trim();
        String method = request.getMethod().trim();
        if ((TextUtils.equals(SCHEME_HTTP, scheme)
                || TextUtils.equals(SCHEME_HTTPS, scheme))
                && method.equalsIgnoreCase(METHOD_GET)) {

            return mWebViewCache.getResource(request, mWebViewCacheMode, mUserAgent);
        }
        return null;
    }

    @Override
    public void setCacheMode(FastCacheMode mode, CacheConfig cacheConfig) {
        if (mWebViewCache != null) {
            mWebViewCache.setCacheMode(mode, cacheConfig);
        }
    }

    @Override
    public void addResourceInterceptor(ResourceInterceptor interceptor) {
        if (mWebViewCache != null) {
            mWebViewCache.addResourceInterceptor(interceptor);
        }
    }

    @Override
    public void destroy() {
        if (mWebViewCache != null) {
            mWebViewCache.destroy();
        }
    }

    /**
     * Returns true if the pattern of the url matches with that of EXTERNAL URL pattern,
     * false otherwise.
     *
     * @param strUrl
     * @return
     */
    private boolean isExternalLink(String strUrl) {
        return hostForThisPage != null && strUrl != null &&
                !hostForThisPage.equals(Uri.parse(strUrl).getHost());
    }

    /**
     * Checks if {@param strUrl} is valid enroll link and, if so,
     * calls {@link ActionListener#onClickEnroll(String, boolean)}
     *
     * @return true if an action listener is set and URL was a valid enroll link, false otherwise
     */
    /**
     * Checks if {@param strUrl} is recognizable link for the app, if so,
     * calls {@link ActionListener#onLinkRecognized(WebViewLink)}
     *
     * @param strUrl The URL to parse.
     * @return Whether the URL had atleast one recognized link in it.
     */
    private boolean parseRecognizedLinkAndCallListener(@Nullable String strUrl) {
        if (null == actionListener) {
            return false;
        }
        final WebViewLink helperObj = WebViewLink.parse(strUrl);
        if (null == helperObj) {
            return false;
        }
        actionListener.onLinkRecognized(helperObj);
        logger.debug("found a recognized URL: " + strUrl);
        return true;
    }

    public void setHostForThisPage(@Nullable String hostForThisPage) {
        this.hostForThisPage = hostForThisPage;
    }

    public void onFilesSelection(Uri[] files) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(files);
        }
    }

    /**
     * Action listener interface for handling a user's click on recognized links in a WebView.
     */
    public interface ActionListener {
        /**
         * Callback that gets called when this client has intercepted a recognizable link in the
         * WebView. Sub-classes or any implementation of this class should override this method to
         * handle or further act upon the recognized link.
         */
        void onLinkRecognized(@NonNull WebViewLink helper);
    }

    /**
     * Page state callbacks.
     */
    public interface IPageStatusListener {
        /**
         * Callback that indicates page loading has started.
         */
        void onPageStarted();

        /**
         * Callback that indicates page loading has finished.
         */
        void onPageFinished();

        /**
         * Callback that indicates error during page load.
         */
        void onPageLoadError(WebView view, int errorCode, String description, String failingUrl);

        /**
         * Callback that indicates error during page load.
         */
        void onPageLoadError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse,
                             boolean isMainRequestFailure);

        /**
         * Callback that indicates a page's loading progress inside a WebView whether it be
         * through a full page load/refresh or through an AJAX request.
         * <br>
         * Note: This is a utility callback that exposes {@link WebChromeClient#onProgressChanged(WebView, int)}
         *
         * @param webView  The WebView in which a page is being loaded.
         * @param progress Progress of the page being loaded.
         */
        void onPageLoadProgressChanged(WebView webView, int progress);
    }

    public interface CompletionCallback {
        void blockCompletionHandler(boolean isCompleted);
    }
}
