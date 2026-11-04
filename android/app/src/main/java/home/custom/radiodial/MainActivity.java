package home.custom.radiodial;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native Radio Dial for the TV. Same station list and behaviour as
 * radio-dial.pages.dev: the list is stations.json (built by build_stations.py),
 * bundled as res/raw/stations.json. "Refresh list" downloads the site's current
 * copy and keeps it until the bundled file changes. Grouped, with favourites
 * and a one-week memory of streams that failed. No WebView (the TV's system
 * WebView segfaults), no tracking: plain HTTPS audio streams played with the
 * platform player.
 */
public class MainActivity extends Activity {

    private static final String STATIONS_URL = "https://radio-dial.pages.dev/stations.json";
    private static final String[] RADIO_BROWSER_HOSTS = {
            "de1.api.radio-browser.info", "nl1.api.radio-browser.info", "at1.api.radio-browser.info"
    };
    private static final int REQUEST_EXPORT = 7101, REQUEST_IMPORT = 7102;
    private static final long DEAD_TTL = 7L * 24 * 3600 * 1000;
    private static final int FOCUS = 0xFF3B82F6;
    private static final int INK_FIXED = 0xFF282529;   // text on the pink / yellow blocks
    private static final int RED = 0xFFE54555;

    private static class Station {
        String name, code, description, url, group, country, logo;
    }

    // ---- data / state ----
    private final List<Station> stations = new ArrayList<Station>();
    private final List<Station> localStations = new ArrayList<Station>();
    private final List<Station> shown = new ArrayList<Station>();
    private final List<Station> directoryResults = new ArrayList<Station>();
    private final Set<String> favourites = new HashSet<String>();
    private final Map<String, Long> dead = new HashMap<String, Long>();
    private final LruCache<String, Bitmap> logos = new LruCache<String, Bitmap>(16);
    private final Set<String> loadingLogos = new HashSet<String>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private MediaController player;
    private ListenableFuture<MediaController> controllerFuture;
    private Station pendingStart;
    private EditText searchField;
    private String searchQuery = "";
    private boolean compact, destroyed, directoryMode;

    private final Player.Listener controllerListener = new Player.Listener() {
        @Override public void onMediaItemTransition(MediaItem item, int reason) {
            syncFromController();
        }
        @Override public void onIsPlayingChanged(boolean isPlaying) {
            if (player == null) return;
            wantPlaying = player.getPlayWhenReady();
            Station current = stationForCurrentItem();
            if (current != null) {
                playing = current;
                tuned = current;
                if (isPlaying) {
                    markLive(current);
                    status = "Playing " + current.name + ".";
                    error = "";
                } else if (!wantPlaying) {
                    status = "Paused.";
                }
            }
            if (adapter != null) adapter.notifyDataSetChanged();
            if (cardName != null) refreshCard();
        }
        @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
            wantPlaying = ready;
            if (!ready && playing != null && cardName != null) {
                status = "Paused.";
                refreshCard();
            }
        }
        @Override public void onPlayerError(PlaybackException error) {
            streamFailed("Stream failed.", token);
        }
    };
    private Station tuned, playing;
    private String groupFilter = "all";
    private boolean favouritesView, showDead, muted, dark, wantPlaying;
    private int failCount, skipBudget, token;
    private String status = "Choose a station to tune in.", error = "";
    private SharedPreferences prefs;
    private float unit = 1f;
    private String bundledKey = "";

    // ---- palette (the site's CSS variables) ----
    private int ink, line, paper, cardBg, asideBg, pink, yellow, blue, muted_;

    // ---- views ----
    private TextView statusText, errorText, cardName, cardDesc, badgeCode, sectionTitle;
    private TextView playBtn, favBtn, navStations, navFavourites, deadChip;
    private TextView liveBadge;
    private LinearLayout liveBadgeBox;
    private PulseDot liveDot;
    private AudioBars audioBars;
    private ImageView badgeLogo;
    private LinearLayout chipBox;
    private final List<TextView> chips = new ArrayList<TextView>();
    private GridView grid;
    private StationAdapter adapter;

    // ====================================================================
    // lifecycle
    // ====================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("radiodial", MODE_PRIVATE);
        dark = prefs.getBoolean("dark", false);
        favourites.addAll(prefs.getStringSet("fav", new HashSet<String>()));
        loadDead();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        android.app.UiModeManager uiMode = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        boolean isTv = uiMode != null && uiMode.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
        boolean largeScreen = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        compact = !isTv && !largeScreen;
        // Phone uses dp-based native sizing; TV retains the 1920x1080 canvas scale.
        unit = compact ? dm.density : Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        String bundled = readBundled();
        bundledKey = String.valueOf(bundled.hashCode());
        String cached = prefs.getString("stations_cache", null);
        boolean cacheFresh = cached != null
                && bundledKey.equals(prefs.getString("stations_cache_for", ""));
        List<Station> list = parseStations(cacheFresh ? cached : bundled);
        if (list != null) stations.addAll(list);
        loadLocalStations();
        appendLocalStations();
        if (stations.isEmpty()) status = "Loading stations\u2026";
        buildUi();
        connectPlaybackService();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (stations.isEmpty()) fetchStations(false); // nothing bundled yet: use the site's list
    }

    private String readBundled() {
        try {
            InputStream in = getResources().openRawResource(R.raw.stations);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "[]";
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Playback belongs to PlaybackService and continues for Android Auto / media controls.
        // Freeze the header animations so they don't burn battery off-screen.
        if (liveDot != null) liveDot.pause();
        if (audioBars != null) audioBars.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (liveDot != null) liveDot.resume();
        if (audioBars != null) audioBars.resume();
        updateLiveBadge();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        io.shutdownNow();
        if (player != null) {
            player.removeListener(controllerListener);
            player.release();
            player = null;
        } else if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
        super.onDestroy();
    }

    private void connectPlaybackService() {
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(new Runnable() {
            @Override public void run() {
                if (destroyed) { MediaController.releaseFuture(controllerFuture); return; }
                try {
                    player = controllerFuture.get();
                    player.addListener(controllerListener);
                    if (pendingStart != null) {
                        Station queued = pendingStart;
                        pendingStart = null;
                        startPlay(queued);
                    } else {
                        syncFromController();
                    }
                } catch (Exception e) {
                    status = "Player unavailable. Reopen Radio Dial to retry.";
                    if (statusText != null) statusText.setText(status);
                }
            }
        }, new java.util.concurrent.Executor() {
            @Override public void execute(Runnable command) { ui.post(command); }
        });
    }

    private Station stationForCurrentItem() {
        if (player == null || player.getCurrentMediaItem() == null) return null;
        String mediaId = player.getCurrentMediaItem().mediaId;
        String streamUrl = player.getCurrentMediaItem().localConfiguration == null ? ""
                : player.getCurrentMediaItem().localConfiguration.uri.toString();
        for (Station station : stations) {
            if (station.url.equals(streamUrl) || station.url.equals(mediaId)
                    || ("station:" + station.url).equals(mediaId)) return station;
        }
        return null;
    }

    private void syncFromController() {
        Station current = stationForCurrentItem();
        if (current == null) return;
        playing = current;
        tuned = current;
        wantPlaying = player.getPlayWhenReady();
        status = wantPlaying ? "Playing " + current.name + "." : "Paused.";
        if (adapter != null) adapter.notifyDataSetChanged();
        if (cardName != null) refreshCard();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_T:
            case KeyEvent.KEYCODE_MENU:
                toggleTheme();
                return true;
            case KeyEvent.KEYCODE_M:
                toggleMute();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                tuneStep(1, wantPlaying);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                tuneStep(-1, wantPlaying);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // ====================================================================
    // layout helpers (designed on a 1920x1080 canvas, scaled to the screen)
    // ====================================================================

    private int px(float v) {
        return Math.round(v * unit);
    }

    private void applyPalette() {
        if (dark) {
            ink = 0xFFF2EDF3; line = 0xFFE0D8E2;
            paper = 0xFF18161B; cardBg = 0xFF242028; asideBg = 0xFF1E1A22;
            pink = 0xFFB94E7C; yellow = 0xFFBC991A; blue = 0xFF347585; muted_ = 0xFFA79EA7;
        } else {
            ink = 0xFF282529; line = ink; paper = 0xFFFFFDF9; cardBg = 0xFFFFFFFF; asideBg = 0xFFFFFEFA;
            pink = 0xFFF7ADCF; yellow = 0xFFF8D860; blue = 0xFF9FE5ED; muted_ = 0xFF766F73;
        }
    }

    private GradientDrawable pageBackdrop() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        background.setGradientCenter(0.05f, 0.08f);
        background.setGradientRadius(Math.max(dp(220), getResources().getDisplayMetrics().widthPixels * 0.24f));
        int glow = dark ? 0xFF26242A : 0xFFFFFEFC;
        background.setColors(new int[]{glow, glow, paper});
        return background;
    }

    private void addBadgeHighlight(FrameLayout badge, int insetPx, int strokePx) {
        GradientDrawable highlight = new GradientDrawable();
        highlight.setShape(GradientDrawable.OVAL);
        highlight.setColor(0x00000000);
        highlight.setStroke(strokePx, 0x66FFFFFF);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
        params.setMargins(insetPx, insetPx, insetPx, insetPx);
        View highlightView = new View(this);
        highlightView.setBackground(highlight);
        highlightView.setClickable(false);
        badge.addView(highlightView, params);
    }

    private TextView text(String s, float sizePx, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable rect(int fill, int radiusPx, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor == ink ? line : strokeColor);
        return g;
    }

    /** Ink-outlined block: blue ring when focused, selFill when selected. */
    private StateListDrawable bordered(int fill, int selFill, int radiusPx) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rect(fill, radiusPx, px(4), FOCUS));
        s.addState(new int[]{android.R.attr.state_selected}, rect(selFill, radiusPx, px(3), ink));
        s.addState(new int[]{}, rect(fill, radiusPx, px(3), ink));
        return s;
    }

    private void roundClip(View v, final int radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** The site's hard offset shadow: an ink block peeking out bottom-right.
     *  The shadow is sized exactly like the content (never MATCH_PARENT),
     *  so it can't blow up a WRAP_CONTENT parent. */
    private FrameLayout shadowed(View content, int radiusPx, int off) {
        FrameLayout f = new FrameLayout(this);
        f.setClipChildren(false);
        View sh = new View(this);
        sh.setBackground(rect(line, radiusPx, 0, 0));
        // Sized after layout to exactly mirror the content: WRAP_CONTENT
        // measurement can't be trusted for a bare View inside a WRAP parent.
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                0, 0, Gravity.TOP | Gravity.LEFT);
        sp.leftMargin = off;
        sp.topMargin = off;
        f.addView(sh, sp);
        final View shadow = sh;
        final View body = content;
        final int inset = off;
        sh.post(new Runnable() {
            @Override public void run() {
                FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(
                        body.getWidth(), body.getHeight(), Gravity.TOP | Gravity.LEFT);
                lp2.leftMargin = inset;
                lp2.topMargin = inset;
                shadow.setLayoutParams(lp2);
            }
        });
        FrameLayout.LayoutParams cp = (content.getLayoutParams() instanceof FrameLayout.LayoutParams)
                ? (FrameLayout.LayoutParams) content.getLayoutParams()
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.rightMargin = off;
        cp.bottomMargin = off;
        f.addView(content, cp);
        return f;
    }

    private TextView block(String label, float size, int textColor, int fill, int selFill,
                           int radiusPx, int padH, View.OnClickListener click) {
        TextView t = text(label, size, textColor, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(padH, 0, padH, 0);
        t.setBackground(bordered(fill, selFill, radiusPx));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }

    private TextView roundButton(String label, int widthPx, int fill, View.OnClickListener click) {
        TextView b = block(label, compact ? sp(18) : px(26), INK_FIXED, fill, fill, px(32), 0, click);
        b.setMinWidth(widthPx);
        return b;
    }

    /** The tilted pink "radio / DIAL" wordmark. */
    private class Logo extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Logo() { super(MainActivity.this); }
        @Override protected void onDraw(Canvas c) {
            float scale = compact ? 0.45f : 1f;
            c.save();
            c.rotate(-3f, getWidth() / 2f, getHeight() / 2f);
            p.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            p.setTextSize(px(92) * scale);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(px(7) * scale);
            p.setColor(line);
            c.drawText("radio", px(6) * scale, px(96) * scale, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(pink);
            c.drawText("radio", px(6) * scale, px(96) * scale, p);
            p.setTextSize(px(26) * scale);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setLetterSpacing(0.3f);
            p.setColor(ink);
            c.drawText("DIAL", px(16) * scale, compact ? dp(56) : px(136), p);
            p.setLetterSpacing(0f);
            c.restore();
        }
    }

    // ====================================================================
    // screen
    // ====================================================================

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int sp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().scaledDensity);
    }

    /** Compact, touch-first layout. The TV layout below keeps its large-screen sidebar. */
    private void buildPhoneUi() {
        applyPalette();
        getWindow().setStatusBarColor(paper);
        getWindow().setNavigationBarColor(paper);
        int systemUi = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (android.os.Build.VERSION.SDK_INT >= 26 && !dark) {
            systemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUi);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(pageBackdrop());
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18), dp(12), dp(18), dp(14));
        shell.setBackground(rect(cardBg, dp(24), dp(3), ink));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Logo logo = new Logo();
        header.addView(logo, new LinearLayout.LayoutParams(dp(128), dp(58)));
        TextView theme = block("◐", px(24), ink, cardBg, pink, dp(24), dp(8),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleTheme(); }
                });
        theme.setContentDescription("Toggle dark theme");
        header.addView(theme, new LinearLayout.LayoutParams(dp(42), dp(42)));
        shell.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        navStations = block("~  Stations", sp(14), ink, pink, pink, dp(16), dp(10),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { setFavouritesView(false); }
                });
        navFavourites = block("★  Saved", sp(14), ink, cardBg, pink, dp(16), dp(10),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { setFavouritesView(true); }
                });
        nav.addView(navStations, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams savedLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        savedLp.setMargins(dp(8), 0, 0, 0);
        nav.addView(navFavourites, savedLp);
        shell.addView(nav, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(42), 0, dp(10), 0, dp(14)));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Pick a frequency.", sp(29), ink, Typeface.BOLD);
        titleStack.addView(title);
        statusText = text(status, sp(12), muted_, Typeface.NORMAL);
        titleStack.addView(statusText, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(4), 0, 0));
        titleRow.addView(titleStack, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Live cluster (mirrors the site header): bouncing equalizer bars while
        // playing + a badge with a pulsing dot. Tapping the badge jumps to live.
        LinearLayout liveRow = new LinearLayout(this);
        liveRow.setOrientation(LinearLayout.HORIZONTAL);
        liveRow.setGravity(Gravity.CENTER_VERTICAL);
        audioBars = new AudioBars();
        LinearLayout.LayoutParams barsLp = new LinearLayout.LayoutParams(dp(22), dp(16));
        barsLp.setMargins(0, 0, dp(6), 0);
        liveRow.addView(audioBars, barsLp);
        liveBadgeBox = new LinearLayout(this);
        liveBadgeBox.setOrientation(LinearLayout.HORIZONTAL);
        liveBadgeBox.setGravity(Gravity.CENTER_VERTICAL);
        liveBadgeBox.setPadding(dp(9), dp(6), dp(9), dp(6));
        liveBadgeBox.setBackground(rect(cardBg, dp(20), dp(1), line));
        liveBadgeBox.setFocusable(true);
        liveBadgeBox.setClickable(true);
        liveBadgeBox.setContentDescription("Jump to live");
        liveBadgeBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { snapToLive(); }
        });
        liveDot = new PulseDot();
        liveBadgeBox.addView(liveDot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        liveBadge = text("LIVE RADIO", sp(10), muted_, Typeface.BOLD);
        LinearLayout.LayoutParams badgeTextLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeTextLp.setMargins(dp(6), 0, 0, 0);
        liveBadgeBox.addView(liveBadge, badgeTextLp);
        liveRow.addView(liveBadgeBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        updateLiveBadge();
        titleRow.addView(liveRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(titleRow, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(14)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rect(pink, dp(22), dp(3), ink));
        LinearLayout identity = new LinearLayout(this);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(rect(yellow, dp(40), dp(2), ink));
        roundClip(badge, dp(40));
        badgeCode = text("", sp(17), INK_FIXED, Typeface.BOLD);
        badgeCode.setTypeface(Typeface.create("serif", Typeface.BOLD));
        badgeCode.setGravity(Gravity.CENTER);
        badgeCode.setMaxLines(3);
        badge.addView(badgeCode, new FrameLayout.LayoutParams(-1, -1));
        badgeLogo = new ImageView(this);
        badgeLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        badgeLogo.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgeImageLp = new FrameLayout.LayoutParams(-1, -1);
        badgeImageLp.setMargins(dp(2), dp(2), dp(2), dp(2));
        badge.addView(badgeLogo, badgeImageLp);
        addBadgeHighlight(badge, dp(2), dp(8));
        identity.addView(badge, new LinearLayout.LayoutParams(dp(76), dp(76)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        TextView eyebrow = text("NOW PLAYING", sp(10), INK_FIXED, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.12f);
        info.addView(eyebrow);
        cardName = text("Choose a station", sp(24), INK_FIXED, Typeface.BOLD);
        cardName.setSingleLine(true);
        cardName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(cardName, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, dp(3), 0, 0));
        cardDesc = text("Direct public streams", sp(14), INK_FIXED, Typeface.NORMAL);
        cardDesc.setSingleLine(true);
        cardDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(cardDesc, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, dp(3), 0, 0));
        identity.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(identity, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        TextView prev = roundButton("‹", dp(44), 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { tuneStep(-1, wantPlaying); }
        });
        playBtn = roundButton("▶", dp(54), blue, new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        TextView next = roundButton("›", dp(44), 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { tuneStep(1, wantPlaying); }
        });
        favBtn = roundButton("☆", dp(44), 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFavourite(); }
        });
        TextView[] buttons = {prev, playBtn, next, favBtn};
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(0, dp(46), i == 1 ? 1.15f : 1f);
            buttonLp.setMargins(dp(3), dp(10), dp(3), 0);
            controls.addView(buttons[i], buttonLp);
        }
        card.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));
        card.setElevation(dp(4));
        shell.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(14)));

        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        sectionTitle = text("On the dial", sp(19), ink, Typeface.BOLD);
        section.addView(sectionTitle);
        shell.addView(section, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(30), 0, 0, 0, dp(6)));

        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchField.setTextColor(ink);
        searchField.setHintTextColor(muted_);
        searchField.setHint("⌕  Search stations");
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setPadding(dp(14), 0, dp(14), 0);
        searchField.setBackground(rect(cardBg, dp(18), dp(2), ink));
        searchField.setText(searchQuery);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString().trim();
                if (directoryMode) {
                    directoryMode = false;
                    directoryResults.clear();
                    status = "Choose a station to tune in.";
                }
                refreshShown();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        searchField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchRadioBrowser(); return true;
            }
            return false;
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView find = block("⌕", sp(20), ink, yellow, yellow, dp(10), dp(4),
                new View.OnClickListener() { @Override public void onClick(View v) { searchRadioBrowser(); } });
        find.setContentDescription("Find stations online");
        TextView more = block("⋮", sp(20), ink, cardBg, pink, dp(10), dp(4), null);
        more.setContentDescription("More station options");
        more.setOnClickListener(v -> {
            int menuTheme = dark ? android.R.style.Theme_Material : android.R.style.Theme_Material_Light;
            android.view.ContextThemeWrapper themedContext =
                    new android.view.ContextThemeWrapper(MainActivity.this, menuTheme);
            android.widget.PopupMenu menu = new android.widget.PopupMenu(themedContext, more);
            menu.getMenu().add("Export station list").setOnMenuItemClickListener(item -> {
                exportStations(); return true;
            });
            menu.getMenu().add("Import station list").setOnMenuItemClickListener(item -> {
                importStations(); return true;
            });
            menu.show();
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        searchLp.setMargins(0, 0, dp(6), 0);
        actions.addView(searchField, searchLp);
        LinearLayout.LayoutParams findLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        findLp.setMargins(0, dp(3), dp(5), 0);
        actions.addView(find, findLp);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(dp(38), dp(40));
        moreLp.setMargins(0, dp(3), 0, 0);
        actions.addView(more, moreLp);
        shell.addView(actions, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), 0, 0, 0, dp(8)));

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipToPadding(false);
        chipScroll.setPadding(dp(2), dp(2), dp(2), dp(2));
        chipBox = new LinearLayout(this);
        chipBox.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chipBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(chipScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(50), 0, 0, 0, dp(6)));

        grid = new GridView(this);
        adapter = new StationAdapter();
        grid.setAdapter(adapter);
        grid.setNumColumns(1);
        grid.setVerticalSpacing(dp(6));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(rect(0x00000000, dp(16), dp(2), FOCUS));
        grid.setDrawSelectorOnTop(true);
        grid.setClipToPadding(false);
        grid.setPadding(dp(2), dp(2), dp(2), dp(6));
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                // Resolve through the adapter so a click always maps to the item
                // currently displayed, including immediately after changing filters.
                Station station = (Station) adapter.getItem(pos);
                if (directoryMode) { selectDirectoryStation(station); return; }
                tuned = station;
                if (playing == station && wantPlaying) userStop();
                else play(station);
            }
        });
        shell.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        errorText = text(error, px(20), RED, Typeface.BOLD);
        shell.addView(errorText, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, dp(4), 0, 0));

        FrameLayout.LayoutParams shellLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        shellLp.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(shell, shellLp);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);
        rebuildChips();
        refreshShown();
    }

    private void buildUi() {
        if (compact) { buildPhoneUi(); return; }
        applyPalette();
        FrameLayout root = new FrameLayout(this);
        root.setBackground(pageBackdrop());

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.HORIZONTAL);
        frame.setBackground(rect(cardBg, px(28), px(3), ink));
        roundClip(frame, px(28));

        // ---------- sidebar ----------
        LinearLayout aside = new LinearLayout(this);
        aside.setOrientation(LinearLayout.VERTICAL);
        aside.setBackgroundColor(asideBg);
        aside.setPadding(px(32), px(34), px(32), px(30));
        aside.addView(new Logo(), lp(px(340), px(150), 0, 0, 0, px(20)));

        navStations = navButton("~", "Stations", new View.OnClickListener() {
            @Override public void onClick(View v) { setFavouritesView(false); }
        });
        navFavourites = navButton("\u2605", "Favourites", new View.OnClickListener() {
            @Override public void onClick(View v) { setFavouritesView(true); }
        });
        aside.addView(navStations, lp(ViewGroup.LayoutParams.MATCH_PARENT, px(74), 0, 0, 0, px(10)));
        aside.addView(navFavourites, lp(ViewGroup.LayoutParams.MATCH_PARENT, px(74), 0, 0, 0, px(10)));
        aside.addView(navButton("\u2315", "Search", new View.OnClickListener() {
            @Override public void onClick(View v) { openSearchDialog(); }
        }), lp(ViewGroup.LayoutParams.MATCH_PARENT, px(74), 0, 0, 0, px(10)));

        LinearLayout tip = new LinearLayout(this);
        tip.setOrientation(LinearLayout.VERTICAL);
        tip.setPadding(px(22), px(20), px(22), px(20));
        tip.setBackground(rect(yellow, px(20), px(2), ink));
        tip.addView(text("One click, no website maze.", px(22), INK_FIXED, Typeface.BOLD));
        tip.addView(text("Direct public streams, played straight on your TV.", px(20),
                INK_FIXED, Typeface.NORMAL), lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(6), 0, 0));
        aside.addView(tip, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(26), 0, 0));

        View fill = new View(this);
        aside.addView(fill, new LinearLayout.LayoutParams(1, 0, 1f));
        aside.addView(block("\u25D0 Toggle Theme", px(22), ink, cardBg, cardBg, px(18), px(10),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleTheme(); }
                }), lp(ViewGroup.LayoutParams.MATCH_PARENT, px(60), 0, 0, 0, 0));

        // ---------- main ----------
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(px(48), px(40), px(52), px(24));

        LinearLayout topline = new LinearLayout(this);
        topline.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Pick a frequency.", px(56), ink, Typeface.BOLD));
        statusText = text(status, px(22), muted_, Typeface.NORMAL);
        titles.addView(statusText, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(10), 0, 0));
        topline.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView live = text("\u25CF LIVE RADIO", px(20), ink, Typeface.BOLD);
        live.setGravity(Gravity.CENTER);
        live.setPadding(px(20), px(10), px(20), px(10));
        live.setBackground(rect(cardBg, px(40), px(2), ink));
        live.setFocusable(true);
        live.setClickable(true);
        live.setContentDescription("Jump to live");
        live.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { snapToLive(); }
        });
        topline.addView(live, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        main.addView(topline);

        // now-playing card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(px(26), px(24), px(26), px(24));
        card.setBackground(rect(pink, px(23), px(3), ink));

        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(rect(yellow, px(55), px(3), ink));
        roundClip(badge, px(55));
        badgeCode = text("", px(27), INK_FIXED, Typeface.BOLD);
        badgeCode.setTypeface(Typeface.create("serif", Typeface.BOLD));
        badgeCode.setGravity(Gravity.CENTER);
        badgeCode.setMaxLines(3);
        badge.addView(badgeCode, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        badgeLogo = new ImageView(this);
        badgeLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        badgeLogo.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgeImageLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        badgeImageLp.setMargins(px(3), px(3), px(3), px(3));
        badge.addView(badgeLogo, badgeImageLp);
        addBadgeHighlight(badge, px(3), px(8));
        card.addView(badge, lp(px(110), px(110), 0, 0, px(24), 0));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("NOW TUNED TO", px(18), INK_FIXED, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.12f);
        info.addView(eyebrow);
        cardName = text("", px(46), INK_FIXED, Typeface.BOLD);
        cardName.setSingleLine(true);
        cardName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(cardName, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(4), 0, 0));
        cardDesc = text("", px(22), INK_FIXED, Typeface.NORMAL);
        cardDesc.setSingleLine(true);
        cardDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(cardDesc, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(6), 0, 0));
        card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView prev = roundButton("<", px(64), 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { tuneStep(-1, wantPlaying); }
        });
        playBtn = roundButton("Play", px(130), yellow, new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        TextView next = roundButton(">", px(64), 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { tuneStep(1, wantPlaying); }
        });
        favBtn = roundButton("\u2606", px(64), 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFavourite(); }
        });
        TextView[] controls = {prev, playBtn, next, favBtn};
        for (int i = 0; i < controls.length; i++) {
            card.addView(controls[i], lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(64),
                    i == 0 ? px(20) : 0, 0, i < controls.length - 1 ? px(10) : 0, 0));
        }
        LinearLayout.LayoutParams cardLp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0);
        card.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(shadowed(card, px(23), px(6)), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(28), 0, px(22)));

        LinearLayout sectionRow = new LinearLayout(this);
        sectionRow.setOrientation(LinearLayout.HORIZONTAL);
        sectionRow.setGravity(Gravity.CENTER_VERTICAL);
        sectionRow.addView(text("On the dial", px(32), ink, Typeface.BOLD));
        TextView hint = text("Up / Down  tune    OK  play or stop    T  theme",
                px(20), muted_, Typeface.NORMAL);
        hint.setGravity(Gravity.RIGHT);
        sectionRow.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        main.addView(sectionRow, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, px(14)));

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipToPadding(false);
        chipScroll.setPadding(px(6), px(6), px(6), px(6));
        chipBox = new LinearLayout(this);
        chipBox.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chipBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(chipScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, px(-6), 0, px(-6), px(8)));

        adapter = new StationAdapter();
        grid = new GridView(this);
        grid.setAdapter(adapter);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(px(18));
        grid.setVerticalSpacing(px(16));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false);
        grid.setClipToPadding(false);
        grid.setPadding(px(4), px(4), px(4), px(4));
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                Station s = (Station) adapter.getItem(pos);
                tuned = s;
                if (playing == s && wantPlaying) userStop();
                else play(s);
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < shown.size() && tuned != shown.get(pos)) {
                    play(shown.get(pos)); // Up / Down tunes and plays on TV
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        main.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        errorText = text(error, px(22), RED, Typeface.BOLD);
        main.addView(errorText, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(8), 0, 0));
        main.addView(text("Streams are supplied by their broadcasters. They may include broadcast advertising.",
                px(18), muted_, Typeface.NORMAL), lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(6), 0, 0));

        View divider = new View(this);
        divider.setBackgroundColor(ink);
        frame.addView(aside, new LinearLayout.LayoutParams(px(400), ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(divider, new LinearLayout.LayoutParams(px(3), ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        frame.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        fp.setMargins(px(72), px(56), px(72), px(60));
        root.addView(shadowed(frame, px(28), px(9)), fp);

        setContentView(root);
        rebuildChips();
        refreshShown();
        if (grid != null) grid.requestFocus();
    }

    /** TV search: same filter as the phone field, plus Radio Browser lookup. */
    private void openSearchDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(40), px(32), px(40), px(28));
        box.setBackground(rect(cardBg, px(24), px(2), ink));
        box.addView(text("Search stations", px(32), ink, Typeface.BOLD));
        final android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24));
        field.setTextColor(ink);
        field.setHintTextColor(muted_);
        field.setHint("Type a name, genre or place");
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.setBackground(rect(paper, px(16), px(2), ink));
        field.setPadding(px(20), px(14), px(20), px(14));
        field.setText(searchQuery);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = px(16);
        box.addView(field, fp);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString().trim();
                if (directoryMode) {
                    directoryMode = false;
                    directoryResults.clear();
                    status = "Choose a station to tune in.";
                }
                refreshShown();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        field.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchRadioBrowser();
                dialog.dismiss();
                return true;
            }
            return false;
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = px(18);
        box.addView(row, rp);
        TextView find = block("Search online", px(24), INK_FIXED, yellow, yellow, px(16), px(16),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        searchRadioBrowser();
                        dialog.dismiss();
                    }
                });
        row.addView(find, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = block("Clear", px(24), ink, cardBg, pink, px(16), px(16),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        searchQuery = "";
                        directoryMode = false;
                        directoryResults.clear();
                        refreshShown();
                        dialog.dismiss();
                    }
                });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = px(12);
        row.addView(clear, cp);
        dialog.setContentView(box, new ViewGroup.LayoutParams(px(760), ViewGroup.LayoutParams.WRAP_CONTENT));
        android.view.Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        field.requestFocus();
    }

    private TextView navButton(String symbol, String label, View.OnClickListener click) {
        TextView t = text(symbol + "   " + label, px(28), ink, Typeface.NORMAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(22), 0, px(22), 0);
        t.setBackground(bordered(0x00000000, pink, px(20)));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }

    // ====================================================================
    // filter chips
    // ====================================================================

    private List<String> groupList() {
        List<String> groups = new ArrayList<String>();
        for (Station s : stations) if (!groups.contains(s.group)) groups.add(s.group);
        return groups;
    }

    private TextView chip(String label, final String group, View.OnClickListener click) {
        TextView c = block(label, compact ? sp(12) : px(22), ink, cardBg, pink, px(30), compact ? dp(14) : px(24), click);
        c.setTag(group);
        chipBox.addView(c, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                compact ? dp(40) : px(56), 0, 0, compact ? dp(8) : px(12), 0));
        return c;
    }

    private void rebuildChips() {
        chipBox.removeAllViews();
        chips.clear();
        View.OnClickListener groupClick = new View.OnClickListener() {
            @Override public void onClick(View v) {
                groupFilter = (String) v.getTag();
                if (groupFilter.equals("all")) favouritesView = false; // "All" means the whole dial
                refreshShown();
            }
        };
        chips.add(chip("", "all", groupClick));
        for (String g : groupList()) chips.add(chip("", g, groupClick));
        deadChip = chip("", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showDead = !showDead;
                refreshShown();
            }
        });
        chip("Refresh list", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                status = "Refreshing the list\u2026";
                refreshCard();
                fetchStations(true);
            }
        });
    }

    private void updateChips() {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (Station s : stations) {
            Integer n = counts.get(s.group);
            counts.put(s.group, n == null ? 1 : n + 1);
        }
        for (TextView c : chips) {
            String g = (String) c.getTag();
            if (g.equals("all")) c.setText("All " + stations.size());
            else c.setText(g + " " + (counts.get(g) == null ? 0 : counts.get(g)));
            c.setSelected(g.equals(groupFilter));
        }
        int deadCount = 0;
        for (Station s : stations) if (isDead(s)) deadCount++;
        deadChip.setVisibility(deadCount == 0 && !showDead ? View.GONE : View.VISIBLE);
        deadChip.setText((showDead ? "Hide" : "Show") + " offline " + deadCount);
        deadChip.setSelected(showDead);
    }

    // ====================================================================
    // state -> views
    // ====================================================================

    private void refreshShown() {
        directoryMode = false;
        directoryResults.clear();
        shown.clear();
        for (Station s : stations) {
            if (favouritesView && !favourites.contains(s.name)) continue;
            if (!groupFilter.equals("all") && !s.group.equals(groupFilter)) continue;
            if (!searchQuery.isEmpty() && !(s.name + " " + s.code + " " + s.description + " " + s.group)
                    .toLowerCase(java.util.Locale.ROOT).contains(searchQuery.toLowerCase(java.util.Locale.ROOT))) continue;
            if (!showDead && isDead(s)) continue;
            shown.add(s);
        }
        if (!shown.isEmpty() && (tuned == null || !shown.contains(tuned))) tuned = shown.get(0);
        navStations.setSelected(!favouritesView);
        navFavourites.setSelected(favouritesView);
        updateChips();
        if (sectionTitle != null) sectionTitle.setText("On the dial");
        adapter.notifyDataSetChanged();
        if (!shown.isEmpty() && grid != null) grid.setSelection(shown.indexOf(tuned));
        refreshCard();
    }

    private void refreshCard() {
        boolean has = !shown.isEmpty() && tuned != null;
        cardName.setText(has ? tuned.name : "No station");
        cardDesc.setText(has ? tuned.description
                : (favouritesView ? "Nothing here yet \u2014 press \u2606 on a station." : ""));
        statusText.setText(status);
        errorText.setText(error);
        playBtn.setText(has && playing == tuned && player != null && player.isPlaying() ? "Ⅱ" : "▶");
        favBtn.setText(has && favourites.contains(tuned.name) ? "\u2605" : "\u2606");
        updateLiveBadge();
        showBadge(has ? tuned : null);
    }

    private void updateLiveBadge() {
        if (liveBadge == null) return;
        boolean active = player != null && player.isPlaying();
        liveBadge.setText("LIVE RADIO");
        liveBadge.setTextColor(muted_);
        liveBadgeBox.setBackground(rect(cardBg, dp(20), dp(1), line));
        if (liveDot != null) liveDot.setColor(active ? 0xFF168653 : 0xFFE54555);
        if (audioBars != null) audioBars.setActive(active);
    }

    /** The site's pulsing LIVE dot: 1.4s loop, shrinking to 65% and fading to 45%. */
    private class PulseDot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.animation.ValueAnimator pulse;
        private float fraction;
        PulseDot() {
            super(MainActivity.this);
            paint.setColor(0xFFE54555);
        }
        void setColor(int color) { paint.setColor(color); invalidate(); }
        void pause() { if (pulse != null) pulse.pause(); }
        void resume() { if (pulse != null) pulse.resume(); }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (pulse == null) {
                pulse = android.animation.ValueAnimator.ofFloat(0f, 1f);
                pulse.setDuration(700);
                pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                pulse.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        fraction = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
            }
            pulse.start();
        }
        @Override protected void onDetachedFromWindow() {
            if (pulse != null) pulse.cancel();
            super.onDetachedFromWindow();
        }
        @Override protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f * (1f - 0.35f * fraction);
            paint.setAlpha(Math.round(255 * (1f - 0.55f * fraction)));
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, paint);
        }
    }

    /** The site's equalizer bars: four bouncing bars, visible only while playing. */
    private class AudioBars extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.animation.ValueAnimator bounce;
        private float fraction;
        private boolean active;
        AudioBars() {
            super(MainActivity.this);
            setVisibility(View.GONE);
        }
        void setActive(boolean on) {
            active = on;
            setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) {
                if (bounce == null) {
                    bounce = android.animation.ValueAnimator.ofFloat(0f, 1f);
                    bounce.setDuration(800);
                    bounce.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    bounce.setRepeatMode(android.animation.ValueAnimator.RESTART);
                    bounce.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                            fraction = (Float) a.getAnimatedValue();
                            invalidate();
                        }
                    });
                }
                if (!bounce.isStarted() || bounce.isPaused()) bounce.start();
            } else if (bounce != null) {
                bounce.cancel();
            }
        }
        void pause() { if (bounce != null && bounce.isRunning()) bounce.pause(); }
        void resume() { if (active && bounce != null && bounce.isPaused()) bounce.resume(); }
        private float wave(float phase) {
            float t = (fraction + phase) % 1f;
            return t < 0.5f ? t * 2f : 2f - t * 2f;   // triangle wave, like alternate
        }
        @Override protected void onDraw(Canvas c) {
            paint.setColor(ink);
            float[] phases = {0.1f, 0.3f, 0.2f, 0.4f};
            int n = 4;
            float gap = getWidth() / (n * 2f + n - 1);
            float barW = gap * 2f;
            float minH = dp(4), maxH = getHeight();
            for (int i = 0; i < n; i++) {
                float h = minH + (maxH - minH) * wave(phases[i]);
                float left = i * (barW + gap);
                c.drawRoundRect(left, getHeight() - h, left + barW, getHeight(),
                        dp(2), dp(2), paint);
            }
        }
    }

    private void showBadge(final Station s) {
        badgeLogo.setVisibility(View.GONE);
        badgeCode.setVisibility(View.VISIBLE);
        if (s == null) { badgeCode.setText(""); return; }
        String code = s.code == null ? s.name : s.code;
        boolean longCode = code.replace(" ", "").length() > 5;
        badgeCode.setText(code.replaceFirst(" ", "\n"));
        badgeCode.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                compact ? sp(longCode ? 14 : 19) : px(longCode ? 14 : 27));
        int codeInset = longCode ? (compact ? dp(14) : px(14)) : 0;
        badgeCode.setPadding(codeInset, 0, codeInset, 0);
        if (s.logo.isEmpty()) return;
        Bitmap cached = logos.get(s.logo);
        if (cached != null) {
            badgeLogo.setImageBitmap(cached);
            badgeLogo.setVisibility(View.VISIBLE);
            return;
        }
        final String url = s.logo;
        io.execute(new Runnable() {
            @Override public void run() {
                byte[] data = download(url);
                if (data == null) return;
                final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp == null) return;   // e.g. SVG: keep the code badge
                logos.put(url, bmp);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (tuned == s) {
                            badgeLogo.setImageBitmap(bmp);
                            badgeLogo.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    private void setFavouritesView(boolean on) {
        favouritesView = on;
        if (on) groupFilter = "all";
        refreshShown();
    }

    private void toggleTheme() {
        dark = !dark;
        prefs.edit().putBoolean("dark", dark).apply();
        buildUi();
    }

    private void toggleMute() {
        muted = !muted;
        if (player != null) player.setVolume(muted ? 0f : 1f);
        refreshCard();
    }

    private void toggleFavourite() {
        if (shown.isEmpty() || tuned == null) return;
        if (!favourites.remove(tuned.name)) favourites.add(tuned.name);
        prefs.edit().putStringSet("fav", new HashSet<String>(favourites)).apply();
        refreshShown();
    }

    private void togglePlay() {
        if (shown.isEmpty() || tuned == null) return;
        if (playing == tuned && wantPlaying) userStop();
        else play(tuned);
    }

    /** Jump to the live edge. Progressive Icecast streams resume from a stale
     *  buffer after a background pause, so a tap reconnects to live. HLS live
     *  seeks within the window when possible. */
    private void snapToLive() {
        if (playing == null || !wantPlaying) {
            if (tuned != null) play(tuned);
            return;
        }
        if (player != null && player.getCurrentMediaItem() != null
                && player.isCurrentMediaItemLive() && player.isCurrentMediaItemSeekable()) {
            player.seekToDefaultPosition();
            status = "Jumped to live — " + playing.name + ".";
            refreshCard();
        } else {
            status = "Catching up to live — " + playing.name + "…";
            startPlay(playing);
        }
    }

    private void tuneStep(int dir, boolean thenPlay) {
        if (shown.isEmpty()) return;
        int i = shown.indexOf(tuned);
        tuned = shown.get(((i < 0 ? 0 : i) + dir + shown.size()) % shown.size());
        if (grid != null) grid.setSelection(shown.indexOf(tuned));
        refreshCard();
        play(tuned); // the shown station is always the playing station
    }

    // ====================================================================
    // station grid
    // ====================================================================

    private class StationAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleStations().size(); }
        @Override public Object getItem(int i) { return visibleStations().get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Station s = visibleStations().get(i);
            boolean isPlaying = (s == playing && wantPlaying);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(compact ? dp(14) : px(22), 0, compact ? dp(14) : px(20), 0);
            row.setBackground(rect(isPlaying ? yellow : cardBg, compact ? dp(16) : px(20), dp(2), ink));
            row.setAlpha(isDead(s) ? 0.5f : 1f);
            row.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, compact ? dp(64) : px(112)));

            if (s.logo != null && !s.logo.isEmpty()) {
                FrameLayout thumb = new FrameLayout(MainActivity.this);
                int thumbSize = compact ? dp(34) : px(44);
                thumb.setVisibility(View.GONE);
                thumb.setBackground(rect(yellow, compact ? dp(12) : px(12), dp(2), line));
                roundClip(thumb, compact ? dp(12) : px(12));
                ImageView thumbnail = new ImageView(MainActivity.this);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                FrameLayout.LayoutParams imageLp = new FrameLayout.LayoutParams(-1, -1);
                imageLp.setMargins(dp(2), dp(2), dp(2), dp(2));
                thumb.addView(thumbnail, imageLp);
                bindStationLogo(thumbnail, s);
                LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(thumbSize, thumbSize);
                thumbLp.setMargins(0, 0, compact ? dp(10) : px(14), 0);
                row.addView(thumb, thumbLp);
            }

            LinearLayout col = new LinearLayout(MainActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(s.name, compact ? sp(16) : px(28), isPlaying ? INK_FIXED : ink, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(name);
            TextView desc = text(s.description, compact ? sp(12) : px(19), isPlaying ? INK_FIXED : muted_, Typeface.NORMAL);
            desc.setSingleLine(true);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(desc, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, compact ? dp(3) : px(6), 0, 0));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (favourites.contains(s.name)) {
                row.addView(text("\u2605", compact ? sp(18) : px(26), isPlaying ? INK_FIXED : pinkDeep(), Typeface.NORMAL),
                        lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                                px(10), 0, 0, 0));
            }
            if (isPlaying) {
                row.addView(text("\u25B6", compact ? sp(15) : px(24), INK_FIXED, Typeface.NORMAL),
                        lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                                px(10), 0, 0, 0));
            }
            return row;
        }
    }

    private void bindStationLogo(final ImageView image, final Station station) {
        image.setTag(station.logo);
        image.setVisibility(View.GONE);
        if (station.logo == null || station.logo.isEmpty()) return;
        Bitmap cached = logos.get(station.logo);
        if (cached != null) {
            image.setImageBitmap(cached);
            image.setVisibility(View.VISIBLE);
            ((View) image.getParent()).setVisibility(View.VISIBLE);
            return;
        }
        synchronized (loadingLogos) {
            if (!loadingLogos.add(station.logo)) return;
        }
        final String logoUrl = station.logo;
        io.execute(new Runnable() {
            @Override public void run() {
                byte[] data = download(logoUrl);
                final Bitmap bitmap = data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap != null) logos.put(logoUrl, bitmap);
                synchronized (loadingLogos) { loadingLogos.remove(logoUrl); }
                if (bitmap == null) return;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (logoUrl.equals(image.getTag())) {
                            image.setImageBitmap(bitmap);
                            image.setVisibility(View.VISIBLE);
                            ((View) image.getParent()).setVisibility(View.VISIBLE);
                        }
                        if (adapter != null) adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    private List<Station> visibleStations() {
        return directoryMode ? directoryResults : shown;
    }

    private int pinkDeep() { return ink; }

    // ====================================================================
    // station list: download, parse, cache
    // ====================================================================

    private byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "RadioDial/1.0 (radio-dial.pages.dev)");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private List<Station> parseStations(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            List<Station> out = new ArrayList<Station>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Station s = new Station();
                s.name = o.optString("name", "").trim();
                s.url = o.optString("url", "").trim();
                if (s.name.isEmpty() || !s.url.startsWith("https://")) continue;
                s.code = o.optString("code", s.name);
                s.description = o.optString("description", "");
                s.group = o.optString("group", "").trim();
                if (s.group.isEmpty()) s.group = "International";
                s.country = o.optString("country", "");
                s.logo = o.optString("logo", "");
                out.add(s);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void loadLocalStations() {
        localStations.clear();
        List<Station> saved = parseStations(prefs.getString("local_stations", "[]"));
        if (saved != null) localStations.addAll(saved);
    }

    private void appendLocalStations() {
        for (Station extra : localStations) if (!containsUrl(stations, extra.url)) stations.add(extra);
    }

    private boolean containsUrl(List<Station> list, String url) {
        for (Station s : list) if (s.url.equals(url)) return true;
        return false;
    }

    private JSONArray stationJson(List<Station> list, boolean onlyPersonal) {
        JSONArray array = new JSONArray();
        for (Station s : list) {
            if (onlyPersonal && !favourites.contains(s.name) && !containsUrl(localStations, s.url)) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("name", s.name); item.put("code", s.code);
                item.put("description", s.description); item.put("url", s.url);
                item.put("group", s.group); item.put("country", s.country); item.put("logo", s.logo);
                item.put("favourite", favourites.contains(s.name)); array.put(item);
            } catch (Exception ignored) { }
        }
        return array;
    }

    private void saveLocalStations() {
        prefs.edit().putString("local_stations", stationJson(localStations, false).toString()).apply();
    }

    private void refreshAutoCatalog() {
        startService(new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_RELOAD_CATALOG));
    }

    private void searchRadioBrowser() {
        final String query = searchField == null ? "" : searchField.getText().toString().trim();
        if (query.isEmpty()) { status = "Type a station, place or genre first."; refreshCard(); return; }
        status = "Searching Radio Browser…"; error = ""; refreshCard();
        io.execute(new Runnable() {
            @Override public void run() {
                List<Station> found = null;
                try {
                    String encoded = URLEncoder.encode(query, "UTF-8");
                    for (String host : RADIO_BROWSER_HOSTS) {
                        byte[] body = download("https://" + host + "/json/stations/search?name=" + encoded
                                + "&limit=50&order=clickcount&reverse=true&hidebroken=true");
                        if (body == null) continue;
                        JSONArray rows = new JSONArray(new String(body, "UTF-8"));
                        List<Station> parsed = new ArrayList<Station>();
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject row = rows.optJSONObject(i);
                            if (row == null) continue;
                            String url = row.optString("url_resolved", row.optString("url", "")).trim();
                            String name = row.optString("name", "").trim();
                            if (name.isEmpty() || !url.startsWith("https://")) continue;
                            Station station = new Station();
                            station.name = name; station.code = name; station.url = url;
                            station.description = row.optString("language", "Internet radio").trim();
                            String tags = row.optString("tags", "").trim();
                            station.group = tags.isEmpty() ? row.optString("country", "International") : tags.split(",")[0].trim();
                            if (station.group.isEmpty()) station.group = "International";
                            station.country = row.optString("countrycode", "");
                            station.logo = row.optString("favicon", "").trim();
                            if (!station.logo.startsWith("https://")) station.logo = "";
                            parsed.add(station);
                        }
                        found = parsed; break;
                    }
                } catch (Exception ignored) { }
                final List<Station> results = found;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        if (results == null) {
                            status = "Radio Browser is unavailable. Try again later.";
                            error = "Search failed; your saved catalogue is unchanged."; refreshCard(); return;
                        }
                        directoryResults.clear(); directoryResults.addAll(results);
                        directoryMode = true; status = results.size() + " results · tap one to save it.";
                        sectionTitle.setText("Search results"); statusText.setText(status);
                        adapter.notifyDataSetChanged();
                        if (grid != null) grid.setSelection(0);
                    }
                });
            }
        });
    }

    private void selectDirectoryStation(Station station) {
        if (!containsUrl(localStations, station.url)) localStations.add(station);
        if (!containsUrl(stations, station.url)) stations.add(station);
        saveLocalStations(); refreshAutoCatalog(); directoryMode = false; directoryResults.clear(); tuned = station;
        status = "Saved to this device."; error = ""; refreshShown(); play(station);
    }

    private void exportStations() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "radio-dial-stations.json");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void importStations() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("Can't open destination");
                JSONObject backup = new JSONObject(); backup.put("format", "radio-dial-stations-v1");
                backup.put("stations", stationJson(stations, true)); out.write(backup.toString(2).getBytes("UTF-8"));
                android.widget.Toast.makeText(this, "Station backup saved.", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "Couldn't save station backup.", android.widget.Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_IMPORT) {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new java.io.IOException("Can't open backup");
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                JSONObject backup = new JSONObject(out.toString("UTF-8"));
                JSONArray array = backup.optJSONArray("stations");
                if (array == null || !"radio-dial-stations-v1".equals(backup.optString("format")))
                    throw new IllegalArgumentException("Unsupported backup format");
                List<Station> restored = parseStations(array.toString());
                if (restored == null) throw new IllegalArgumentException("Invalid station list");
                Set<String> oldLocal = new HashSet<String>();
                for (Station s : localStations) oldLocal.add(s.url);
                for (int i = stations.size() - 1; i >= 0; i--) if (oldLocal.contains(stations.get(i).url)) stations.remove(i);
                localStations.clear(); favourites.clear();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject row = array.optJSONObject(i);
                    if (row != null && row.optBoolean("favourite")) favourites.add(row.optString("name"));
                }
                for (Station s : restored) if (!containsUrl(stations, s.url)) localStations.add(s);
                appendLocalStations(); saveLocalStations();
                prefs.edit().putStringSet("fav", new HashSet<String>(favourites)).apply();
                refreshAutoCatalog();
                refreshShown();
                android.widget.Toast.makeText(this, "Station backup restored.", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "Couldn't restore that station backup.", android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    private void fetchStations(final boolean manual) {
        io.execute(new Runnable() {
            @Override public void run() {
                byte[] data = download(STATIONS_URL + (manual ? "?v=" + System.currentTimeMillis() : ""));
                String json = null;
                List<Station> list = null;
                if (data != null) {
                    try { json = new String(data, "UTF-8"); } catch (Exception ignored) { }
                    if (json != null) list = parseStations(json);
                }
                final List<Station> fresh = list;
                final String freshJson = json;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (fresh != null && !fresh.isEmpty()) {
                            prefs.edit().putString("stations_cache", freshJson)
                                    .putString("stations_cache_for", bundledKey).apply();
                            applyStations(fresh);
                            refreshAutoCatalog();
                            if (manual) status = "List refreshed: " + fresh.size() + " stations.";
                            else if (!wantPlaying) status = "Choose a station to tune in.";
                            error = "";
                        } else if (stations.isEmpty()) {
                            error = "No stations yet. Fill radio/res/raw/stations.json, or check the connection and Refresh list.";
                        } else if (manual) {
                            error = "Couldn't refresh the list; keeping the saved one.";
                        }
                        refreshCard();
                    }
                });
            }
        });
    }

    /** Swap in a new list, keeping the tuned / playing stations by stream URL. */
    private void applyStations(List<Station> list) {
        String tunedUrl = tuned != null ? tuned.url : null;
        String playingUrl = playing != null ? playing.url : null;
        stations.clear();
        stations.addAll(list);
        appendLocalStations();
        tuned = null;
        for (Station s : stations) {
            if (s.url.equals(tunedUrl)) tuned = s;
            if (s.url.equals(playingUrl)) playing = s;
        }
        if (groupFilter != null && !groupFilter.equals("all") && !groupList().contains(groupFilter)) {
            groupFilter = "all";
        }
        rebuildChips();
        refreshShown();
    }

    // ====================================================================
    // dead-stream memory (a failed stream is hidden for a week)
    // ====================================================================

    private void loadDead() {
        try {
            JSONObject o = new JSONObject(prefs.getString("dead", "{}"));
            long now = System.currentTimeMillis();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                long ts = o.optLong(k);
                if (now - ts < DEAD_TTL) dead.put(k, ts);
            }
        } catch (Exception ignored) { }
    }

    private void saveDead() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Long> e : dead.entrySet()) o.put(e.getKey(), e.getValue().longValue());
        } catch (Exception ignored) { }
        prefs.edit().putString("dead", o.toString()).apply();
    }

    private boolean isDead(Station s) {
        Long ts = dead.get(s.url);
        return ts != null && System.currentTimeMillis() - ts < DEAD_TTL;
    }

    private void markDead(Station s) {
        dead.put(s.url, System.currentTimeMillis());
        saveDead();
    }

    private void markLive(Station s) {
        if (dead.remove(s.url) != null) saveDead();
    }

    // ====================================================================
    // playback: retry twice, then mark dead and move on (like the site)
    // ====================================================================

    private void play(Station s) {
        failCount = 0;
        skipBudget = 0;
        startPlay(s);
    }

    private void startPlay(final Station s) {
        releasePlayer();
        playing = s;
        tuned = s;
        wantPlaying = true;
        final int my = ++token;
        status = "Tuning " + s.name + "\u2026";
        error = "";
        if (adapter != null) adapter.notifyDataSetChanged();
        if (cardName != null) refreshCard();
        if (player == null) {
            pendingStart = s;
            return;
        }
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(s.name)
                .setSubtitle(s.description)
                .setArtist(s.group)
                .setAlbumTitle("Radio Dial · " + s.group)
                .build();
        MediaItem item = new MediaItem.Builder()
                .setMediaId("station:" + s.url)
                .setUri(s.url)
                .setMediaMetadata(metadata)
                .build();
        player.setVolume(muted ? 0f : 1f);
        player.setMediaItem(item);
        player.prepare();
        player.play();
    }

    private void releasePlayer() {
        pendingStart = null;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
    }

    private void userStop() {
        token++;
        wantPlaying = false;
        releasePlayer();
        if (playing != null) {
            playing = null;
            status = "Stopped. Choose a station to tune in.";
            if (adapter != null) adapter.notifyDataSetChanged();
            if (cardName != null) refreshCard();
        }
    }

    private void streamFailed(String message, int my) {
        if (my != token || !wantPlaying || playing == null) return;
        releasePlayer();
        final Station s = playing;
        if (failCount >= 2) {
            failCount = 0;
            failStation(s, message);
            return;
        }
        failCount++;
        status = "Signal dropped \u2014 retrying " + s.name + "\u2026";
        refreshCard();
        final int next = ++token;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (next == token && wantPlaying && playing == s) startPlay(s);
            }
        }, failCount == 1 ? 2000 : 5000);
    }

    private void failStation(Station s, String message) {
        int i = shown.indexOf(s);
        Station following = null;
        if (shown.size() > 1) following = shown.get((Math.max(i, 0) + 1) % shown.size());
        markDead(s);
        error = message;
        if (skipBudget < 4 && following != null && following != s) {
            skipBudget++;
            status = "Skipping to the next station\u2026";
            final Station go = following;
            final int my = ++token;
            refreshShown();
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (my == token && wantPlaying) startPlay(go);
                }
            }, 1200);
        } else {
            skipBudget = 0;
            error = message + " Several streams in a row failed \u2014 check your connection.";
            token++;
            wantPlaying = false;
            playing = null;
            status = "Stopped.";
            refreshShown();
        }
    }
}
