package home.custom.radiodial;

import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionError;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared background player and browsable station library for the app and Android Auto. */
@UnstableApi
public final class PlaybackService extends MediaLibraryService {
    private static final String ROOT = "root";
    private static final String ALL = "group:all";
    private static final String FAVOURITES = "group:favourites";
    private static final String PREFS = "radiodial";
    private static final String FAV_KEY = "fav";
    static final String ACTION_RELOAD_CATALOG = "home.custom.radiodial.RELOAD_CATALOG";

    private final List<Station> stations = new ArrayList<>();
    private MediaLibrarySession session;
    private ExoPlayer player;

    private static final class Station {
        final String name;
        final String code;
        final String description;
        final String url;
        final String group;
        final String logo;

        Station(JSONObject json) {
            name = json.optString("name", "").trim();
            code = json.optString("code", name).trim();
            description = json.optString("description", "").trim();
            url = json.optString("url", "").trim();
            group = json.optString("group", "Other").trim();
            logo = json.optString("logo", "").trim();
        }

        String id() { return "station:" + url; }
        boolean matches(String q) {
            String needle = q.toLowerCase(Locale.ROOT);
            return name.toLowerCase(Locale.ROOT).contains(needle)
                    || code.toLowerCase(Locale.ROOT).contains(needle)
                    || description.toLowerCase(Locale.ROOT).contains(needle)
                    || group.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loadStations();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        player.setHandleAudioBecomingNoisy(true);
        session = new MediaLibrarySession.Builder(this, player, new LibraryCallback()).build();
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int result = super.onStartCommand(intent, flags, startId);
        if (intent != null && ACTION_RELOAD_CATALOG.equals(intent.getAction())) {
            loadStations();
            if (session != null) {
                session.notifyChildrenChanged(ROOT, rootChildren().size(), null);
                session.notifyChildrenChanged(ALL, stations.size(), null);
                session.notifyChildrenChanged(FAVOURITES, children(FAVOURITES).size(), null);
                for (String group : stationGroups()) {
                    String id = "group:" + group;
                    session.notifyChildrenChanged(id, children(id).size(), null);
                }
            }
        }
        return result;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.getPlayer().release();
            session.release();
            session = null;
        }
        player = null;
        super.onDestroy();
    }

    private void loadStations() {
        stations.clear();
        String bundled = readBundled();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String cached = prefs.getString("stations_cache", null);
        String source = cached != null && String.valueOf(bundled.hashCode()).equals(
                prefs.getString("stations_cache_for", "")) ? cached : bundled;
        appendStations(source);
        appendStations(prefs.getString("local_stations", "[]"));
    }

    private String readBundled() {
        try (InputStream input = getResources().openRawResource(R.raw.stations);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } catch (Exception ignored) { return "[]"; }
    }

    private void appendStations(String json) {
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Station station = new Station(item);
                if (station.name.isEmpty() || !station.url.startsWith("https://")) continue;
                boolean exists = false;
                for (Station saved : stations) if (saved.url.equals(station.url)) { exists = true; break; }
                if (!exists) stations.add(station);
            }
        } catch (Exception ignored) { }
    }

    private Set<String> stationGroups() {
        Set<String> groups = new LinkedHashSet<>();
        for (Station station : stations) groups.add(station.group);
        return groups;
    }

    private MediaItem rootItem() {
        return browseItem(ROOT, "Radio Dial", "Your stations", null);
    }

    private MediaItem browseItem(String id, String title, String subtitle, Uri artwork) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false);
        if (artwork != null) metadata.setArtworkUri(artwork);
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata.build()).build();
    }

    private MediaItem stationItem(Station station) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setTitle(station.name)
                .setSubtitle(station.description)
                .setArtist(station.group)
                .setAlbumTitle("Radio Dial · " + station.group)
                .setIsBrowsable(false)
                .setIsPlayable(true);
        if (station.logo.startsWith("https://")) metadata.setArtworkUri(Uri.parse(station.logo));
        return new MediaItem.Builder()
                .setMediaId(station.id())
                .setUri(station.url)
                .setMediaMetadata(metadata.build())
                .build();
    }

    private MediaItem findItem(String id) {
        if (ROOT.equals(id)) return rootItem();
        if (ALL.equals(id)) return browseItem(ALL, "All stations", stations.size() + " stations", null);
        if (FAVOURITES.equals(id)) return browseItem(FAVOURITES, "Favourites", "Saved stations", null);
        if (id.startsWith("group:")) {
            String group = id.substring("group:".length());
            for (Station station : stations) {
                if (station.group.equals(group)) return browseItem(id, group, "Station collection", null);
            }
        }
        for (Station station : stations) if (station.id().equals(id)) return stationItem(station);
        return null;
    }

    private List<MediaItem> rootChildren() {
        List<MediaItem> result = new ArrayList<>();
        result.add(browseItem(ALL, "All stations", stations.size() + " stations", null));
        result.add(browseItem(FAVOURITES, "Favourites", "Saved stations", null));
        for (String group : stationGroups()) result.add(browseItem("group:" + group, group, "Station collection", null));
        return result;
    }

    private List<MediaItem> children(String parentId) {
        List<MediaItem> result = new ArrayList<>();
        if (ROOT.equals(parentId)) return rootChildren();
        Set<String> favouriteNames = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(FAV_KEY, java.util.Collections.<String>emptySet());
        for (Station station : stations) {
            if (ALL.equals(parentId)
                    || (FAVOURITES.equals(parentId) && favouriteNames.contains(station.name))
                    || parentId.equals("group:" + station.group)) {
                result.add(stationItem(station));
            }
        }
        return result;
    }

    private List<MediaItem> search(String query) {
        List<MediaItem> result = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return result;
        for (Station station : stations) if (station.matches(query.trim())) result.add(stationItem(station));
        return result;
    }

    private final class LibraryCallback implements MediaLibrarySession.Callback {
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                MediaLibrarySession session, MediaSession.ControllerInfo browser,
                @Nullable MediaLibraryService.LibraryParams params) {
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params));
        }

        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                MediaLibrarySession session, MediaSession.ControllerInfo browser, String mediaId) {
            MediaItem item = findItem(mediaId);
            return Futures.immediateFuture(item == null
                    ? LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    : LibraryResult.ofItem(item, null));
        }

        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                MediaLibrarySession session, MediaSession.ControllerInfo browser, String parentId,
                int page, int pageSize, @Nullable MediaLibraryService.LibraryParams params) {
            List<MediaItem> all = children(parentId);
            long startLong = (long) page * pageSize;
            if (startLong >= all.size()) {
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.<MediaItem>of(), params));
            }
            int start = (int) startLong;
            int end = Math.min(all.size(), start + pageSize);
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(start, end)), params));
        }

        @Override
        public ListenableFuture<LibraryResult<Void>> onSearch(
                MediaLibrarySession session, MediaSession.ControllerInfo browser, String query,
                @Nullable MediaLibraryService.LibraryParams params) {
            session.notifySearchResultChanged(browser, query, search(query).size(), params);
            return Futures.immediateFuture(LibraryResult.ofVoid());
        }

        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(
                MediaLibrarySession session, MediaSession.ControllerInfo browser, String query,
                int page, int pageSize, @Nullable MediaLibraryService.LibraryParams params) {
            List<MediaItem> all = search(query);
            long startLong = (long) page * pageSize;
            if (startLong >= all.size()) {
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.<MediaItem>of(), params));
            }
            int start = (int) startLong;
            int end = Math.min(all.size(), start + pageSize);
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(start, end)), params));
        }
    }
}
