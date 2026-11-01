package home.custom.radiodial;

import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;

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
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition;

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
import java.nio.charset.StandardCharsets;

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

        String id(String collection) {
            return "station/" + encode(collection) + "/" + encode(url);
        }
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

    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String decode(String value) {
        try { return new String(Base64.decode(value, Base64.URL_SAFE), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    private String collectionFromId(String id) {
        if (id == null || !id.startsWith("station/")) return null;
        String[] parts = id.split("/", 3);
        return parts.length == 3 ? decode(parts[1]) : null;
    }

    private String urlFromId(String id) {
        if (id == null) return "";
        if (id.startsWith("station/")) {
            String[] parts = id.split("/", 3);
            return parts.length == 3 ? decode(parts[2]) : "";
        }
        return id.startsWith("station:") ? id.substring("station:".length()) : "";
    }

    private Station stationByUrl(String url) {
        for (Station station : stations) if (station.url.equals(url)) return station;
        return null;
    }

    private MediaItem stationItem(Station station) { return stationItem(station, ALL); }

    private MediaItem stationItem(Station station, String collection) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setTitle(station.name)
                .setSubtitle(station.description)
                .setArtist(station.group)
                .setAlbumTitle("Radio Dial · " + station.group)
                .setIsBrowsable(false)
                .setIsPlayable(true);
        if (station.logo.startsWith("https://")) {
            Uri artwork = new Uri.Builder().scheme("content")
                    .authority(getPackageName() + ".artwork")
                    .appendPath(encode(station.url)).build();
            metadata.setArtworkUri(artwork);
        }
        return new MediaItem.Builder()
                .setMediaId(station.id(collection))
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
        Station station = stationByUrl(urlFromId(id));
        if (station != null) return stationItem(station, collectionFromId(id) == null ? ALL : collectionFromId(id));
        return null;
    }

    private List<MediaItem> rootChildren() {
        List<MediaItem> result = new ArrayList<>();
        result.add(browseItem(FAVOURITES, "Favourites", "Saved stations", null));
        result.add(browseItem(ALL, "All stations", stations.size() + " stations", null));
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
                result.add(stationItem(station, parentId));
            }
        }
        return result;
    }

    private MediaItem resolve(MediaItem item) {
        Station station = stationByUrl(urlFromId(item.mediaId));
        return station == null ? item : stationItem(station,
                collectionFromId(item.mediaId) == null ? ALL : collectionFromId(item.mediaId));
    }

    private List<MediaItem> search(String query) {
        List<MediaItem> result = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return result;
        for (Station station : stations) if (station.matches(query.trim())) result.add(stationItem(station));
        return result;
    }

    private final class LibraryCallback implements MediaLibrarySession.Callback {
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(
                MediaSession session, MediaSession.ControllerInfo controller, List<MediaItem> mediaItems) {
            List<MediaItem> resolved = new ArrayList<>();
            for (MediaItem item : mediaItems) resolved.add(resolve(item));
            return Futures.immediateFuture(resolved);
        }

        @Override
        public ListenableFuture<MediaItemsWithStartPosition> onSetMediaItems(
                MediaSession session, MediaSession.ControllerInfo controller, List<MediaItem> mediaItems,
                int startIndex, long startPositionMs) {
            if (mediaItems.size() == 1) {
                MediaItem requested = mediaItems.get(0);
                String collection = collectionFromId(requested.mediaId);
                Station selected = stationByUrl(urlFromId(requested.mediaId));
                if (selected != null) {
                    if (collection == null || children(collection).stream()
                            .noneMatch(item -> selected.url.equals(urlFromId(item.mediaId)))) {
                        collection = "group:" + selected.group;
                    }
                    List<MediaItem> queue = children(collection);
                    for (int i = 0; i < queue.size(); i++) {
                        if (selected.url.equals(urlFromId(queue.get(i).mediaId))) {
                            return Futures.immediateFuture(new MediaItemsWithStartPosition(queue, i, startPositionMs));
                        }
                    }
                }
            }
            List<MediaItem> resolved = new ArrayList<>();
            for (MediaItem item : mediaItems) resolved.add(resolve(item));
            return Futures.immediateFuture(new MediaItemsWithStartPosition(resolved, startIndex, startPositionMs));
        }

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
