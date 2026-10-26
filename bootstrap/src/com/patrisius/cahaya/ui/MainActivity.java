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
import com.patrisius.cahaya.addon.StreamItem;
import com.patrisius.cahaya.jsrunner.JsScraperRunner;
import com.patrisius.cahaya.plugin.Plugin;
import com.patrisius.cahaya.plugin.PluginManager;
import com.patrisius.cahaya.scraper.FibWatchScraper;
import com.patrisius.cahaya.scraper.Scraper;
import com.patrisius.cahaya.torrent.TorrentStreamer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
    // v0.10.7: infoBar REMOVED — it showed the focused title/menu name in
    // a 64dp bar above the content, duplicating the adaptive banner which
    // already shows the title. Field + updateInfo() kept (null-safe no-op)
    // so existing call sites need no changes; content now fills the full
    // height and the banner sits at the very top.
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
    // v0.10.12: Home screen cache. makeHomeScreen() used to build a NEW
    // empty homeRows on every Home click, while catalog rows only load
    // once (homeLoaded guard) — so clicking Home after the first load
    // discarded all rows and showed just the hero. Reusing the built
    // screen keeps the rows, the scroll position and the hero intact.
    private View cachedHomeRoot = null;
    private final List<CatalogItem> allItems = new ArrayList<>();
    private boolean homeLoaded = false;

    private AddonManager mgr;
    private PluginManager plugins;
    private AddonWebServer webServer;

    private TextView accountLabel;

    private void refreshAccountLabel() {
        if (accountLabel == null) return;
        if (GoogleAccount.isSignedIn(this)) {
            String name = GoogleAccount.displayName(this);
            accountLabel.setText("👤 " + (name != null ? name : "Signed in"));
        } else {
            accountLabel.setText("👤 Not signed in");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccountLabel();
        // Refresh Continue Watching when returning from player
        if (homeRows != null) {
            main.post(this::refreshContinueWatching);
        }
    }

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
        sidebar.setBackgroundColor(Color.TRANSPARENT); // v0.10.28: blurred photo shows through the side panel too
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

        // Google account, shown under the logo (v0.10.8 device-code sign-in).
        // Sign in/out itself lives in Settings; this is just the status.
        accountLabel = new TextView(this);
        accountLabel.setText("👤 Not signed in");
        accountLabel.setTextSize(13);
        accountLabel.setTextColor(Color.parseColor("#9AA0A6"));
        accountLabel.setPadding(dp(16), 0, dp(16), dp(12));
        accountLabel.setSingleLine(true);
        accountLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sidebar.addView(accountLabel, new LinearLayout.LayoutParams(
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

        // No top name bar anymore (v0.10.7): the adaptive banner is the
        // title now, so content starts at the very top and gains 64dp.
        content = new FrameLayout(this);
        content.setPadding(dp(28), dp(8), dp(28), dp(28));
        contentCol.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Blurred-background layer under everything (v0.10.27):
        // a plain ImageView first in a FrameLayout, root on top of it.
        FrameLayout frame = new FrameLayout(this);
        appBgView = new ImageView(this);
        appBgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        appBgView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(appBgView);
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
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
                menuRows.get(0).requestFocus();
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
        // v1.0.0: the selected tab shows NO highlight while focus is
        // in the body — transparent background and grey label, just
        // like every other tab. Only the actually focused row gets a
        // background (selected: #35617A, other: #26292F) and white text.
        if (focused) {
            row.setBackgroundColor(Color.parseColor(
                    isSel ? "#35617A" : "#26292F"));
            label.setTextColor(Color.WHITE);
        } else {
            row.setBackgroundColor(Color.TRANSPARENT);
            label.setTextColor(Color.parseColor("#9AA0A6"));
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
        // v1.0.1: folded rail 76 -> 56dp (icons are 40dp, still fit
        // with margin) and body left padding 28 -> 16dp while folded,
        // so the body sits closer to the rail and looks balanced.
        int to = dp(expanded ? 280 : 56);
        if (content != null) {
            content.setPadding(dp(expanded ? 28 : 16), dp(8), dp(28), dp(28));
        }
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
        if (accountLabel != null) {
            accountLabel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
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

    /** v0.10.29/0.10.30: pressing Left from the content pane must land on
     *  the menu row of the tab currently displayed, not on whatever
     *  sidebar row Android's focus search finds nearest — from a low
     *  row (e.g. Popular Movies) that was Settings at the bottom.
     *  v0.10.29 grabbed EVERY Left press, which broke stepping back
     *  left through the cards in a row. v0.10.30 only overrides when
     *  focus search finds no content view to the left (or a sidebar
     *  view): a card with a neighbour to its left keeps normal
     *  navigation. EditText focus is left alone so Left still moves
     *  the text cursor in the Search box and in dialogs. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // v1.1.3: Back from any non-Home tab returns to Home first; a
        // second Back, now on Home, falls through to the default path
        // and exits. Dialogs and the on-screen keyboard consume Back
        // in their own windows before it reaches here, so they still
        // dismiss first.
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK
                && selected != 0) {
            selectMenu(0, true);
            return true;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            View focus = getCurrentFocus();
            if (focus != null && !(focus instanceof android.widget.EditText)
                    && !isUnder(focus, sidebar)
                    && selected >= 0 && selected < menuRows.size()) {
                View next = android.view.FocusFinder.getInstance()
                        .findNextFocus((ViewGroup) getWindow().getDecorView(),
                                focus, View.FOCUS_LEFT);
                if (next == null || isUnder(next, sidebar)) {
                    menuRows.get(selected).requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
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
            default:
                // v0.10.12: reuse the built Home screen (see cachedHomeRoot
                // note). Rebuilding it blanked all catalog rows on every
                // Home click, because rows only load once (homeLoaded).
                if (cachedHomeRoot != null) {
                    screen = cachedHomeRoot;
                } else {
                    screen = makeHomeScreen();
                    cachedHomeRoot = screen;
                }
                break;
        }
        content.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (idx == 0) {
            // Refresh Playing History on every return to Home (new plays
            // since the last visit appear at the top). Idempotent: it
            // removes and re-adds its own row only.
            main.post(this::refreshContinueWatching);
        }
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
        // Home root: pinned hero slot on top, scrollable rows below it.
        LinearLayout homeRoot = new LinearLayout(this);
        homeRoot.setOrientation(LinearLayout.VERTICAL);

        heroSlot = new LinearLayout(this);
        heroSlot.setOrientation(LinearLayout.VERTICAL);
        homeRoot.addView(heroSlot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Re-attach the persistent hero if Home is rebuilt (e.g. returning
        // from another menu): it was detached with the old content pane.
        if (heroView != null) {
            if (heroView.getParent() instanceof ViewGroup) {
                ((ViewGroup) heroView.getParent()).removeView(heroView);
            }
            heroSlot.addView(heroView);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        homeStatus = new TextView(this);
        homeStatus.setText("Loading trending…");
        homeStatus.setTextColor(Color.GRAY);
        col.addView(homeStatus);

        homeRows = new LinearLayout(this);
        homeRows.setOrientation(LinearLayout.VERTICAL);
        col.addView(homeRows);

        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        homeRoot.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (!homeLoaded) {
            homeLoaded = true;
            // v1.2.0: the old catalog rows are gone at the user's word
            // (Popular Movies / Popular Series + the six Nuvio
            // by-language rows). Home is now Playing History (inserted
            // at index 0 by refreshContinueWatching) plus our own
            // merged trending rows, crawled from the sources validated
            // 2026-10-26 (Tudum, Douban, K-Drama Crush, TMDB addon,
            // IMDb charts, AniList). See TrendingEngine.
            loadTrendingRows();
        } else if (!allItems.isEmpty()) {
            homeStatus.setVisibility(View.GONE);
        }
        // Playing History is refreshed by selectMenu() on every return
        // to Home (and by onResume), not here, to avoid a double refresh
        // on the first build.
        return homeRoot;
    }

    /** "Playing History" row (v0.10.12, was "Lanjutkan Menonton"): the
     *  very first row on Home, above all catalog rows. Cards click to
     *  a stream picker (see resumeHistoryItem), not to a Details screen
     *  and not straight to playback: the user picks a stream first,
     *  then the player resumes at the recorded position. */
    private View continueRow = null;
    private void refreshContinueWatching() {
        if (homeRows == null) return;
        // Remove old row
        if (continueRow != null) {
            homeRows.removeView(continueRow);
            continueRow = null;
        }
        List<ContinueWatching.Item> items = ContinueWatching.load(this);
        if (items.isEmpty()) return;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(6), 0, dp(14));

        TextView h = new TextView(this);
        h.setText("Playing History");
        h.setTextSize(24);
        h.setTextColor(Color.WHITE);
        h.setPadding(0, 0, 0, dp(8));
        col.addView(h);

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (ContinueWatching.Item it : items) {
            strip.addView(makeContinueCard(it));
        }
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(strip);
        col.addView(hsv);

        continueRow = col;
        // Hero is pinned above the scroll area now, so Continue Watching
        // is simply the first scrollable row.
        homeRows.addView(col, 0);
    }

    private View makeContinueCard(ContinueWatching.Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int w = dp(200);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(12), 0);
        card.setLayoutParams(lp);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));

        // Thumbnail with progress overlay
        FrameLayout thumb = new FrameLayout(this);
        thumb.setLayoutParams(new LinearLayout.LayoutParams(w - dp(8), dp(112)));
        ImageView poster = new ImageView(this);
        poster.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(Color.parseColor("#23262B"));
        thumb.addView(poster);
        if (it.poster != null) loadPoster(it.poster, poster);

        // "Sisa X mnt" badge (top-right, like reference)
        TextView badge = new TextView(this);
        badge.setText(it.remainingText());
        badge.setTextSize(11);
        badge.setTextColor(Color.WHITE);
        badge.setBackgroundColor(Color.parseColor("#AA000000"));
        badge.setPadding(dp(6), dp(3), dp(6), dp(3));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        badgeLp.setMargins(0, dp(6), dp(6), 0);
        thumb.addView(badge, badgeLp);

        // Progress bar (bottom)
        View progressBg = new View(this);
        progressBg.setBackgroundColor(Color.parseColor("#44FFFFFF"));
        FrameLayout.LayoutParams pbBgLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        pbBgLp.gravity = android.view.Gravity.BOTTOM;
        thumb.addView(progressBg, pbBgLp);

        View progress = new View(this);
        progress.setBackgroundColor(Color.parseColor("#E8A33D"));
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                0, dp(4));
        pbLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        thumb.addView(progress, pbLp);
        // Set width after layout
        final double prog = it.progress();
        thumb.post(() -> {
            int fullW = thumb.getWidth();
            ViewGroup.LayoutParams p = progress.getLayoutParams();
            p.width = (int) (fullW * prog);
            progress.setLayoutParams(p);
        });

        card.addView(thumb);

        TextView name = new TextView(this);
        name.setText(it.title);
        name.setTextColor(Color.LTGRAY);
        name.setMaxLines(2);
        name.setPadding(0, dp(6), 0, 0);
        card.addView(name);

        card.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.05f : 1.0f);
            v.setScaleY(hasFocus ? 1.05f : 1.0f);
            name.setTextColor(hasFocus ? Color.WHITE : Color.LTGRAY);
            if (hasFocus) {
                updateInfo(it.title);
                updateHero(new CatalogItem(it.videoId, it.type, it.title, it.poster));
            }
        });
        card.setOnClickListener(v -> resumeHistoryItem(it));

        return card;
    }

    /** Playing History click (v0.10.22 pick-then-resume, v0.10.23
     *  all sources): collects streams from every addon/plugin AND
     *  the in-app scrapers (FibWatch + WebView JS 4khdhub/playimdb),
     *  exactly like Details, sorts most-seeders-first, and shows a
     *  stream picker — no automatic playback. Whichever stream the
     *  user picks, the player is launched with start_pos = saved
     *  position, so playback resumes at the recorded elapsed time.
     *  Torrent picks go through TorrentStreamer first, like Details,
     *  and this path DOES pass start_pos, so torrents resume too.
     *  Why a meta fetch first: the history item carries no moviedbId
     *  (JS scrapers need it), no year, and for series its title may
     *  include an episode suffix (FibWatch needs the series title).
     *  The meta call supplies name/year/moviedbId; on meta failure
     *  it falls back to the stored title with the addon/plugin
     *  sources still running. */
    private void resumeHistoryItem(ContinueWatching.Item it) {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage("Finding streams…")
                .setCancelable(false)
                .create();
        progress.show();

        // Series episode ids look like "tt1234567:1:2"; the meta call
        // needs the bare series id, and the scrapers need season/ep.
        String seriesId = it.videoId;
        int season = 0;
        int episode = 0;
        if ("series".equals(it.type) && it.videoId != null) {
            String[] parts = it.videoId.split(":");
            if (parts.length >= 1) {
                seriesId = parts[0];
            }
            if (parts.length >= 3) {
                try {
                    season = Integer.parseInt(parts[1]);
                    episode = Integer.parseInt(parts[2]);
                } catch (Exception ignored) {
                }
            }
        }
        final int fSeason = season;
        final int fEpisode = episode;
        mgr.meta(AddonManager.DEFAULT_ADDON, it.type, seriesId,
                new AddonManager.Callback<com.patrisius.cahaya.addon.MetaDetails>() {
                    @Override
                    public void onResult(com.patrisius.cahaya.addon.MetaDetails d) {
                        int year = 0;
                        if (d.releaseInfo != null) {
                            java.util.regex.Matcher ym = java.util.regex.Pattern
                                    .compile("\\b(19|20)\\d{2}\\b")
                                    .matcher(d.releaseInfo);
                            if (ym.find()) {
                                try {
                                    year = Integer.parseInt(ym.group());
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        collectHistoryStreams(it, d.name, year, d.moviedbId,
                                fSeason, fEpisode, progress);
                    }

                    @Override
                    public void onError(Exception e) {
                        collectHistoryStreams(it, it.title, 0, 0,
                                fSeason, fEpisode, progress);
                    }
                });
    }

    /** Stream collection for a history item, after the meta lookup.
     *  Addons + plugins + FibWatch + 2 JS providers, same as Details;
     *  a dead source just contributes nothing and never blocks the
     *  picker. */
    private void collectHistoryStreams(ContinueWatching.Item it,
            String scraperTitle, int scraperYear, int moviedbId,
            int season, int episode, AlertDialog progress) {
        final List<StreamItem> all = new ArrayList<>();
        final List<String> addonUrls = new ArrayList<>(mgr.addonUrls());
        final List<Plugin> enabledPlugins = new ArrayList<>();
        for (Plugin p : plugins.plugins()) {
            if (p.enabled) {
                enabledPlugins.add(p);
            }
        }
        final AtomicInteger pending = new AtomicInteger(
                addonUrls.size() + enabledPlugins.size() + 3);

        final Runnable done = () -> {
            if (pending.decrementAndGet() > 0) {
                return;
            }
            progress.dismiss();
            if (all.isEmpty()) {
                Toast.makeText(MainActivity.this,
                        "No streams found — install a streams addon or plugin",
                        Toast.LENGTH_LONG).show();
                return;
            }
            java.util.Collections.sort(all, (a, b) ->
                    Integer.compare(b.seeders, a.seeders));
            showHistoryPicker(it, all);
        };

        AddonManager.Callback<List<StreamItem>> collect =
                new AddonManager.Callback<List<StreamItem>>() {
                    @Override
                    public void onResult(List<StreamItem> items) {
                        synchronized (all) {
                            all.addAll(items);
                        }
                        main.post(done);
                    }

                    @Override
                    public void onError(Exception e) {
                        main.post(done);
                    }
                };
        for (String base : addonUrls) {
            mgr.streams(base, it.type, it.videoId, collect);
        }
        for (Plugin p : enabledPlugins) {
            plugins.pluginStreams(p.baseUrl, it.type, it.videoId,
                    new PluginManager.Callback<List<StreamItem>>() {
                        @Override
                        public void onResult(List<StreamItem> items) {
                            collect.onResult(items);
                        }

                        @Override
                        public void onError(Exception e) {
                            collect.onError(e);
                        }
                    });
        }
        new FibWatchScraper().getStreams(scraperTitle, scraperYear,
                it.type, season, episode,
                new Scraper.Callback() {
                    @Override
                    public void onResult(List<StreamItem> items) {
                        collect.onResult(items);
                    }

                    @Override
                    public void onError(Exception e) {
                        collect.onError(e);
                    }
                });
        String[] jsProviders = {
                JsScraperRunner.PROVIDER_4KHDHUB,
                JsScraperRunner.PROVIDER_PLAYIMDB,
        };
        for (String pid : jsProviders) {
            new JsScraperRunner(this, pid).run(moviedbId, it.type,
                    season, episode,
                    new JsScraperRunner.Callback() {
                        @Override
                        public void onResult(List<StreamItem> items) {
                            collect.onResult(items);
                        }

                        @Override
                        public void onError(String message) {
                            collect.onError(new Exception(message));
                        }
                    });
        }
    }

    /** Stream picker for a Playing History card. Same shape as
     *  Details' picker: title + host (torrents also show seeders and
     *  size). The picked stream — torrent or direct — is launched
     *  with start_pos = the recorded position, so the player resumes
     *  at the elapsed time the app recorded, whatever the source. */
    private void showHistoryPicker(ContinueWatching.Item it,
                                   List<StreamItem> streams) {
        String[] labels = new String[streams.size()];
        for (int i = 0; i < streams.size(); i++) {
            StreamItem s = streams.get(i);
            String sub = s.source;
            try {
                sub = new java.net.URL(s.source).getHost();
            } catch (Exception ignored) {
            }
            if (s.isTorrent()) {
                StringBuilder sb = new StringBuilder();
                if (s.seeders >= 0) {
                    sb.append("👤 ").append(s.seeders).append(" seeders");
                }
                if (s.size != null) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(s.size);
                }
                if (sb.length() > 0) sb.append(" · ");
                sb.append(sub);
                sub = sb.toString();
            }
            labels[i] = s.title + "\n" + sub;
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose stream")
                .setItems(labels, (d, which) -> {
                    StreamItem s = streams.get(which);
                    if (s.isTorrent()) {
                        playHistoryTorrent(it, s);
                        return;
                    }
                    Intent i = new Intent(MainActivity.this,
                            PlayerActivity.class);
                    i.putExtra("url", s.url);
                    i.putExtra("title", it.title);
                    i.putExtra("type", it.type);
                    i.putExtra("video_id", it.videoId);
                    if (it.poster != null) i.putExtra("poster", it.poster);
                    if (s.headers != null && !s.headers.isEmpty()) {
                        StringBuilder hb = new StringBuilder();
                        for (java.util.Map.Entry<String, String> e
                                : s.headers.entrySet()) {
                            if (hb.length() > 0) hb.append("\n");
                            hb.append(e.getKey()).append(": ")
                                    .append(e.getValue());
                        }
                        i.putExtra("headers", hb.toString());
                    }
                    // v1.1.0: a finished item restarts from 0, not
                    // from its end position (which would end instantly).
                    double resumePos = (it.duration > 0 && it.position >= it.duration - 30)
                            ? 0 : it.position;
                    if (resumePos > 0) {
                        i.putExtra("start_pos", resumePos);
                    }
                    startActivity(i);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void playHistoryTorrent(ContinueWatching.Item it, StreamItem s) {
        TextView msg = new TextView(this);
        msg.setPadding(48, 32, 48, 32);
        msg.setTextSize(18);
        msg.setText("Starting…");
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Torrent")
                .setView(msg)
                .setNegativeButton("Cancel", (d, w) -> TorrentStreamer.stopActive())
                .setCancelable(false)
                .create();
        dlg.show();

        new TorrentStreamer().start(this, s.url, s.fileIdx,
                new TorrentStreamer.Callback() {
                    @Override
                    public void onProgress(String stage, int percent, String detail) {
                        main.post(() -> {
                            if (dlg.isShowing()) {
                                msg.setText(stage + "\n" + percent + "%"
                                        + (detail.isEmpty() ? "" : "\n" + detail));
                            }
                        });
                    }

                    @Override
                    public void onReady(String httpUrl, String fileName) {
                        main.post(() -> {
                            if (dlg.isShowing()) dlg.dismiss();
                            Intent i = new Intent(MainActivity.this,
                                    PlayerActivity.class);
                            i.putExtra("url", httpUrl);
                            i.putExtra("title", it.title);
                            i.putExtra("type", it.type);
                            i.putExtra("video_id", it.videoId);
                            if (it.poster != null) i.putExtra("poster", it.poster);
                            double resumePos = (it.duration > 0 && it.position >= it.duration - 30)
                                    ? 0 : it.position;
                            if (resumePos > 0) {
                                i.putExtra("start_pos", resumePos);
                            }
                            startActivity(i);
                        });
                    }

                    @Override
                    public void onError(String errMsg) {
                        main.post(() -> {
                            if (dlg.isShowing()) dlg.dismiss();
                            Toast.makeText(MainActivity.this, errMsg,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    // Adaptive hero banner (v0.10.2): one persistent banner at the top of
    // Home. Every card focus calls updateHero(item), which swaps the banner
    // to that title immediately (catalog name/poster/type) and then fills
    // in the full info (description, year, genres, IMDb rating, backdrop,
    // episode count) from /meta. Meta results are cached per id in
    // memory AND on disk (SharedPreferences), so banner info persists
    // across restarts and shows instantly instead of "Loading info…"
    // on every focus; a cached banner still refreshes in the
    // background and updates only if Cinemeta's data changed (so a
    // rating that was empty when first cached can appear later).
    // A sequence guard drops stale replies.
    // Episode cards (Playing History) carry composite ids like
    // "tt123:1:2": the meta fetch uses the bare series id, while the
    // banner title stays the episode title.
    private View heroView = null;
    // v0.10.4: fixed slot ABOVE the Home ScrollView. The hero lives here,
    // not inside homeRows, so it stays pinned at the top while the rows
    // scroll underneath it.
    private LinearLayout heroSlot = null;
    private ImageView heroBg = null;
    private TextView heroTitle = null;
    private TextView heroMeta = null;
    private TextView heroDesc = null;
    private TextView heroBadge = null;
    private CatalogItem heroItem = null;
    private int heroMetaSeq = 0;
    private final java.util.Map<String, com.patrisius.cahaya.addon.MetaDetails> heroMetaCache =
            new java.util.HashMap<>();

    private void loadRow(String type, String id, String header) {
        loadRowFrom(AddonManager.DEFAULT_ADDON, type, id, null, header, true);
    }

    /**
     * Load one Home row from any addon catalog. seedHero=true only for
     * Popular Movies: the first item of that row becomes the initial
     * banner; other rows must not steal the banner if they reply first.
     */
    private void loadRowFrom(String baseUrl, String type, String id,
            String extra, String header, final boolean seedHero) {
        mgr.catalogExtra(baseUrl, type, id, extra,
                new AddonManager.Callback<List<CatalogItem>>() {
                    @Override
                    public void onResult(List<CatalogItem> items) {
                        allItems.addAll(items);
                        if (homeStatus != null) {
                            homeStatus.setVisibility(View.GONE);
                        }
                        if (homeRows != null) {
                            // Create the adaptive hero once, in its pinned
                            // slot above the scroll area; its content then
                            // follows card focus while rows scroll below.
                            if (heroView == null) {
                                heroView = makeHero();
                                if (heroSlot != null
                                        && heroView.getParent() != heroSlot) {
                                    if (heroView.getParent() instanceof ViewGroup) {
                                        ((ViewGroup) heroView.getParent())
                                                .removeView(heroView);
                                    }
                                    heroSlot.addView(heroView);
                                }
                            }
                            if (seedHero && heroItem == null && !items.isEmpty()) {
                                updateHero(items.get(0));
                            }
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

    /** Trending rows (v1.4.0): Home's only catalog rows besides
     *  Playing History. Rows show only titles resolved to a
     *  Cinemeta/TMDB entry with picture + info (resolveRows); cards
     *  carry real ids and open Details directly. A background
     *  refresh re-posts rows; the previous trending views are
     *  removed first so they don't duplicate. Playing History is
     *  untouched (refreshContinueWatching owns it, and it inserts
     *  at index 0, so it stays first). */
    private java.util.List<View> trendingViews = null;

    private void loadTrendingRows() {
        if (homeStatus != null) homeStatus.setText("Loading trending…");
        com.patrisius.cahaya.addon.TrendingEngine.loadRows(this,
                new AddonManager.Callback<java.util.Map<String, java.util.List<CatalogItem>>>() {
                    @Override
                    public void onResult(java.util.Map<String, java.util.List<CatalogItem>> rows) {
                        if (homeRows == null) return;
                        if (heroView == null) {
                            heroView = makeHero();
                            if (heroSlot != null && heroView.getParent() != heroSlot) {
                                if (heroView.getParent() instanceof ViewGroup) {
                                    ((ViewGroup) heroView.getParent()).removeView(heroView);
                                }
                                heroSlot.addView(heroView);
                            }
                        }
                        if (trendingViews != null) {
                            for (View v : trendingViews) homeRows.removeView(v);
                        }
                        trendingViews = new java.util.ArrayList<>();
                        if (homeStatus != null) homeStatus.setText("Filtering trending…");
                        final boolean[] seeded = new boolean[]{heroItem != null};
                        com.patrisius.cahaya.addon.TrendingEngine.resolveRows(
                                MainActivity.this, rows,
                                new com.patrisius.cahaya.addon.TrendingEngine.RowListener() {
                                    @Override
                                    public void onRow(String key, java.util.List<CatalogItem> resolved) {
                                        if (homeRows == null || resolved == null || resolved.isEmpty()) return;
                                        if (!seeded[0]) {
                                            updateHero(resolved.get(0));
                                            seeded[0] = true;
                                        }
                                        View row = makeTrendingRow(
                                                com.patrisius.cahaya.addon.TrendingEngine.titleForKey(key),
                                                resolved);
                                        trendingViews.add(row);
                                        homeRows.addView(row);
                                    }

                                    @Override
                                    public void onDone() {
                                        if (homeStatus != null) homeStatus.setVisibility(View.GONE);
                                        if (trendingViews.isEmpty() && homeStatus != null) {
                                            homeStatus.setText("Couldn't load trending lists — check connection and reopen Home.");
                                            homeStatus.setVisibility(View.VISIBLE);
                                        }
                                    }
                                });
                    }

                    @Override
                    public void onError(Exception e) {
                        if (homeStatus != null) {
                            homeStatus.setText("Couldn't load trending lists — check connection and reopen Home.");
                        }
                    }
                });
    }

    private View makeTrendingRow(String header, java.util.List<CatalogItem> items) {
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
            strip.addView(makeTrendingCard(it));
        }
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(strip);
        col.addView(hsv);
        return col;
    }

    /** Trending card (v1.4.0): rows contain only resolved items,
     *  so cards carry real Cinemeta/TMDB ids and open Details
     *  directly. Focus behavior is identical, including the hero
     *  follow. */
    private View makeTrendingCard(final CatalogItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int w = dp(110);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(10), 0);
        card.setLayoutParams(lp);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));

        ImageView poster = new ImageView(this);
        poster.setLayoutParams(new LinearLayout.LayoutParams(w - dp(8), dp(160)));
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
                updateHero(item);
            }
        });
        card.setOnClickListener(v -> openTrendingItem(item));
        loadPoster(item.poster, poster);
        return card;
    }

    /** Resolve a trending title to a Cinemeta entry, then Details.
     *  Season/format suffixes are stripped for the query (Cinemeta
     *  search matches the bare title better); the row's type is tried
     *  first, then the other type. */
    private void openTrendingItem(final CatalogItem item) {
        // v1.3.0: a synced item already carries a real Cinemeta id,
        // so it opens Details directly instead of searching again.
        if (item.id != null && !item.id.startsWith("trending_")) {
            Intent i = new Intent(MainActivity.this, DetailsActivity.class);
            i.putExtra("id", item.id);
            i.putExtra("type", item.type);
            startActivity(i);
            return;
        }
        String q = item.name;
        q = q.replaceAll(":\\s*Season\\s*\\d+\\s*$", "");
        q = q.replaceAll(":\\s*Limited Series\\s*$", "");
        q = q.trim();
        final String query = q;
        try {
            final String extra = "search=" + java.net.URLEncoder.encode(query, "UTF-8");
            final String firstType = item.type;
            final String secondType = "series".equals(firstType) ? "movie" : "series";
            mgr.catalogExtra(AddonManager.DEFAULT_ADDON, firstType, "top", extra,
                    new AddonManager.Callback<java.util.List<CatalogItem>>() {
                        @Override
                        public void onResult(java.util.List<CatalogItem> items) {
                            if (items != null && !items.isEmpty()) {
                                openDetails(items.get(0));
                            } else {
                                searchOtherType();
                            }
                        }

                        @Override
                        public void onError(Exception e) {
                            searchOtherType();
                        }

                        private void searchOtherType() {
                            mgr.catalogExtra(AddonManager.DEFAULT_ADDON, secondType, "top", extra,
                                    new AddonManager.Callback<java.util.List<CatalogItem>>() {
                                        @Override
                                        public void onResult(java.util.List<CatalogItem> items) {
                                            if (items != null && !items.isEmpty()) {
                                                openDetails(items.get(0));
                                            } else {
                                                Toast.makeText(MainActivity.this,
                                                        "Not in catalog: " + query,
                                                        Toast.LENGTH_LONG).show();
                                            }
                                        }

                                        @Override
                                        public void onError(Exception e) {
                                            Toast.makeText(MainActivity.this,
                                                    "Not in catalog: " + query,
                                                    Toast.LENGTH_LONG).show();
                                        }
                                    });
                        }

                        private void openDetails(CatalogItem found) {
                            Intent i = new Intent(MainActivity.this, DetailsActivity.class);
                            i.putExtra("id", found.id);
                            i.putExtra("type", found.type);
                            startActivity(i);
                        }
                    });
        } catch (Exception e) {
            Toast.makeText(this, "Not in catalog: " + query, Toast.LENGTH_LONG).show();
        }
    }

    /** Adaptive hero banner (v0.10.2): created once, content follows focus.
     *  Backdrop, title, meta line and description are stored in fields so
     *  updateHero() can swap them without rebuilding the view. */
    private View makeHero() {
        FrameLayout hero = new FrameLayout(this);
        // v0.10.3: was 320dp — at the box's density that ate ~60% of a
        // 1080p screen and pushed the first row's posters off-screen.
        // 200dp leaves room for the top bar + row header + full posters.
        int heroH = dp(200);
        hero.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heroH));
        hero.setPadding(0, 0, 0, dp(16));

        // Backdrop (poster/backdrop scaled to fill, with dark overlay)
        heroBg = new ImageView(this);
        heroBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroBg.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        heroBg.setBackgroundColor(Color.parseColor("#23262B"));
        hero.addView(heroBg);

        // Dark overlay (bottom half) so text stays readable
        View shade = new View(this);
        shade.setBackgroundColor(Color.parseColor("#AA000000"));
        FrameLayout.LayoutParams shadeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        shadeLp.gravity = android.view.Gravity.BOTTOM;
        hero.addView(shade, shadeLp);

        // Text content (bottom-left)
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(24), 0, dp(24), dp(24));
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.gravity = android.view.Gravity.BOTTOM;
        hero.addView(text, textLp);

        heroBadge = new TextView(this);
        heroBadge.setText("★ FEATURED");
        heroBadge.setTextSize(12);
        heroBadge.setTextColor(Color.parseColor("#E8A33D"));
        text.addView(heroBadge);

        heroTitle = new TextView(this);
        heroTitle.setText("Cahaya");
        heroTitle.setTextSize(28);
        heroTitle.setTextColor(Color.WHITE);
        heroTitle.setTypeface(heroTitle.getTypeface(), android.graphics.Typeface.BOLD);
        text.addView(heroTitle);

        heroMeta = new TextView(this);
        heroMeta.setText("");
        heroMeta.setTextSize(14);
        heroMeta.setTextColor(Color.parseColor("#B0B0B0"));
        text.addView(heroMeta);

        heroDesc = new TextView(this);
        heroDesc.setText("");
        heroDesc.setTextSize(14);
        heroDesc.setTextColor(Color.parseColor("#D0D0D0"));
        heroDesc.setMaxLines(2);
        heroDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(heroDesc);

        // Click opens details for whichever title the banner shows now.
        // v1.2.0: a trending hero item has a synthetic id, so it goes
        // through the title-search path like a trending card click.
        hero.setFocusable(true);
        hero.setClickable(true);
        hero.setOnClickListener(v -> {
            if (heroItem == null) return;
            if (heroItem.id != null && heroItem.id.startsWith("trending_")) {
                openTrendingItem(heroItem);
                return;
            }
            Intent i = new Intent(MainActivity.this, DetailsActivity.class);
            i.putExtra("id", heroItem.id);
            i.putExtra("type", heroItem.type);
            startActivity(i);
        });

        return hero;
    }

    /**
     * Point the banner at the focused title. Catalog data (name, type,
     * poster) shows instantly; /meta then fills description, year,
     * genres, rating, backdrop and episode count. Cached meta applies
     * instantly. Stale /meta replies (focus moved on) are dropped.
     */
    /** Bare id for a meta lookup: episode ids "tt123:1:2" -> "tt123".
     *  v1.3.1: a TMDB id ("tmdb:293607") keeps its prefix — splitting
     *  on ':' would leave "tmdb" and every meta lookup would fail. */
    private static String heroBaseId(String id) {
        if (id == null) return "";
        if (id.startsWith("tmdb:")) return id;
        int c = id.indexOf(':');
        return c >= 0 ? id.substring(0, c) : id;
    }

    /** Meta source for an id: TMDB-addon ids come from the TMDB
     *  addon, everything else from Cinemeta (v1.3.1). */
    private static String metaAddonFor(String id) {
        return id != null && id.startsWith("tmdb:")
                ? AddonManager.TMDB_ADDON : AddonManager.DEFAULT_ADDON;
    }

    /** Disk-persistent hero meta cache (SharedPreferences, JSON per id). */
    private android.content.SharedPreferences heroMetaPrefs() {
        return getSharedPreferences("hero_meta_cache", MODE_PRIVATE);
    }

    private void saveHeroMeta(com.patrisius.cahaya.addon.MetaDetails d) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", d.id);
            o.put("type", d.type);
            o.put("name", d.name);
            o.put("poster", d.poster);
            o.put("background", d.background);
            o.put("description", d.description);
            o.put("releaseInfo", d.releaseInfo);
            o.put("genres", d.genres);
            o.put("rating", d.rating);
            o.put("moviedbId", d.moviedbId);
            o.put("episodeCount", d.episodeCount);
            heroMetaPrefs().edit().putString("hero:" + d.id, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private com.patrisius.cahaya.addon.MetaDetails loadHeroMetaDisk(String metaId) {
        try {
            String s = heroMetaPrefs().getString("hero:" + metaId, null);
            if (s == null) return null;
            JSONObject o = new JSONObject(s);
            com.patrisius.cahaya.addon.MetaDetails d =
                    new com.patrisius.cahaya.addon.MetaDetails();
            d.id = o.optString("id", metaId);
            d.type = o.optString("type", "");
            d.name = o.optString("name", "");
            d.poster = o.optString("poster", null);
            d.background = o.optString("background", null);
            d.description = o.optString("description", "");
            d.releaseInfo = o.optString("releaseInfo", "");
            d.genres = o.optString("genres", "");
            d.rating = o.optString("rating", "");
            d.moviedbId = o.optInt("moviedbId", 0);
            d.episodeCount = o.optInt("episodeCount", 0);
            return d;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** True when two meta results would render the same banner. */
    private static boolean heroMetaEquals(
            com.patrisius.cahaya.addon.MetaDetails a,
            com.patrisius.cahaya.addon.MetaDetails b) {
        if (a == null || b == null) return a == b;
        return eq(a.name, b.name) && eq(a.releaseInfo, b.releaseInfo)
                && eq(a.genres, b.genres) && eq(a.rating, b.rating)
                && eq(a.description, b.description)
                && eq(a.background, b.background) && eq(a.poster, b.poster)
                && eq(a.type, b.type) && a.episodeCount == b.episodeCount;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void updateHero(CatalogItem item) {
        if (item == null || heroView == null) return;
        if (heroItem != null && heroItem.id.equals(item.id)) return;
        heroItem = item;
        final int seq = ++heroMetaSeq;
        final String metaId = heroBaseId(item.id);

        heroBadge.setText("series".equals(item.type) ? "★ SERIES" : "★ MOVIE");
        heroTitle.setText(item.name);
        heroMeta.setText("series".equals(item.type) ? "Series" : "Movie");
        heroDesc.setText("");
        loadPoster(item.poster, heroBg);
        updateAppBackground(item.poster);

        com.patrisius.cahaya.addon.MetaDetails cached = heroMetaCache.get(metaId);
        if (cached == null) {
            cached = loadHeroMetaDisk(metaId);
            if (cached != null) {
                heroMetaCache.put(metaId, cached);
            }
        }
        if (cached != null) {
            applyHeroMeta(cached);
            // Background refresh: the cached banner stays on screen
            // while fresh meta is fetched; if Cinemeta changed
            // (e.g. a rating filled in since the cache was written),
            // the banner and both caches update. A failed refresh
            // is silent — the cached banner is better than blank.
            final com.patrisius.cahaya.addon.MetaDetails shown = cached;
            mgr.meta(metaAddonFor(item.id), item.type, metaId,
                    new AddonManager.Callback<com.patrisius.cahaya.addon.MetaDetails>() {
                        @Override
                        public void onResult(com.patrisius.cahaya.addon.MetaDetails d) {
                            if (seq != heroMetaSeq || heroItem == null
                                    || !heroItem.id.equals(item.id)) return;
                            heroMetaCache.put(d.id, d);
                            saveHeroMeta(d);
                            if (!heroMetaEquals(shown, d)) {
                                applyHeroMeta(d);
                            }
                        }

                        @Override
                        public void onError(Exception e) {
                        }
                    });
            return;
        }
        heroDesc.setText("Loading info…");
        mgr.meta(metaAddonFor(item.id), item.type, metaId,
                new AddonManager.Callback<com.patrisius.cahaya.addon.MetaDetails>() {
                    @Override
                    public void onResult(com.patrisius.cahaya.addon.MetaDetails d) {
                        if (seq != heroMetaSeq || heroItem == null
                                || !heroItem.id.equals(item.id)) return;
                        heroMetaCache.put(d.id, d);
                        saveHeroMeta(d);
                        applyHeroMeta(d);
                    }

                    @Override
                    public void onError(Exception e) {
                        // Keep the catalog fallback (title/poster/type);
                        // a failed meta fetch must not blank the banner.
                        if (seq != heroMetaSeq) return;
                        heroDesc.setText("");
                    }
                });
    }

    /** Fill the banner from a /meta result (backdrop preferred over poster).
     *  For episode cards the banner title stays the episode title;
     *  only the meta line, description and backdrop come from the
     *  series meta. The rating shown is Cinemeta's imdbRating — it
     *  appears only when Cinemeta actually carries one for the title
     *  (e.g. Breaking Bad 9.5); an empty rating means the source has
     *  none, not that the banner drops it. */
    private void applyHeroMeta(com.patrisius.cahaya.addon.MetaDetails d) {
        if (heroItem == null || !heroBaseId(heroItem.id).equals(d.id)) return;
        if (d.background != null && !d.background.isEmpty()) {
            loadPoster(d.background, heroBg);
            updateAppBackground(d.background);
        } else if (d.poster != null) {
            loadPoster(d.poster, heroBg);
            updateAppBackground(d.poster);
        }
        if (heroItem.id.equals(d.id) && d.name != null && !d.name.isEmpty()) {
            heroTitle.setText(d.name);
        }
        StringBuilder meta = new StringBuilder();
        if (d.releaseInfo != null && !d.releaseInfo.isEmpty()) meta.append(d.releaseInfo);
        meta.append(meta.length() > 0 ? "  ·  " : "")
                .append("series".equals(d.type) ? "Series" : "Movie");
        int eps = d.episodeCount > 0 ? d.episodeCount : d.videos.size();
        if (eps > 0) meta.append("  ·  ").append(eps).append(" episodes");
        if (d.genres != null && !d.genres.isEmpty()) meta.append("  ·  ").append(d.genres);
        if (d.rating != null && !d.rating.isEmpty()) meta.append("  ·  ★ ").append(d.rating);
        heroMeta.setText(meta.toString());
        heroDesc.setText(d.description != null ? d.description : "");
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

    /** Google device-code sign-in dialog (v0.10.8).
     *  Shows the code + QR, polls Google until the user approves on
     *  their phone, then stores the profile. If no TV OAuth client ID
     *  is baked in yet, shows the setup steps instead of failing. */
    private GoogleAccount.PollTask currentGooglePoll = null;

    private void showGoogleSignInDialog() {
        if (!GoogleAccount.isConfigured()) {
            new AlertDialog.Builder(this)
                    .setTitle("Google sign-in not set up yet")
                    .setMessage("Cahaya needs a Google OAuth client of type "
                            + "\"TVs and Limited Input devices\" before anyone can sign in.\n\n"
                            + "1. Open console.cloud.google.com and create/select a project (e.g. Cahaya).\n"
                            + "2. APIs & Services > OAuth consent screen: set it up (External).\n"
                            + "3. APIs & Services > Credentials > Create Credentials > OAuth client ID > "
                            + "TVs and Limited Input devices.\n"
                            + "4. Copy the client ID (ends in .apps.googleusercontent.com) and send it to me — "
                            + "it is not a secret — and I will bake it into the next build.\n\n"
                            + "If the consent screen stays in Testing, add your Google account under Test users.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

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
        t2.setText("google.com/device");
        t2.setTextSize(20);
        t2.setTextColor(Color.WHITE);
        t2.setPadding(0, dp(4), 0, dp(4));
        col.addView(t2);

        TextView t3 = new TextView(this);
        t3.setText("and enter this code:");
        t3.setTextColor(Color.GRAY);
        col.addView(t3);

        final TextView codeView = new TextView(this);
        codeView.setText("Requesting code…");
        codeView.setTextSize(32);
        codeView.setTextColor(Color.parseColor("#E8A33D"));
        codeView.setTypeface(codeView.getTypeface(), android.graphics.Typeface.BOLD);
        codeView.setGravity(android.view.Gravity.CENTER);
        codeView.setPadding(0, dp(8), 0, dp(8));
        col.addView(codeView);

        final ImageView qrView = new ImageView(this);
        col.addView(qrView);

        final TextView status = new TextView(this);
        status.setText("Waiting…");
        status.setTextColor(Color.GRAY);
        status.setGravity(android.view.Gravity.CENTER);
        status.setPadding(0, dp(12), 0, 0);
        col.addView(status);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Sign in with Google")
                .setView(col)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnDismissListener(d -> {
            if (currentGooglePoll != null) {
                currentGooglePoll.cancel();
                currentGooglePoll = null;
            }
        });
        dlg.show();

        GoogleAccount.requestDeviceCode(new GoogleAccount.Callback<GoogleAccount.DeviceCode>() {
            @Override
            public void onResult(GoogleAccount.DeviceCode d) {
                codeView.setText(d.userCode);
                String qrTarget = (d.verificationUrlComplete != null
                        && !d.verificationUrlComplete.isEmpty())
                        ? d.verificationUrlComplete : d.verificationUrl;
                try {
                    qrView.setImageBitmap(qrBitmap(qrTarget, dp(240)));
                } catch (Exception ignored) {
                    // Code text alone is enough.
                }
                status.setText("Enter the code on your phone.\nThis screen waits for your approval…");
                currentGooglePoll = GoogleAccount.pollForToken(MainActivity.this, d,
                        new GoogleAccount.Callback<GoogleAccount.Profile>() {
                            @Override
                            public void onResult(GoogleAccount.Profile p) {
                                currentGooglePoll = null;
                                dlg.dismiss();
                                refreshAccountLabel();
                                Toast.makeText(MainActivity.this,
                                        "Signed in as " + p.name,
                                        Toast.LENGTH_SHORT).show();
                                selectMenu(5, false);
                            }

                            @Override
                            public void onError(Exception e) {
                                currentGooglePoll = null;
                                status.setText("Sign-in failed: " + e.getMessage());
                            }
                        });
            }

            @Override
            public void onError(Exception e) {
                codeView.setText("—");
                status.setText("Could not start sign-in: " + e.getMessage());
            }
        });
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
        // v0.10.5: was 140dp wide / 200dp tall — shrunk ~20% so more
        // titles fit per row and each row takes less vertical space
        // under the pinned hero.
        int w = dp(110);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(10), 0);
        card.setLayoutParams(lp);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));

        ImageView poster = new ImageView(this);
        poster.setLayoutParams(new LinearLayout.LayoutParams(w - dp(8), dp(160)));
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
                updateHero(item);
            }
        });
        card.setOnClickListener(v -> {
            // v1.1.2: a click on a Search-tab result records the query
            // exactly as typed at click time — even an incomplete word.
            // Home rows never reach this branch (selected != 1 there).
            if (selected == 1 && searchInput != null) {
                String q = searchInput.getText().toString().trim();
                if (!q.isEmpty()) {
                    saveSearchQuery(q);
                    if (refreshSearchHistory != null) refreshSearchHistory.run();
                }
            }
            Intent i = new Intent(MainActivity.this, DetailsActivity.class);
            i.putExtra("id", item.id);
            i.putExtra("type", item.type);
            startActivity(i);
        });

        loadPoster(item.poster, poster);
        return card;
    }

    // ---------------- Search ----------------

    /** v1.1.2: the Search tab's live input + history refresher, kept so
     *  a result-card click can record whatever is typed at click time
     *  (even a partial word). Only meaningful while selected == 1. */
    private android.widget.EditText searchInput;
    private Runnable refreshSearchHistory;

    /** Search history (v1.1.1): last 10 queries, most recent first,
     *  in SharedPreferences as a JSON array. */
    private java.util.List<String> loadSearchHistory() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            String json = getSharedPreferences("search_history", MODE_PRIVATE)
                    .getString("queries", "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        } catch (Exception ignored) {}
        return out;
    }

    private void saveSearchQuery(String q) {
        if (q == null || q.isEmpty()) return;
        java.util.List<String> h = loadSearchHistory();
        h.remove(q);
        h.add(0, q);
        while (h.size() > 10) h.remove(h.size() - 1);
        try {
            JSONArray arr = new JSONArray();
            for (String x : h) arr.put(x);
            getSharedPreferences("search_history", MODE_PRIVATE)
                    .edit().putString("queries", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

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
        searchInput = input;
        input.setHint("Search movies and series…");
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
        note.setText("Type to search Cinemeta movies + series live. Loaded Home titles are included too.");
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(8), 0, dp(8));
        col.addView(note);

        // v1.1.1: recent-searches row (label + horizontal chip row).
        // Populated by rebuildHistory below, once doSearch exists.
        final LinearLayout historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        historyBox.setPadding(0, dp(4), 0, dp(8));
        TextView histLabel = new TextView(this);
        histLabel.setText("Recent searches");
        histLabel.setTextColor(Color.GRAY);
        histLabel.setTextSize(13);
        histLabel.setPadding(0, 0, 0, dp(6));
        historyBox.addView(histLabel);
        final LinearLayout historyRow = new LinearLayout(this);
        historyRow.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView histScroll =
                new android.widget.HorizontalScrollView(this);
        histScroll.setHorizontalScrollBarEnabled(false);
        histScroll.addView(historyRow);
        historyBox.addView(histScroll);
        historyBox.setVisibility(View.GONE);
        col.addView(historyBox);

        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // v1.1.1: live search + history. doSearch(true) = explicit
        // search (Go / Enter / history chip): saves the query to
        // history and moves focus to the first result. doSearch(false)
        // = live typing: searches but neither saves (typing "b-r-e-a"
        // must not flood history with fragments) nor steals focus
        // from the input. searchSeq drops stale replies so an older
        // query's late response can't overwrite a newer one.
        final int[] searchSeq = {0};
        final Runnable[] rebuildHistoryRef = new Runnable[1];
        final java.util.function.Consumer<Boolean> doSearch = (save) -> {
            String raw = input.getText().toString().trim();
            final String q = raw.toLowerCase();
            final int mySeq = ++searchSeq[0];
            results.removeAllViews();
            if (q.isEmpty()) {
                return;
            }
            if (save) {
                saveSearchQuery(raw);
                if (rebuildHistoryRef[0] != null) rebuildHistoryRef[0].run();
            }
            TextView searching = new TextView(this);
            searching.setText("Searching Cinemeta for \"" + raw + "\"…");
            searching.setTextColor(Color.GRAY);
            results.addView(searching);
            // Live Cinemeta search: /catalog/{type}/top/search=<query>.json
            // (verified live 2026-10-24). Nuvio's catalog returns empty
            // for search, so Cinemeta is the only live source here.
            final String extra;
            try {
                extra = "search=" + java.net.URLEncoder.encode(raw, "UTF-8")
                        .replace("+", "%20");
            } catch (Exception e) {
                results.removeAllViews();
                TextView err = new TextView(this);
                err.setText("Could not encode search query.");
                err.setTextColor(Color.GRAY);
                results.addView(err);
                return;
            }
            final List<CatalogItem> live = new ArrayList<>();
            final int[] done = {0};
            final int[] failed = {0};
            AddonManager.Callback<List<CatalogItem>> cb =
                    new AddonManager.Callback<List<CatalogItem>>() {
                public void onResult(List<CatalogItem> items) {
                    if (items != null) {
                        live.addAll(items);
                    }
                    finish();
                }
                public void onError(Exception e) {
                    failed[0]++;
                    finish();
                }
                private void finish() {
                    done[0]++;
                    if (done[0] < 2) {
                        return;
                    }
                    if (mySeq != searchSeq[0]) return; // superseded by a newer keystroke
                    results.removeAllViews();
                    // Live results first, then loaded-Home matches not
                    // already present, deduped by catalog id.
                    List<CatalogItem> hits = new ArrayList<>();
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (CatalogItem it : live) {
                        if (seen.add(it.id)) {
                            hits.add(it);
                        }
                    }
                    for (CatalogItem it : allItems) {
                        if (it.name.toLowerCase().contains(q)
                                && seen.add(it.id)) {
                            hits.add(it);
                        }
                    }
                    if (hits.isEmpty()) {
                        TextView empty = new TextView(MainActivity.this);
                        empty.setText(failed[0] == 2
                                ? "Search failed — check connection and try again."
                                : "No matches for \"" + q + "\".");
                        empty.setTextColor(Color.GRAY);
                        results.addView(empty);
                    } else {
                        results.addView(makeRow(hits.size() + " result(s)", hits));
                        if (save) {
                            View f = firstFocusable(results);
                            if (f != null) {
                                f.requestFocus();
                            }
                        }
                    }
                }
            };
            mgr.catalogExtra(AddonManager.DEFAULT_ADDON, "movie", "top", extra, cb);
            mgr.catalogExtra(AddonManager.DEFAULT_ADDON, "series", "top", extra, cb);
        };
        final Runnable liveSearch = () -> doSearch.accept(false);
        rebuildHistoryRef[0] = () -> {
            historyRow.removeAllViews();
            java.util.List<String> hist = loadSearchHistory();
            historyBox.setVisibility(hist.isEmpty() ? View.GONE : View.VISIBLE);
            for (String query : hist) {
                TextView chip = new TextView(MainActivity.this);
                chip.setText(query);
                chip.setTextColor(Color.WHITE);
                chip.setTextSize(15);
                chip.setSingleLine(true);
                chip.setPadding(dp(16), dp(8), dp(16), dp(8));
                chip.setFocusable(true);
                chip.setClickable(true);
                chip.setBackgroundColor(Color.parseColor("#2A4A5A"));
                chip.setOnFocusChangeListener((v, hasFocus) ->
                        chip.setBackgroundColor(Color.parseColor(
                                hasFocus ? "#35617A" : "#2A4A5A")));
                final String q = query;
                chip.setOnClickListener(v -> {
                    input.setText(q);
                    main.removeCallbacks(liveSearch);
                    doSearch.accept(true);
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = dp(8);
                historyRow.addView(chip, clp);
            }
        };
        refreshSearchHistory = rebuildHistoryRef[0];
        rebuildHistoryRef[0].run();
        go.setOnClickListener(v -> {
            main.removeCallbacks(liveSearch);
            doSearch.accept(true);
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                main.removeCallbacks(liveSearch);
                doSearch.accept(true);
                return true;
            }
            return false;
        });
        // v1.1.1: search directly as the user types — debounced
        // 500ms so one keystroke burst fires one search, not one
        // request per character.
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                main.removeCallbacks(liveSearch);
                main.postDelayed(liveSearch, 500);
            }
            public void afterTextChanged(android.text.Editable s) {}
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

        // Google Account (v0.10.8 device-code sign-in)
        TextView gh = new TextView(this);
        gh.setText("Google Account");
        gh.setTextSize(22);
        gh.setTextColor(Color.WHITE);
        gh.setPadding(0, dp(8), 0, dp(4));
        col.addView(gh);

        TextView gStatus = new TextView(this);
        if (GoogleAccount.isSignedIn(this)) {
            String n = GoogleAccount.displayName(this);
            String e = GoogleAccount.displayEmail(this);
            gStatus.setText("Signed in as " + (n != null ? n : "")
                    + (e != null && !e.isEmpty() ? "\n" + e : ""));
        } else {
            gStatus.setText("Not signed in.\nSign in with your Google account on your phone.");
        }
        gStatus.setTextColor(Color.GRAY);
        gStatus.setPadding(0, 0, 0, dp(8));
        col.addView(gStatus);

        Button gBtn = new Button(this);
        gBtn.setFocusable(true);
        if (GoogleAccount.isSignedIn(this)) {
            gBtn.setText("Sign out");
            gBtn.setOnClickListener(v -> GoogleAccount.signOut(MainActivity.this,
                    new GoogleAccount.Callback<Void>() {
                        @Override
                        public void onResult(Void r) {
                            refreshAccountLabel();
                            Toast.makeText(MainActivity.this,
                                    "Signed out", Toast.LENGTH_SHORT).show();
                            selectMenu(5, false);
                        }

                        @Override
                        public void onError(Exception e) {
                            refreshAccountLabel();
                            selectMenu(5, false);
                        }
                    }));
        } else {
            gBtn.setText("Sign in with Google");
            gBtn.setOnClickListener(v -> showGoogleSignInDialog());
        }
        col.addView(gBtn, new LinearLayout.LayoutParams(dp(280),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        addSettingRow(col, "Version", "1.4.0 (build 67)");
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

    /** Full-app background (v0.10.26, fixed v0.10.27): the focused
     *  title's backdrop (or poster) blurred and darkened, drawn in a
     *  dedicated ImageView that sits UNDER the whole root layout.
     *  v0.10.26 drew it as the window background, which never showed:
     *  the decor view's solid #14161A background paints over the
     *  window background drawable. The sidebar stays solid for
     *  readability; the blurred photo shows through the transparent
     *  content area behind the rows. Blur runs on imgIo over a small
     *  downscaled copy, so it is cheap; a sequence guard drops stale
     *  replies on fast focus moves. */
    private int appBgSeq = 0;
    private ImageView appBgView = null;

    private void updateAppBackground(String url) {
        if (url == null || url.isEmpty()) return;
        final int seq = ++appBgSeq;
        final String u = url;
        imgIo.execute(() -> {
            try {
                android.graphics.Bitmap src = memCache.get(u);
                if (src == null) {
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "Cahaya/0.7.0 (Android TV)");
                    InputStream in = c.getInputStream();
                    src = BitmapFactory.decodeStream(in);
                    in.close();
                    if (src != null) memCache.put(u, src);
                }
                if (src == null) return;
                // Downscale hard: a 160px-wide blurred image stretched
                // over the window looks smooth and blurs in milliseconds.
                int w = 160;
                int h = Math.max(1, (int) (src.getHeight() * (w / (double) src.getWidth())));
                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(
                        src, w, h, true);
                boxBlur(small, 8, 3);
                android.graphics.Canvas cv = new android.graphics.Canvas(small);
                cv.drawColor(Color.argb(120, 0, 0, 0));
                final android.graphics.Bitmap bg = small;
                main.post(() -> {
                    if (seq != appBgSeq) return;
                    if (appBgView != null) {
                        appBgView.setImageBitmap(bg);
                    }
                });
            } catch (Exception ignored) {
                // Keep the solid window background on any failure.
            }
        });
    }

    /** Stack blur (fast box-blur approximation), in place, on a small bitmap. */
    /** Separable box blur (prefix sums, 3 passes) — replaces the
     *  v0.10.26 stack blur, which was verified offline to collapse
     *  any image to a near-solid average colour (its sliding window
     *  stopped advancing). This one was verified on a synthetic
     *  red/blue/white image to preserve the colour gradient. */
    private static void boxBlur(android.graphics.Bitmap bmp, int radius, int iters) {
        if (radius < 1 || iters < 1) return;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);
        int[] out = new int[w * h];
        for (int it = 0; it < iters; it++) {
            // Horizontal pass
            for (int y = 0; y < h; y++) {
                int[] pr = new int[w + 1];
                int[] pg = new int[w + 1];
                int[] pb = new int[w + 1];
                for (int x = 0; x < w; x++) {
                    int p = pix[y * w + x];
                    pr[x + 1] = pr[x] + ((p >> 16) & 0xff);
                    pg[x + 1] = pg[x] + ((p >> 8) & 0xff);
                    pb[x + 1] = pb[x] + (p & 0xff);
                }
                for (int x = 0; x < w; x++) {
                    int a = Math.max(0, x - radius);
                    int b = Math.min(w, x + radius + 1);
                    int n = b - a;
                    out[y * w + x] = (0xff000000)
                            | (((pr[b] - pr[a]) / n) << 16)
                            | (((pg[b] - pg[a]) / n) << 8)
                            | ((pb[b] - pb[a]) / n);
                }
            }
            // Vertical pass
            for (int x = 0; x < w; x++) {
                int[] pr = new int[h + 1];
                int[] pg = new int[h + 1];
                int[] pb = new int[h + 1];
                for (int y = 0; y < h; y++) {
                    int p = out[y * w + x];
                    pr[y + 1] = pr[y] + ((p >> 16) & 0xff);
                    pg[y + 1] = pg[y] + ((p >> 8) & 0xff);
                    pb[y + 1] = pb[y] + (p & 0xff);
                }
                for (int y = 0; y < h; y++) {
                    int a = Math.max(0, y - radius);
                    int b = Math.min(h, y + radius + 1);
                    int n = b - a;
                    pix[y * w + x] = (0xff000000)
                            | (((pr[b] - pr[a]) / n) << 16)
                            | (((pg[b] - pg[a]) / n) << 8)
                            | ((pb[b] - pb[a]) / n);
                }
            }
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }

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
