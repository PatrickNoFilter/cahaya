package com.patrisius.cahaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.patrisius.cahaya.CahayaApp;
import com.patrisius.cahaya.R;
import com.patrisius.cahaya.addon.AddonManager;
import com.patrisius.cahaya.qr.QrCode;
import com.patrisius.cahaya.web.AddonWebServer;
import com.patrisius.cahaya.addon.CatalogItem;
import com.patrisius.cahaya.plugin.Plugin;
import com.patrisius.cahaya.plugin.PluginManager;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v0.2 bootstrap: Nuvio-style left sidebar navigation (Home, Search,
 * Library, Addons, Settings) with a content pane on the right. All views
 * are framework widgets (no Leanback dependency); D-pad navigates with
 * left/right between sidebar and content.
 */
public class MainActivity extends Activity {

    private static final String[] MENU = {"Home", "Search", "Library", "Addons", "Plugins", "Settings"};
    private static final String[] ICONS = {"🏠", "🔍", "📚", "🧩", "🔌", "⚙️"};

    private final ExecutorService imgIo = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private LruCache<String, Bitmap> memCache;

    private LinearLayout sidebar;
    private FrameLayout content;
    private ImageView brandLogo;
    private TextView infoBar;
    private final List<LinearLayout> menuRows = new ArrayList<>();
    private final List<TextView> menuLabels = new ArrayList<>();
    private int selected = 0;
    private boolean sidebarExpanded = true;

    private void updateInfo(String text) {
        if (infoBar != null) {
            infoBar.setText(text);
        }
    }

    private LinearLayout homeRows;
    private TextView homeStatus;
    private final List<CatalogItem> allItems = new ArrayList<>();
    private boolean homeLoaded = false;

    private AddonManager mgr;
    private PluginManager plugins;
    private AddonWebServer webServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#14161A"));

        int cacheBytes = (int) (Runtime.getRuntime().maxMemory() / 8);
        memCache = new LruCache<String, Bitmap>(cacheBytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);

        sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(Color.parseColor("#1B1E23"));
        int sideW = dp(280);
        root.addView(sidebar, new LinearLayout.LayoutParams(
                sideW, ViewGroup.LayoutParams.MATCH_PARENT));

        brandLogo = new ImageView(this);
        brandLogo.setImageResource(R.drawable.app_banner);
        brandLogo.setAdjustViewBounds(true);
        brandLogo.setScaleType(ImageView.ScaleType.FIT_START);
        brandLogo.setPadding(dp(16), dp(20), dp(16), dp(12));
        sidebar.addView(brandLogo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < MENU.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setFocusable(true);
            row.setClickable(true);

            TextView icon = new TextView(this);
            icon.setText(ICONS[i]);
            icon.setTextSize(24);
            row.addView(icon, new LinearLayout.LayoutParams(dp(40),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView label = new TextView(this);
            label.setText(MENU[i]);
            label.setTextSize(17);
            label.setSingleLine(true);
            row.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setOnClickListener(v -> selectMenu(idx, true));
            row.setOnFocusChangeListener((v, hasFocus) -> paintMenuItem(idx, hasFocus));
            sidebar.addView(row);
            menuRows.add(row);
            menuLabels.add(label);
        }

        // Version label at bottom of sidebar
        TextView versionLabel = new TextView(this);
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            versionLabel.setText("v" + ver);
        } catch (Exception e) {
            versionLabel.setText("v?");
        }
        versionLabel.setTextSize(12);
        versionLabel.setTextColor(Color.parseColor("#888888"));
        versionLabel.setPadding(dp(16), dp(8), dp(16), dp(16));
        versionLabel.setGravity(android.view.Gravity.BOTTOM);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f);
        vlp.gravity = android.view.Gravity.BOTTOM;
        sidebar.addView(versionLabel, vlp);

        LinearLayout contentCol = new LinearLayout(this);
        contentCol.setOrientation(LinearLayout.VERTICAL);
        root.addView(contentCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        infoBar = new TextView(this);
        infoBar.setTextSize(20);
        infoBar.setSingleLine(true);
        infoBar.setEllipsize(android.text.TextUtils.TruncateAt.END);
        infoBar.setTextColor(Color.WHITE);
        infoBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        infoBar.setPadding(dp(28), 0, dp(28), 0);
        contentCol.addView(infoBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        content = new FrameLayout(this);
        content.setPadding(dp(28), dp(8), dp(28), dp(28));
        contentCol.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(
                (oldFocus, newFocus) -> {
                    if (newFocus == null) {
                        return;
                    }
                    boolean inSidebar = isUnder(newFocus, sidebar);
                    setSidebarExpanded(inSidebar);
                    if (inSidebar) {
                        updateInfo(MENU[selected]);
                    }
                });

        mgr = ((CahayaApp) getApplication()).addons();
        plugins = new PluginManager(this, mgr);
        mgr.ensureDefaults(new AddonManager.Callback<Boolean>() {
            @Override
            public void onResult(Boolean fresh) {
                selectMenu(0, false);
                menuRows.get(0).requestFocus();
            }

            @Override
            public void onError(Exception e) {
                selectMenu(0, false);
                if (homeStatus != null) {
                    homeStatus.setText("Offline — couldn't reach addon: " + e.getMessage());
                }
            }
        });
    }

    private void paintMenuItem(int idx, boolean focused) {
        LinearLayout row = menuRows.get(idx);
        TextView label = menuLabels.get(idx);
        boolean isSel = idx == selected;
        if (isSel) {
            row.setBackgroundColor(focused
                    ? Color.parseColor("#35617A") : Color.parseColor("#2A4A5A"));
            label.setTextColor(Color.WHITE);
        } else {
            row.setBackgroundColor(focused
                    ? Color.parseColor("#26292F") : Color.TRANSPARENT);
            label.setTextColor(focused ? Color.WHITE : Color.parseColor("#9AA0A6"));
        }
        row.setScaleX(focused ? 1.04f : 1.0f);
        row.setScaleY(focused ? 1.04f : 1.0f);
    }

    private void refreshMenuPaint() {
        for (int i = 0; i < menuRows.size(); i++) {
            paintMenuItem(i, menuRows.get(i).isFocused());
        }
    }

    /**
     * Collapses the sidebar to an icon rail when focus moves into the
     * content pane, and expands it (icons + labels) when focus returns.
     */
    private void setSidebarExpanded(boolean expanded) {
        if (sidebarExpanded == expanded) {
            return;
        }
        sidebarExpanded = expanded;
        int from = sidebar.getWidth();
        int to = dp(expanded ? 280 : 76);
        ValueAnimator anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(220);
        anim.addUpdateListener(a -> {
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) sidebar.getLayoutParams();
            lp.width = (int) a.getAnimatedValue();
            sidebar.setLayoutParams(lp);
        });
        anim.start();
        brandLogo.setVisibility(expanded ? View.VISIBLE : View.GONE);
        for (TextView label : menuLabels) {
            label.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        // Nuvio-style: center the icons in the collapsed rail
        int sidePad = expanded ? dp(16) : 0;
        for (LinearLayout row : menuRows) {
            row.setGravity(expanded
                    ? android.view.Gravity.CENTER_VERTICAL
                    : android.view.Gravity.CENTER);
            row.setPadding(sidePad, dp(12), sidePad, dp(12));
        }
    }

    private static boolean isUnder(View v, ViewGroup parent) {
        android.view.ViewParent p = v.getParent();
        while (p != null) {
            if (p == parent) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    private void selectMenu(int idx, boolean moveFocus) {
        selected = idx;
        refreshMenuPaint();
        updateInfo(MENU[idx]);
        content.removeAllViews();
        View screen;
        switch (idx) {
            case 1: screen = makeSearchScreen(); break;
            case 2: screen = makeLibraryScreen(); break;
            case 3: screen = makeAddonsScreen(); break;
            case 4: screen = makePluginsScreen(); break;
            case 5: screen = makeSettingsScreen(); break;
            default: screen = makeHomeScreen(); break;
        }
        content.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (moveFocus) {
            View f = screen.findFocus();
            if (f == null) {
                f = firstFocusable(screen);
            }
            if (f != null) {
                f.requestFocus();
            }
        }
    }

    private View firstFocusable(View v) {
        if (v.isFocusable()) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstFocusable(g.getChildAt(i));
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    // ---------------- Home ----------------

    private View makeHomeScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        homeStatus = new TextView(this);
        homeStatus.setText("Loading catalogs…");
        homeStatus.setTextColor(Color.GRAY);
        col.addView(homeStatus);

        homeRows = new LinearLayout(this);
        homeRows.setOrientation(LinearLayout.VERTICAL);
        col.addView(homeRows);

        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!homeLoaded) {
            homeLoaded = true;
            loadRow("movie", "top", "Popular Movies");
            loadRow("series", "top", "Popular Series");
        } else if (!allItems.isEmpty()) {
            homeStatus.setVisibility(View.GONE);
        }
        return scroll;
    }

    private void loadRow(String type, String id, String header) {
        mgr.catalog(AddonManager.DEFAULT_ADDON, type, id,
                new AddonManager.Callback<List<CatalogItem>>() {
                    @Override
                    public void onResult(List<CatalogItem> items) {
                        allItems.addAll(items);
                        if (homeStatus != null) {
                            homeStatus.setVisibility(View.GONE);
                        }
                        if (homeRows != null) {
                            homeRows.addView(makeRow(header, items));
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        if (homeStatus != null) {
                            homeStatus.setText("Couldn't load " + header + ": " + e.getMessage());
                        }
                    }
                });
    }

    private View makeRow(String header, List<CatalogItem> items) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(6), 0, dp(14));

        TextView h = new TextView(this);
        h.setText(header);
        h.setTextSize(24);
        h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(8));
        col.addView(h);

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (CatalogItem it : items) {
            strip.addView(makeCard(it));
        }
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(strip);
        col.addView(hsv);
        return col;
    }

    /** Nuvio-style: show a QR + LAN address so the phone can push addon URLs. */
    private void showPhoneInstallDialog() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        webServer = new AddonWebServer((url, cb) -> mgr.installAddon(url, cb));
        final int port;
        try {
            port = webServer.start();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start web server: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        String ip = getLanIp();
        final String addr = "http://" + (ip != null ? ip : "this-tv") + ":" + port;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);

        TextView t1 = new TextView(this);
        t1.setText("On your phone, open:");
        t1.setTextColor(Color.GRAY);
        col.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(addr);
        t2.setTextSize(20);
        t2.setTextColor(Color.WHITE);
        t2.setPadding(0, dp(4), 0, dp(12));
        col.addView(t2);

        try {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(qrBitmap(addr, dp(280)));
            col.addView(qr);
        } catch (Exception e) {
            TextView err = new TextView(this);
            err.setText("QR unavailable: " + e.getMessage());
            err.setTextColor(Color.RED);
            col.addView(err);
        }

        TextView t3 = new TextView(this);
        t3.setText("Paste the addon URL on the phone page.\n"
                + "The server stops when this dialog closes.");
        t3.setTextColor(Color.GRAY);
        t3.setGravity(android.view.Gravity.CENTER);
        t3.setPadding(0, dp(12), 0, 0);
        col.addView(t3);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("\uD83D\uDCF1 Add via phone")
                .setView(col)
                .setNegativeButton("Close", null)
                .create();
        dlg.setOnDismissListener(d -> {
            if (webServer != null) {
                webServer.stop();
                webServer = null;
            }
        });
        dlg.show();
        updateInfo("Add via phone: " + addr);
    }

    private String getLanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Bitmap qrBitmap(String text, int px) {
        boolean[][] m = QrCode.encode(text);
        int n = m.length, quiet = 2, total = n + quiet * 2;
        Bitmap small = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < total; y++) {
            for (int x = 0; x < total; x++) {
                int mx = x - quiet, my = y - quiet;
                boolean dark = mx >= 0 && my >= 0 && mx < n && my < n && m[my][mx];
                small.setPixel(x, y, dark ? Color.BLACK : Color.WHITE);
            }
        }
        return Bitmap.createScaledBitmap(small, px, px, false);
    }

    /** TV-friendly dialog: paste any Stremio addon manifest URL to install it. */
    private void showInstallUrlDialog() {        final EditText input = new EditText(this);
        input.setHint("https://…");
        input.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        int pad = dp(16);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Install addon from URL")
                .setMessage("Paste the addon's base URL (its manifest.json is fetched to verify it).")
                .setView(input)
                .setPositiveButton("Install", (d, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) {
                        return;
                    }
                    Toast.makeText(MainActivity.this,
                            "Installing…", Toast.LENGTH_SHORT).show();
                    mgr.installAddon(url, new AddonManager.Callback<String>() {
                        @Override
                        public void onResult(String addonName) {
                            Toast.makeText(MainActivity.this,
                                    "Installed: " + addonName, Toast.LENGTH_SHORT).show();
                            selectMenu(3, false);
                        }

                        @Override
                        public void onError(Exception e) {
                            Toast.makeText(MainActivity.this,
                                    "Install failed: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View makeCard(final CatalogItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int w = dp(140);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(12), 0);
        card.setLayoutParams(lp);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));

        ImageView poster = new ImageView(this);
        poster.setLayoutParams(new LinearLayout.LayoutParams(w - dp(8), dp(200)));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(Color.parseColor("#23262B"));
        card.addView(poster);

        final TextView name = new TextView(this);
        name.setText(item.name);
        name.setTextColor(Color.LTGRAY);
        name.setMaxLines(2);
        name.setPadding(0, dp(6), 0, 0);
        card.addView(name);

        card.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.08f : 1.0f);
            v.setScaleY(hasFocus ? 1.08f : 1.0f);
            v.setBackgroundColor(hasFocus ? Color.parseColor("#2A4A5A") : Color.TRANSPARENT);
            name.setTextColor(hasFocus ? Color.WHITE : Color.LTGRAY);
            if (hasFocus) {
                updateInfo(item.name);
            }
        });
        card.setOnClickListener(v -> {
            Intent i = new Intent(MainActivity.this, DetailsActivity.class);
            i.putExtra("id", item.id);
            i.putExtra("type", item.type);
            startActivity(i);
        });

        loadPoster(item.poster, poster);
        return card;
    }

    // ---------------- Search ----------------

    private View makeSearchScreen() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView h = new TextView(this);
        h.setText("Search");
        h.setTextSize(28);
        h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(12));
        col.addView(h);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        final EditText input = new EditText(this);
        input.setHint("Search loaded titles…");
        input.setHintTextColor(Color.GRAY);
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.setMargins(0, 0, dp(12), 0);
        bar.addView(input, ilp);

        Button go = new Button(this);
        go.setText("Search");
        go.setFocusable(true);
        bar.addView(go);
        col.addView(bar);

        TextView note = new TextView(this);
        note.setText("Searches titles already loaded on Home. Full cross-addon search in v0.3.");
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(8), 0, dp(8));
        col.addView(note);

        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Runnable doSearch = () -> {
            String q = input.getText().toString().trim().toLowerCase();
            results.removeAllViews();
            if (q.isEmpty()) {
                return;
            }
            List<CatalogItem> hits = new ArrayList<>();
            for (CatalogItem it : allItems) {
                if (it.name.toLowerCase().contains(q)) {
                    hits.add(it);
                }
            }
            if (hits.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("No matches for \"" + q + "\" in loaded titles.");
                empty.setTextColor(Color.GRAY);
                results.addView(empty);
            } else {
                results.addView(makeRow(hits.size() + " result(s)", hits));
                View f = firstFocusable(results);
                if (f != null) {
                    f.requestFocus();
                }
            }
        };
        go.setOnClickListener(v -> doSearch.run());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch.run();
                return true;
            }
            return false;
        });
        return col;
    }

    // ---------------- Library ----------------

    private View makeLibraryScreen() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(60), 0, 0);

        TextView h = new TextView(this);
        h.setText("Library");
        h.setTextSize(28);
        h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(12));
        col.addView(h);

        TextView empty = new TextView(this);
        empty.setText("Your library is empty.\n\nSave titles here from the details screen (v0.3).");
        empty.setTextColor(Color.GRAY);
        empty.setTextSize(16);
        col.addView(empty);
        return col;
    }

    // ---------------- Addons ----------------

    private View makeAddonsScreen() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView h = new TextView(this);
        h.setText("Addons");
        h.setTextSize(28);
        h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(12));
        col.addView(h);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        for (String url : mgr.addonUrls()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackgroundColor(Color.parseColor("#1E2126"));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(10));
            row.setLayoutParams(rlp);
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnFocusChangeListener((v, hasFocus) ->
                    v.setBackgroundColor(hasFocus
                            ? Color.parseColor("#2A4A5A") : Color.parseColor("#1E2126")));

            final TextView name = new TextView(this);
            name.setText("Loading…");
            name.setTextSize(18);
            name.setTextColor(Color.WHITE);
            row.addView(name);

            TextView sub = new TextView(this);
            sub.setText(url);
            sub.setTextColor(Color.GRAY);
            row.addView(sub);

            final String base = url;
            row.setOnClickListener(v -> Toast.makeText(MainActivity.this,
                    base + " — configure in v0.3", Toast.LENGTH_SHORT).show());
            list.addView(row);

            imgIo.execute(() -> {
                String label = base;
                try {
                    String body = AddonManager.fetchText(base + "/manifest.json");
                    JSONObject m = new JSONObject(body);
                    label = m.optString("name", base)
                            + "  ·  v" + m.optString("version", "?");
                } catch (Exception ignored) {
                    // keep the URL as label
                }
                final String done = label;
                main.post(() -> name.setText(done));
            });
        }

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.VERTICAL);
        urlRow.setPadding(dp(16), dp(12), dp(16), dp(12));
        urlRow.setBackgroundColor(Color.parseColor("#1E2126"));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        urlLp.setMargins(0, 0, 0, dp(10));
        urlRow.setLayoutParams(urlLp);
        urlRow.setFocusable(true);
        urlRow.setClickable(true);
        urlRow.setOnFocusChangeListener((v, hasFocus) ->
                v.setBackgroundColor(hasFocus
                        ? Color.parseColor("#2A4A5A") : Color.parseColor("#1E2126")));

        TextView urlName = new TextView(this);
        urlName.setText("＋ Install from URL");
        urlName.setTextSize(18);
        urlName.setTextColor(Color.parseColor("#4FC3F7"));
        urlRow.addView(urlName);

        TextView urlSub = new TextView(this);
        urlSub.setText("Paste your configured addon URL, e.g. MediaFusion");
        urlSub.setTextColor(Color.GRAY);
        urlRow.addView(urlSub);

        urlRow.setOnClickListener(v -> showInstallUrlDialog());
        col.addView(urlRow);

        LinearLayout phoneRow = new LinearLayout(this);
        phoneRow.setOrientation(LinearLayout.VERTICAL);
        phoneRow.setPadding(dp(16), dp(12), dp(16), dp(12));
        phoneRow.setBackgroundColor(Color.parseColor("#1E2126"));
        LinearLayout.LayoutParams phoneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        phoneLp.setMargins(0, 0, 0, dp(10));
        phoneRow.setLayoutParams(phoneLp);
        phoneRow.setFocusable(true);
        phoneRow.setClickable(true);
        phoneRow.setOnFocusChangeListener((v, hasFocus) ->
                v.setBackgroundColor(hasFocus
                        ? Color.parseColor("#2A4A5A") : Color.parseColor("#1E2126")));

        TextView phoneName = new TextView(this);
        phoneName.setText("\uD83D\uDCF1 Add via phone (QR)");
        phoneName.setTextSize(18);
        phoneName.setTextColor(Color.parseColor("#4FC3F7"));
        phoneRow.addView(phoneName);

        TextView phoneSub = new TextView(this);
        phoneSub.setText("Scan the code with your phone, paste the URL there");
        phoneSub.setTextColor(Color.GRAY);
        phoneRow.addView(phoneSub);

        phoneRow.setOnClickListener(v -> showPhoneInstallDialog());
        col.addView(phoneRow);

        TextView sh = new TextView(this);
        sh.setText("Suggested");
        sh.setTextSize(22);
        sh.setTextColor(Color.WHITE);
        sh.setPadding(0, dp(20), 0, dp(8));
        col.addView(sh);

        final java.util.Set<String> installed = mgr.addonUrls();
        for (final String[] s : AddonManager.SUGGESTED_ADDONS) {
            if (installed.contains(s[1])) {
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackgroundColor(Color.parseColor("#1E2126"));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(10));
            row.setLayoutParams(rlp);
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnFocusChangeListener((v, hasFocus) ->
                    v.setBackgroundColor(hasFocus
                            ? Color.parseColor("#2A4A5A") : Color.parseColor("#1E2126")));

            TextView name = new TextView(this);
            name.setText("＋ " + s[0]);
            name.setTextSize(18);
            name.setTextColor(Color.parseColor("#4FC3F7"));
            row.addView(name);

            TextView sub = new TextView(this);
            sub.setText(s[2]);
            sub.setTextColor(Color.GRAY);
            row.addView(sub);

            row.setOnClickListener(v -> {
                Toast.makeText(MainActivity.this,
                        "Installing " + s[0] + "…", Toast.LENGTH_SHORT).show();
                mgr.installAddon(s[1], new AddonManager.Callback<String>() {
                    @Override
                    public void onResult(String addonName) {
                        Toast.makeText(MainActivity.this,
                                "Installed: " + addonName, Toast.LENGTH_SHORT).show();
                        selectMenu(3, false);
                    }

                    @Override
                    public void onError(Exception e) {
                        Toast.makeText(MainActivity.this,
                                "Install failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            });
            col.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col);
        return sv;
    }

    // ---------------- Plugins ----------------

    private View makePluginsScreen() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView h = new TextView(this);
        h.setText("Plugins");
        h.setTextSize(28);
        h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(12));
        col.addView(h);

        TextView note = new TextView(this);
        note.setText("Native plugins extend Cahaya with extra streams and subtitles.\n"
                + "Click toggles a plugin · long-press removes it.");
        note.setTextColor(Color.GRAY);
        note.setPadding(0, 0, 0, dp(12));
        col.addView(note);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        refreshPluginList(list);

        TextView install = new TextView(this);
        install.setText("＋  Install from URL");
        install.setTextSize(18);
        install.setTextColor(Color.parseColor("#4FC3F7"));
        install.setPadding(dp(16), dp(16), dp(16), dp(16));
        install.setFocusable(true);
        install.setClickable(true);
        install.setOnFocusChangeListener((v, hasFocus) ->
                v.setBackgroundColor(hasFocus
                        ? Color.parseColor("#2A4A5A") : Color.TRANSPARENT));
        install.setOnClickListener(v -> showInstallPluginDialog(list));
        col.addView(install);
        return col;
    }

    private void refreshPluginList(LinearLayout list) {
        list.removeAllViews();
        List<Plugin> all = plugins.plugins();
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No plugins installed yet.");
            empty.setTextColor(Color.GRAY);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            list.addView(empty);
            return;
        }
        for (final Plugin p : all) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackgroundColor(Color.parseColor("#1E2126"));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(10));
            row.setLayoutParams(rlp);
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnFocusChangeListener((v, hasFocus) ->
                    v.setBackgroundColor(hasFocus
                            ? Color.parseColor("#2A4A5A") : Color.parseColor("#1E2126")));

            final TextView name = new TextView(this);
            name.setTextSize(18);
            row.addView(name);

            TextView sub = new TextView(this);
            sub.setText(p.baseUrl);
            sub.setTextColor(Color.GRAY);
            row.addView(sub);

            final Runnable paint = () -> {
                name.setText((p.enabled ? "● " : "○ ") + p.name + "  ·  v" + p.version);
                name.setTextColor(p.enabled ? Color.WHITE : Color.GRAY);
            };
            paint.run();
            row.setTag(p);

            row.setOnClickListener(v -> {
                Plugin pl = (Plugin) v.getTag();
                plugins.setEnabled(pl.baseUrl, !pl.enabled);
                pl.enabled = !pl.enabled;
                paint.run();
                Toast.makeText(MainActivity.this,
                        pl.name + (pl.enabled ? " enabled" : " disabled"),
                        Toast.LENGTH_SHORT).show();
            });
            row.setOnLongClickListener(v -> {
                Plugin pl = (Plugin) v.getTag();
                plugins.remove(pl.baseUrl);
                refreshPluginList(list);
                Toast.makeText(MainActivity.this,
                        pl.name + " removed", Toast.LENGTH_SHORT).show();
                return true;
            });
            list.addView(row);
        }
    }

    private void showInstallPluginDialog(final LinearLayout list) {
        final EditText input = new EditText(this);
        input.setHint("https://example.com/my-plugin");
        input.setHintTextColor(Color.GRAY);
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Install plugin")
                .setMessage("Paste the plugin base URL (serves plugin.json):")
                .setView(input)
                .setPositiveButton("Install", (d, which) -> {
                    final String baseUrl = input.getText().toString().trim();
                    if (baseUrl.isEmpty()) {
                        return;
                    }
                    Toast.makeText(MainActivity.this,
                            "Fetching plugin manifest…", Toast.LENGTH_SHORT).show();
                    plugins.install(baseUrl, new PluginManager.Callback<Plugin>() {
                        @Override
                        public void onResult(Plugin p) {
                            refreshPluginList(list);
                            Toast.makeText(MainActivity.this,
                                    "Installed: " + p.name, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(Exception e) {
                            Toast.makeText(MainActivity.this,
                                    "Install failed: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------- Settings ----------------

    private View makeSettingsScreen() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView h = new TextView(this);
        h.setText("Settings");
        h.setTextSize(28);
        h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(12));
        col.addView(h);

        addSettingRow(col, "Version", "0.7.0 (build 8)");
        addSettingRow(col, "Installed addons", String.valueOf(mgr.addonUrls().size()));
        addSettingRow(col, "Installed plugins", String.valueOf(plugins.plugins().size()));
        addSettingRow(col, "Protocol", "Stremio addon protocol (manifest.json)");
        addSettingRow(col, "Player", "System player — coming in v0.3");
        return col;
    }

    private void addSettingRow(LinearLayout col, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.GRAY);
        l.setTextSize(16);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(16);
        row.addView(v);
        col.addView(row);
    }

    // ---------------- posters ----------------

    private void loadPoster(String url, ImageView into) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Bitmap cached = memCache.get(url);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        into.setTag(url);
        imgIo.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Cahaya/0.7.0 (Android TV)");
                InputStream in = c.getInputStream();
                final Bitmap bmp = BitmapFactory.decodeStream(in);
                in.close();
                if (bmp != null) {
                    memCache.put(url, bmp);
                }
                main.post(() -> {
                    if (url.equals(into.getTag())) {
                        into.setImageBitmap(bmp);
                    }
                });
            } catch (Exception ignored) {
                // leave placeholder
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        imgIo.shutdownNow();
        super.onDestroy();
    }
}
