package com.gozdor.lightcraft;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hosts the LightCraft web build (egui compiled to WebAssembly) in a full-screen WebView.
 *
 * The bundled site is served from a synthetic HTTPS origin so the page gets a secure context,
 * which LightCraft's web build needs for its render workers and its origin-private file storage
 * (where the photo library lives). The origin must never change between releases, or the
 * library stored under it would no longer be visible.
 */
public class MainActivity extends Activity {

    private static final String TAG = "LightCraft";
    /** Reserved by Android for app-local content; never resolves on the network. */
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST;
    private static final String START_URL = ORIGIN + "/index.html";
    private static final String ASSET_ROOT = "www";
    private static final int REQUEST_FILES = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> pendingFileCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF1C1C1C);
        setContentView(webView);
        hideSystemBars();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // The site is served by shouldInterceptRequest, not from file:// URLs.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // LightCraft draws its own UI on a canvas and handles pinch zoom itself.
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.addJavascriptInterface(new DownloadBridge(), "LightCraftAndroid");
        webView.setWebViewClient(new LocalSiteClient());
        webView.setWebChromeClient(new ChromeClient());
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                handleDownload(url, contentDisposition, mimeType));

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    // ---- full screen ---------------------------------------------------------------------------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController c = window.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // LightCraft is a single page; going "back" would unload it. Send the app to the
        // background instead so an edit in progress isn't lost.
        moveTaskToBack(true);
    }

    // ---- serving the bundled site --------------------------------------------------------------

    private final class LocalSiteClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!HOST.equals(url.getHost()) || !"https".equals(url.getScheme())) {
                return null; // let the WebView handle anything else (there should be nothing else)
            }
            String path = url.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
            if (path.contains("..")) return notFound();
            String asset = ASSET_ROOT + path;
            try {
                InputStream in = getAssets().open(asset, AssetManager.ACCESS_STREAMING);
                WebResourceResponse r = new WebResourceResponse(mimeFor(path), null, in);
                r.setStatusCodeAndReasonPhrase(200, "OK");
                r.setResponseHeaders(siteHeaders());
                return r;
            } catch (IOException e) {
                // e.g. the optional Chinese font, which this build doesn't ship
                return notFound();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (HOST.equals(url.getHost())) return false;
            // External links (docs, project pages) open in the phone's browser.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (ActivityNotFoundException ignored) {
                // no browser installed; nothing to do
            }
            return true;
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Usually out of memory on a very large raw file. Rebuild the WebView instead of
            // letting the whole app crash; the library itself is safe in storage.
            Log.e(TAG, "WebView renderer gone (crashed=" + detail.didCrash() + ")");
            if (webView == view) {
                webView.destroy();
                webView = null;
                Toast.makeText(MainActivity.this,
                        "LightCraft ran out of memory and was reloaded.", Toast.LENGTH_LONG).show();
                recreate();
            }
            return true;
        }
    }

    private static Map<String, String> siteHeaders() {
        Map<String, String> h = new HashMap<>();
        // Same headers LightCraft's own dev server sends (cross-origin isolation).
        h.put("Cross-Origin-Opener-Policy", "same-origin");
        h.put("Cross-Origin-Embedder-Policy", "require-corp");
        h.put("Cross-Origin-Resource-Policy", "same-origin");
        h.put("Cache-Control", "no-cache");
        return h;
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        r.setResponseHeaders(siteHeaders());
        return r;
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
        // WebAssembly.compileStreaming rejects anything but application/wasm.
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".txt") || p.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
    }

    // ---- opening photos (the page's <input type=file>) -----------------------------------------

    private final class ChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = callback;

            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            // Raw files have many MIME types (or none); offer everything and let LightCraft decide.
            pick.setType("*/*");
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(pick, REQUEST_FILES);
                return true;
            } catch (ActivityNotFoundException e) {
                pendingFileCallback = null;
                Toast.makeText(MainActivity.this, "No file picker available.", Toast.LENGTH_LONG).show();
                return false;
            }
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            Log.d(TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILES || pendingFileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        pendingFileCallback.onReceiveValue(result);
        pendingFileCallback = null;
    }

    // ---- saving exports (blob: downloads) ------------------------------------------------------

    private void handleDownload(String url, String contentDisposition, String mimeType) {
        String guessed = URLUtil.guessFileName(url, contentDisposition, mimeType);
        String name = (contentDisposition != null && contentDisposition.contains("filename"))
                ? guessed : null;
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            // DownloadManager can't read blob URLs; fetch the bytes in the page and hand them over.
            String js = "(async () => {"
                    + "try {"
                    + "  const r = await fetch(" + jsString(url) + ");"
                    + "  const b = await r.blob();"
                    + "  const fr = new FileReader();"
                    + "  fr.onloadend = () => LightCraftAndroid.save(fr.result, "
                    + jsString(name == null ? "" : name) + ", b.type || " + jsString(mimeType == null ? "" : mimeType) + ");"
                    + "  fr.readAsDataURL(b);"
                    + "} catch (e) { LightCraftAndroid.failed(String(e)); }"
                    + "})();";
            webView.evaluateJavascript(js, null);
        } else {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ignored) {
                // nothing to hand it to
            }
        }
    }

    private static String jsString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '<': b.append("\\u003c"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private final class DownloadBridge {
        @JavascriptInterface
        public void save(String dataUrl, String name, String mimeType) {
            try {
                int comma = dataUrl.indexOf(',');
                byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                String mime = (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType;
                String fileName = (name == null || name.isEmpty()) ? defaultName(mime) : name;
                String where = writeToDownloads(bytes, fileName, mime);
                toast("Saved " + fileName + " to " + where);
            } catch (Exception e) {
                Log.e(TAG, "save failed", e);
                toast("Couldn't save the file: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void failed(String message) {
            toast("Couldn't save the file: " + message);
        }
    }

    private static String defaultName(String mime) {
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return "LightCraft-" + System.currentTimeMillis() + (ext == null ? "" : "." + ext);
    }

    @SuppressWarnings("deprecation")
    private String writeToDownloads(byte[] bytes, String fileName, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LightCraft");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("MediaStore refused the file");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("couldn't open the file for writing");
                out.write(bytes);
            }
            return "Downloads/LightCraft";
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LightCraft");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("couldn't create " + dir);
            try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
                out.write(bytes);
            }
            return dir.getAbsolutePath();
        }
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
