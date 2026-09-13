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
    private String pendingId;
    private MigrationSnapshotStore snapshots;
    private final java.util.concurrent.ExecutorService fileExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        snapshots = new MigrationSnapshotStore(getFilesDir());
        // Keep export payload out of Bundle/Binder. It survives Activity and process recreation on disk.
        try {
            java.io.File pending = new java.io.File(getFilesDir(), "pending-export.json");
            if(pending.exists()) {
                org.json.JSONObject data = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(pending.toPath()), StandardCharsets.UTF_8));
                pendingText=data.getString("text"); pendingMime=data.getString("mime"); pendingId=data.getString("id");
            }
        } catch(Exception e) { Toast.makeText(this,"Pending export could not be recovered.",Toast.LENGTH_LONG).show(); }
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
        @JavascriptInterface public String recoverSnapshot(String raw) {
            try { return new org.json.JSONObject().put("ok",true).put("state",snapshots.recover(raw)).toString(); }
            catch(Exception e) { return "{\"ok\":false}"; }
        }
        @JavascriptInterface public long stageSnapshot(String raw) {
            try { return snapshots.stage(raw); } catch(Exception e) { return -1; }
        }
        @JavascriptInterface public boolean commitSnapshot(long generation) {
            try { snapshots.commit(generation); return true; } catch(Exception e) { return false; }
        }
        @JavascriptInterface public boolean abortSnapshot(long generation) {
            try { snapshots.abort(generation); return true; } catch(Exception e) { return false; }
        }
        @JavascriptInterface public void saveText(String filename, String text, String mime) {
            saveTextWithId(java.util.UUID.randomUUID().toString(), filename, text, mime);
        }
        @JavascriptInterface public void saveTextWithId(String id, String filename, String text, String mime) {
            runOnUiThread(() -> {
                if(pendingId != null) { exportResult(id,"busy"); return; }
                pendingId=id; pendingText=text == null ? "" : text;
                pendingMime=(mime == null || mime.isEmpty()) ? "text/plain" : mime;
                fileExecutor.execute(() -> {
                    try {
                        android.util.AtomicFile file = new android.util.AtomicFile(new java.io.File(getFilesDir(),"pending-export.json"));
                        java.io.FileOutputStream stream = null;
                        try {
                            stream=file.startWrite();
                            String raw=new org.json.JSONObject().put("id",id).put("text",pendingText).put("mime",pendingMime).toString();
                            stream.write(raw.getBytes(StandardCharsets.UTF_8)); stream.getFD().sync(); file.finishWrite(stream);
                        } catch(Exception e) { if(stream != null) file.failWrite(stream); throw e; }
                        runOnUiThread(() -> {
                            try {
                                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(pendingMime);
                                i.putExtra(Intent.EXTRA_TITLE,filename == null ? "lead-hopper-export.txt" : filename);
                                startActivityForResult(i,SAVE_FILE);
                            } catch(Exception e) { finishExport("failed"); }
                        });
                    } catch(Exception e) { runOnUiThread(() -> finishExport("failed")); }
                });
            });
        }
    }
    private void exportResult(String id,String status) {
        if(webView != null) webView.evaluateJavascript("window.v19ExportResult && window.v19ExportResult("+
                org.json.JSONObject.quote(id)+","+org.json.JSONObject.quote(status)+");",null);
    }
    private void finishExport(String status) {
        String id=pendingId;
        pendingId=null; pendingText=null; pendingMime=null;
        new java.io.File(getFilesDir(),"pending-export.json").delete();
        if(id != null) exportResult(id,status);
        if("saved".equals(status)) Toast.makeText(this,"Saved.",Toast.LENGTH_SHORT).show();
        else if("failed".equals(status)) Toast.makeText(this,"Save failed. Your work is still in Lead Hopper.",Toast.LENGTH_LONG).show();
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
        } else if (requestCode == SAVE_FILE) {
            if(resultCode != RESULT_OK || data == null || data.getData() == null) { finishExport("cancelled"); return; }
            final Uri destination=data.getData();
            final String payload=pendingText;
            fileExecutor.execute(() -> {
                String result="saved";
                try (OutputStream os=getContentResolver().openOutputStream(destination,"wt")) {
                    if(os == null || payload == null) throw new java.io.IOException("Export stream unavailable");
                    os.write(payload.getBytes(StandardCharsets.UTF_8)); os.flush();
                } catch(Exception e) { result="failed"; }
                final String status=result;
                runOnUiThread(() -> finishExport(status));
            });
        }
    }

    @Override public void onBackPressed() {
        if(webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript("!!(window.v19HandleBack && window.v19HandleBack())", result -> {
            if(!"true".equals(result)) MainActivity.super.onBackPressed();
        });
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        fileExecutor.shutdown();
        super.onDestroy();
    }
}
