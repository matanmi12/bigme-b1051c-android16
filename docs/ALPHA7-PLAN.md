# InKognito alpha 7: כל השינויים להטמעה

נכתב ב-11.11 אחרי בדיקת ההתקנה המלאה מ-0. מקורות: [TEST-INSTALL-2026-11-11.md](TEST-INSTALL-2026-11-11.md) (הממצאים 1–15 והבקשות החדשות), ובדיקת המקלדת [../research/phase-d/BIGME-KEYBOARD-SECURITY-2026-11-11.md](../research/phase-d/BIGME-KEYBOARD-SECURITY-2026-11-11.md).

**שם הגרסה:** ‏`InKognito-B1051CPro-alpha7-<תאריך הבנייה>`.
**סימון מצב:** ✅ כבר במקורות · 🔧 להטמיע · ❓ ממתין להחלטה שלך.
**לא מתפרסם:** שום דבר בתוכנית הזו לא עולה ל-GitHub או ל-XDA בלי אישור נפרד שלך.

---

## מצב הביצוע (11.11, ערב)

**כל הסעיפים 1–6 הוטמעו במקורות, ו-alpha 7 נבנה.** מה שנשאר דורש אותך.

| מה | מצב |
|---|---|
| סעיפים 1–4 (באגים, זהות, פיצ'רים, מתקין) | ✅ במקורות וב-build |
| סעיף 5 (מסמכים ב-zip) | ✅ |
| סעיף 6 (פוסטים) | ✅ טיוטות ב-`release/DIST-InKognito-B1051CPro-alpha7-20261111/`. לא פורסם דבר |
| 7.1–7.3 (גרסאות, בנייה, בדיקות) | ✅ ‏unit + cli + sim ‏115/115; סריקת מידע אישי נקייה |
| **7.4 update על הטאבלט שלך** | ✅ בוצע 11.11 ערב (רק system_b נכתב). 7 מודולים, 8 פעמים updated ו-done, השם InKognito B1051C Pro, ‏Clear all, המקלדת, ‏0 דחיות debugfs של ה-shim. ממצא: דיאלוג של Play Protect עצר את stage 2 כ-50 שניות (ראו settings-changes.log) |
| 7.5 התקנה נקייה נוספת | ⏳ אחרי 7.4, בזמן שנוח לך |
| פרסום (GitHub, XDA, ריפו ציבורי) | ⏳ רק באישור נפרד שלך |

**אחרי ה-update (11.11 ערב):** נוספה השהיית Play Protect גם למסלול ה-update, ו-alpha 7 נבנה מחדש באותו שם. הממצאים הפתוחים עברו ל-[BETA1-FINDINGS.md](BETA1-FINDINGS.md); הגרסה הבאה היא beta 1.

**התוצרים:** ‏`release/InKognito-B1051CPro-alpha7-20261111/` ו-`release/DIST-InKognito-B1051CPro-alpha7-20261111/`; ה-SHA-256 העדכניים בקבצי ה-`.sha256` שם וב-PUBLISH-CHECKLIST.md.

**סטיות מהתוכנית, להחלטתך:**
- **3.2, HeliBoard:** נכנס כמודול Magisk ‏`b1051c-heliboard` 0.2.0, כמו שנבדק על הטאבלט, ולא נאפה לתוך האימג'. הנתיב במערכת זהה (`/system/product/app/HeliBoard`), ואפשר להסיר אותו כמו כל מודול.
- **3.2, מילון העברית:** מועתק **תמיד**, לא רק כששפת המערכת עברית. ב-stage 2 שפת המערכת עוד לא נבחרה, ומי שמוסיף עברית אחר כך היה נשאר בלי מילון. HeliBoard טוען אותו רק כשעברית מופעלת (4.8 MB ב-/data).
- **3.2, update מ-alpha 6:** המקלדת עוברת ל-HeliBoard רק אם ברירת המחדל עדיין מקלדת AOSP. מקלדת שהמשתמש בחר נשארת.
- **1.1, שורש הבאג:** Magisk יוצר את תיקיית ה-preinit רק ב-boot-complete, ואתחול 1 לא מגיע לשם. stage 1 יוצר אותה עכשיו בעצמו, באותו נתיב ש-Magisk בוחר. ‏stage 2 בודק כל חוק מול המדיניות החיה וטוען את החסרים.
- **2.3, Bluetooth:** ב-`bt_config.conf` אין שם שמור, וה-stack לוקח את השם מה-model. לכן ב-update מספיק לעדכן את `device_name` ואת `bluetooth_name` בהגדרות.
- **3.1, Clear all:** לכפתור נוסף אייקון קטן שמצויר בקוד, כדי שיתאים לשני הכפתורים שלידו. לא נבדק: Dark theme, ו-Launcher3 עצמו כמסך הבית.
- **4.1, מד ההתקדמות:** המהירות והזמן שנותר נמדדים על ידי המתקין עצמו, כי החישוב של mtkclient שגוי בשעות.

---

## 1. תיקוני באגים מהבדיקה

| # | מה | איפה | מצב |
|---|---|---|---|
| 1.1 | **חוקי SELinux של המודולים לא נטענים בהתקנה חדשה** (ממצא 7, החשוב). ‏metadata נמחק, ולכן תיקיית ה-preinit של Magisk לא קיימת באתחול 1, והחוקים נטענים רק אחרי reboot נוסף. **התיקון:** בתחילת stage 2 בודקים לכל מודול שה-`sepolicy.rule` שלו בתוך המדיניות החיה, ואם לא, מריצים `magiskpolicy --live --apply`. בנוסף, stage 1 יוצר את תיקיית ה-preinit כשה-symlink חסר, וה-log כותב כמה חוקים הועתקו. | `payload-src/provision-stage1.sh`, `provision-stage2.sh` | ✅ |
| 1.2 | **אייקונים בשורת הניווט:** ה-dock של Lawnchair וה-dock של ה-taskbar (Launcher3) מתמלאים בהפעלה הראשונה. ‏`clear_launcher_docks` מנקה את שניהם רק בהתקנה ראשונה. נבדק על הטאבלט. | `defaults/system/apply-system-defaults.sh` | ✅ |
| 1.3 | **הסתרת "WebView Browser Test"** (אפליקציית בדיקה של ה-GSI): ‏`sys_hide_test_apps`, ‏`pm disable-user`. נבדק. | `apply-system-defaults.sh` | ✅ |
| 1.4 | **זמן כיבוי המסך נשאר שתי דקות (121000):** מוסיפים את 121000 לערכי הפלטפורמה, כדי שברירת המחדל של ה-ROM (600000) תחול. | `sys_screen_timeout` | ✅ |
| 1.5 | **אזהרת `qs_auto_tiles`:** ‏SystemUI דורס את הערך. מורידים את בדיקת הקריאה-חזרה, או משאירים אזהרה שקטה. | `sys_qs_auto_tiles` | ✅ |
| 1.6 | **סיכום provisioning ב-logcat** (tag ‏`InKognito`): כל שלב שהסתיים נכתב גם ל-logcat, כדי שאפשר לבדוק התקנה בלי root וממילא בלי דיאלוג של Magisk (ממצא 4). | stage 1/2 | ✅ |
| 1.7 | **הגנה על המקלדת של Bigme:** אם המודול מותקן, ‏`domain_sound_sever` / ‏`domain_user_sever` נמחקים באתחול. קיים במודול `b1051c-bigme-ime` (רק אצלך, ראו 3.3). | `work/module/b1051c-bigme-ime/service.sh` | ✅ |

| 1.8 | **ניגודיות צבעים "Medium" כברירת מחדל** (בקשת הבעלים, 11.11, אחרי תיקון: medium ולא high; Settings › Accessibility › Color contrast). ‏`secure contrast_level=0.5`. מגדירים בהתקנה ראשונה ב-`sys_*` (‏ensure_setting, רק אם הערך הוא ברירת המחדל 0/null). | `apply-system-defaults.sh` | ✅ |

## 2. זהות המכשיר ("TrebleDroid vanilla" → InKognito)

| # | מה | ערך חדש | איפה | מצב |
|---|---|---|---|---|
| 2.1 | `ro.product.product.model` (ממנו נגזר `ro.product.model`: ה-USB, Settings › About › Model, רשימת המכשירים של Google) | `InKognito B1051C Pro` | `build-rom.py`, עריכת ה-build.prop של product באימג'. קודם מאמתים בדיוק איזו שורה ובאיזה קובץ ב-TD16 | ✅ |
| 2.2 | `ro.build.display.id` (‏"Build number") | `InKognito alpha 7 (TrebleDroid 16, BP2A.250605.031.A3)`, נוצר מ-payload version | `build-rom.py`, ‏`/system/build.prop` | ✅ |
| 2.3 | `device_name` + `bluetooth_name` (‏Settings, ‏Bluetooth, hotspot) | `InKognito B1051C Pro` | בהתקנה חדשה הם נגזרים מה-model לבד. ב-update מגדירים אותם **רק אם הם עדיין "TrebleDroid vanilla"**, כדי לא לגעת בשם שהמשתמש בחר | ✅ |
| 2.4 | **לא משנים:** `ro.build.fingerprint` (שער של המודולים eink-compat ו-launcher3, ו-Google Play), ‏`ro.product.device` / ‏`name` / ‏`brand` / ‏`manufacturer`. הקרדיט ל-TrebleDroid נשאר במסמכים ובתפריט Phh Treble Settings. | | | — |

**לבדוק:** ‏`adb devices -l` מראה `model:InKognito_B1051C_Pro`, ומסך About ו-Bluetooth מציגים את השם. ה-shim וה-taskbar עולים כרגיל, כי ה-fingerprint לא השתנה.

## 3. פיצ'רים חדשים

### 3.1 כפתור "Clear all" ב-Recents ‏✅
- **מה:** כפתור "Clear all" בשורת הפעולות של Recents, ליד Screenshot ו-Split screen, שגלוי מיד כשפותחים את Recents.
- **איך:**
  - hook אחד בסוף `RecentsView.init(…)`, אחרי `iput-object p3 …mDesktopRecentsTransitionController`, שמוביל למחלקה חדשה `org.b1051c.l3.ClearAllAction.attach`, בסגנון של HomeBg.
  - המחלקה יוצרת Button בסגנון `OverviewActionButton` עם הטקסט `recents_clear_all`. הלחיצה קוראת ל-`dismissAllTasks(View)` (ברפלקשן), או ל-`getClearAllButton().performClick()`.
  - יש tag נגד הוספה כפולה, ומתג כיבוי ב-Settings.Secure.
  - אין שינויי משאבים.
- **איפה:** ‏`work/launcher3patch` (‏`build.sh`, ‏`src/org/b1051c/l3/`). מודול `b1051c-launcher3` 0.2.6.
- **לבדוק על הטאבלט:**
  - הכפתור מופיע מיד, גם ב-Recents מ-Lawnchair (RecentsActivity) וגם במסך הבית.
  - הסגירה מיידית כשהאנימציות כבויות.
  - חוזרים ל-Lawnchair.
  - הכפתור מוסתר כשאין משימות ובזמן split.
  - הכפתור המקורי שבסוף הגלילה לא מופיע יחד איתו.

### 3.2 המקלדת של ה-ROM: HeliBoard בעיצוב של Bigme ‏✅ (נבדק על הטאבלט, 11.11)
**החלטות הבעלים:** HeliBoard במקום מקלדת AOSP (בשביל swipe), כתב-יד דרך Gboard למי שרוצה, Enter ברוחב קבוע, popup on keypress כבוי. במכשיר: עברית ואנגלית. ב-ROM: שפת המערכת ועוד אנגלית (HeliBoard בוחר את זה לבד לפי שפות המערכת).
- **HeliBoard 4.2** (GitHub v4.2, sha256 ‏`69b2b2fb…`, חתום CN=Helium314 ‏`5ec0a531…`, ‏GPL-3.0, **בלי הרשאת אינטרנט**):
  - מותקן **כאפליקציית מערכת**: `/system/product/app/HeliBoard/HeliBoard.apk`, עם ה-libs שחולצו ל-`lib/arm64`.
  - כאפליקציית מערכת הוא יכול לטעון את ספריית ה-swipe של Google דרך המערכת (`System.loadLibrary("jni_latinimegoogle")`). כאפליקציה רגילה זה נכשל, כי הספרייה צריכה את `libc++.so` הפנימי.
  - ה-APK לא משתנה, כך שעדכונים מ-GitHub עדיין אפשריים.
- **ספריית ה-swipe:** ‏`libjni_latinimegoogle.so` מ-MindTheGapps 16 (‏`41a39a74…`) ב-`/system/product/lib64`. **נכנסת רק לגרסה עם Google**, בתוך מודול ה-gapps (כמו ש-MindTheGapps מפיץ אותה). בגרסה בלי Google אין swipe, אלא אם המשתמש טוען ספרייה בעצמו.
- **המראה (RRO `org.b1051c.overlay.heliboard`, ‏`work/app/heliboardoverlay`, ‏`c0e75af2`):**
  - מחליף את 4 הצורות של Rounded+borders ואת ה-spacebar (`()` ו-`v26`) ואת ה-popup: מסגרת שחורה 2dp, פינות 7dp, ופס קצר כשנלחץ.
  - HeliBoard צובע ב-MULTIPLY, ולכן המסגרת נשארת שחורה, ומקש הפעולה (accent) שחור מלא.
  - מצויר מאפס, בלי קבצים של Bigme.
- **הגדרות ברירת מחדל** (stage 2 כותב את `/data/user_de/0/helium314.keyboard/shared_prefs/helium314.keyboard_preferences.xml`; כאפליקציית מערכת HeliBoard שומר ב-DE):
  - `theme_style=Rounded`, ‏`theme_key_borders=true`, ‏`icon_style=Rounded`.
  - `theme_colors=theme_colors_night=InKognito` ו-`user_colors_InKognito` (‏JSON: רקע, מקשים, פונקציונליים ו-spacebar בלבן; טקסט, accent ושובל בשחור; רמזים וטקסט spacebar ב-#5A5A5A).
  - `navbar_color=true` (שורת הניווט לבנה כשהמקלדת פתוחה; פותר את ג').
  - `popup_on=false`.
  - `gesture_input=true`, ‏`gesture_preview_trail=true`, ‏`gesture_trail_fadeout_duration=400` (במקום 800, לפי הבעלים), ‏`gesture_floating_preview_text=false`, ‏`gesture_floating_preview_dynamic=false`, ‏`gesture_dynamic_preview_follow_system=false`.
  - **לכתוב עם XML parser בלבד**, לא עם `sed`: ‏`&` בהחלפה שבר את הקובץ, ו-HeliBoard איפס אותו ומחק את הספרייה.
- **מילון עברית:** ל-HeliBoard אין מילון עברי מובנה, ובלעדיו אין swipe ואין הצעות.
  - משתמשים ב-`main_he__experimental.dict` מ-Codeberg ‏Helium314/aosp-dictionaries (~505k מילים, 11.11.2025, עם next-word data, ‏**CC BY-SA 4.0**: Leipzig corpora + FrequencyWords). ‏sha ‏`150a3762…`.
  - stage 2 מעתיק אותו ל-`files/dicts/he/main_user.dict` (ב-DE), אבל רק אם שפת המערכת עברית או שהמשתמש הוסיף עברית. **לשקול:** מילונים לשפות נפוצות נוספות שחסרות ב-HeliBoard.
  - **CREDITS/LICENSES:** ‏HeliBoard (GPL-3.0, קישור למקור v4.2, ועדיף tarball של המקור ב-release שלנו) ומילון העברית (CC BY-SA 4.0, ייחוס).
- **המקלדות האחרות:** ‏AOSP LatinIME מקבל `pm disable-user` ב-ROM (הפיך, כמו אצלך), ו-HeliBoard היא המקלדת היחידה. ה-skin ל-AOSP (`work/app/latinimeoverlay`, מודול `b1051c-keyboard-skin`) **בוטל**.
- **כתב-יד:** ‏Gboard מ-Play למי שרוצה (‏KNOWN-ISSUES / INSTALL: "Handwriting: install Gboard").
- **הערה על הבדיקה:** הצעות מילים לא מופיעות בשדה החיפוש של Settings, כי הוא שולח `TYPE_TEXT_FLAG_NO_SUGGESTIONS` (0x90001). בודקים בשדה רגיל.
- **לבדוק:** swipe בעברית ובאנגלית, באצבע ובעט; הצעות בשדה רגיל; המסגרת והפס; ‏Enter באותו גודל בשתי השפות; שורת הניווט לבנה; ושההגדרות שורדות update.

### 3.3 "Bigme input method" ‏✅ הוסרה (11.11)
- היא נבדקה (מודול `b1051c-bigme-ime`) והוסרה לבקשת הבעלים. ‏`domain_*` ‏נמחקים, והמודול מסומן ל-remove. **לא נכנסת ל-ROM.**

### 3.4 מצב refresh ייעודי למקלדת ‏✅ עובד על הטאבלט (11.11, אושר על ידי הבעלים: "הכל נראה מצויין")
**החלטות הבעלים:** Fast להקלדה, A2 ל-swipe, ניקוי (GC16) של אזור המקלדת אחרי הפסקה, המקלדת עוקבת אחרי הדארק תים, וצליל מקשים דולק.
- **E-Ink Control 0.15.24** (APK ‏`9e110045`):
  - `AppProfileService.publishImeRect`: חלון `TYPE_INPUT_METHOD` (מ-`getBoundsInScreen`), ‏`setprop vendor.debug.hwc.shim.ime_rect "l,t,r,b,rot"` (פיקסלי תצוגה; ‏"0" כשאין מקלדת). נשלח רק כשמשתנה, ונרשם ב-log.
  - "Fast while typing" (2.3 B) נשאר כמו שהוא.
  - הגדרות חדשות: `ime_mode` (178), ‏`ime_swipe_mode` (16), ‏`ime_clean` (1500 ms), ושורות "Keyboard" ב-E-Ink Control.
- **eink-compat 0.1.54** (zip ‏`786462d0`, ‏shim ‏`e9962236`):
  - ממפה את המלבן לפיקסלי הפאנל (rotation 0).
  - שומר את ה-surface damage של כל שכבה. חלון המקלדת הוא שכבה של כמעט כל המסך, ולכן מסווגים לפי ה-damage ולא לפי ה-frame.
  - commit שכולו באזור המקלדת יוצא כאזור הזה בלבד: ‏Fast, או A2 כשמזוהה swipe (drag של einkd בתוך המקלדת, או רצף commits במרווח של 150 ms לכל היותר).
  - swipe באפליקציה שמשתנה בעצמה (Google עם תוצאות חיות): בזמן ה-swipe יוצא רק אזור המקלדת. שאר המסך נדחה ונשלח פעם אחת, במצב ובדיתור של האפליקציה, אחרי 150 ms של שקט (`ime_rest_ms`).
  - ניקוי: GC16 של אזור המקלדת אחרי 1500 ms (`ime_clean_ms`), כשהמסך שקט. ‏`recommit()` מקבל עכשיו אזור ו-dither אופציונליים.
  - drag בתוך המקלדת לא מפעיל את ניקוי סוף הגלילה של כל המסך.
- **נבדק:** ב-Settings ה-swipe יוצא ב-A2 ואחריו הניקוי. ב-Google השובל ב-A2 (‏~25 ms בין עדכונים) ועדכון אחד של שאר המסך אחרי ה-swipe.
- **פתוח (קל):** ב-Google ניקוי אזור המקלדת לא קרה בסשן הבדיקה. כנראה האפליקציה לא נותנת 1.5 שניות של שקט (סמן מהבהב). לבדוק אם זה מפריע.
- **גרסאות קודמות (rollback):** ‏eink-compat 0.1.49 עד 0.1.53, ו-einkctl 0.15.22 / 0.15.23.

## 4. המתקין (`work/rom/release-src/inkognito.py`)

| # | מה | מצב |
|---|---|---|
| 4.1 | **מד התקדמות חי** בכל קריאה וכתיבה ארוכה: גיבוי (~12.5 GB), כתיבת system, readback, ‏restore-stock. ‏`Mtk._run` קורא את שורות ה-`Progress: … % … MB/s … left` של mtkclient ומציג שורה אחת שמתעדכנת: שם החלק, אחוז, MB מתוך סה"כ, מהירות וזמן שנותר. הלוג המלא לא משתנה. בלי טרמינל (pipe) מציגים שורה כל 10%. (ממצא 1) | ✅ |
| 4.2 | **הודעת הסיום של `install`:** אחרי Google setup ו-InKognito Setup, ‏**לאתחל פעם אחת** (Settings › Restart). זה אמצעי זהירות עד שהתיקון של 1.1 יוכח על התקנה נקייה נוספת. (ממצא 14) | ✅ |
| 4.3 | **הודעת הסיום:** לאשר "Allow USB debugging?" כשמופיע, ולכבות USB debugging בסוף. (קיים ב-INSTALL, לא בהודעה) | ✅ |
| 4.4 | ✅ כבר ב-alpha 6: ‏stdin=DEVNULL, ביטול נקי ב-EOF, ‏`POWER_ON` אחרי reset. | ✅ |
| 4.5 | בדיקות: ‏unit ל-parser של Progress (שורות אמיתיות מהלוג של היום), ‏cli ו-sim. | ✅ |

## 5. מסמכים בתוך ה-zip (`work/rom/release-src`)

| קובץ | שינויים |
|---|---|
| **INSTALL.md** | **התחלה מהירה בראש הקובץ** (ממצא 2): עד 10 צעדים, פקודה אחת לכל צעד, ומה אמור להופיע אחרי כל צעד. ההסברים המלאים זזים ל"פרטים". **סעיף 4, Developer options על ה-stock של Bigme** (ממצא 3): ההגדרות של Bigme מסתירות את Build number. מתחברים ל-Wi-Fi, מורידים את **Activity Launcher** מ-F-Droid בדפדפן של הטאבלט (`f-droid.org/packages/de.szalkowski.activitylauncher`), פותחים Settings › About tablet, לוחצים 7 פעמים על Build number, ואחר כך Developer options › USB debugging. חלופה: Settings Shortcut מ-Google Play. **סעיף 8:** מד ההתקדמות. **סעיף 9:** ה-reboot אחרי ה-setup, והאזהרה אם תוכנה מבקשת root בדקה הראשונה. **סעיף 11 (Updating):** עדכון מ-alpha 6 שומר את הנתונים. **סעיף 13:** "הטאבלט נשאר כבוי או במסך טעינה אחרי ההתקנה". **סטטוס:** ‏install ו-restore-stock רצו על טאבלט אמיתי (11.11). |
| **README.md** | הסטטוס (install מלא ו-restore-stock נבדקו), התחלה מהירה בקצרה, Clear all ומראה המקלדת ברשימת הפיצ'רים, השם `InKognito B1051C Pro`. |
| **KNOWN-ISSUES.md** | **מוסיפים:** reboot אחד אחרי ה-setup (זמני); הודעת "Magisk needs internet" אם מבקשים root לפני ש-Magisk סיים (לסגור אותה); בקשת root כשהמסך כבוי נדחית אחרי 10 שניות. **מעדכנים:** מה עדיין לא נבדק (רק האימג' בלי Google). **מורידים:** "full install not yet run". |
| **CHANGELOG.md** | סעיף `alpha 7` עם כל הסעיפים 1–4 בשפה של משתמש. |
| **LICENSES.md / CREDITS.md** | אם ב-3.2 נשלחים קבצים של Bigme, לציין אותם. אם מציירים מחדש, אין שינוי. Activity Launcher לא נשלח, רק ממליצים עליו. |

## 6. פוסטים (לא מתפרסמים בלי אישור)

| איפה | שינויים |
|---|---|
| **XDA-POST.md / XDA-POST.html** | הגרסה alpha 7; ה-QUOTE בראש: install ו-update רצו על טאבלט אמיתי, האימג' בלי Google עוד לא; התחלה מהירה, כולל Activity Launcher ו-reboot אחרי setup; בפיצ'רים: Clear all ב-Recents ומראה המקלדת; Known issues לפי 5; קישורים ו-SHA-256 מחדש (`make_xda_page.py`); צילומי מסך מהתקנה נקייה (אפשר מהטאבלט שלך אחרי ההתקנה של היום, כי הוא נקי מ-0, בתנאי שאין שמות רשתות, חשבונות או ספרים). |
| **GITHUB-RELEASE.md** | תגית `alpha7-<תאריך>`; ‏"What changed" לפי 1–4; טבלת הורדות ו-SHA-256 מחדש; הסטטוס. |
| **הריפו הציבורי** | ‏`stage_public.py`, אחר כך `pii_scan` על ה-staging ועל כל ההיסטוריה, commit בזהות noreply, ו-push רק באישור. מודול `b1051c-bigme-ime` ו-APK של Bigme **לעולם לא** (ה-NEVER של `.apk`/`.zip` כבר מכסה את זה; לוודא שגם התיקייה מסוננת). |

## 7. בנייה ובדיקות

1. גרסאות: ‏payload.json ‏`version` ל-alpha 7. ‏`b1051c-launcher3` 0.2.6 (3.1), ה-RRO של המקלדת (3.2), ‏`RELEASE_NAME`, ‏README / INSTALL / KNOWN-ISSUES / XDA / CHANGELOG.
2. `build-rom.py payload` (‏`rm -rf work/rom/out/payload` קודם, עם `set -o pipefail`), ואחריו `image`, ‏`image --no-gapps`, ‏`pii_scan` ו-`release`, שמייצר zip ל-gapps ול-nogapps, כל אחד מתחת ל-2 GiB.
3. ‏dev-tests: ‏unit, ‏cli ו-sim.
4. **על הטאבלט שלך: update מ-alpha 6 ל-alpha 7.** זה ה-update האמיתי הראשון של טאבלט שה-ROM עצמו הגדיר, בלי הכנה ידנית. ממתין לאישור כתיבה.
   - provision.log: ‏8 פעמים updated ו-done.
   - השם: InKognito B1051C Pro.
   - Clear all.
   - מראה המקלדת.
   - 0 דחיות SELinux של ה-shim.
5. **מומלץ: התקנה נקייה נוספת** (restore-stock ואחריו install) רק כדי להוכיח את 1.1 ולבדוק אם עדיין צריך reboot אחרי ה-setup. לפי ההחלטה שלך: על הטאבלט שלך, או אצל בודק מ-XDA.

## 8. החלטות (נסגרו 11.11)
1. **3.2, הגרפיקה של המקלדת:** ✅ **מציירים מחדש בסגנון של Bigme.** לא נכנס אף קובץ של Bigme ל-ROM.
2. **7.5, התקנה נקייה נוספת:** ✅ **על הטאבלט שלך, אחרי ה-update ל-alpha 7**, בזמן שנוח לך.
3. **צילומי מסך ל-XDA:** ✅ **אחרי ההתקנה הנקייה הבאה**, לפני שמתווספים חשבון או ספרים.
