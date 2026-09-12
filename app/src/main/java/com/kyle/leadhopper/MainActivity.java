package com.kyle.leadhopper;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER = 1001;
    private static final int SAVE_FILE = 1002;
    private static final String LOCAL_APP_URL = "file:///android_asset/index.html";
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingText;
    private String pendingMime;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WebView.setWebContentsDebuggingEnabled(false);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSafeBrowsingEnabled(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if ("tel".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_DIAL, u)); }
                    catch (Exception e) { Toast.makeText(MainActivity.this, "Could not open dialer.", Toast.LENGTH_SHORT).show(); }
                    return true;
                }
                if ("mailto".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_SENDTO, u)); }
                    catch (Exception e) { Toast.makeText(MainActivity.this, "Could not open email app.", Toast.LENGTH_SHORT).show(); }
                    return true;
                }
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); }
                    catch (Exception e) { Toast.makeText(MainActivity.this, "Could not open link.", Toast.LENGTH_SHORT).show(); }
                    return true;
                }
                if ("file".equals(scheme) && u.toString().startsWith("file:///android_asset/")) return false;
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                try {
                    startActivityForResult(intent, FILE_CHOOSER);
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Could not open file picker.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl(LOCAL_APP_URL);
    }

    public class AndroidBridge {
        @JavascriptInterface public void saveText(String filename, String text, String mime) {
            pendingText = text == null ? "" : text;
            pendingMime = (mime == null || mime.isEmpty()) ? "text/plain" : mime;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(pendingMime);
                i.putExtra(Intent.EXTRA_TITLE, filename == null ? "lead-hopper-export.txt" : filename);
                startActivityForResult(i, SAVE_FILE);
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER) {
            if (fileCallback != null) {
                Uri[] out = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        out = new Uri[n];
                        for (int k = 0; k < n; k++) out[k] = data.getClipData().getItemAt(k).getUri();
                    } else if (data.getData() != null) {
                        out = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(out);
                fileCallback = null;
            }
        } else if (requestCode == SAVE_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                if (os != null) os.write((pendingText == null ? "" : pendingText).getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Save failed.", Toast.LENGTH_LONG).show();
            } finally {
                pendingText = null;
                pendingMime = null;
            }
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
