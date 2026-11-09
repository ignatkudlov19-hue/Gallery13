package com.marmalade.notebook;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_FILES = 1, REQ_TREE = 2;
    private static final String HOST = "appassets.androidplatform.net";

    private WebView web;
    private FrameLayout root;
    private ValueCallback<Uri[]> filePathCallback;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // файл (с путём внутри выбранной папки), ожидающий записи
    private static class Pending {
        List<String> dirs = new ArrayList<>();
        String name, mime;
        File tmp;
    }
    private final ArrayDeque<Pending> waiting = new ArrayDeque<>();
    private final Map<String, FileOutputStream> open = new HashMap<>();
    private final Map<String, Pending> meta = new HashMap<>();
    private final Map<String, Uri> dirCache = new HashMap<>();
    private int savedCount = 0;

    // Перехват скачиваний blob-ссылок: файлы уходят в приложение по очереди, кусками.
    // Если в имени есть «/», то это путь внутри выбранной папки (так переносятся папки).
    private static final String HOOK =
        "(function(){if(window.__mh)return;window.__mh=1;window.__q=Promise.resolve();" +
        "function send(pb,name){return pb.then(function(b){return new Promise(function(res){" +
        " var id=Date.now()+'_'+Math.random().toString(36).slice(2);" +
        " Android.begin(id,name,b.type||'application/octet-stream');" +
        " var CH=3*1024*1024,pos=0;" +
        " function next(){" +
        "  if(pos>=b.size){Android.end(id);res();return}" +
        "  var part=b.slice(pos,pos+CH);pos+=CH;" +
        "  var fr=new FileReader();" +
        "  fr.onload=function(){Android.chunk(id,fr.result.split(',')[1]);next()};" +
        "  fr.onerror=function(){Android.end(id);res()};" +
        "  fr.readAsDataURL(part);" +
        " }" +
        " next();" +
        "})})}" +
        "document.addEventListener('click',function(e){" +
        "var a=e.target.closest&&e.target.closest('a[download]');" +
        "if(!a||!a.href||a.href.indexOf('blob:')!==0)return;" +
        "e.preventDefault();e.stopPropagation();" +
        "var name=a.download||'file';" +
        "var pb=fetch(a.href).then(function(r){return r.blob()});" +
        "window.__q=window.__q.then(function(){return send(pb,name)}).catch(function(){});" +
        "},true);})();";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("p", MODE_PRIVATE);

        // выемка под камеру: контент не заходит под неё, но экран во всю высоту
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 28) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                DisplayCutout dc = insets.getDisplayCutout();
                int t = 0, l = 0, r = 0;
                if (dc != null) { t = dc.getSafeInsetTop(); l = dc.getSafeInsetLeft(); r = dc.getSafeInsetRight(); }
                v.setPadding(l, t, r, 0);
                return insets;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                v.evaluateJavascript(HOOK, null);
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(HOOK, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    ArrayList<String> acc = new ArrayList<>();
                    for (String a : p.getAcceptTypes()) {
                        if (a == null) continue;
                        for (String t : a.split(",")) {
                            t = t.trim();
                            if (t.contains("/")) acc.add(t);
                        }
                    }
                    if (acc.isEmpty()) acc.add("*/*");
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, acc.toArray(new String[0]));
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(i, REQ_FILES);
                } catch (Exception e) {
                    filePathCallback = null;
                    toast("Не удалось открыть выбор файлов: " + e.getMessage());
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final android.webkit.JsResult r) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("OK", (d, w) -> r.confirm())
                    .setOnCancelListener(d -> r.confirm())
                    .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final android.webkit.JsResult r) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("OK", (d, w) -> r.confirm())
                    .setNegativeButton("Отмена", (d, w) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def,
                                      final android.webkit.JsPromptResult r) {
                final android.widget.EditText et = new android.widget.EditText(MainActivity.this);
                et.setText(def);
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg).setView(et)
                    .setPositiveButton("OK", (d, w) -> r.confirm(et.getText().toString()))
                    .setNegativeButton("Отмена", (d, w) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
                return true;
            }
        });

        hideBars();
        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    // ---------- полноэкранный режим: панели скрыты, появляются по свайпу от края ----------
    private void hideBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILES) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                r = WebChromeClient.FileChooserParams.parseResult(res, data);
                if (r == null || r.length == 0) {
                    ArrayList<Uri> l = new ArrayList<>();
                    if (data.getClipData() != null) {
                        for (int k = 0; k < data.getClipData().getItemCount(); k++)
                            l.add(data.getClipData().getItemAt(k).getUri());
                    } else if (data.getData() != null) {
                        l.add(data.getData());
                    }
                    if (!l.isEmpty()) r = l.toArray(new Uri[0]);
                }
            }
            if (filePathCallback != null) filePathCallback.onReceiveValue(r);
            filePathCallback = null;
        } else if (req == REQ_TREE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                prefs.edit().putString("tree", tree.toString()).apply();
                dirCache.clear();
                flushWaiting();
            } else {
                for (Pending p : waiting) p.tmp.delete();
                waiting.clear();
                toast("Папка не выбрана — файлы не сохранены");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    // ---------- сохранение на устройство ----------
    private void toast(final String t) {
        runOnUiThread(() -> Toast.makeText(this, t, Toast.LENGTH_SHORT).show());
    }

    // одно общее сообщение после серии сохранений
    private final Runnable summary = () -> {
        if (savedCount > 0) toast("Сохранено файлов: " + savedCount);
        savedCount = 0;
        dirCache.clear();
    };

    private void noteSaved() {
        savedCount++;
        ui.removeCallbacks(summary);
        ui.postDelayed(summary, 1500);
    }

    private Uri ensureDir(Uri parent, List<String> dirs) throws Exception {
        String key = "";
        Uri cur = parent;
        for (String d : dirs) {
            key = key + "/" + d;
            Uri cached = dirCache.get(key);
            if (cached == null) {
                cached = DocumentsContract.createDocument(getContentResolver(), cur,
                    DocumentsContract.Document.MIME_TYPE_DIR, d);
                if (cached == null) throw new Exception("dir");
                dirCache.put(key, cached);
            }
            cur = cached;
        }
        return cur;
    }

    private boolean writeToTree(Pending p) {
        String ts = prefs.getString("tree", null);
        if (ts == null) return false;
        try {
            Uri tree = Uri.parse(ts);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
            Uri dir = ensureDir(parent, p.dirs);
            Uri doc = DocumentsContract.createDocument(getContentResolver(), dir, p.mime, p.name);
            if (doc == null) return false;
            try (OutputStream os = getContentResolver().openOutputStream(doc);
                 FileInputStream in = new FileInputStream(p.tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            p.tmp.delete();
            noteSaved();
            return true;
        } catch (Exception e) {
            // доступ к папке мог пропасть — попросим выбрать заново
            prefs.edit().remove("tree").apply();
            dirCache.clear();
            return false;
        }
    }

    private void flushWaiting() {
        while (!waiting.isEmpty()) {
            Pending p = waiting.peek();
            if (writeToTree(p)) waiting.poll();
            else { askTree(); return; }
        }
    }

    private void askTree() {
        runOnUiThread(() -> {
            toast("Выберите папку для сохранения файлов");
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        });
    }

    private static String clean(String s) {
        s = s.replaceAll("[\\\\:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "_" : s;
    }

    private class Bridge {
        @JavascriptInterface
        public void begin(String id, String path, String mime) {
            try {
                Pending p = new Pending();
                String[] parts = path.split("/");
                for (int k = 0; k < parts.length; k++) {
                    if (parts[k].trim().isEmpty()) continue;
                    if (k == parts.length - 1) p.name = clean(parts[k]);
                    else p.dirs.add(clean(parts[k]));
                }
                if (p.name == null) p.name = "file";
                p.mime = mime;
                p.tmp = File.createTempFile("dl_", ".bin", getCacheDir());
                meta.put(id, p);
                open.put(id, new FileOutputStream(p.tmp));
            } catch (Exception e) { toast("Ошибка: " + e.getMessage()); }
        }

        @JavascriptInterface
        public void chunk(String id, String b64) {
            try {
                FileOutputStream o = open.get(id);
                if (o != null) o.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) { toast("Ошибка записи: " + e.getMessage()); }
        }

        @JavascriptInterface
        public void end(String id) {
            try {
                FileOutputStream o = open.remove(id);
                if (o != null) o.close();
            } catch (Exception ignored) {}
            Pending p = meta.remove(id);
            if (p == null) return;
            if (!waiting.isEmpty()) { waiting.add(p); return; }
            if (!writeToTree(p)) { waiting.add(p); askTree(); }
        }
    }
}
