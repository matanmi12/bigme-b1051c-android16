# בדיקת התקנה מלאה מ-0: מחר (11.11)

**המטרה:** חוויית משתמש מלאה, מטאבלט stock ועד InKognito עובד. אתה מריץ את המתקין בטרמינל שלך ומגדיר הכול בעצמך. אני עוקב אחרי הלוג והטאבלט ברקע.
**אחרי הבדיקה לא משחזרים כלום:** ממשיכים מההתקנה הנקייה, וה-soak ממשיך עליה.
**כל כתיבה למחיצה:** אתה מאשר אותה כשמופיעה בקשת `RESTORE` / `FLASH` של המתקין, וכשאתה מקליד את המילה בעצמך, זה האישור.

## מה הוכן אתמול בלילה (בלי לגעת בטאבלט)
- **ערכת stock לשחזור:** `backups/stock-4.9.0-restore-set/`. אלה hardlinks לסט ה-stock המקורי של Bigme, עם `SHA256SUMS` בפורמט של המתקין.
- **`prepare` על הערכה עבר:** המתקין מקבל טאבלט stock, וה-boot שהוא בונה **זהה** ל-`9e687f24` שכבר עלה אצלך.
- **`restore-stock --dry-run` עבר.**

## הסדר
0. **(אני, קריאה בלבד, ~15 דק')** גיבוי מחיצות של המצב הנוכחי. זו רק רשת ביטחון, ולא משחזרים ממנו.
1. **(אתה) חזרה ל-0:** בתיקיית alpha 6 מריצים
   `python3 inkognito.py restore-stock --backup ~/Desktop/Bigme/backups/stock-4.9.0-restore-set`
   ומקלידים `RESTORE`. בסוף לוחצים על power ‏3–5 שניות, לפי ההודעה.
   *זה לא חלק מהחוויה של משתמש רגיל, כי הוא מגיע כבר עם stock. אבל זו הבדיקה הראשונה של `restore-stock` על חומרה.*
2. **(אתה)** הגדרה קצרה של stock Android. ‏INSTALL.md סעיף 4: ‏USB debugging ואישור המחשב.
3. **(אתה) כמו משתמש חדש:**
   - מורידים את ה-zip מה-[release](https://github.com/matanmi12/bigme-b1051c-android16/releases/tag/alpha6-20261110) ובודקים את ה-`.sha256`.
   - פותחים אותו בתיקייה חדשה, וממשיכים לפי INSTALL.md מסעיף 1. כולל mtkclient: אפשר לפי סעיף 2, או `--mtk` עם ה-mtkclient הקיים.
   - dry-run (סעיף 7), ואז `install` (סעיף 8). המתקין עושה גיבוי משלו (~12.5 GB), ואז מקלידים `FLASH`.
4. **(אתה)** power ‏3–5 שניות, ואז האתחול הראשון: ~5 דק' ואתחול עצמי אחד. אחריו Google setup ומסך InKognito Setup.
5. **(אני ברקע):**
   - הלוג של המתקין, מתיקיית ה-work שלו.
   - אחרי האתחול, דרך adb: ‏`provision.log` לכל שלב, הגדרות ברירת המחדל, ה-shim, ‏logcat.
   - כל בעיה נרשמת כממצא.

## מה הבדיקה מכסה
`restore-stock`, ‏`install` מלא עם מחיקה, האתחול הראשון ושני השלבים של ה-provisioning, ‏Google setup ומסך ה-Setup. כל אלה פתוחים ב-KNOWN-ISSUES.
**לא מכוסה:** האימג' בלי Google, ומכשיר שה-bootloader שלו כבר "תקוע" במצב verity (`fix-boot-warning`, נדחה).

## ממצאים מהבדיקה (לגרסה הבאה)
1. **(הבעלים, 11:30) progress bar במתקין:** בזמן כתיבה או קריאה ארוכה (system_b, ‏5 GB, ~10 דק') המסך לא מראה כלום, ולא רואים שמשהו מתקדם. צריך להראות שורה אחת שמתעדכנת, למשל `system_b: 12% (590 MB / 5.06 GB), 7.5 MB/s, ~9 min left`. הנתונים כבר קיימים בפלט של mtkclient (`Progress: … 11.7% Write: … 7.52 MB/s 09m:26s left`), ש-`Mtk._run` כותב היום רק ללוג.
2. **(הבעלים, 11:5x) INSTALL.md ארוך מדי:** "אין לי סבלנות לקרוא את המסמך". צריך **התחלה מהירה** בראש INSTALL.md, וגם ב-README וב-XDA: עד 10 צעדים קצרים, פקודה אחת לכל צעד, ומה אמור להופיע אחרי כל צעד. ההסברים המלאים זזים לסעיפי "פרטים". המשתמש בבדיקה פתח את ה-zip ב-`~/Desktop/Bigme/test/`.
3. **(הבעלים, 12:0x) Developer options חסום ב-stock של Bigme:** ההגדרות של Bigme לא מציגות "About tablet" ו-"Build number". צריך אפליקציה שפותחת את מסכי ההגדרות המוסתרים. במקור היה מותקן `com.arumcomm.settingsshortcut` ("Settings Shortcut", מ-Google Play, לפי `settings-backup-2026-10-17/packages_user.txt`). **INSTALL.md סעיף 4** צריך לכלול:
   - **Activity Launcher** (קוד פתוח, ‏F-Droid, ‏`de.szalkowski.activitylauncher`, בלי חשבון Google): מורידים אותו בדפדפן של הטאבלט, מתקינים, פותחים את Settings › "About tablet" / "My device info", לוחצים 7 פעמים על Build number, ואחר כך Developer options › USB debugging.
   - חלופה: Settings Shortcut מ-Google Play.
   - צריך Wi-Fi ב-stock. בשלב הזה יש לבדוק מה עבד בפועל, ואת שמות המסכים המדויקים.
   - **(הבעלים, שוב, בגיבוי של install):** אותו דבר בשלב הגיבוי (~12.5 GB בקריאה, הרבה דקות בלי שום פלט). ה-progress צריך לכסות **כל** קריאה וכתיבה ארוכה: גיבוי, כתיבה, readback, ‏restore-stock.
4. **(שלי, 12:22) `adb shell su` במערכת חדשה פותח דיאלוג "Superuser Request" של Magisk על הטאבלט.** זה הפריע לחוויה. בבדיקה הבאה לא מריצים su מרחוק עד שהבעלים מאשר, ולפני כן קוראים רק דרך `adb shell` רגיל (getprop, ‏logcat). אפשרות לגרסה הבאה: ‏`post_setup` ירשום ב-logcat סיכום קצר של שלבי ה-provisioning, כך שאפשר לקרוא אותו בלי root.
5. **(12:21–12:22) סדר האתחול הראשון:** ‏Preloader ב-12:21:26, ‏TrebleDroid ב-USB ב-12:21:51, ו-`sys.boot_completed=1` כבר ב-12:22:32, בלי ה-Preloader השני שהיה אמור להופיע אחרי האתחול העצמי של stage 1. כנראה stage 1 נדחה (deferred: ה-daemon של Magisk לא הגיב ב-zygote-start), ואז stage 2 מתקין את המודולים, מאפס את ה-setup ומאתחל. צריך לאמת מול provision.log.
6. **(הבעלים, 12:3x) Magisk מבקש להתעדכן מהאינטרנט:** זו אפליקציית ה-stub של Magisk ("Upgrade to full Magisk"), ש-magiskd מתקין כשאין אפליקציית Magisk מלאה. ב-ROM, ‏stage 2 אמור להתקין את Magisk 30.7 המצורף לפני שזה קורה. כלומר stage 2 עוד לא התקין את האפליקציות, או לא רץ (ראו ממצא 5). לבעלים נאמר ללחוץ Cancel. **לגרסה הבאה:** להתקין את אפליקציית Magisk המלאה מוקדם (stage 1, או מיד ב-boot_completed), או להסתיר את בקשת ה-stub, כדי שמשתמש חדש לא יראה אותה.
   - **עדכון 12:4x:** ‏stage 1 כן רץ ואתחל (ב-`persist.sys.boot.reason.history` יש `reboot,inkognito-stage1`), כך שממצא 5 היה התרעת שווא: האתחול היה מהיר מכדי להופיע ב-USB. ‏stage 2 התקין את כל האפליקציות ב-12:22:15–20, כולל Magisk 30.7 ‏(vc 30700). ההודעה "Magisk צריך אינטרנט כדי לסיים התקנה" הופיעה ב-12:22:38, וזה ה-`su` שלי מ-shell (ממצא 4), שהגיע לפני שאפליקציית Magisk סיימה את ההגדרה הראשונה שלה. כדי לסגור את ממצא 6 צריך לבדוק אם היא מופיעה גם בלי בקשה מבחוץ. **פתוח:** ‏`reboot,factory_reset` בהיסטוריה אחרי stage 1, ו-lastUpdateTime 12:30:15 של Magisk.
7. **(13:0x) הבאג החשוב: חוקי ה-SELinux של המודולים לא נטענו באתחול.** ‏stage 1 כתב "`rules:  lines`" (ריק; ב-update של אתמול: 8). ‏`/metadata/watchdog/magisk` (תיקיית ה-preinit של Magisk) לא היה קיים באתחול 1, כי metadata נמחק בהתקנה. הוא נוצר רק באתחול 2 (12:22), ו-`sepolicy.rule` נכתב אליו רק ב-12:30, אחרי שהמדיניות כבר נטענה. התוצאה: 884 דחיות `hal_graphics_composer_default → debugfs file`, בדיוק החוק של eink-compat, כך שה-shim רץ עם חלק מהפעולות חסומות. **תיקון לגרסה הבאה:** ב-stage 2 (וב-post-fs-data של המודול), אם חוקי המודולים לא בתוך המדיניות החיה, מריצים `magiskpolicy --live --apply` לכל `sepolicy.rule` של מודול. בנוסף, stage 1 צריך ליצור את תיקיית ה-preinit גם כשה-symlink עוד לא קיים. **אצל הבעלים: תוקן באתחול (12:45). אחרי האתחול 0 דחיות composer→debugfs.**
8. **(13:0x) `screen_off_timeout` נשאר 121000 (שתי דקות):** ברירת המחדל של ה-ROM היא 600000. ‏`ensure_setting` דילג עליו כי הערך "not a platform default". הערך 121000 כנראה מגיע מ-TrebleDroid או מ-Google setup. צריך להוסיף אותו לרשימת ערכי הפלטפורמה.
9. **(13:0x) `qs_auto_tiles`:** אחרי הכתיבה נקרא 'wallet,cast' (אזהרה בלבד, SystemUI דורס את הערך).
10. **(12:36) בקשת root בזמן שהמסך כבוי הולכת לאיבוד בשקט:** ה-SuRequestActivity נפתח מאחורי מסך הנעילה, ואחרי 10 שניות Magisk דחה. ‏E-Ink Control ו-Lock & Sleep מקבלים policy מראש, כך שזה לא אמור לפגוע בהם, אבל כדאי לוודא.
11. **(הבעלים, 12:4x) אייקונים בשורת הניווט (היומן מאחורי Back):** בהפעלה הראשונה Lawnchair מילא dock מהפריסה שלו (Calendar, Gallery, Play Store, WebView Browser Test, ‏`container -101`), למרות `hotseat_mode=disabled`. האייקונים מצוירים בשורת הניווט השקופה. בגיבוי של הבעלים ה-dock ריק. **אצל הבעלים:** ‏4 השורות נמחקו (‏sqlite3, ‏Lawnchair הופעל מחדש, הבעלות נשמרה). **עדכון 12:5x: האייקונים שנשארו שייכים ל-taskbar של Launcher3 המערכתי**, שיש לו dock נפרד ב-`launcher_6_by_5.db` (Calendar, Gallery, Play Store, WebView Browser Test, Camera). גם הם נמחקו אצל הבעלים. **במקורות:** ‏`clear_launcher_docks` ב-`post_setup` (רק בהתקנה ראשונה) מנקה את שני ה-docks, ‏Lawnchair ו-Launcher3. נבדק על הטאבלט (0/0, rc 0).
12. **(12:4x) "WebView Browser Test" (`org.chromium.webview_shell`, אפליקציית בדיקה של ה-GSI) מופיעה למשתמש.** **הבעלים: כן.** הוסתרה אצלו (`disable-user`), ובמקורות נוסף `sys_hide_test_apps` לברירות המחדל של ההתקנה הראשונה.
13. **(הבעלים) DPI:** הבעלים זכר 340. בגיבוי `layer-20261110` יש 350 (`display_density_forced=350`, ‏physical 350), ועל הטאבלט כרגע 350, כלומר אין שינוי. אם הבעלים ירצה 340, זו החלטה חדשה.
14. **(הבעלים, 13:0x) InKognito Setup עבד נהדר. התצוגה עובדת נהדר אחרי ה-reboot, וכל השאר תקין.** כל עוד ממצא 7 לא מתוקן, **המשתמש צריך לבצע reboot אחד אחרי ה-setup**. צריך לכתוב את זה במפורש ב-INSTALL.md, ב-KNOWN-ISSUES וב-XDA, ואחרי התיקון לבדוק אם זה עדיין נדרש. ‏DPI נשאר 350 (החלטת הבעלים). ל-Developer options הבעלים השתמש ב-Activity Launcher לפי ההמלצה (ממצא 3).
15. **(הבעלים) זהות המכשיר: "TrebleDroid vanilla" מופיע** ב-USB, ב-Settings › About › Model/Device name, ב-Bluetooth וברשימת המכשירים של Google. המקור הוא `ro.product.model` / `ro.product.product.model` (‏build.prop של product ב-GSI), ומהם נגזרים `device_name` ו-`bluetooth_name` בהפעלה הראשונה. ‏`ro.build.display.id` = ‏treble_arm64_bvS-… ("Build number"). **אסור לשנות:** ‏`ro.build.fingerprint` (שער של המודולים eink-compat ו-launcher3, ‏Play), וגם לא את `ro.product.device` / ‏`ro.product.name` / ‏`ro.product.brand`. ‏ZenOS קורא את model ואת manufacturer לתצוגה בלבד.
   - **החלטת הבעלים:** ‏`InKognito B1051C Pro` כ-model, וגם כשם המכשיר ושם ה-Bluetooth. ‏Build number: ‏`InKognito alpha 7 (TrebleDroid 16, BP2A.250605.031.A3)`. נכנס לסבב התיקונים.

## פיצ'רים חדשים ל-alpha 7 (בקשות הבעלים, 11.11)
- **א. "Clear all" ב-Recents:** כפתור שמופיע מיד כשפותחים את Recents, בנוסף לחלונות. היום הוא נמצא רק בסוף הרשימה, אחרי גלילה.
  - **ממצאי המחקר (Launcher3QuickStep.orig.apk, ‏bf92e8d5):** אין flag או overlay שמפעילים את זה. ה-`ClearAllButton` שבמערכת מופיע רק בסוף הגלילה, ושורת הפעולות (`OverviewActionsView`: Screenshot, Split, Save app pair) נעלמת בזמן שהוא מופיע.
  - **התוכנית (אפשרות A):** hook אחד בסוף `RecentsView.init(…)`, אחרי `iput-object p3 …mDesktopRecentsTransitionController`, שקורא ל-`org.b1051c.l3.ClearAllAction.attach`. זו מחלקה חדשה ב-classes2.dex, בסגנון של HomeBg.
  - המחלקה מוסיפה לשורת הפעולות (`action_buttons`) כפתור בסגנון `OverviewActionButton` עם הטקסט `recents_clear_all`. הלחיצה עליו קוראת ל-`dismissAllTasks(View)` (ברפלקשן) או ל-`getClearAllButton().performClick()`.
  - אין שינויי משאבים, ויש מתג כיבוי ב-Settings.Secure. זה מכסה את RecentsActivity (Lawnchair) ואת QuickstepLauncher.
  - **לבדוק על הטאבלט:** שהשורה מוצגת ב-Recents של ה-fallback, שה-dismiss מסתיים מיד כשהאנימציות כבויות, ושחוזרים ל-Lawnchair.
- **ב. המקלדת של Bigme מה-stock, בנוסף למקלדת שלנו:** בדיקת אבטחה ב-`research/phase-d/BIGME-KEYBOARD-SECURITY-2026-11-11.md`.
  - ‏"Bigme input method" (`com.xrz.input` 4.0) בלי SDK-ים של מעקב ובלי כתובות סיניות.
  - הקלט הקולי שולח אודיו **לא מוצפן** (`ws://…:8888`) לשרת של Bigme, אבל רק אם `domain_sound_sever` מוגדר. ב-InKognito הוא לא מוגדר.
  - הכתב היד מוריד מודלים מ-Google.
  - `BK_Input` (גרסת AOSP של Bigme) בלי אינטרנט, אבל הוא בעל אותו package כמו המקלדת שלנו, ולכן אפשר להתקין אותו רק במקומה.
  - **ממתין לבעלים:** איזו מקלדת, ואישור לניסוי על הטאבלט.
