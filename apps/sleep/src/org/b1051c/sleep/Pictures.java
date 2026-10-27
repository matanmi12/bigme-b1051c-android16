package org.b1051c.sleep;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Where the sleep picture comes from: the built-in badge, one picked picture, or the next picture of a folder.
 *  choose() only names the picture (cheap, so an unchanged frame is skipped without decoding); load() decodes it. */
final class Pictures {
    static final int PAGE_W = 1860, PAGE_H = 2480;   // the portrait page

    private Pictures() {}

    /** A named picture: a file, or an asset (file == null). */
    static final class Choice {
        final String name; final File file;
        Choice(String n, File f) { name = n; file = f; }
    }

    static File pictureDir(Context c) {
        File d = c.getExternalFilesDir("picture");
        if (d == null) d = new File(c.getFilesDir(), "picture");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static boolean isImage(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return f.isFile() && (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp") || n.endsWith(".bmp"));
    }

    /** The folder's pictures, sorted by name (empty when the folder is missing or unreadable). */
    static List<File> folderPictures(String folder) {
        File[] all = new File(folder).listFiles();
        List<File> out = new ArrayList<>();
        if (all == null) return out;
        Arrays.sort(all, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : all) if (isImage(f)) out.add(f);
        return out;
    }

    /** Name the picture for the next sleep. `advance` moves a folder on by one (a wake). Null = page colour only. */
    static Choice choose(Context c, Prefs p, boolean night, boolean advance) {
        String mode = p.mode();
        if (Prefs.MODE_FOLDER.equals(mode)) {
            List<File> files = folderPictures(p.folder());
            if (files.isEmpty()) return null;
            int i = p.p.getInt(Prefs.S_FOLDER_INDEX, 0);
            if (advance) i++;
            i = ((i % files.size()) + files.size()) % files.size();
            File f = files.get(i);
            p.p.edit().putInt(Prefs.S_FOLDER_INDEX, i).putString(Prefs.S_FOLDER_LAST, f.getName()).apply();
            return new Choice(f.getName(), f);
        }
        if (Prefs.SRC_FILE.equals(p.source()) && !p.file().isEmpty()) {
            File f = new File(pictureDir(c), p.file());
            if (f.isFile()) return new Choice(f.getName(), f);
        }
        return new Choice(night ? "badge-dark.png" : "badge-cream.png", null);
    }

    /** Decode a choice, no larger than the page (bounded memory; EXIF orientation applied). Null when it cannot be
     *  read (a build without the private art, a deleted file): the page colour and the overlays alone. */
    static Bitmap load(Context c, Choice ch) {
        if (ch == null) return null;
        try {
            ImageDecoder.Source src = ch.file != null ? ImageDecoder.createSource(ch.file)
                                                      : ImageDecoder.createSource(c.getAssets(), ch.name);
            return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(false);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float k = Math.min(1f, Math.min(PAGE_W / (float) w, PAGE_H / (float) h));
                if (k < 1f) decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
            });
        } catch (IOException | RuntimeException e) {
            android.util.Log.w("sleep", "picture " + ch.name + " not loaded: " + e);
            return null;
        }
    }

    private static final String EXTERNAL = "com.android.externalstorage.documents";

    /** The file path of a folder picked in Android's folder picker ("primary:Pictures/X" -> /sdcard/Pictures/X,
     *  "<SD_UUID>:Books" -> /storage/<SD_UUID>/Books). Null for other providers (Drive, Downloads, ...): the folder
     *  is read as files with the photo permission. */
    static String treePath(Uri tree) {
        if (tree == null || !EXTERNAL.equals(tree.getAuthority())) return null;
        String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String vol = id.substring(0, colon), rel = id.substring(colon + 1);
        String base = "primary".equals(vol) ? "/sdcard" : "/storage/" + vol;
        return rel.isEmpty() ? base : base + "/" + rel;
    }

    /** The picker's starting place for a folder path (the reverse of treePath); null when it is not on shared storage. */
    static Uri pathToDocument(String path) {
        String p = path.replaceFirst("^/storage/emulated/0(?=/|$)", "/sdcard");
        String vol, rel;
        if (p.equals("/sdcard") || p.startsWith("/sdcard/")) { vol = "primary"; rel = p.substring(Math.min(p.length(), 8)); }
        else if (p.startsWith("/storage/")) {
            String rest = p.substring(9);
            int s = rest.indexOf('/');
            vol = s < 0 ? rest : rest.substring(0, s);
            rel = s < 0 ? "" : rest.substring(s + 1);
        } else return null;
        return android.provider.DocumentsContract.buildDocumentUri(EXTERNAL, vol + ":" + rel);
    }

    /** Copy a picked document into files/picture/ (so no media permission is needed later); returns the file name. */
    static String importUri(Context c, Uri uri) throws IOException {
        String name = "picture-" + System.currentTimeMillis() + ".img";
        try (android.database.Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && cur.getString(i) != null) name = cur.getString(i).replaceAll("[^A-Za-z0-9._-]", "_");
            }
        } catch (RuntimeException ignored) {}
        File out = new File(pictureDir(c), name);
        try (InputStream in = c.getContentResolver().openInputStream(uri); FileOutputStream o = new FileOutputStream(out)) {
            if (in == null) throw new IOException("cannot open " + uri);
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        }
        return name;
    }
}
