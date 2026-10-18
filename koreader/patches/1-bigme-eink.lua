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
        jni:callVoidMethod(android.app.activity.clazz, "sendBroadcast", "(Landroid/content/Intent;)V", intent)
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
-- level 0..FL_MAX = the brighter channel, warmth 0..100 = crossfade from cold to warm
local cold0, warm0 = secureInt("b1051c_fl_cold", 0), secureInt("b1051c_fl_warm", 0)
local light = { level = math.max(cold0, warm0), warmth = 50 }
if cold0 + warm0 > 0 then
    light.warmth = cold0 >= warm0 and math.floor(50 * warm0 / cold0 + 0.5) or math.floor(100 - 50 * cold0 / warm0 + 0.5)
end

local function channels(level, warmth)
    local c = level * math.min(1, (100 - warmth) / 50)
    local w = level * math.min(1, warmth / 50)
    return math.floor(c + 0.5), math.floor(w + 0.5)
end

local function pushLight(level)
    local c, w = channels(level, light.warmth)
    broadcast(PKG .. ".KO_LIGHT", { cold = c, warm = w })
end

android.hasLights = function() return true end
android.isWarmthDevice = function() return true end
android.hasStandaloneWarmth = function() return true end

local AndroidPowerD = require("device/android/powerd")
function AndroidPowerD:init()
    self.fl_min, self.fl_max = 0, 100
    self.fl_warmth_min, self.fl_warmth_max = 0, 100
    self.bright_diff, self.warm_diff = FL_MAX, 100
end
function AndroidPowerD:frontlightIntensityHW()
    return math.floor(light.level * 100 / FL_MAX + 0.5)
end
function AndroidPowerD:setIntensityHW(intensity)
    self.fl_intensity = intensity
    light.level = math.floor(intensity * FL_MAX / 100 + 0.5)
    pushLight(light.level)
    self:_decideFrontlightState()
end
function AndroidPowerD:frontlightWarmthHW() return light.warmth end
function AndroidPowerD:setWarmthHW(warmth)
    light.warmth = math.max(0, math.min(100, warmth))
    if self:isFrontlightOn() then pushLight(light.level) end
end
function AndroidPowerD:isFrontlightOnHW() return light.level > 0 and not light.off end
function AndroidPowerD:turnOffFrontlightHW()
    light.off = true
    broadcast(PKG .. ".KO_LIGHT", { cold = 0, warm = 0 })
end
function AndroidPowerD:turnOnFrontlightHW()
    light.off = false
    if light.level == 0 then light.level = math.floor((self.fl_intensity or 50) * FL_MAX / 100 + 0.5) end
    pushLight(light.level)
    return false
end

logger.info("bigme-eink: e-ink + front light via E-Ink Control; light", cold0, warm0, "-> level", light.level, "warmth", light.warmth)
