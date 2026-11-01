package home.custom.radiodial;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Supplies cached local artwork URIs to Android Auto. Only catalogue station artwork is served. */
public final class StationArtworkProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/*"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        return new AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        if (!"r".equals(mode) || getContext() == null) throw new java.io.FileNotFoundException("Read only");
        try {
            String[] segments = uri.getPathSegments().toArray(new String[0]);
            if (segments.length != 1) throw new java.io.FileNotFoundException("Unknown artwork");
            String stationUrl = new String(Base64.decode(segments[0], Base64.URL_SAFE), StandardCharsets.UTF_8);
            String imageUrl = findLogo(stationUrl);
            if (imageUrl == null || !imageUrl.startsWith("https://")) throw new java.io.FileNotFoundException("Artwork unavailable");
            File cache = new File(getContext().getCacheDir(), "auto-artwork");
            if (!cache.exists() && !cache.mkdirs()) throw new java.io.FileNotFoundException("Cache unavailable");
            File image = new File(cache, sha256(imageUrl));
            if (!image.isFile() || image.length() == 0) download(imageUrl, image);
            return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (java.io.FileNotFoundException e) { throw e; }
        catch (Exception e) { throw new java.io.FileNotFoundException("Artwork unavailable: " + e.getMessage()); }
    }

    private String findLogo(String stationUrl) throws Exception {
        SharedPreferences prefs = getContext().getSharedPreferences("radiodial", 0);
        String bundled;
        try (InputStream input = getContext().getResources().openRawResource(R.raw.stations);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) > 0) output.write(buffer, 0, n);
            bundled = output.toString("UTF-8");
        }
        String cached = prefs.getString("stations_cache", null);
        if (cached != null && String.valueOf(bundled.hashCode()).equals(prefs.getString("stations_cache_for", ""))) {
            String found = logoFrom(cached, stationUrl); if (found != null) return found;
        }
        String found = logoFrom(bundled, stationUrl);
        return found != null ? found : logoFrom(prefs.getString("local_stations", "[]"), stationUrl);
    }

    private String logoFrom(String json, String stationUrl) throws Exception {
        JSONArray stations = new JSONArray(json);
        for (int i = 0; i < stations.length(); i++) {
            JSONObject station = stations.optJSONObject(i);
            if (station != null && stationUrl.equals(station.optString("url"))) return station.optString("logo", "");
        }
        return null;
    }

    private void download(String imageUrl, File target) throws Exception {
        URL url = new URL(imageUrl);
        for (int redirects = 0; redirects <= 3; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(7000);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new java.io.IOException("Bad redirect");
                url = new URL(url, location);
                if (!"https".equalsIgnoreCase(url.getProtocol())) throw new java.io.IOException("HTTPS required");
                continue;
            }
            if (code < 200 || code >= 300 || !connection.getContentType().toLowerCase().startsWith("image/")) {
                connection.disconnect(); throw new java.io.IOException("Not an image");
            }
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192]; int n; long size = 0;
                while ((n = in.read(buffer)) > 0) {
                    size += n; if (size > 5L * 1024 * 1024) throw new java.io.IOException("Artwork too large");
                    out.write(buffer, 0, n);
                }
            } finally { connection.disconnect(); }
            if (!temp.renameTo(target)) { temp.delete(); throw new java.io.IOException("Cache write failed"); }
            return;
        }
        throw new java.io.IOException("Too many redirects");
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
