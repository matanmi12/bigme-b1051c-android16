--[[ InKognito / Bigme B1051C Pro (Android 16 GSI): KOReader drives the e-ink full refresh and the two-channel
front light through E-Ink Control (org.b1051c.einkctl, KoBridgeReceiver). Roadmap 24 + 25.1.

KOReader has no profile for this device, so it treats it as a plain tablet: it never asks for a full refresh and
its light settings move the Android window brightness, not the lm3630a front light. This early patch runs before
the device is created and:
  * reports an e-ink screen (platform "bigme") without partial-update support: every frame already reaches the
    panel through the HWC; a "full" refresh request (KOReader's "full refresh every N pages", chapter flashes)
    becomes one broadcast KO_REFRESH -> one settled GC16 of the final frame;
  * reports a front light with warmth and maps KOReader's intensity (0-100) + warmth (0-100) onto the cold/warm
    channels (raw 0-222): warmth 50 = both channels at the intensity level, 0 = cold only, 100 = warm only;
    broadcast KO_LIGHT, so the QS sliders follow. The start values come from E-Ink Control (Settings.Secure).
2-bigme-light-dialog.lua swaps the native Android light dialog for KOReader's own front-light widget.
Undo: delete both files from koreader/patches/.
]]
local ok, android = pcall(require, "android")
if not ok or type(android) ~= "table" or not android.app or not android.jni then return end
local ffi = require("ffi")
local logger = require("logger")

local PKG = "org.b1051c.einkctl"
local FL_MAX = 222

local function clearException(env)
    if env[0].ExceptionCheck(env) ~= 0 then
        env[0].ExceptionClear(env)
        return true
    end
    return false
end

-- Sent with the sender's identity shared (BroadcastOptions.setShareIdentityEnabled, Android 14+): E-Ink Control's
-- receiver accepts only broadcasts it can attribute to org.koreader.launcher (review 2026-10-21).
local function broadcast(action, extras)
    local sent = pcall(android.jni.context, android.jni, android.app.activity.vm, function(jni)
        local env = jni.env
        local cls = env[0].FindClass(env, "android/content/Intent")
        local ctor = env[0].GetMethodID(env, cls, "<init>", "(Ljava/lang/String;)V")
        local intent = env[0].NewObject(env, cls, ctor, env[0].NewStringUTF(env, action))
        jni:callObjectMethod(intent, "setPackage", "(Ljava/lang/String;)Landroid/content/Intent;",
            env[0].NewStringUTF(env, PKG))
        for k, v in pairs(extras or {}) do
            jni:callObjectMethod(intent, "putExtra", "(Ljava/lang/String;I)Landroid/content/Intent;",
                env[0].NewStringUTF(env, k), ffi.new("int32_t", v))
        end
        local optcls = env[0].FindClass(env, "android/app/BroadcastOptions")
        local mk = env[0].GetStaticMethodID(env, optcls, "makeBasic", "()Landroid/app/BroadcastOptions;")
        local opts = env[0].CallStaticObjectMethod(env, optcls, mk)
        jni:callObjectMethod(opts, "setShareIdentityEnabled", "(Z)Landroid/app/BroadcastOptions;", ffi.new("int32_t", 1))
        local bundle = jni:callObjectMethod(opts, "toBundle", "()Landroid/os/Bundle;")
        jni:callVoidMethod(android.app.activity.clazz, "sendBroadcast",
            "(Landroid/content/Intent;Ljava/lang/String;Landroid/os/Bundle;)V", intent, nil, bundle)
        env[0].DeleteLocalRef(env, optcls)
        env[0].DeleteLocalRef(env, cls)
        if clearException(env) then logger.warn("bigme-eink: broadcast failed", action) end
    end)
    if not sent then logger.warn("bigme-eink: JNI error on", action) end
end

local function secureInt(name, default)
    local okr, v = pcall(android.jni.context, android.jni, android.app.activity.vm, function(jni)
        local env = jni.env
        local cr = jni:callObjectMethod(android.app.activity.clazz, "getContentResolver",
            "()Landroid/content/ContentResolver;")
        local r = jni:callStaticIntMethod("android/provider/Settings$Secure", "getInt",
            "(Landroid/content/ContentResolver;Ljava/lang/String;I)I",
            cr, env[0].NewStringUTF(env, name), ffi.new("int32_t", default))
        if clearException(env) then return default end
        return tonumber(r)
    end)
    return okr and v or default
end

-- e-ink -----------------------------------------------------------------------------------------------------
local FULL, PARTIAL, FULL_UI, PARTIAL_UI, FAST = 1, 2, 3, 4, 5
android.isEink = function() return true, "bigme" end
android.isEinkFull = function() return false end
android.getEinkConstants = function() return FULL, PARTIAL, FULL_UI, PARTIAL_UI, FAST, 0, 0, 0 end
android.einkUpdate = function(mode)
    if mode == FULL or mode == FULL_UI then broadcast(PKG .. ".KO_REFRESH") end
end

-- front light -----------------------------------------------------------------------------------------------
-- level 0..FL_MAX = the brighter channel, warmth 0..100 = crossfade from cold to warm. E-Ink Control publishes the
-- channels it applies in Settings.Secure (the QS sliders and its own screen go through it too); 0/0 = light off.
-- Review 2026-10-21 (three Medium findings): the published values are read again before every light action, a
-- start with the light off keeps a usable level so that Toggle works, and a level set from the widget or a gesture
-- means "on".
local DEFAULT_LEVEL = math.floor(40 * FL_MAX / 100 + 0.5)   -- when nothing better is known

local function fromChannels(c, w)
    local level, warmth = math.max(c, w), 50
    if c + w > 0 then
        warmth = c >= w and math.floor(50 * w / c + 0.5) or math.floor(100 - 50 * c / w + 0.5)
    end
    return level, warmth
end

local cold0, warm0 = secureInt("b1051c_fl_cold", 0), secureInt("b1051c_fl_warm", 0)
local light = { level = 0, warmth = 50, off = false }
light.level, light.warmth = fromChannels(cold0, warm0)
if light.level == 0 then
    -- off at start (fresh device, or switched off before KOReader opened): BasePowerD:turnOnFrontlight does nothing
    -- while fl_intensity == fl_min, so keep a level and remember only that the light is off
    light.level, light.off = DEFAULT_LEVEL, true
end
local seen = { cold = cold0, warm = warm0 }   -- the published values this patch last read or wrote

local function channels(level, warmth)
    local c = level * math.min(1, (100 - warmth) / 50)
    local w = level * math.min(1, warmth / 50)
    return math.floor(c + 0.5), math.floor(w + 0.5)
end

local function pushLight(level)
    local c, w = channels(level, light.warmth)
    seen.cold, seen.warm = c, w
    broadcast(PKG .. ".KO_LIGHT", { cold = c, warm = w })
end

-- A change made outside KOReader while it runs (QS sliders, E-Ink Control): take it over before acting on the
-- light, else the next KOReader light action would push the old level and mix back.
local function syncFromSystem(self)
    local c, w = secureInt("b1051c_fl_cold", -1), secureInt("b1051c_fl_warm", -1)
    if c < 0 or w < 0 or (c == seen.cold and w == seen.warm) then return end
    seen.cold, seen.warm = c, w
    if c + w == 0 then
        light.off = true                                    -- level and warmth stay, for the next Toggle
    else
        light.off = false
        light.level, light.warmth = fromChannels(c, w)
    end
    self.fl_intensity = self:frontlightIntensityHW()
    self.fl_warmth = light.warmth
    self:_decideFrontlightState()
    logger.dbg("bigme-eink: light changed outside KOReader:", c, w, "-> level", light.level, "warmth", light.warmth, "off", light.off)
end

android.hasLights = function() return true end
android.isWarmthDevice = function() return true end
android.hasStandaloneWarmth = function() return true end

local BasePowerD = require("device/generic/powerd")
local AndroidPowerD = require("device/android/powerd")
function AndroidPowerD:init()
    self.fl_min, self.fl_max = 0, 100
    self.fl_warmth_min, self.fl_warmth_max = 0, 100
    self.bright_diff, self.warm_diff = FL_MAX, 100
end
-- the cached values KOReader's widget, gestures and toggles start from: synced with the system first
function AndroidPowerD:frontlightIntensity()
    syncFromSystem(self)
    return BasePowerD.frontlightIntensity(self)
end
function AndroidPowerD:frontlightWarmth()
    syncFromSystem(self)
    return BasePowerD.frontlightWarmth(self)
end
function AndroidPowerD:isFrontlightOn()
    syncFromSystem(self)
    return BasePowerD.isFrontlightOn(self)
end
function AndroidPowerD:frontlightIntensityHW()
    return math.floor(light.level * 100 / FL_MAX + 0.5)
end
function AndroidPowerD:setIntensityHW(intensity)
    syncFromSystem(self)
    self.fl_intensity = intensity
    light.level = math.floor(intensity * FL_MAX / 100 + 0.5)
    light.off = light.level <= 0                            -- a level from the widget or a gesture means "on"
    pushLight(light.level)
    self:_decideFrontlightState()
end
function AndroidPowerD:frontlightWarmthHW() return light.warmth end
function AndroidPowerD:setWarmthHW(warmth)
    syncFromSystem(self)
    light.warmth = math.max(0, math.min(100, warmth))
    if self:isFrontlightOn() then pushLight(light.level) end
end
function AndroidPowerD:isFrontlightOnHW() return light.level > 0 and not light.off end
function AndroidPowerD:turnOffFrontlightHW()
    light.off = true
    seen.cold, seen.warm = 0, 0
    broadcast(PKG .. ".KO_LIGHT", { cold = 0, warm = 0 })
end
function AndroidPowerD:turnOnFrontlightHW()
    light.off = false
    if light.level <= 0 then light.level = DEFAULT_LEVEL end
    pushLight(light.level)
    return false
end

logger.info("bigme-eink: e-ink + front light via E-Ink Control; light", cold0, warm0, "-> level", light.level, "warmth", light.warmth, "off", light.off)

-- Self-test for the bridge (adb): with the marker file present, one KO_LIGHT with the current values goes out at
-- start; E-Ink Control logs whether it accepted the sender. Nothing changes on the panel.
local marker = io.open("/sdcard/koreader/patches/.bridge-test", "r")
if marker then
    marker:close()
    broadcast(PKG .. ".KO_LIGHT", { cold = cold0, warm = warm0 })
    logger.info("bigme-eink: bridge self-test sent (KO_LIGHT", cold0, warm0, ")")
end
