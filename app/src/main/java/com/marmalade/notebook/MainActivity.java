package com.marmalade.notebook;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_FILES = 1, REQ_TREE = 2;
    private static final String HOST = "appassets.androidplatform.net";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private SharedPreferences prefs;

    // файлы, ожидающие выбора папки
    private static class Pending { String name, mime; java.io.File tmp; }
    private final ArrayDeque<Pending> waiting = new ArrayDeque<>();
    private final Map<String, java.io.FileOutputStream> open = new HashMap<>();
    private final Map<String, Pending> meta = new HashMap<>();

    private static final String HOOK =
        "(function(){if(window.__mh)return;window.__mh=1;" +
        "document.addEventListener('click',function(e){" +
        "var a=e.target.closest&&e.target.closest('a[download]');" +
        "if(!a||!a.href||a.href.indexOf('blob:')!==0)return;" +
        "e.preventDefault();e.stopPropagation();" +
        "var name=a.download||'file';" +
        "fetch(a.href).then(function(r){return r.blob()}).then(function(b){" +
        " var id=Date.now()+'_'+Math.random().toString(36).slice(2);" +
        " Android.begin(id,name,b.type||'application/octet-stream');" +
        " var CH=1024*1024*3,pos=0;" +
        " function next(){" +
        "  if(pos>=b.size){Android.end(id);return}" +
        "  var part=b.slice(pos,pos+CH);pos+=CH;" +
        "  var fr=new FileReader();" +
        "  fr.onload=function(){Android.chunk(id,fr.result.split(',')[1]);next()};" +
        "  fr.readAsDataURL(part);" +
        " }" +
        " next();" +
        "});" +
        "},true);})();";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("p", MODE_PRIVATE);
        web = new WebView(this);
        setContentView(web);

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
                    java.util.ArrayList<String> acc = new java.util.ArrayList<>();
                    for (String a : p.getAcceptTypes()) {
                        if (a == null) continue;
                        for (String t : a.split(",")) {
                            t = t.trim();
                            if (t.contains("/")) acc.add(t);
                        }
                    }
                    if (acc.isEmpty()) acc.add("*/*");
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    if (acc.size() == 1) {
                        i.setType(acc.get(0));
                    } else {
                        i.setType("*/*");
                        i.putExtra(Intent.EXTRA_MIME_TYPES, acc.toArray(new String[0]));
                    }
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(Intent.createChooser(i, "Выберите файлы"), REQ_FILES);
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

        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILES) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                r = WebChromeClient.FileChooserParams.parseResult(res, data);
            }
            if (filePathCallback != null) filePathCallback.onReceiveValue(r);
            filePathCallback = null;
        } else if (req == REQ_TREE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                prefs.edit().putString("tree", tree.toString()).apply();
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

    // ---------- сохранение ----------
    private void toast(final String t) {
        runOnUiThread(() -> Toast.makeText(this, t, Toast.LENGTH_SHORT).show());
    }

    private boolean writeToTree(Pending p) {
        String ts = prefs.getString("tree", null);
        if (ts == null) return false;
        try {
            Uri tree = Uri.parse(ts);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
            Uri doc = DocumentsContract.createDocument(getContentResolver(), parent, p.mime, p.name);
            if (doc == null) return false;
            try (OutputStream os = getContentResolver().openOutputStream(doc);
                 java.io.FileInputStream in = new java.io.FileInputStream(p.tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            p.tmp.delete();
            return true;
        } catch (Exception e) {
            // доступ к папке мог пропасть — попросим выбрать заново
            prefs.edit().remove("tree").apply();
            return false;
        }
    }

    private void flushWaiting() {
        int ok = 0, total = waiting.size();
        while (!waiting.isEmpty()) {
            Pending p = waiting.peek();
            if (writeToTree(p)) { waiting.poll(); ok++; }
            else { askTree(); return; }
        }
        toast("Сохранено файлов: " + ok + " из " + total);
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

    private class Bridge {
        @JavascriptInterface
        public void begin(String id, String name, String mime) {
            try {
                Pending p = new Pending();
                p.name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                p.mime = mime;
                p.tmp = java.io.File.createTempFile("dl", ".bin", getCacheDir());
                meta.put(id, p);
                open.put(id, new java.io.FileOutputStream(p.tmp));
            } catch (Exception e) { toast("Ошибка: " + e.getMessage()); }
        }

        @JavascriptInterface
        public void chunk(String id, String b64) {
            try {
                java.io.FileOutputStream o = open.get(id);
                if (o != null) o.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) { toast("Ошибка записи: " + e.getMessage()); }
        }

        @JavascriptInterface
        public void end(String id) {
            try {
                java.io.FileOutputStream o = open.remove(id);
                if (o != null) o.close();
            } catch (Exception ignored) {}
            Pending p = meta.remove(id);
            if (p == null) return;
            if (!waiting.isEmpty()) { waiting.add(p); return; }
            if (writeToTree(p)) { toast("Сохранено: " + p.name); }
            else { waiting.add(p); askTree(); }
        }
    }
}
