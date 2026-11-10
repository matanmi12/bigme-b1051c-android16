--[[ InKognito / Bigme B1051C Pro (Android 16 GSI): the cover of the book KOReader opens becomes the "Book cover"
sleep picture of the Lock & Sleep app (org.b1051c.sleep 0.2.0+, ROADMAP 18 c). Roadmap wave 5.

A few seconds after a book opens, its cover (KOReader's own choice: a custom cover when one is set) is scaled down to
the page size if it is larger, written to <KOReader data dir>/inkognito-cover.png and announced with a COVER broadcast
sent with the sender's identity shared (BroadcastOptions.setShareIdentityEnabled, as in 1-bigme-eink.lua): Lock &
Sleep accepts COVER only when Android names KOReader as the sender. The app copies the file and renders the sleep
picture ahead of the next sleep, so going to sleep is never delayed. The last book's cover stays until another opens.
A late patch (prefix 2): ReaderUI exists before the first book opens.
Undo: delete this file from koreader/patches/ (and inkognito-cover.png from the KOReader folder).
]]
local ok, android = pcall(require, "android")
if not ok or type(android) ~= "table" or not android.app or not android.jni then return end
local ffi = require("ffi")
local logger = require("logger")
local DataStorage = require("datastorage")
local ReaderUI = require("apps/reader/readerui")
local UIManager = require("ui/uimanager")

local PKG = "org.b1051c.sleep"
local OUT = DataStorage:getDataDir() .. "/inkognito-cover.png"
local PAGE_W, PAGE_H = 1860, 2480
local DELAY_S = 3               -- after the book is on screen: the export never competes with opening it
local last_file

local function clearException(env)
    if env[0].ExceptionCheck(env) ~= 0 then
        env[0].ExceptionClear(env)
        return true
    end
    return false
end

-- NewStringUTF takes modified UTF-8: characters outside the BMP (emoji) would abort the VM, so they are dropped.
local function jstr(env, s)
    s = tostring(s or ""):gsub("[\240-\247][\128-\191][\128-\191][\128-\191]", "")
    return env[0].NewStringUTF(env, s)
end

local function sendCover(title)
    local sent = pcall(android.jni.context, android.jni, android.app.activity.vm, function(jni)
        local env = jni.env
        local cls = env[0].FindClass(env, "android/content/Intent")
        local ctor = env[0].GetMethodID(env, cls, "<init>", "(Ljava/lang/String;)V")
        local intent = env[0].NewObject(env, cls, ctor, jstr(env, PKG .. ".COVER"))
        jni:callObjectMethod(intent, "setPackage", "(Ljava/lang/String;)Landroid/content/Intent;", jstr(env, PKG))
        jni:callObjectMethod(intent, "addFlags", "(I)Landroid/content/Intent;", ffi.new("int32_t", 0x20))   -- FLAG_INCLUDE_STOPPED_PACKAGES
        jni:callObjectMethod(intent, "putExtra", "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;",
            jstr(env, "title"), jstr(env, title))
        local optcls = env[0].FindClass(env, "android/app/BroadcastOptions")
        local mk = env[0].GetStaticMethodID(env, optcls, "makeBasic", "()Landroid/app/BroadcastOptions;")
        local opts = env[0].CallStaticObjectMethod(env, optcls, mk)
        jni:callObjectMethod(opts, "setShareIdentityEnabled", "(Z)Landroid/app/BroadcastOptions;", ffi.new("int32_t", 1))
        local bundle = jni:callObjectMethod(opts, "toBundle", "()Landroid/os/Bundle;")
        jni:callVoidMethod(android.app.activity.clazz, "sendBroadcast",
            "(Landroid/content/Intent;Ljava/lang/String;Landroid/os/Bundle;)V", intent, nil, bundle)
        env[0].DeleteLocalRef(env, optcls)
        env[0].DeleteLocalRef(env, cls)
        if clearException(env) then logger.warn("inkognito-cover: broadcast failed") end
    end)
    if not sent then logger.warn("inkognito-cover: JNI error on COVER") end
end

local function exportCover(ui)
    if not ui or ui.tearing_down or not ui.document then return end   -- closed again within DELAY_S
    local file = ui.document.file
    if not file or file == last_file then return end
    local got, bb = pcall(function()
        return require("apps/filemanager/filemanagerbookinfo"):getCoverImage(ui.document)
    end)
    if not got or not bb then
        logger.info("inkognito-cover: no cover for", file)
        return
    end
    local w, h = bb:getWidth(), bb:getHeight()
    local k = math.min(1, PAGE_W / w, PAGE_H / h)
    if k < 1 then
        bb = require("ui/renderimage"):scaleBlitBuffer(bb, math.floor(w * k), math.floor(h * k), true)
    end
    local tmp = OUT .. ".tmp"
    local wrote, err = bb:writeToFile(tmp, "png")
    bb:free()
    if not wrote then
        logger.warn("inkognito-cover: writing", tmp, "failed:", err)
        os.remove(tmp)
        return
    end
    os.rename(tmp, OUT)
    last_file = file
    local props = ui.doc_props or {}
    sendCover(props.display_title or props.title or file:match("([^/]+)$"))
    logger.info("inkognito-cover: cover of", file, "exported", w, "x", h, "scale", k)
end

local orig_init = ReaderUI.init
ReaderUI.init = function(self, ...)
    local r = orig_init(self, ...)
    local ui = self
    UIManager:scheduleIn(DELAY_S, function() pcall(exportCover, ui) end)
    return r
end

logger.info("inkognito-cover: ready, covers go to", OUT)
