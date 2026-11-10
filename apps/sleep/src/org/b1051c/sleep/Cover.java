package org.b1051c.sleep;

import android.content.Context;
import java.io.File;

/**
 * "Book cover" sleep picture (ROADMAP 18 c): KOReader's patch 3-inkognito-cover.lua writes the cover of the book it
 * opens to /sdcard/koreader/inkognito-cover.png and sends COVER (with its identity shared, like the E-Ink Control
 * bridge). The file is copied as root into files/cover/ (KOReader's folder is not this app's to read), so the cover
 * stays the last book's until another one is opened, and the frame is rendered ahead of the next sleep.
 */
final class Cover {
    static final String KOREADER = "org.koreader.launcher";
    static final String SRC = "/data/media/0/koreader/inkognito-cover.png";   // /sdcard/koreader/... as root sees it

    private Cover() {}

    static File file(Context c) { return new File(new File(c.getFilesDir(), "cover"), "cover.png"); }

    /** Copy KOReader's export in; returns "" or an error line. */
    static String importFromKoreader(Context c, String title) {
        File dst = file(c);
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        int uid = android.os.Process.myUid();
        String d = dst.getAbsolutePath();
        String r = Root.run("S=" + SRC + "; D=" + d + "; [ -s $S ] || { echo missing; exit 0; }; cp -f $S $D.tmp && chown "
                + uid + ":" + uid + " $D.tmp && chmod 0600 $D.tmp && chcon $(stat -c %C " + (dir == null ? d : dir.getAbsolutePath())
                + ") $D.tmp && mv -f $D.tmp $D && echo ok");
        if (!"ok".equals(r)) return "cover: " + r;
        String t = title == null ? "" : title.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (t.length() > 120) t = t.substring(0, 120);
        new Prefs(c).p.edit().putString(Prefs.S_COVER_TITLE, t).putLong(Prefs.S_COVER_AT, System.currentTimeMillis()).apply();
        return "";
    }
}
