// hwcshim -- HWC2 interposer for Bigme's hwcomposer.mt6877.so.
// B1051C Pro port (2026-10-17) of awong-dev/hibreak-pro-color tools/hwcshim:
//   our HWC BuildID is bdef0c8d90fec30662441f364f4f06b7, so the BuildID-gated
//   OverlayPortParam/CommitInfo peeks are DISABLED. The RGBA fix therefore takes the
//   client-target fd from vendor-API/ABI sources captured in setClientTarget:
//   gralloc_extra_query(handle, GRALLOC_EXTRA_GET_ION_FD=1) or native_handle fd[0].
//   knob: vendor.debug.hwc.shim.ct_src  0 auto (peek>gralloc>handle), 1 peek only,
//                                       2 gralloc ion fd, 3 native_handle fd[0]
//
// THE FIX (2026-09-17): on a GSI the HWC pre-converts the RGBA client target
// to gray8 on the CPU (OverlayEngine::processImage -> convertToGray) and
// commits that buffer, but the kernel's DRM_IOCTL_EINK_UPDATE path reads the
// fd as 4 bytes/pixel and converts to gray itself. Result: every 4 gray pixels
// collapse into one -> the frame tiled 4x. With commit_use_ct (default on) the
// commit's fd is swapped for the RGBA client target and the panel is correct.
//
// Installed as /vendor/lib64/hw/hwcomposer.mtk_common.so. libhardware tries
// `hwcomposer.<ro.hardware.hwcomposer>.so` (= mtk_common) before
// `hwcomposer.<ro.hardware>.so` (= mt6877), so this module wins without the
// original being renamed. It dlopens the real HWC, patches getFunction on the
// hwc2_device it returns, and hands back that same device -- so every real
// entry point keeps receiving the device pointer it expects.
//
// Two layers of visibility:
//
//   IN  -- what SurfaceFlinger hands the HWC over HWC2: layers, composition
//          types, the client target and its gralloc geometry/format.
//   OUT -- what the HWC hands the kernel. The real HWC calls its own exported
//          functions and libdrm/libc through the PLT (it is not linked
//          -Bsymbolic), so we rewrite its GOT slots for:
//            xrz::EffectHandler::convertToGray / imageSmoothing
//            OverlayEngine::convertLayerToGray / processImage
//            mtk_commit(drm_eink_update)       -> DRM_IOCTL_EINK_UPDATE
//            drmModeAddFB2WithModifiers / drmModeAtomicCommit / drmModeSetCrtc
//            ioctl
//
// Struct offsets marked BUILD-SPECIFIC were recovered by disassembling the
// HWC with BuildID 8a5cbea66efcb94a1a6cfdb53a239598 (firmware
// Bigme_HiBreak_V1.0_20251125). They are only used when the loaded library's
// BuildID matches; otherwise those peeks are skipped and only ABI-defined
// hooks stay active.
//
// Runtime knobs (all readable by the composer domain: `vendor.debug.hwc.`
// maps to vendor_mtk_hwc_debug_log_prop; set them from a root shell, then
// `pkill -f composer@2.3` -- init respawns it and SF reconnects):
//
//   vendor.debug.hwc.shim.log           0 quiet, 1 normal (default), 2 verbose
//   vendor.debug.hwc.shim.refresh_mode  -1 never call setLayerRefreshMode
//                                       (default); otherwise the EinkRefreshMode
//                                       to apply to every layer (178 = NORMAL)
//   vendor.debug.hwc.shim.preview       N -> ASCII-preview the next N gray
//                                       conversions and eink commits
//   vendor.debug.hwc.shim.disable       1 -> pure passthrough, no hooks
//   vendor.debug.hwc.shim.commit_stride  N  -> rewrite drm_eink_update.stride before the ioctl (0 = leave)
//   vendor.debug.hwc.shim.commit_format  N  -> rewrite .format (-1 = leave)
//   vendor.debug.hwc.shim.commit_mode    N  -> rewrite .mode   (-1 = leave)
//   vendor.debug.hwc.shim.commit_height  N  -> rewrite .height (0 = leave)
//   vendor.debug.hwc.shim.commit_use_ct  1 (DEFAULT; 0 = tiled baseline) -> commit the RGBA client target itself
//                                          instead of the HWC's CPU-converted gray8
//                                          buffer. The kernel (eink_pre_process ->
//                                          0x48c3f8c4) reads the fd at 4 B/px, pitch
//                                          = width*4, and runs its own rgb->gray; the
//                                          gray8 buffer is what produces the 4:1 tiling.
//   NOTE: `stride`/`height` in drm_eink_update are really the source WIDTH/HEIGHT
//   (the kernel clamps the update rect to x,y,x+w-1,y+h-1); +0x30/+0x34 are x/y.
//   Sleep picture (Lock & Sleep app, ROADMAP feature 18; module 0.1.35):
//   vendor.debug.hwc.shim.standby        N  -> frame #N is ready in standby_path (2480x1860 RGBX, 18,451,200 B); it is
//                                             committed as one GC16 when SurfaceFlinger turns the panel off, so the
//                                             bistable panel keeps it while the tablet sleeps. 0 = none (transparent).
//   vendor.debug.hwc.shim.standby_path   file (default /data/vendor/b1051c/sleep/frame.rgbx)
//   vendor.debug.hwc.shim.standby_test   N  -> commit the loaded frame now, display on (A/B and the orientation check)
//   vendor.debug.hwc.shim.standby_wake_gc16  1 (default) -> one settled full refresh after the first commit of a wake
//   vendor.debug.hwc.shim.standby_dither -1 (default: the last commit's value) or the dither field of that GC16
//   vendor.debug.hwc.shim.standby_hold_ms 0  -> wait this long between our commit and the vendor's OFF
//   Time of sleep (Lock & Sleep 0.2.0; module 0.1.48): clock.bin next to the frame holds the app's glyphs for
//   "0-9 : AM PM" and an empty box of the frame; the shim writes the local time into that box at OFF.
//   vendor.debug.hwc.shim.standby_clock  1 (default) -> draw it; 0 = leave the box empty
//
// No STL, no libc++: the only DT_NEEDED are liblog, libdl, libc.

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <inttypes.h>
#include <link.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <poll.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>
#include <math.h>
#include <linux/dma-buf.h>

#include "lut_data.h"   // stock Bigme 3D LUTs g_c001 / g_c002 (colour pipeline)

#define TAG "hwcshim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGV(...) do { if (g_log >= 2) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__); } while (0)

static const char* REAL_HWC = "/vendor/lib64/hw/hwcomposer.mt6877.so";
static const char* REAL_HWC_BASENAME = "hwcomposer.mt6877.so";
static const uint8_t KNOWN_BUILD_ID[16] = {0x8a,0x5c,0xbe,0xa6,0x6e,0xfc,0xb9,0x4a,
                                           0x1a,0x6c,0xfd,0xb5,0x3a,0x23,0x95,0x98};
// Bigme B1051C Pro, firmware B1051C_R_4.9.0 (vendor mp1V15125): the build whose CommitInfo+0x8b and
// OverlayPortParam+0x78/+0x178 were disassembled for the processImage crash guard (review 2026-10-21: that guard
// ran ungated on any HWC exporting the symbol).
static const uint8_t KNOWN_BUILD_ID_B1051C[16] = {0xbd,0xef,0x0c,0x8d,0x90,0xfe,0xc3,0x06,
                                                  0x62,0x44,0x1f,0x36,0x4f,0x4f,0x06,0xb7};

// ---------------------------------------------------------------- libhardware
// Minimal copies of hardware/libhardware/include/hardware/{hardware,hwcomposer2}.h
// (LP64 layout). The NDK does not ship them.

struct hw_module_t;
struct hw_device_t;
struct hw_module_methods_t {
    int (*open)(const hw_module_t*, const char*, hw_device_t**);
};
struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char* id;
    const char* name;
    const char* author;
    hw_module_methods_t* methods;
    void* dso;
    uint64_t reserved[32 - 7];
};
struct hw_device_t {
    uint32_t tag;
    uint32_t version;
    hw_module_t* module;
    uint64_t reserved[12];
    int (*close)(hw_device_t*);
};
#define MAKE_TAG_CONSTANT(A,B,C,D) (((A) << 24) | ((B) << 16) | ((C) << 8) | (D))
#define HARDWARE_MODULE_TAG MAKE_TAG_CONSTANT('H','W','M','T')

typedef void (*hwc2_function_pointer_t)();
struct hwc2_device_t {
    hw_device_t common;
    void (*getCapabilities)(hwc2_device_t*, uint32_t* outCount, int32_t* outCapabilities);
    hwc2_function_pointer_t (*getFunction)(hwc2_device_t*, int32_t descriptor);
};

typedef uint64_t hwc2_display_t;
typedef uint64_t hwc2_layer_t;
struct native_handle_t { int version; int numFds; int numInts; int data[0]; };
typedef const native_handle_t* buffer_handle_t;
struct hwc_rect_t { int32_t left, top, right, bottom; };
struct hwc_frect_t { float left, top, right, bottom; };
struct hwc_region_t { size_t numRects; const hwc_rect_t* rects; };

enum {
    FN_ACCEPT_DISPLAY_CHANGES = 1, FN_CREATE_LAYER = 2, FN_DESTROY_LAYER = 4,
    FN_DUMP = 6, FN_GET_CHANGED_COMPOSITION_TYPES = 8, FN_GET_CLIENT_TARGET_SUPPORT = 9,
    FN_GET_DISPLAY_REQUESTS = 14, FN_PRESENT_DISPLAY = 20, FN_REGISTER_CALLBACK = 21,
    FN_SET_CLIENT_TARGET = 23, FN_SET_LAYER_BLEND_MODE = 27, FN_SET_LAYER_BUFFER = 28,
    FN_SET_LAYER_COMPOSITION_TYPE = 30, FN_SET_LAYER_DATASPACE = 31,
    FN_SET_LAYER_DISPLAY_FRAME = 32, FN_SET_LAYER_PLANE_ALPHA = 33,
    FN_SET_LAYER_SOURCE_CROP = 35, FN_SET_LAYER_SURFACE_DAMAGE = 36, FN_SET_LAYER_TRANSFORM = 37, FN_SET_LAYER_Z_ORDER = 39,
    FN_SET_POWER_MODE = 41, FN_VALIDATE_DISPLAY = 43,
    // Bigme extension. Recovered from the vendor composer@2.3-service: its
    // initDispatch requests descriptor 71 into the slot used by
    // "HwcHal setLayerRefreshMode mode=%x"; the HWC side is
    // HWCMediator::layerStateSetRefreshMode(hwc2_device*, ulong, ulong, int).
    FN_BIGME_SET_LAYER_REFRESH_MODE = 71,
    FN_MAX_PROBE = 128,
};

typedef int32_t (*PFN_acceptDisplayChanges)(hwc2_device_t*, hwc2_display_t);
typedef int32_t (*PFN_createLayer)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t*);
typedef int32_t (*PFN_destroyLayer)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t);
typedef void    (*PFN_dump)(hwc2_device_t*, uint32_t* outSize, char* outBuffer);
typedef int32_t (*PFN_getChangedCompositionTypes)(hwc2_device_t*, hwc2_display_t, uint32_t*, hwc2_layer_t*, int32_t*);
typedef int32_t (*PFN_getClientTargetSupport)(hwc2_device_t*, hwc2_display_t, uint32_t, uint32_t, int32_t, int32_t);
typedef int32_t (*PFN_getDisplayRequests)(hwc2_device_t*, hwc2_display_t, int32_t*, uint32_t*, hwc2_layer_t*, int32_t*);
typedef int32_t (*PFN_presentDisplay)(hwc2_device_t*, hwc2_display_t, int32_t* outFence);
typedef int32_t (*PFN_setClientTarget)(hwc2_device_t*, hwc2_display_t, buffer_handle_t, int32_t, int32_t, hwc_region_t);
typedef int32_t (*PFN_setLayerBlendMode)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, int32_t);
typedef int32_t (*PFN_setLayerBuffer)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, buffer_handle_t, int32_t);
typedef int32_t (*PFN_setLayerCompositionType)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, int32_t);
typedef int32_t (*PFN_setLayerDataspace)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, int32_t);
typedef int32_t (*PFN_setLayerDisplayFrame)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, hwc_rect_t);
typedef int32_t (*PFN_setLayerPlaneAlpha)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, float);
typedef int32_t (*PFN_setLayerSourceCrop)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, hwc_frect_t);
typedef int32_t (*PFN_setLayerSurfaceDamage)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, hwc_region_t);
typedef int32_t (*PFN_setLayerTransform)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, int32_t);
typedef int32_t (*PFN_setLayerZOrder)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, uint32_t);
typedef int32_t (*PFN_setPowerMode)(hwc2_device_t*, hwc2_display_t, int32_t);
typedef int32_t (*PFN_validateDisplay)(hwc2_device_t*, hwc2_display_t, uint32_t*, uint32_t*);
typedef int32_t (*PFN_bigmeSetLayerRefreshMode)(hwc2_device_t*, hwc2_display_t, hwc2_layer_t, int32_t);

// ---------------------------------------------------------------- state

static void* g_real_handle;
static hw_module_t* g_real_module;
static hwc2_device_t* g_dev;
static hwc2_function_pointer_t (*g_real_getFunction)(hwc2_device_t*, int32_t);
static hwc2_function_pointer_t g_real_fn[FN_MAX_PROBE];
static bool g_probed;
static bool g_build_id_ok;      // gate for BUILD-SPECIFIC struct peeks (HiBreak build, see file header)
static bool g_b1051c_ok;        // gate for the B1051C-build peeks (processImage guard)
static int  g_log = 1;
static bool g_disabled;
static int  g_refresh_mode = -1;
static int  g_refresh_mode_applied = -1;
static int  g_preview_budget;   // decremented per preview; refilled from prop
static int  g_commit_stride, g_commit_height;
static int  g_commit_format = -1, g_commit_mode = -1;
static int  g_commit_use_ct = 1;
static int  g_last_ct_fd = -1;      // OverlayPortParam+0x78 seen at processImage/convertLayerToGray IN
static int  g_ct_src = 0;           // B1051C port: where the client-target fd comes from (see header)
static int  g_ct_ion_fd = -1;       // gralloc_extra_query(ct, 1) at setClientTarget (-1 = query failed)
static int  g_ct_handle_fd0 = -1;   // ct native_handle->data[0] at setClientTarget
static uint64_t g_ct_ms;            // when SF handed that client target over (ms; under g_lock)
// The last client targets with their acquire fences (review 2026-10-21: the commit used the newest client target
// whose GPU rendering may not have finished; the vendor fenced only its own job's). Newest first; under g_lock.
struct CtRef { int fd; int fence; uint64_t seq; };   // fd = gralloc ion fd; fence = our dup of SF's acquire fence (-1 = none / signalled)
#define CT_RING 4
static CtRef g_ctring[CT_RING] = {{-1, -1, 0}, {-1, -1, 0}, {-1, -1, 0}, {-1, -1, 0}};
static uint64_t g_ct_seq;
static uint64_t g_ct_waits, g_ct_fallbacks;   // stats: fence waits that blocked; older target picked

// Colour pipeline state (see "colour pipeline" below); read from props in refresh_knobs().
// Stock knobs (sat/con/bri/mode) plus the wave 3 stages (ROADMAP wave 3 step 4; vendor.debug.hwc.shim.*, all 0 = off):
//   level_black 0..128  input black point (8-bit): everything at or below it becomes black ("text darkening")
//   level_white 0..128  255 minus the input white point: everything at or above it becomes white ("background bleach")
//   ok_sat   -100..300  chroma change in OKLCh, in %, with ok_neutral 0..100 (chroma, 1/1000) below which greys are
//                       protected (scaled by (C/n)^2)
//   tone     -100..100  contrast around mid-grey on OKLab lightness, in %
//   lut      -1/0/1     the stock 3D LUT: -1 as stock (with vivid only), 0 never, 1 always
//   cal      0 / N      the panel calibration table (calibration step 4, 2026-10-26): 33^3 RGB8 from build_lut.py in
//                       /data/vendor/b1051c/color/cal.lut, measured with every other stage off, so it replaces them
//                       (levels stay); N > 0 = on, a new N reloads the file. Missing / wrong size = the stages as usual.
struct ColorParams { int sat, con, bri, mode, lb, lw, oks, okn, tone, lut, cal; };
static ColorParams g_cp_want = {0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0}, g_cp_built = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -2, -1};
// What this commit uses: g_cp_want, minus vivid in the fast modes (ROADMAP wave 3 step 1, 11a: Fast / Extreme / A2
// without the 3D LUT; vendor.debug.hwc.shim.vivid_fast=1 keeps it). Set per vendor commit, read by the ink paths too.
static ColorParams g_cp_eff = {0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0};
static int g_vivid_fast = 0;
// Dither knobs (research/phase-d/color/KERNEL-DITHER.md, HD256-SMOOTHING.md; all off by default):
//   smooth 1       stock "HD 256" smoothing on our own pass in the 16-level modes (177, 180): Floyd-Steinberg per
//                  channel to the panel's 16 levels, 8 strips, a pixel whose input did not change keeps its output
//                  (no crawling grain); the kernel's floor to 4 bits then keeps every value. Not in Fast / Extreme.
//   fast_dither N  the kernel dither of Fast / Extreme commits: -1 = the vendor's (3 / 6), 0..8 = that type
//   gc16_prep 1    a requested full refresh after a Fast / Extreme / A2 update is sent with dither 0 (it re-sent
//                  the fast commit's dither 3 / 6 and came out 1-bit); 2 = also a GU16 with dither 0 before it
static int g_smooth = 0, g_fast_dither = -1, g_gc16_prep = 0;
// Auto full refresh: the vendor HWC's "auto global flush" (clean_frequency) only turns a rect commit into a
// full-area commit in the current mode; our commits are always full-area, so on the GSI it changes nothing.
// We count page turns the same way (check_commit raised by einkd, >= 800 ms apart) and make every N-th a GC16.
static int g_clean_freq = 0, g_check_commit = 0, g_auto_gc16 = 1;
// Settled full refresh: ONE GC16 of the final frame instead of GC16 on every frame of a screen change (owner
// 2026-10-18: "too many flashes"). vendor.debug.hwc.shim.gc16_req is a counter the app bumps; each new value, and
// every clean_frequency-th page turn (auto_gc16 1; 2 = the old in-place rewrite), asks for one GC16 once commits
// have been quiet for vendor.debug.hwc.shim.gc16_quiet_ms (default 350, at most 2.5 s after the request). A helper
// thread re-sends the last commit with mode 4, so it also works on a static screen, where the HWC commits nothing.
static pthread_mutex_t g_commit_lock = PTHREAD_MUTEX_INITIALIZER;   // one mtk_commit at a time (HWC + helper)
static uint64_t g_last_commit_ms;   // atomic
static uint32_t g_last_vendor_mode = 180;   // atomic: e-ink mode of the last vendor commit (the GC16 helper's colour stages)
static int g_gc16_internal;         // atomic: auto-N request from the HWC thread
static int g_turns;                 // page turns since the last GC16 (HWC thread; reset by the helper too)
// Stock counts a "page" only when the update covers >= 40 % of the screen (HWC countFlushLimit, const 0.4); we
// counted every touch-driven commit (ours are always full-area), so small taps added up too. SF's surface damage
// is no help (it is not re-sent for static layers, so stale full damage lingers), so we measure the picture:
// one sample per 8x8 cell of the client target, compared with the frame at the previous candidate.
static int g_page_min_pct = 40;     // vendor.debug.hwc.shim.page_min_pct (0 = count every touch-driven commit)
static int g_boot_damage;           // vendor.debug.hwc.shim.boot_damage: 1 while booting (module post-fs-data)
static uint8_t g_thumb[(2480 / 8) * (1860 / 8)];
static bool g_thumb_valid;

// True Regal: with global_refresh_mode 180 the vendor HWC commits 177 unless a layer carries 180 (none does on
// the GSI; verified 2026-10-18: Regal requested, 0xb1 committed). vendor.debug.hwc.shim.force_regal 0 = vendor behaviour.
static int g_global_mode = 0, g_force_regal = 1;
static int  g_color_enable = 1, g_color_threads = 4;
// Scrolling (wave 4, stock "Page sliding optimization"): einkd sets vendor.xrz.is_scrolling while a finger drags; the
// vendor HWC commits Fast (178) in HD 256 / Regal meanwhile and cleans with its own GC16 ~350 ms after its first frame
// back in the quality mode. A still screen has no such frame: then the helper cleans once commits are quiet
// (scroll_clean 1 = GC16 like the vendor, default; 2 = the frame again in the app's mode, no flash; 0 = vendor only),
// and a vendor GC16 that still follows becomes a normal update: one flash per scroll. vendor.debug.hwc.shim.scroll
// (E-Ink Control, per app): 179 = the scroll's Fast updates go out as Extreme (also in a Fast app, which then gets
// the frame again in Fast at the end); 0 / 178 = no rewrite (einkd reads the same prop: 0 = no scroll detection).
static int g_scroll_mode = 178, g_scroll_clean = 1;
static int g_scrolling;                  // atomic: vendor.xrz.is_scrolling as the helper saw it last
static int g_scroll_pending;             // atomic: 0, or the mode the helper cleans in (4 = GC16) once quiet
static int g_scroll_cleaned;             // atomic: the helper cleaned this scroll
static int g_scroll_vendor;              // atomic: the vendor's cleaning GC16 is still to come
static int g_scroll_fast;                // atomic: this scroll put Fast / Extreme on the panel (else nothing to clean)
static uint64_t g_scroll_end_ms, g_scroll_t1_ms;   // atomic: scroll end; first vendor commit after it (0 = none yet)

static int (*real_gralloc_extra_query)(buffer_handle_t, int, void*);

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

struct BufInfo {
    bool valid;
    int32_t w, h, stride, vstride, alloc_size, format, usage, q17, ion_fd;
    uint32_t sf_info[8];
};

struct LayerState {
    bool used;
    hwc2_layer_t id;
    hwc2_display_t display;
    buffer_handle_t buffer;
    BufInfo buf;
    int32_t comp_requested, comp_changed_to;
    int32_t dataspace, transform, blend;
    float alpha;
    uint32_t z;
    hwc_rect_t frame;
    hwc_frect_t crop;
    int32_t refresh_mode;   // last value we pushed via descriptor 71
    uint32_t buffer_changes;
    buffer_handle_t damaged_for;   // boot_damage: buffer we last forced full damage for
    uint64_t chg_seq;              // g_layer_seq when its picture or geometry last changed
    uint64_t buf_ms;               // when it last got a new buffer (a playing video)
    hwc_rect_t dmg;                // SF's surface damage for dmg_buf (union, buffer pixels; full = the whole crop)
    buffer_handle_t dmg_buf;       // the buffer that damage belongs to (the keyboard area classifies by it)
};
#define MAX_LAYERS 96
static LayerState g_layers[MAX_LAYERS];
// Parts of the screen with a mode of their own (owner 2026-10-27; desc 71 showed the HWC honours per-layer modes, but
// one e-ink update has one mode, and A16's SF never sends them, so the shim decides per commit from what changed):
//   nav_mode N    when the only layer that changed since the last vendor commit is the navigation strip (a layer along
//                 a full screen edge, 80..400 px thick; the status bar is ~53), only that strip goes out, in mode N
//                 (178 default, 179, 16; 0 = the app's mode). Same pixels as the app's mode (colour stages, smoothing),
//                 so only what really changed is driven.
//   video_mode N  while a video layer plays (YUV, >= 5 % of the screen, a new frame within 600 ms): the whole screen
//                 in mode N (179 default, 178; 0 = off); when it stops, one clean as after a scroll.
// Under g_lock: g_layer_seq counts layer changes; g_commit_layer_seq = its value at the previous vendor commit.
static uint64_t g_layer_seq, g_commit_layer_seq, g_layer_gone_seq;
static hwc_rect_t g_gone_box;      // union of the layers removed since the last vendor commit (empty: right <= left)
static int g_nav_mode = 178, g_video_mode = 179;
static uint64_t g_video_ms;        // atomic: last commit with a playing video
// Keyboard area (owner 2026-11-11, ALPHA7-PLAN 3.4). E-Ink Control publishes the keyboard window as
// vendor.debug.hwc.shim.ime_rect "l,t,r,b,rotation" in display pixels ("0" = no keyboard; rotation 0 only). A commit
// where only layers inside that area changed goes out as the area alone, in
//   ime_mode N        178 Fast (default) while typing; 0 = the app's mode
//   ime_swipe_mode N  0x10 A2 (default) from the 3rd keyboard-only commit in a row, each closer than ime_swipe_gap_ms
//                     (default 150) to the one before: a swipe trail is being drawn; 0 = ime_mode
// and every commit that touched the area arms one GC16 of that area ime_clean_ms (default 1500, 0 = off) after the
// last of them, once the screen is quiet (the settled full refresh helper sends it).
static pthread_mutex_t g_ime_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_ime_l, g_ime_t, g_ime_r, g_ime_b, g_ime_ok;   // display pixels of the keyboard window (under g_ime_lock)
static int g_ime_mode = 178, g_ime_swipe_mode = 0x10, g_ime_swipe_gap = 150, g_ime_clean_ms = 1500;
static uint64_t g_ime_last_ms;     // under g_ime_lock: the last commit that touched the keyboard area
static hwc_rect_t g_ime_clean_rect;   // under g_ime_lock: the area to clean
static int g_ime_clean;            // atomic: a clean-up of g_ime_clean_rect is due
// While a swipe runs in an app that changes the screen too (live search results), only the keyboard area goes out;
// the rest waits until the swipe pauses for ime_rest_ms (default 150) and then goes out once in the app's mode.
static int g_ime_rest_ms = 150;
static int g_ime_rest;             // atomic: the rest of the screen is due (g_ime_rest_rect, g_ime_rest_mode)
static hwc_rect_t g_ime_rest_rect; // under g_ime_lock
static uint32_t g_ime_rest_mode;   // under g_ime_lock
static int g_ime_rest_dither;      // under g_ime_lock: the app's own dither for that update

struct Counters {
    uint64_t validate, present, set_client_target, create_layer, destroy_layer;
    uint64_t eink_update, eink_vsync, eink_other;
    uint64_t convert_to_gray, image_smoothing, convert_layer_to_gray, process_image;
    uint64_t addfb2, atomic_commit, set_crtc;
    uint64_t status_props_dropped;   // vendor.xrz.* status writes swallowed by hook_property_set
    uint64_t last_present_ns, present_max_us;
};
static Counters g_ct;

static buffer_handle_t g_ct_handle;
static BufInfo g_ct_buf;
static int32_t g_ct_dataspace;

// The last eink commit seen, kept for `dumpsys SurfaceFlinger` (our DUMP hook).
struct drm_eink_update {          // 68 bytes; DRM_IOWR(0x40 + 0x8f, 0x44) = 0xC04464CF
    uint32_t mode;
    int32_t  left, top, right, bottom;   // inclusive
    uint32_t dither;
    uint32_t rsvd0[4];
    int32_t  fd;
    uint32_t format;                     // 0 => HWC already produced gray8 in place
    uint32_t rsvd1[2];                   // all fields are 4-byte aligned in the HWC (68-byte copy)
    uint32_t stride;                     // pixels
    uint32_t height;
    uint32_t rsvd2;
};
static_assert(sizeof(drm_eink_update) == 0x44, "drm_eink_update must be 68 bytes");
#define DRM_IOCTL_EINK_UPDATE      0xC04464CFu
#define DRM_IOCTL_EINK_WAIT_VSYNC  0xC00464D1u
static drm_eink_update g_last_update;
static uint64_t g_last_update_seq;
// Pen ink layer (see "pen ink layer" below): while a pen session runs, the vendor's own commits are held back, as
// stock's handwrittenservice did with vendor.xrz.disable_commit=1, so they cannot paint over the provisional ink.
static int g_ink_session;           // atomic: 1 = vendor commits are held
static int g_ink_inflight;          // atomic: vendor commits past the hold check that have not returned yet
static int g_ink_cancel;            // atomic: the display left ON -> drop the session without a settle commit
static int g_ink_held;              // vendor commits swallowed in this session (under g_lock)
static drm_eink_update g_ink_held_u;   // the last of them: mode/dither for the settle commit (under g_lock)
// Sleep picture (Lock & Sleep app; ROADMAP feature 18, module 0.1.35): the app pre-renders the panel's raw frame into
// vendor.debug.hwc.shim.standby_path and bumps the counter prop vendor.debug.hwc.shim.standby; the full-refresh helper
// thread loads it into an ION buffer of ours, and setPowerMode(OFF) commits it as one GC16 before the vendor turns the
// panel off (the kernel's eink_suspend waits for the pending update; stock showed its standby picture the same way).
// Nothing else reaches the panel while it sleeps: a commit after the suspend stalls 4 s and fails (2026-10-19).
static int g_display_off;           // atomic: 1 from setPowerMode(OFF) until the next ON (display 0)
static int g_standby_hold;          // atomic: 1 while the vendor's OFF runs after our commit (its own commits are dropped)
static int g_standby_shown;         // atomic: the sleep picture was committed at the last OFF
static int g_wake_gc16;             // atomic: arm one settled GC16 after the first vendor commit following that wake
static pthread_mutex_t g_standby_lock = PTHREAD_MUTEX_INITIALIZER;   // order: g_standby_lock -> g_commit_lock -> g_lock
struct Standby {
    int fd = -1; uint8_t* map = nullptr;   // our ION buffer (cached), allocated once
    int loaded_seq = 0; bool valid = false;
    uint64_t load_ms = 0, commit_ns = 0;
    int commits = 0, last_ret = 0, last_errno = 0, fails = 0;
};
static Standby g_standby;           // under g_standby_lock
#define STANDBY_W 2480
#define STANDBY_H 1860
#define STANDBY_BYTES ((size_t)STANDBY_W * STANDBY_H * 4)
#define PAGE_W STANDBY_H   // the portrait page the app draws: 1860 wide, 2480 tall
#define PAGE_H STANDBY_W
// Time of sleep (Lock & Sleep 0.2.0, module 0.1.48). Android tells apps about the screen going off ~650 ms after
// setPowerMode(OFF) (measured 2026-10-29: OFF 22 ms after "Going to sleep", SCREEN_OFF 650 ms later), so the app
// cannot draw the time into the frame. It leaves a box of the frame empty and writes clock.bin next to it: header,
// glyph table, 8-bit coverage per glyph (box height, page orientation). At OFF the shim writes the local time into
// the box, from the frame's own pixels under it (saved at load), so each sleep starts from a clean box.
#define CLOCK_MAGIC "B1SLPCK1"
#define CLOCK_MAX_GLYPHS 16
struct ClockHdr {
    char magic[8]; uint32_t seq;   // the frame counter this file belongs to
    uint32_t flags;                // bit 0: 24-hour; bit 1: rot 90 (else SurfaceFlinger's ROT_270)
    int32_t off_s, off2_s;         // UTC offset now, and after trans_s (next DST change; 0 = none)
    int64_t trans_s;
    uint32_t fg;                   // text colour, bytes R,G,B,A
    int32_t box_x, box_y, box_w, box_h, x0, nglyph, reserved;   // page px; x0 = pen start (left aligned)
};
struct ClockGlyph { uint8_t ch, pad[3]; int32_t adv_q6, left, w; };   // advance in 1/64 px; bitmap x = pen + left
static_assert(sizeof(ClockHdr) == 64, "clock.bin header");
static_assert(sizeof(ClockGlyph) == 16, "clock.bin glyph");
struct Clock {
    bool valid = false; ClockHdr h; ClockGlyph g[CLOCK_MAX_GLYPHS]; const uint8_t* bits[CLOCK_MAX_GLYPHS];
    uint8_t* file = nullptr;       // the whole file; bits[] point into it
    uint8_t* bg = nullptr;         // the frame under the box, page order, 4 B/px
    int draws = 0;
};
static Clock g_clock;              // under g_standby_lock

// ---------------------------------------------------------------- helpers

static uint64_t now_ns() {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + ts.tv_nsec;
}

static int prop_int(const char* name, int def) {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get(name, v) <= 0 || !v[0]) return def;
    return (int)strtol(v, nullptr, 0);
}

static int clampi(int v, int lo, int hi);

static void refresh_knobs() {
    g_log = prop_int("vendor.debug.hwc.shim.log", 1);
    g_refresh_mode = prop_int("vendor.debug.hwc.shim.refresh_mode", -1);
    g_commit_stride = prop_int("vendor.debug.hwc.shim.commit_stride", 0);
    g_commit_height = prop_int("vendor.debug.hwc.shim.commit_height", 0);
    g_commit_format = prop_int("vendor.debug.hwc.shim.commit_format", -1);
    g_commit_mode   = prop_int("vendor.debug.hwc.shim.commit_mode", -1);
    g_commit_use_ct = prop_int("vendor.debug.hwc.shim.commit_use_ct", 1);
    g_ct_src = prop_int("vendor.debug.hwc.shim.ct_src", 0);
    g_color_enable = prop_int("vendor.debug.hwc.shim.color", 1);
    g_color_threads = prop_int("vendor.debug.hwc.shim.color_threads", 4);
    g_cp_want.sat  = prop_int("vendor.xrz.global_color_enhance", 0);
    g_cp_want.con  = prop_int("vendor.xrz.global_contrast", 0);
    g_cp_want.bri  = prop_int("vendor.xrz.global_brightness_level", 0);
    g_cp_want.mode = prop_int("vendor.xrz.global_color_mode", 0);
    g_cp_want.lb   = clampi(prop_int("vendor.debug.hwc.shim.level_black", 0), 0, 128);
    g_cp_want.lw   = clampi(prop_int("vendor.debug.hwc.shim.level_white", 0), 0, 128);
    g_cp_want.oks  = clampi(prop_int("vendor.debug.hwc.shim.ok_sat", 0), -100, 300);
    g_cp_want.okn  = clampi(prop_int("vendor.debug.hwc.shim.ok_neutral", 20), 0, 100);
    g_cp_want.tone = clampi(prop_int("vendor.debug.hwc.shim.tone", 0), -100, 100);
    g_cp_want.lut  = clampi(prop_int("vendor.debug.hwc.shim.lut", -1), -1, 1);
    g_cp_want.cal  = clampi(prop_int("vendor.debug.hwc.shim.cal", 0), 0, 1000000);
    g_smooth = prop_int("vendor.debug.hwc.shim.smooth", 0);
    g_fast_dither = clampi(prop_int("vendor.debug.hwc.shim.fast_dither", -1), -1, 8);
    g_gc16_prep = prop_int("vendor.debug.hwc.shim.gc16_prep", 0);
    g_clean_freq = prop_int("vendor.xrz.clean_frequency", 0);
    g_check_commit = prop_int("vendor.xrz.check_commit", 0);
    g_auto_gc16 = prop_int("vendor.debug.hwc.shim.auto_gc16", 1);
    g_page_min_pct = prop_int("vendor.debug.hwc.shim.page_min_pct", 40);
    g_boot_damage = prop_int("vendor.debug.hwc.shim.boot_damage", 0);
    g_global_mode = prop_int("vendor.xrz.global_refresh_mode", 0);
    g_force_regal = prop_int("vendor.debug.hwc.shim.force_regal", 1);
    g_vivid_fast = prop_int("vendor.debug.hwc.shim.vivid_fast", 0);
    g_scroll_mode = prop_int("vendor.debug.hwc.shim.scroll", 178);
    g_scroll_clean = prop_int("vendor.debug.hwc.shim.scroll_clean", 1);
    g_nav_mode = prop_int("vendor.debug.hwc.shim.nav_mode", 178);
    g_ime_mode = prop_int("vendor.debug.hwc.shim.ime_mode", 178);
    g_ime_swipe_mode = prop_int("vendor.debug.hwc.shim.ime_swipe_mode", 0x10);
    g_ime_swipe_gap = clampi(prop_int("vendor.debug.hwc.shim.ime_swipe_gap_ms", 150), 20, 1000);
    g_ime_clean_ms = clampi(prop_int("vendor.debug.hwc.shim.ime_clean_ms", 1500), 0, 10000);
    g_ime_rest_ms = clampi(prop_int("vendor.debug.hwc.shim.ime_rest_ms", 150), 30, 2000);
    {
        char v[PROP_VALUE_MAX] = {0};
        int l = 0, t = 0, r = 0, b = 0, rot = -1;
        bool ok = __system_property_get("vendor.debug.hwc.shim.ime_rect", v) > 0
                  && sscanf(v, "%d,%d,%d,%d,%d", &l, &t, &r, &b, &rot) == 5 && rot == 0 && r > l && b > t;
        pthread_mutex_lock(&g_ime_lock);
        g_ime_ok = ok; g_ime_l = l; g_ime_t = t; g_ime_r = r; g_ime_b = b;
        pthread_mutex_unlock(&g_ime_lock);
    }
    g_video_mode = prop_int("vendor.debug.hwc.shim.video_mode", 179);
    if (g_cp_want.sat < 0) g_cp_want.sat = 0; if (g_cp_want.sat > 100) g_cp_want.sat = 100;
    if (g_cp_want.con < 0) g_cp_want.con = 0; if (g_cp_want.con > 100) g_cp_want.con = 100;
    if (g_cp_want.bri < 0) g_cp_want.bri = 0; if (g_cp_want.bri > 100) g_cp_want.bri = 100;
    int p = prop_int("vendor.debug.hwc.shim.preview", 0);
    if (p > 0) {
        g_preview_budget = p;
        __system_property_set("vendor.debug.hwc.shim.preview", "0");  // one-shot; may be denied, harmless
    }
}

// Log the first `burst` events, then every `every`th.
struct Limiter { uint64_t n; uint32_t burst, every; };
static bool lim(Limiter& l) {
    uint64_t n = __atomic_fetch_add(&l.n, 1, __ATOMIC_RELAXED);
    if (g_log >= 2) return true;
    return n < l.burst || (n % l.every) == 0;
}

// ---------------------------------------------------------------- colour pipeline
// CPU port of Bigme's SurfaceFlinger "process shader" (stock B1051C_R_4.9.0, recovered verbatim from
// /system/bin/surfaceflinger): contrast and brightness gamma curves, the HSL "vivid" stretch, then the
// 3D LUT g_c001 (g_c002 in colour mode 2 = Comic, which also skips the curves). Same props as stock:
//   vendor.debug.hwc.shim.auto_gc16 1 (default): every vendor.xrz.clean_frequency-th page turn becomes GC16
//   vendor.xrz.global_color_enhance  vivid / saturation 0-100
//   vendor.xrz.global_contrast       contrast 0-100
//   vendor.xrz.global_brightness_level  colour brightness 0-100
//   vendor.xrz.global_color_mode     0 default, 1 magazine, 2 comic, 3 video, 4 custom
// Source = SF's client target (always freshly rendered), destination = the HWC's own buffer for this
// commit, so the effect can never be applied twice. All zero -> off (plain client-target commit).
// Knobs: vendor.debug.hwc.shim.color (0 = off), vendor.debug.hwc.shim.color_threads (1-8, default 4).
// The stock "dither" shader is SF's dim-layer path (colour * ditherAlpha + <1/512 noise); AOSP's own
// dimming already produces the same result, so there is nothing to port for it here.

static uint32_t* g_ctab;          // 64^3 lattice -> output pixel (memory order R,G,B,A), used when vivid > 0
static uint32_t  g_c1d[256];      // exact 8-bit curve (contrast/brightness only)
static bool      g_use3d;
static uint8_t   g_q6[256];       // 8-bit value -> nearest 6-bit lattice index

static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
static float clampf01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

static bool color_3d(const ColorParams& p) { return p.sat > 0 || p.oks != 0 || p.tone != 0 || p.lut == 1 || p.cal > 0; }

static bool color_active() {
    const ColorParams& p = g_cp_eff;
    return g_color_enable && (color_3d(p) || (p.mode != 2 && (p.con > 0 || p.bri > 0)) || p.lb > 0 || p.lw > 0);
}

// The parameters for a commit in e-ink mode `mode` (low 16 bits of drm_eink_update.mode): the 3D stages (vivid,
// OKLCh, tone, LUT) stay off in the fast modes unless vivid_fast is set; levels and the 1D curves stay.
// Fast 0xb2, Extreme 0xb3, A2 0x10, DU 0x2 (HWC-COMMIT-POLICY.md).
// HD 256 smoothing applies to the 16-level modes the apps run in: HD 177 and Regal 180 (smooth knob).
static bool smooth_mode(uint32_t mode) {
    uint32_t m = mode & 0xffff;
    return g_smooth > 0 && g_color_enable && (m == 177 || m == 180);
}

static void color_select(uint32_t mode) {
    ColorParams p = g_cp_want;
    uint32_t m = mode & 0xffff;
    if (!g_vivid_fast && (m == 178 || m == 179 || m == 0x10 || m == 0x2)) { p.sat = 0; p.oks = 0; p.tone = 0; p.cal = 0; if (p.lut == 1) p.lut = -1; }
    g_cp_eff = p;
}

// ---- OKLab (Björn Ottosson, 2020), for the saturation and tone stages ---------------------------------------
static float srgb_lin(float c) { return c <= 0.04045f ? c / 12.92f : powf((c + 0.055f) / 1.055f, 2.4f); }
static float srgb_enc(float l) { return l <= 0.0031308f ? 12.92f * l : 1.055f * powf(l, 1.0f / 2.4f) - 0.055f; }
static void oklab_from_srgb(const float c[3], float lab[3]) {
    float r = srgb_lin(c[0]), g = srgb_lin(c[1]), b = srgb_lin(c[2]);
    float l = cbrtf(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b);
    float m = cbrtf(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b);
    float s = cbrtf(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b);
    lab[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s;
    lab[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s;
    lab[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s;
}
static void srgb_from_oklab(const float lab[3], float c[3]) {
    float l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2];
    float m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2];
    float s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2];
    l = l * l * l; m = m * m * m; s = s * s * s;
    float r = +4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s;
    float g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s;
    float b = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s;
    c[0] = clampf01(srgb_enc(clampf01(r))); c[1] = clampf01(srgb_enc(clampf01(g))); c[2] = clampf01(srgb_enc(clampf01(b)));
}

// lutMap(): 64x64x64 cube laid out as 8x8 tiles of 64x64 (blue selects the tile), nearest sample in
// red/green (Skia image-shader eval, no filtering) and a linear mix between the two blue tiles.
static void lut_map(const uint8_t* lut, float c[3]) {
    float blue = c[2] * 63.0f, bf = floorf(blue), bc = ceilf(blue);
    int q1y = (int)floorf(bf / 8.0f), q1x = (int)bf - q1y * 8;
    int q2y = (int)floorf(bc / 8.0f), q2x = (int)bc - q2y * 8;
    float tx = 63.0f * c[0], ty = 63.0f * c[1];
    int x1 = clampi((int)(q1x * 64 + tx), 0, 511), y1 = clampi((int)(q1y * 64 + ty), 0, 511);
    int x2 = clampi((int)(q2x * 64 + tx), 0, 511), y2 = clampi((int)(q2y * 64 + ty), 0, 511);
    const uint8_t* a = lut + ((size_t)y1 * 512 + x1) * 3;
    const uint8_t* b = lut + ((size_t)y2 * 512 + x2) * 3;
    float f = blue - bf;
    for (int k = 0; k < 3; k++) c[k] = clampf01((a[k] + (b[k] - a[k]) * f) / 255.0f);
}

// ---- panel calibration table (cal) ---------------------------------------------------------------------------
#define CAL_N 33
#define CAL_BYTES ((size_t)CAL_N * CAL_N * CAL_N * 3)
static uint8_t* g_cal;          // CAL_N^3 x RGB8, entry ((r * 33 + g) * 33 + b) * 3 (build_lut.py, numpy C order)
static int  g_cal_gen;          // the cal value the table was (tried to be) loaded for
static bool g_cal_ok;

static void cal_load(int gen) {
    if (gen == g_cal_gen) return;
    g_cal_gen = gen; g_cal_ok = false;
    if (gen <= 0) return;
    const char* path = "/data/vendor/b1051c/color/cal.lut";
    if (!g_cal) g_cal = (uint8_t*)malloc(CAL_BYTES);
    int fd = g_cal ? open(path, O_RDONLY | O_CLOEXEC) : -1;
    struct stat st; memset(&st, 0, sizeof(st));
    if (fd < 0 || fstat(fd, &st) != 0 || (size_t)st.st_size != CAL_BYTES) {
        LOGW("cal: %s not loaded (%s, %lld B, need %zu): colour stages as usual", path, fd < 0 ? strerror(errno) : "size",
             (long long)st.st_size, CAL_BYTES);
        if (fd >= 0) close(fd);
        return;
    }
    size_t got = 0;
    while (got < CAL_BYTES) {
        ssize_t n = read(fd, g_cal + got, CAL_BYTES - got);
        if (n <= 0) break;
        got += (size_t)n;
    }
    close(fd);
    g_cal_ok = got == CAL_BYTES;
    LOGI("cal: table #%d %s (%zu B)", gen, g_cal_ok ? "loaded" : "short read", got);
}

static void cal_map(float c[3]) {   // trilinear over the 33^3 lattice
    int i[3]; float f[3];
    for (int k = 0; k < 3; k++) {
        float x = clampf01(c[k]) * (CAL_N - 1);
        i[k] = (int)x; if (i[k] > CAL_N - 2) i[k] = CAL_N - 2;
        f[k] = x - i[k];
    }
    float o[3] = {0, 0, 0};
    for (int dr = 0; dr < 2; dr++)
        for (int dg = 0; dg < 2; dg++)
            for (int db = 0; db < 2; db++) {
                float w = (dr ? f[0] : 1 - f[0]) * (dg ? f[1] : 1 - f[1]) * (db ? f[2] : 1 - f[2]);
                const uint8_t* e = g_cal + (((size_t)(i[0] + dr) * CAL_N + (i[1] + dg)) * CAL_N + (i[2] + db)) * 3;
                for (int k = 0; k < 3; k++) o[k] += w * e[k];
            }
    for (int k = 0; k < 3; k++) c[k] = clampf01(o[k] / 255.0f);
}

static void process_px(const ColorParams& p, float c[3]) {
    // wave 3 levels: input black / white points, per channel (background bleach, text darkening)
    if (p.lb > 0 || p.lw > 0) {
        float lo = p.lb / 255.0f, hi = 1.0f - p.lw / 255.0f;
        for (int k = 0; k < 3; k++) c[k] = clampf01((c[k] - lo) / (hi - lo));
    }
    // the calibration table was measured with every other stage off: it replaces them
    if (p.cal > 0 && g_cal_ok) { cal_map(c); return; }
    if (p.mode != 2) {
        if (p.con > 0) {
            float g = 1.001f - p.con / 100.0f;
            for (int k = 0; k < 3; k++) c[k] = 1.0f - powf(1.0f - c[k], g);
        }
        if (p.bri > 0) {
            float g = 1.001f - p.bri / 100.0f;
            for (int k = 0; k < 3; k++) c[k] = powf(c[k], g);
        }
        if (p.sat > 0) {
            float inc = p.sat / 100.0f;
            float mn = fminf(fminf(c[0], c[1]), c[2]), mx = fmaxf(fmaxf(c[0], c[1]), c[2]);
            float delta = mx - mn, value = mx + mn, L = value / 2.0f, S;
            if (L <= 0.5f) S = value > 0.0f ? delta / value : 0.0f;          // the shader divides by 0 at black;
            else S = (2.0f - value) > 0.0f ? delta / (2.0f - value) : 0.0f;  // the result is black either way
            float alpha = (inc + S > 1.0f) ? S : 1.0f - inc;
            alpha = 1.0f / (alpha + 0.0001f) - 1.0f;
            for (int k = 0; k < 3; k++) c[k] = clampf01(c[k] + (c[k] - L) * alpha);
        }
    }
    // wave 3: chroma in OKLCh with the neutrals protected, and a contrast curve on OKLab lightness
    if (p.oks != 0 || p.tone != 0) {
        float lab[3];
        oklab_from_srgb(c, lab);
        if (p.oks != 0) {
            float C = sqrtf(lab[1] * lab[1] + lab[2] * lab[2]);
            float n = p.okn / 1000.0f, guard = (n <= 0.0f || C >= n) ? 1.0f : (C / n) * (C / n);
            float f = 1.0f + (p.oks / 100.0f) * guard;
            if (f < 0.0f) f = 0.0f;
            lab[1] *= f; lab[2] *= f;
        }
        if (p.tone != 0) lab[0] = clampf01(0.5f + (lab[0] - 0.5f) * (1.0f + p.tone / 100.0f));
        srgb_from_oklab(lab, c);
    }
    bool lut = p.lut == 1 || (p.lut == -1 && p.sat > 0);
    if (lut) lut_map(p.mode == 2 ? kLutC002 : kLutC001, c);
}

// Two table sets are kept: the fast modes drop the 3D stages (color_select), so an app with a keyboard, or two
// apps in different modes, would otherwise rebuild the 64^3 table (~130 ms) at every switch.
struct TableSet { ColorParams p; bool valid, use3d; uint32_t* ctab; uint32_t c1d[256]; uint64_t used; };
static TableSet g_tabs[2];

static void build_tables() {
    ColorParams p = g_cp_eff;
    uint64_t t0 = now_ns();
    for (int v = 0; v < 256; v++) g_q6[v] = (uint8_t)((v * 63 + 127) / 255);
    cal_load(p.cal);
    TableSet* t = nullptr;
    for (int i = 0; i < 2; i++) if (g_tabs[i].valid && memcmp(&g_tabs[i].p, &p, sizeof(p)) == 0) t = &g_tabs[i];
    if (t) {
        t->used = t0;
        g_use3d = t->use3d; g_ctab = t->ctab; memcpy(g_c1d, t->c1d, sizeof(g_c1d));
        g_cp_built = p;
        return;
    }
    t = &g_tabs[0];
    for (int i = 1; i < 2; i++) if (!g_tabs[i].valid || g_tabs[i].used < t->used) t = &g_tabs[i];
    t->p = p; t->valid = true; t->used = t0; t->use3d = color_3d(p);
    if (!t->use3d) {
        for (int v = 0; v < 256; v++) {
            float c[3] = {v / 255.0f, v / 255.0f, v / 255.0f};
            process_px(p, c);
            t->c1d[v] = (uint32_t)lrintf(c[0] * 255.0f);
        }
    } else {
        if (!t->ctab) t->ctab = (uint32_t*)malloc(64 * 64 * 64 * sizeof(uint32_t));
        if (!t->ctab) { t->use3d = false; t->valid = false; g_use3d = false; return; }
        for (int r = 0; r < 64; r++)
            for (int g = 0; g < 64; g++)
                for (int b = 0; b < 64; b++) {
                    float c[3] = {r / 63.0f, g / 63.0f, b / 63.0f};
                    process_px(p, c);
                    t->ctab[(r << 12) | (g << 6) | b] = (uint32_t)lrintf(c[0] * 255.0f) | ((uint32_t)lrintf(c[1] * 255.0f) << 8)
                                                      | ((uint32_t)lrintf(c[2] * 255.0f) << 16) | 0xff000000u;
                }
    }
    g_use3d = t->use3d; g_ctab = t->ctab; memcpy(g_c1d, t->c1d, sizeof(g_c1d));
    g_cp_built = p;
    LOGI("colour tables: vivid=%d contrast=%d brightness=%d mode=%d levels=%d/%d ok_sat=%d(n%d) tone=%d lut=%d cal=%d%s path=%s (%" PRIu64 " ms)",
         p.sat, p.con, p.bri, p.mode, p.lb, p.lw, p.oks, p.okn, p.tone, p.lut, p.cal, p.cal > 0 && !g_cal_ok ? "(not loaded)" : "",
         g_use3d ? "3D-LUT" : "1D-curve", (now_ns() - t0) / 1000000);
}

// dma-buf mappings, cached per buffer (identified by its inode; a live mapping pins the buffer, so the
// inode cannot be recycled while we hold it). Round-robin eviction keeps at most 8 buffers mapped.
struct MapEnt { void* ptr; size_t size; ino_t ino; dev_t dev; bool rw; };
static MapEnt g_maps[8];
static unsigned g_map_next;
// g_maps is shared by the HWC thread and the pen ink thread (settle commit): whoever maps holds this lock for as
// long as it uses the pointer, since a later map_dmabuf may evict (munmap) it.
static pthread_mutex_t g_map_lock = PTHREAD_MUTEX_INITIALIZER;
struct MapGuard { MapGuard() { pthread_mutex_lock(&g_map_lock); } ~MapGuard() { pthread_mutex_unlock(&g_map_lock); } };

static void* map_dmabuf(int fd, size_t need, bool rw) {
    struct stat st;
    if (fstat(fd, &st) != 0) return nullptr;
    for (MapEnt& e : g_maps)
        if (e.ptr && e.ino == st.st_ino && e.dev == st.st_dev && (e.rw || !rw)) return e.size >= need ? e.ptr : nullptr;
    off_t sz = lseek(fd, 0, SEEK_END);
    if (sz < (off_t)need) { if (sz < 0) sz = (off_t)need; else return nullptr; }
    void* p = mmap(nullptr, (size_t)sz, rw ? (PROT_READ | PROT_WRITE) : PROT_READ, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) return nullptr;
    MapEnt& e = g_maps[g_map_next++ % 8];
    if (e.ptr) munmap(e.ptr, e.size);
    e = {p, (size_t)sz, st.st_ino, st.st_dev, rw};
    return p;
}

static void dmabuf_sync(int fd, uint64_t flags) {
    struct dma_buf_sync s = {flags};
    ioctl(fd, DMA_BUF_IOCTL_SYNC, &s);
}

// Like stock's dirty area: % of the screen covered by the bounding box of the 8x8 cells that changed since the
// last call (a cell = mean luma of 4 samples, changed by more than ~8 %). A page of text on white changes only the
// cells with ink, so a plain changed-cell count would miss page turns; the box does not. HWC thread only.
static int frame_change_pct(int fd, int w, int h, int stride_px) {
    if (fd < 0 || w <= 0 || h <= 0 || w > 2480 || h > 1860) return 100;
    MapGuard mg;
    size_t need = (size_t)stride_px * h * 4;
    const uint8_t* p = (const uint8_t*)map_dmabuf(fd, need, false);
    if (!p) return 100;
    dmabuf_sync(fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ);
    int tw = w / 8, th = h / 8, x0 = tw, x1 = -1, y0 = th, y1 = -1;
    for (int ty = 0; ty < th; ty++) {
        const uint8_t* r0 = p + ((size_t)(ty * 8 + 2) * stride_px) * 4;
        const uint8_t* r1 = p + ((size_t)(ty * 8 + 6) * stride_px) * 4;
        for (int tx = 0; tx < tw; tx++) {
            size_t a = (size_t)(tx * 8 + 2) * 4, b = (size_t)(tx * 8 + 6) * 4;
            const uint8_t* s4[4] = {r0 + a, r0 + b, r1 + a, r1 + b};
            int sum = 0;
            for (int k = 0; k < 4; k++) sum += (s4[k][0] * 77 + s4[k][1] * 150 + s4[k][2] * 29) >> 8;
            uint8_t y = (uint8_t)(sum >> 2);
            uint8_t& t = g_thumb[ty * tw + tx];
            if (g_thumb_valid && (y > t ? y - t : t - y) > 20) {
                if (tx < x0) x0 = tx; if (tx > x1) x1 = tx;
                if (ty < y0) y0 = ty; if (ty > y1) y1 = ty;
            }
            t = y;
        }
    }
    dmabuf_sync(fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);
    bool first = !g_thumb_valid;
    g_thumb_valid = true;
    if (first) return 100;
    return x1 < 0 ? 0 : (x1 - x0 + 1) * (y1 - y0 + 1) * 100 / (tw * th);
}

// A band of rows to convert. With `raw` set (incremental pass), a row equal to its copy in `raw` is skipped and a
// converted row is copied into `raw`; `done` counts the rows converted.
struct ColorJob { const uint8_t* src; uint8_t* dst; uint8_t* raw; size_t pitch; int x0, x1, y0, y1; bool force; int done; };

static void color_rows(ColorJob* j) {
    int n = j->x1 - j->x0;
    uint32_t in[2560], out[2560];            // one row; bulk copies are much faster than pixel-wise
    if (n > 2560) n = 2560;                  // access on uncached buffers
    size_t bytes = (size_t)n * 4;
    int done = 0;
    for (int y = j->y0; y < j->y1; y++) {
        size_t off = (size_t)y * j->pitch + (size_t)j->x0 * 4;
        memcpy(in, j->src + off, bytes);
        if (j->raw) {
            if (!j->force && memcmp(in, j->raw + off, bytes) == 0) continue;   // unchanged since the row was last converted
            memcpy(j->raw + off, in, bytes);
        }
        if (g_use3d) {
            const uint32_t* T = g_ctab;
            for (int i = 0; i < n; i++) {
                uint32_t p = in[i];
                out[i] = T[(g_q6[p & 0xff] << 12) | (g_q6[(p >> 8) & 0xff] << 6) | g_q6[(p >> 16) & 0xff]];
            }
        } else {
            for (int i = 0; i < n; i++) {
                uint32_t p = in[i];
                out[i] = g_c1d[p & 0xff] | (g_c1d[(p >> 8) & 0xff] << 8) | (g_c1d[(p >> 16) & 0xff] << 16) | 0xff000000u;
            }
        }
        memcpy(j->dst + off, out, bytes);
        done++;
    }
    j->done = done;
}

// Persistent worker pool (review 2026-10-21: three threads were created and joined on every commit). The caller
// works too, so `g_color_threads` is the total; workers block on a condvar between frames.
static pthread_mutex_t g_pool_mx = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_pool_cv = PTHREAD_COND_INITIALIZER, g_pool_done = PTHREAD_COND_INITIALIZER;
static void (*g_pool_fn)(void*);
static char* g_pool_jobs;
static size_t g_pool_jobsz;
static int g_pool_njobs, g_pool_next, g_pool_pending, g_pool_threads;
static uint64_t g_pool_gen;

static void pool_work() {   // take jobs until none are left
    for (;;) {
        pthread_mutex_lock(&g_pool_mx);
        if (g_pool_next >= g_pool_njobs) { pthread_mutex_unlock(&g_pool_mx); return; }
        void* j = g_pool_jobs + g_pool_jobsz * (size_t)g_pool_next++;
        pthread_mutex_unlock(&g_pool_mx);
        g_pool_fn(j);
        pthread_mutex_lock(&g_pool_mx);
        if (--g_pool_pending == 0) pthread_cond_broadcast(&g_pool_done);
        pthread_mutex_unlock(&g_pool_mx);
    }
}

static void* pool_thread(void*) {
    uint64_t seen = 0;
    for (;;) {
        pthread_mutex_lock(&g_pool_mx);
        while (g_pool_gen == seen) pthread_cond_wait(&g_pool_cv, &g_pool_mx);
        seen = g_pool_gen;
        pthread_mutex_unlock(&g_pool_mx);
        pool_work();
    }
    return nullptr;
}

// Run fn on each of the n jobs (jobsz bytes apart) on the pool and this thread; returns when all are done.
// Serialised by the caller (g_map_lock).
static void pool_run(void (*fn)(void*), void* jobs, size_t jobsz, int n, int threads) {
    int want = clampi(threads, 1, 8) - 1;
    while (g_pool_threads < want) {
        pthread_t th;
        if (pthread_create(&th, nullptr, pool_thread, nullptr) != 0) break;
        pthread_detach(th);
        g_pool_threads++;
    }
    pthread_mutex_lock(&g_pool_mx);
    g_pool_fn = fn; g_pool_jobs = (char*)jobs; g_pool_jobsz = jobsz; g_pool_njobs = n; g_pool_next = 0; g_pool_pending = n; g_pool_gen++;
    pthread_cond_broadcast(&g_pool_cv);
    pthread_mutex_unlock(&g_pool_mx);
    pool_work();
    pthread_mutex_lock(&g_pool_mx);
    while (g_pool_pending > 0) pthread_cond_wait(&g_pool_done, &g_pool_mx);
    pthread_mutex_unlock(&g_pool_mx);
}

static void color_rows_job(void* j) { color_rows((ColorJob*)j); }

static int dmabuf_alloc(size_t len, uint32_t flags);   // ION dma-buf (defined with the pen ink layer below)

// Our own commit buffers for the colour pipeline (review 2026-10-21: the pass converted the whole 2480x1860 frame on
// every commit, into the vendor's buffer). The processed frame lives in `g_cproc` (cached memory); a pass converts
// only the rows of the client target that differ from `g_craw`, the raw copy of what was converted before, and
// stamps them with the pass number in `g_crowseq`. The commit carries one of COUT_N dma-bufs in turn (the kernel
// keeps reading a buffer while the panel updates, so one buffer serialised every update: 2026-10-22 04:3x, two
// commits per swipe); each gets the rows that changed since it was last used. Invariants: g_cproc == convert(g_craw)
// row by row, and dma-buf k == g_cproc for every row with g_crowseq <= k.synced. A change of the tables converts
// every row again.
#define COUT_N 3
#define ION_FLAG_CACHED_ 1
struct COut { int fd; uint8_t* map; uint32_t synced; };
static COut      g_cout[COUT_N] = {{-1, nullptr, 0}, {-1, nullptr, 0}, {-1, nullptr, 0}};
static int       g_cout_next;
static int       g_cout_last = -1;            // fd of the last buffer committed (dump)
static size_t    g_cout_len;
static uint32_t  g_cout_stride, g_cout_height;
static uint8_t*  g_cproc;                     // processed frame, cached
static uint8_t*  g_craw;                      // raw copy of the converted client-target rows, cached
static uint32_t* g_crowseq;                   // per row: pass that last changed it
static uint32_t  g_cseq;                      // pass counter
static bool      g_craw_valid;
static uint64_t g_cstat_frames, g_cstat_rows, g_cstat_us;   // dump stats

static void cout_release() {
    for (int i = 0; i < COUT_N; i++) {
        if (g_cout[i].map) munmap(g_cout[i].map, g_cout_len);
        if (g_cout[i].fd >= 0) close(g_cout[i].fd);
        g_cout[i] = {-1, nullptr, 0};
    }
    g_cout_len = 0;
}

static bool cout_prepare(uint32_t stride, uint32_t height) {
    size_t need = (size_t)stride * 4 * height;
    if (g_cout[0].fd >= 0 && g_cout_stride == stride && g_cout_height == height && g_cout_len >= need) return true;
    cout_release();
    for (int i = 0; i < COUT_N; i++) {
        int fd = dmabuf_alloc(need, ION_FLAG_CACHED_);   // cached: the pass writes it; DMA_BUF_SYNC_END flushes for the kernel
        void* m = fd >= 0 ? mmap(nullptr, need, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0) : MAP_FAILED;
        if (m == MAP_FAILED) { if (fd >= 0) close(fd); cout_release(); return false; }
        g_cout[i] = {fd, (uint8_t*)m, 0};
    }
    g_cout_len = need; g_cout_stride = stride; g_cout_height = height;
    uint8_t* proc = (uint8_t*)realloc(g_cproc, need);
    uint8_t* raw = (uint8_t*)realloc(g_craw, need);
    uint32_t* seq = (uint32_t*)realloc(g_crowseq, (size_t)height * sizeof(uint32_t));
    if (!proc || !raw || !seq) { cout_release(); return false; }
    g_cproc = proc; g_craw = raw; g_crowseq = seq;
    memset(g_crowseq, 0, (size_t)height * sizeof(uint32_t));
    g_craw_valid = false;
    LOGI("colour: %d own commit buffers %ux%u (%zu B each, cached ION) fds=%d,%d,%d + processed and raw copies",
         COUT_N, stride, height, need, g_cout[0].fd, g_cout[1].fd, g_cout[2].fd);
    return true;
}

// One band of the incremental pass (worker): diff, convert the changed rows into g_cproc, bring the commit buffer's
// rows up to date.
struct IncJob { const uint8_t* src; uint8_t* out; uint32_t synced; size_t pitch; int x1, y0, y1; bool force; int conv, copied;
                const uint8_t* from; };   // rows copied into `out` from here (g_cproc); null = convert only (smoothing)

static void inc_rows(IncJob* j) {
    int n = j->x1 > 2560 ? 2560 : j->x1;
    uint32_t in[2560], outrow[2560];
    size_t bytes = (size_t)n * 4;
    int conv = 0, copied = 0;
    for (int y = j->y0; y < j->y1; y++) {
        size_t off = (size_t)y * j->pitch;
        memcpy(in, j->src + off, bytes);                                   // the uncached read, once
        if (j->force || memcmp(in, g_craw + off, bytes) != 0) {
            memcpy(g_craw + off, in, bytes);
            if (g_use3d) {
                const uint32_t* T = g_ctab;
                for (int i = 0; i < n; i++) {
                    uint32_t p = in[i];
                    outrow[i] = T[(g_q6[p & 0xff] << 12) | (g_q6[(p >> 8) & 0xff] << 6) | g_q6[(p >> 16) & 0xff]];
                }
            } else {
                for (int i = 0; i < n; i++) {
                    uint32_t p = in[i];
                    outrow[i] = g_c1d[p & 0xff] | (g_c1d[(p >> 8) & 0xff] << 8) | (g_c1d[(p >> 16) & 0xff] << 16) | 0xff000000u;
                }
            }
            memcpy(g_cproc + off, outrow, bytes);
            g_crowseq[y] = g_cseq;
            conv++;
        }
        if (j->from && g_crowseq[y] > j->synced) { memcpy(j->out + off, j->from + off, bytes); copied++; }
    }
    j->conv = conv; j->copied = copied;
}

static void inc_rows_job(void* j) { inc_rows((IncJob*)j); }

// ---- HD 256 smoothing (knob smooth; research/phase-d/color/HD256-SMOOTHING.md) ---------------------------------
// Our own implementation of what the stock HWC's imageSmoothing does: Floyd-Steinberg per channel to the 16 levels
// the panel shows (0, 17, .. 255; the kernel's floor to 4 bits keeps them), in 8 vertical strips that no error
// crosses, and a pixel whose input equals the last smoothed input keeps its last output, so the grain does not crawl
// over unchanged content. Like stock's fast path it starts at the first changed row with zero error (the exact
// variant recurses from row 0; the difference is invisible). Input g_cproc, previous input g_csm_in, output g_csm.
static uint8_t* g_csm;            // smoothed frame (what the commit buffers get while smoothing)
static uint8_t* g_csm_in;         // the g_cproc rows it was made from
static size_t   g_csm_len;
static bool     g_csm_valid, g_csm_last;   // reuse allowed; the last pass smoothed

static inline int q16(int v) { return ((v * 240 + 2160) >> 12) * 17; }   // nearest of the 16 levels

struct SmoothJob { size_t pitch; int x0, x1, y0, y1; bool reuse; };

static void smooth_strip(SmoothJob* j) {
    int w = j->x1 - j->x0;
    if (w <= 0 || w > 320) return;
    static thread_local int32_t err[3][2][323];   // FS numerators (x16) per channel, this row / next row, +1 guard
    for (int c = 0; c < 3; c++) memset(err[c][j->y0 & 1], 0, sizeof(err[c][0]));
    for (int y = j->y0; y < j->y1; y++) {
        int cp = y & 1, np = cp ^ 1;
        for (int c = 0; c < 3; c++) memset(err[c][np], 0, sizeof(err[c][0]));
        const uint32_t* s = (const uint32_t*)(g_cproc + (size_t)y * j->pitch);
        uint32_t* ps = (uint32_t*)(g_csm_in + (size_t)y * j->pitch);
        uint32_t* o = (uint32_t*)(g_csm + (size_t)y * j->pitch);
        bool down = y < j->y1 - 1;
        for (int x = j->x0; x < j->x1; x++) {
            int i = x - j->x0 + 1;
            uint32_t px = s[x], out = px & 0xff000000u;
            int e[3];
            for (int c = 0; c < 3; c++) {
                int v = (int)((px >> (8 * c)) & 0xff) + (err[c][cp][i] >> 4);
                v = v < 0 ? 0 : (v > 255 ? 255 : v);
                int q = q16(v);
                e[c] = v - q;
                out |= (uint32_t)q << (8 * c);
            }
            if (j->reuse && ps[x] == px) out = o[x];
            o[x] = out; ps[x] = px;
            bool right = x < j->x1 - 1;
            for (int c = 0; c < 3; c++) {
                if (right) err[c][cp][i + 1] += 7 * e[c];
                if (down) {
                    if (i > 1) err[c][np][i - 1] += 3 * e[c];
                    err[c][np][i] += 5 * e[c];
                    if (right) err[c][np][i + 1] += e[c];
                }
            }
        }
    }
}

static void smooth_strip_job(void* j) { smooth_strip((SmoothJob*)j); }

struct CopyJob { uint8_t* out; const uint8_t* from; uint32_t synced; size_t pitch, bytes; int y0, y1, copied; };

static void copy_rows_job(void* v) {
    CopyJob* j = (CopyJob*)v;
    int n = 0;
    for (int y = j->y0; y < j->y1; y++)
        if (g_crowseq[y] > j->synced) { memcpy(j->out + (size_t)y * j->pitch, j->from + (size_t)y * j->pitch, j->bytes); n++; }
    j->copied = n;
}

// Incremental pass: client target -> the next of our commit buffers. *fd = that buffer, *rows = rows converted,
// *copied = rows refreshed in the buffer. `smooth`: HD 256 smoothing after the conversion (the buffer gets the
// smoothed rows). False = not done (caller commits the client target unprocessed).
static bool color_apply_inc(int src_fd, uint32_t stride, uint32_t height, int* fd, int* rows, int* copied, bool smooth) {
    MapGuard mg;
    *rows = 0; *copied = 0; *fd = -1;
    if (stride == 0 || height == 0 || stride > 2560) return false;
    if (g_ct_buf.valid && (g_ct_buf.stride != (int32_t)stride || g_ct_buf.h != (int32_t)height)) return false;
    if (!cout_prepare(stride, height)) return false;
    bool rebuilt = memcmp(&g_cp_built, &g_cp_eff, sizeof(ColorParams)) != 0;
    if (rebuilt) build_tables();
    if (g_use3d && !g_ctab) return false;
    size_t pitch = (size_t)stride * 4, need = pitch * height;
    const uint8_t* s = (const uint8_t*)map_dmabuf(src_fd, need, false);
    if (!s) return false;
    COut& k = g_cout[g_cout_next];
    g_cout_next = (g_cout_next + 1) % COUT_N;
    bool force = !g_craw_valid || rebuilt;
    if (smooth && g_csm_len != need) {
        uint8_t* a = (uint8_t*)realloc(g_csm, need);
        uint8_t* b = a ? (uint8_t*)realloc(g_csm_in, need) : nullptr;
        if (a) g_csm = a;
        if (b) g_csm_in = b;
        if (!a || !b) { g_csm_len = 0; smooth = false; } else g_csm_len = need;
        g_csm_valid = false;
    }
    if (smooth != g_csm_last) { force = true; g_csm_valid = false; }   // every row again from the other source
    g_csm_last = smooth;
    g_cseq++;
    dmabuf_sync(src_fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ);
    dmabuf_sync(k.fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE);
    int nt = clampi(g_color_threads, 1, 8);
    IncJob jobs[8];
    for (int i = 0; i < nt; i++)
        jobs[i] = {s, k.map, k.synced, pitch, (int)stride, (int)height * i / nt, (int)height * (i + 1) / nt, force, 0, 0,
                   smooth ? nullptr : g_cproc};
    pool_run(inc_rows_job, jobs, sizeof(IncJob), nt, nt);
    int cp = 0;
    if (smooth) {
        int top = -1, bot = -1;                      // the rows this pass converted
        for (int y = 0; y < (int)height; y++) if (g_crowseq[y] == g_cseq) { if (top < 0) top = y; bot = y + 1; }
        if (top >= 0) {
            if (!g_csm_valid) { top = 0; bot = (int)height; }
            SmoothJob sj[8];
            for (int i = 0; i < 8; i++) sj[i] = {pitch, (int)stride * i / 8, (int)stride * (i + 1) / 8, top, bot, g_csm_valid};
            pool_run(smooth_strip_job, sj, sizeof(SmoothJob), 8, nt);
            if (!g_csm_valid) for (int y = 0; y < (int)height; y++) g_crowseq[y] = g_cseq;
            g_csm_valid = true;
        }
        CopyJob cj[8];
        for (int i = 0; i < nt; i++)
            cj[i] = {k.map, g_csm, k.synced, pitch, (size_t)stride * 4, (int)height * i / nt, (int)height * (i + 1) / nt, 0};
        pool_run(copy_rows_job, cj, sizeof(CopyJob), nt, nt);
        for (int i = 0; i < nt; i++) cp += cj[i].copied;
    }
    dmabuf_sync(k.fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE);
    dmabuf_sync(src_fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);
    k.synced = g_cseq;
    g_craw_valid = true;
    int conv = 0;
    for (int i = 0; i < nt; i++) { conv += jobs[i].conv; cp += jobs[i].copied; }
    *rows = conv; *copied = cp; *fd = k.fd;
    g_cout_last = k.fd;
    return true;
}

// Full pass of one box into a caller's buffer (pen ink paths): the processed client target rendered into `dst_fd`.
// False = not done.
static bool color_apply(int src_fd, int dst_fd, uint32_t stride, uint32_t height, int left, int top, int right, int bottom) {
    MapGuard mg;
    if (stride == 0 || height == 0 || stride > 2560) return false;
    if (g_ct_buf.valid && (g_ct_buf.stride != (int32_t)stride || g_ct_buf.h != (int32_t)height)) return false;
    int x0 = clampi(left, 0, (int)stride), x1 = clampi(right + 1, 0, (int)stride);
    int y0 = clampi(top, 0, (int)height), y1 = clampi(bottom + 1, 0, (int)height);
    if (x1 <= x0 || y1 <= y0) return false;
    if (memcmp(&g_cp_built, &g_cp_eff, sizeof(ColorParams)) != 0) { build_tables(); g_craw_valid = false; }
    if (g_use3d && !g_ctab) return false;
    size_t pitch = (size_t)stride * 4, need = pitch * height;
    const uint8_t* s = (const uint8_t*)map_dmabuf(src_fd, need, false);
    uint8_t* d = (uint8_t*)map_dmabuf(dst_fd, need, true);
    if (!s || !d) return false;
    dmabuf_sync(src_fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ);
    dmabuf_sync(dst_fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE);
    int nt = clampi(g_color_threads, 1, 8), rows = y1 - y0;
    if (rows < nt * 8) nt = 1;
    ColorJob jobs[8];
    for (int i = 0; i < nt; i++) jobs[i] = {s, d, nullptr, pitch, x0, x1, y0 + rows * i / nt, y0 + rows * (i + 1) / nt, true, 0};
    pool_run(color_rows_job, jobs, sizeof(ColorJob), nt, nt);
    dmabuf_sync(dst_fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE);
    dmabuf_sync(src_fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);
    return true;
}

static const char* hal_format_name(int32_t f) {
    switch (f) {
        case 1: return "RGBA_8888"; case 2: return "RGBX_8888"; case 3: return "RGB_888";
        case 4: return "RGB_565"; case 5: return "BGRA_8888"; case 0x16: return "RGBA_FP16";
        case 0x2b: return "RGBA_1010102"; case 0x23: return "YCbCr_420_888";
        case 0x11: return "YCrCb_420_SP"; case 0x14: return "YCbCr_422_I";
        case 0x35: return "BLOB"; case 0x22: return "IMPLEMENTATION_DEFINED";
        case 0x38: return "R_8"; case 0x36: return "Y8"; case 0x20203859: return "Y8(fourcc)";
        default: return "?";
    }
}
static const char* comp_name(int32_t t) {
    switch (t) { case 0: return "INVALID"; case 1: return "CLIENT"; case 2: return "DEVICE";
                 case 3: return "SOLID"; case 4: return "CURSOR"; case 5: return "SIDEBAND";
                 default: return "?"; }
}
static void fourcc_str(uint32_t f, char out[5]) {
    for (int i = 0; i < 4; i++) { char c = (char)((f >> (8*i)) & 0xff); out[i] = (c >= 32 && c < 127) ? c : '.'; }
    out[4] = 0;
}

// gralloc_extra_query ids. Recovered from the HWC's getPrivateHandle
// (0xade08): it queries 10,11,12,13,15,14,100,17 in that order and defaults
// 13 (vertical stride) to 11 (height) when zero. 16 = usage and 1 = ion fd are
// inferred from MediaTek's public gralloc_extra.h and are labelled as such.
static void query_buf(buffer_handle_t h, BufInfo* b) {
    memset(b, 0, sizeof(*b));
    if (!h || !real_gralloc_extra_query) return;
    int32_t v;
#define Q(id, field) do { v = 0; if (real_gralloc_extra_query(h, id, &v) == 0) b->field = v; } while (0)
    Q(10, w); Q(11, h); Q(12, stride); Q(13, vstride); Q(14, alloc_size); Q(15, format);
    Q(16, usage); Q(17, q17); Q(1, ion_fd);
#undef Q
    uint32_t big[1024]; memset(big, 0, sizeof(big));
    if (real_gralloc_extra_query(h, 100, big) == 0) memcpy(b->sf_info, big, sizeof(b->sf_info));
    b->valid = true;
}

static void log_handle_raw(const char* what, buffer_handle_t h) {
    if (!h) { LOGI("%s: handle=NULL", what); return; }
    char line[1024]; int n = snprintf(line, sizeof(line), "%s: handle=%p ver=%d fds=%d ints=%d fd[",
                                      what, h, h->version, h->numFds, h->numInts);
    for (int i = 0; i < h->numFds && n < (int)sizeof(line) - 16; i++)
        n += snprintf(line + n, sizeof(line) - n, "%s%d", i ? "," : "", h->data[i]);
    n += snprintf(line + n, sizeof(line) - n, "] ints[");
    for (int i = 0; i < h->numInts && n < (int)sizeof(line) - 16; i++)
        n += snprintf(line + n, sizeof(line) - n, "%s%x", i ? "," : "", (unsigned)h->data[h->numFds + i]);
    snprintf(line + n, sizeof(line) - n, "]");
    LOGI("%s", line);
}

static void log_bufinfo(const char* what, const BufInfo* b) {
    if (!b->valid) { LOGI("%s: (no gralloc_extra)", what); return; }
    LOGI("%s: %dx%d stride=%d(px) vstride=%d alloc=%d fmt=0x%x(%s) usage=0x%x q17=0x%x ion_fd=%d "
         "bytes/row@32bpp=%d @8bpp=%d @2bpp=%d",
         what, b->w, b->h, b->stride, b->vstride, b->alloc_size, b->format, hal_format_name(b->format),
         b->usage, b->q17, b->ion_fd, b->stride * 4, b->stride, b->stride / 4);
    LOGI("%s: sf_info[0..7]=%08x %08x %08x %08x %08x %08x %08x %08x", what,
         b->sf_info[0], b->sf_info[1], b->sf_info[2], b->sf_info[3],
         b->sf_info[4], b->sf_info[5], b->sf_info[6], b->sf_info[7]);
}

static bool bufinfo_differs(const BufInfo* a, const BufInfo* b) {
    return a->w != b->w || a->h != b->h || a->stride != b->stride || a->format != b->format ||
           a->usage != b->usage || a->alloc_size != b->alloc_size;
}

// ---------------------------------------------------------------- previews
//
// The diagnostic that answers the pitch question directly: render a buffer as
// ASCII at candidate pitches. The pitch that produces a coherent picture is
// the pitch the content was written at. `pitch_score` is the same idea as a
// number: mean |row[r] - row[r+1]| is small at the true pitch and large at a
// wrong one.

static double pitch_score(const uint8_t* base, size_t avail, uint32_t pitch, uint32_t width, uint32_t rows) {
    if (pitch == 0 || width == 0 || rows < 2) return -1;
    uint64_t acc = 0, cnt = 0;
    for (uint32_t r = 0; r + 1 < rows; r += 7) {
        size_t o0 = (size_t)r * pitch, o1 = (size_t)(r + 1) * pitch;
        if (o1 + width > avail) break;
        for (uint32_t x = 0; x < width; x += 13) { int d = (int)base[o0 + x] - (int)base[o1 + x]; acc += d < 0 ? -d : d; cnt++; }
    }
    return cnt ? (double)acc / cnt : -1;
}

static void preview_gray(const char* what, const uint8_t* base, size_t avail, uint32_t pitch, uint32_t width, uint32_t height) {
    static const char ramp[] = "@%#*+=-:. ";   // dark -> light
    const uint32_t cols = 82, rows = 41;
    uint32_t sx = width / cols ? width / cols : 1, sy = height / rows ? height / rows : 1;
    LOGI("%s: preview pitch=%u %ux%u (each char = %ux%u px)", what, pitch, width, height, sx, sy);
    char line[cols + 1];
    for (uint32_t r = 0; r < rows; r++) {
        uint32_t y = r * sy; if (y >= height) break;
        for (uint32_t c = 0; c < cols; c++) {
            uint32_t x = c * sx; size_t o = (size_t)y * pitch + x;
            line[c] = (o < avail) ? ramp[base[o] * 9 / 255] : '?';
        }
        line[cols] = 0;
        LOGI("|%s|", line);
    }
}

static void preview_rgba_lum(const char* what, const uint8_t* base, size_t avail, uint32_t pitch_bytes, uint32_t width, uint32_t height) {
    static const char ramp[] = "@%#*+=-:. ";
    const uint32_t cols = 82, rows = 41;
    uint32_t sx = width / cols ? width / cols : 1, sy = height / rows ? height / rows : 1;
    LOGI("%s: RGBA luminance preview pitch=%uB %ux%u", what, pitch_bytes, width, height);
    char line[cols + 1];
    for (uint32_t r = 0; r < rows; r++) {
        uint32_t y = r * sy; if (y >= height) break;
        for (uint32_t c = 0; c < cols; c++) {
            uint32_t x = c * sx; size_t o = (size_t)y * pitch_bytes + (size_t)x * 4;
            if (o + 3 >= avail) { line[c] = '?'; continue; }
            unsigned l = (base[o] * 77 + base[o+1] * 150 + base[o+2] * 29) >> 8;
            line[c] = ramp[l * 9 / 255];
        }
        line[cols] = 0;
        LOGI("|%s|", line);
    }
}

// Byte-pattern statistics over the first `bytes`: fraction of 0xFF at each
// residue mod 4. In-place RGBA->gray8 leaves alpha (=0xFF) at every 4th byte
// in the untouched tail; a fully-converted gray8 region shows no such stripe.
static void stripe_stats(const char* what, const uint8_t* base, size_t bytes) {
    uint64_t ff[4] = {0,0,0,0}, n[4] = {0,0,0,0};
    for (size_t i = 0; i < bytes; i += 1) { n[i & 3]++; if (base[i] == 0xff) ff[i & 3]++; }
    LOGI("%s: 0xFF fraction by byte%%4 over %zu B: %.2f %.2f %.2f %.2f", what, bytes,
         n[0] ? (double)ff[0]/n[0] : 0, n[1] ? (double)ff[1]/n[1] : 0,
         n[2] ? (double)ff[2]/n[2] : 0, n[3] ? (double)ff[3]/n[3] : 0);
}

static bool take_preview() {
    if (g_preview_budget <= 0) return false;
    g_preview_budget--;
    return true;
}

// ---------------------------------------------------------------- GOT hooking

struct HookSpec { const char* sym; void* hook; void** orig; int hits; };

struct GotCtx { HookSpec* specs; size_t n; int total; };

static int got_cb(struct dl_phdr_info* info, size_t, void* data) {
    GotCtx* ctx = (GotCtx*)data;
    if (!info->dlpi_name || !strstr(info->dlpi_name, REAL_HWC_BASENAME)) return 0;
    ElfW(Addr) bias = info->dlpi_addr;
    const ElfW(Dyn)* dyn = nullptr;
    for (int i = 0; i < info->dlpi_phnum; i++)
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) dyn = (const ElfW(Dyn)*)(bias + info->dlpi_phdr[i].p_vaddr);
    if (!dyn) { LOGE("got: no PT_DYNAMIC in %s", info->dlpi_name); return 1; }

    const ElfW(Sym)* symtab = nullptr; const char* strtab = nullptr;
    const ElfW(Rela)* jmprel = nullptr; size_t jmprelsz = 0;
    const ElfW(Rela)* rela = nullptr; size_t relasz = 0;
    auto fix = [&](ElfW(Addr) p) -> ElfW(Addr) { return p < bias ? p + bias : p; };
    for (const ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_SYMTAB: symtab = (const ElfW(Sym)*)fix(d->d_un.d_ptr); break;
            case DT_STRTAB: strtab = (const char*)fix(d->d_un.d_ptr); break;
            case DT_JMPREL: jmprel = (const ElfW(Rela)*)fix(d->d_un.d_ptr); break;
            case DT_PLTRELSZ: jmprelsz = d->d_un.d_val; break;
            case DT_RELA: rela = (const ElfW(Rela)*)fix(d->d_un.d_ptr); break;
            case DT_RELASZ: relasz = d->d_un.d_val; break;
        }
    }
    if (!symtab || !strtab) { LOGE("got: no symtab/strtab"); return 1; }

    auto scan = [&](const ElfW(Rela)* r, size_t sz) {
        if (!r) return;
        for (size_t i = 0; i < sz / sizeof(ElfW(Rela)); i++) {
            uint32_t type = ELF64_R_TYPE(r[i].r_info);
            if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) continue;
            const char* name = strtab + symtab[ELF64_R_SYM(r[i].r_info)].st_name;
            for (size_t s = 0; s < ctx->n; s++) {
                if (strcmp(name, ctx->specs[s].sym) != 0) continue;
                void** slot = (void**)(bias + r[i].r_offset);
                uintptr_t page = (uintptr_t)slot & ~(uintptr_t)(getpagesize() - 1);
                if (mprotect((void*)page, getpagesize(), PROT_READ | PROT_WRITE) != 0) {
                    LOGE("got: mprotect %p failed: %s", slot, strerror(errno)); continue;
                }
                if (!*ctx->specs[s].orig) *ctx->specs[s].orig = *slot;   // dlsym failed: fall back to slot
                *slot = ctx->specs[s].hook;
                ctx->specs[s].hits++; ctx->total++;
                LOGV("got: %s slot %p -> %p (orig %p)", name, slot, ctx->specs[s].hook, *ctx->specs[s].orig);
            }
        }
    };
    scan(jmprel, jmprelsz);
    scan(rela, relasz);
    return 1;
}

// BuildID of the loaded real HWC, via its PT_NOTE.
static int buildid_cb(struct dl_phdr_info* info, size_t, void* data) {
    if (!info->dlpi_name || !strstr(info->dlpi_name, REAL_HWC_BASENAME)) return 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type != PT_NOTE) continue;
        const uint8_t* p = (const uint8_t*)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
        const uint8_t* end = p + info->dlpi_phdr[i].p_memsz;
        while (p + sizeof(ElfW(Nhdr)) <= end) {
            const ElfW(Nhdr)* nh = (const ElfW(Nhdr)*)p;
            const uint8_t* name = p + sizeof(*nh);
            const uint8_t* desc = name + ((nh->n_namesz + 3) & ~3u);
            if (nh->n_type == NT_GNU_BUILD_ID && nh->n_namesz == 4 && memcmp(name, "GNU", 4) == 0) {
                size_t n = nh->n_descsz < 20 ? nh->n_descsz : 20;
                memcpy(data, desc, n);
                ((uint8_t*)data)[20] = (uint8_t)nh->n_descsz;
                return 1;
            }
            p = desc + ((nh->n_descsz + 3) & ~3u);
        }
    }
    return 0;
}

// ---------------------------------------------------------------- OUT hooks

struct Rect { int32_t left, top, right, bottom; };   // android::Rect

// xrz::EffectHandler::convertToGray(void* src, void* dst, android::Rect, int pitchBytes, int)
// Static member; the HWC calls it with dst == src (in-place) and
// pitch = 4 * stride (see OverlayEngine::convertLayerToGray at 0xa56b0).
static int64_t (*real_convertToGray)(void*, void*, Rect, int, int);
static int64_t hook_convertToGray(void* src, void* dst, Rect r, int pitch, int flag) {
    static Limiter L = {0, 30, 300};
    uint32_t w = (uint32_t)(r.right - r.left), h = (uint32_t)(r.bottom - r.top);
    bool pv = take_preview();
    bool say = lim(L) || pv;
    if (say) LOGI("convertToGray IN : src=%p dst=%p rect=[%d,%d,%d,%d] (%ux%u) pitch=%dB flag=%d in_place=%d",
                  src, dst, r.left, r.top, r.right, r.bottom, w, h, pitch, flag, src == dst);
    size_t avail = (size_t)pitch * h;
    if (pv && src && pitch > 0) {
        preview_rgba_lum("convertToGray src", (const uint8_t*)src, avail, (uint32_t)pitch, w, h);
        stripe_stats("convertToGray src", (const uint8_t*)src, avail);
    }
    uint64_t t0 = now_ns();
    int64_t ret = real_convertToGray(src, dst, r, pitch, flag);
    uint64_t dt = now_ns() - t0;
    __atomic_fetch_add(&g_ct.convert_to_gray, 1, __ATOMIC_RELAXED);
    if (say && dst && pitch > 0) {
        const uint8_t* d = (const uint8_t*)dst;
        double s_w = pitch_score(d, avail, w, w, h);
        double s_pitch = pitch_score(d, avail, (uint32_t)pitch, w, h);
        double s_stride = pitch_score(d, avail, (uint32_t)pitch / 4, w, h);
        LOGI("convertToGray OUT: ret=%" PRId64 " %" PRIu64 "us  dst[0..7]=%02x%02x%02x%02x%02x%02x%02x%02x "
             "row-continuity score (lower=coherent): pitch=w(%u):%.1f pitch/4(%u):%.1f pitch(%u):%.1f",
             ret, dt / 1000, d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7],
             w, s_w, (unsigned)pitch / 4, s_stride, (unsigned)pitch, s_pitch);
        if (pv) {
            stripe_stats("convertToGray dst first quarter", d, avail / 4);
            stripe_stats("convertToGray dst last quarter", d + avail - avail / 4, avail / 4);
            preview_gray("convertToGray dst @pitch/4", d, avail, (uint32_t)pitch / 4, w, h);
            preview_gray("convertToGray dst @pitch", d, avail, (uint32_t)pitch, w, h);
        }
    }
    return ret;
}

// xrz::EffectHandler::imageSmoothing(void*, void*, void*, void*, android::Rect, int, int)
static int64_t (*real_imageSmoothing)(void*, void*, void*, void*, Rect, int, int);
static int64_t hook_imageSmoothing(void* a, void* b, void* c, void* d, Rect r, int x, int y) {
    static Limiter L = {0, 20, 300};
    if (lim(L)) LOGI("imageSmoothing: %p %p %p %p rect=[%d,%d,%d,%d] %d %d", a, b, c, d, r.left, r.top, r.right, r.bottom, x, y);
    int64_t ret = real_imageSmoothing(a, b, c, d, r, x, y);
    __atomic_fetch_add(&g_ct.image_smoothing, 1, __ATOMIC_RELAXED);
    return ret;
}

// BUILD-SPECIFIC peeks into OverlayPortParam / CommitInfo (see file header).
static void log_port_and_commit(const char* what, const uint8_t* param, const uint8_t* ci) {
    if (!g_build_id_ok || !param || !ci) return;
    const uint8_t* obj = *(const uint8_t* const*)(param + 0x178);
    int32_t src_fd = *(const int32_t*)(param + 0x78);
    int32_t ow = 0, oh = 0, os = 0;
    if (obj) { ow = *(const int32_t*)(obj + 0x48); oh = *(const int32_t*)(obj + 0x4c); os = *(const int32_t*)(obj + 0x50); }
    LOGI("%s: port.src_fd=%d port.obj=%p {w=%d h=%d stride=%d}  ci.mode=0x%x ci.rect=[%d,%d,%d,%d] ci.dither=%d "
         "ci.format=%d ci.flag5c=%d ci.antiflicker=%d",
         what, src_fd, obj, ow, oh, os,
         *(const uint32_t*)(ci + 0x08), *(const int32_t*)(ci + 0x0c), *(const int32_t*)(ci + 0x10),
         *(const int32_t*)(ci + 0x14), *(const int32_t*)(ci + 0x18), *(const int32_t*)(ci + 0x3c),
         *(const int32_t*)(ci + 0x54), ci[0x5c], ci[0x8c]);
}

static int64_t (*real_convertLayerToGray)(void*, void*, void*);
static void capture_ct_fd(const void* param) {
    // B1051C port: the +0x78 peek is only trusted on the known HWC build. Otherwise use the
    // fd sources captured at setClientTarget (same buffer the HWC is about to convert).
    int src = g_ct_src;
    if (src == 0) src = (g_build_id_ok && param) ? 1 : (g_ct_ion_fd >= 0 ? 2 : 3);
    int fd = -1;
    if (src == 1) { if (g_build_id_ok && param) fd = *(const int32_t*)((const uint8_t*)param + 0x78); }
    else if (src == 2) fd = g_ct_ion_fd;
    else if (src == 3) fd = g_ct_handle_fd0;
    if (fd >= 0) g_last_ct_fd = fd;
}

// The client target to read: the newest one whose acquire fence has signalled (SF's GPU work is done), else an
// older complete one; with none complete, the newest (the caller may wait on it with ct_pick). A buffer that is
// still being rendered into is never picked over an older complete frame. Caller holds g_lock.
static int ct_pick_locked() {
    if (!(g_ct_src == 0 && !g_build_id_ok && g_ct_ion_fd >= 0)) return g_last_ct_fd;   // peek / handle sources
    int busy[CT_RING]; int nbusy = 0;
    for (int i = 0; i < CT_RING; i++) {
        CtRef& r = g_ctring[i];
        if (r.fd < 0) continue;
        if (r.fence >= 0) {
            struct pollfd p = {r.fence, POLLIN, 0};
            if (poll(&p, 1, 0) > 0) { close(r.fence); r.fence = -1; }
        }
        bool same_as_busy = false;
        for (int k = 0; k < nbusy; k++) if (busy[k] == r.fd) same_as_busy = true;
        if (r.fence < 0 && !same_as_busy) { if (i > 0) g_ct_fallbacks++; return r.fd; }
        if (r.fence >= 0) busy[nbusy++] = r.fd;
    }
    return g_ctring[0].fd >= 0 ? g_ctring[0].fd : g_ct_ion_fd;
}

// Like ct_pick_locked, but when only an unfinished newest target exists, waits up to wait_ms for its fence
// (outside g_lock).
static int ct_pick(int wait_ms) {
    pthread_mutex_lock(&g_lock);
    int fd = ct_pick_locked();
    int wfd = -1;
    if (wait_ms > 0 && fd == g_ctring[0].fd && g_ctring[0].fence >= 0) wfd = dup(g_ctring[0].fence);
    pthread_mutex_unlock(&g_lock);
    if (wfd >= 0) {
        struct pollfd p = {wfd, POLLIN, 0};
        __atomic_fetch_add(&g_ct_waits, 1, __ATOMIC_RELAXED);
        poll(&p, 1, wait_ms);
        close(wfd);
    }
    return fd;
}

static int64_t hook_convertLayerToGray(void* self, void* param, void* ci) {
    static Limiter L = {0, 30, 300};
    capture_ct_fd(param);
    bool say = lim(L);
    if (say) log_port_and_commit("convertLayerToGray IN ", (const uint8_t*)param, (const uint8_t*)ci);
    int64_t ret = real_convertLayerToGray(self, param, ci);
    __atomic_fetch_add(&g_ct.convert_layer_to_gray, 1, __ATOMIC_RELAXED);
    if (say) { LOGI("convertLayerToGray OUT: ret=%" PRId64, ret); log_port_and_commit("convertLayerToGray OUT", (const uint8_t*)param, (const uint8_t*)ci); }
    return ret;
}

// Vendor bug (crash 2026-10-18, global_refresh_mode=180): processImage ionMMap()s the port fd (param+0x78) with
// the size of its own GraphicBuffer (param+0x178: height @0x4c, stride px @0x50, 4 B/px). If that mmap fails it
// still runs the "skip same frame" memcmp (CommitInfo+0x8b set) on the MAP_FAILED pointer -> SIGSEGV. When the
// dma-buf is smaller than that (or the fd is bad), clear +0x8b for this call so the vendor's own failure path runs.
static bool port_fd_mappable(const uint8_t* param) {
    int fd = *(const int*)(param + 0x78);
    const uint8_t* gb = *(const uint8_t* const*)(param + 0x178);
    if (fd < 0 || gb == nullptr) return false;
    int64_t need = (int64_t)*(const int*)(gb + 0x4c) * *(const int*)(gb + 0x50) * 4;
    off_t size = lseek(fd, 0, SEEK_END);
    return size > 0 && need > 0 && size >= need;
}

static int64_t (*real_processImage)(void*, void*, void*);
static int64_t hook_processImage(void* self, void* param, void* ci) {
    static Limiter L = {0, 30, 300};
    capture_ct_fd(param);
    bool say = lim(L);
    if (say) log_port_and_commit("processImage IN ", (const uint8_t*)param, (const uint8_t*)ci);
    // B1051C build only (review 2026-10-21): these offsets come from its disassembly and mean nothing elsewhere
    uint8_t* skip_same = g_b1051c_ok ? (uint8_t*)ci + 0x8b : nullptr;
    uint8_t saved_skip = skip_same ? *skip_same : 0;
    bool guarded = skip_same && saved_skip && !port_fd_mappable((const uint8_t*)param);
    if (guarded) {
        static Limiter LG = {0, 20, 600};
        *skip_same = 0;
        if (lim(LG)) LOGW("processImage: port fd %d not mappable at GraphicBuffer size -> same-frame check off for this frame",
                          *(const int*)((const uint8_t*)param + 0x78));
    }
    int64_t ret = real_processImage(self, param, ci);
    if (guarded) *skip_same = saved_skip;
    __atomic_fetch_add(&g_ct.process_image, 1, __ATOMIC_RELAXED);
    if (say) { LOGI("processImage OUT: ret=%" PRId64, ret); log_port_and_commit("processImage OUT", (const uint8_t*)param, (const uint8_t*)ci); }
    return ret;
}

static void log_eink_update(const char* what, const drm_eink_update* u, int ret, uint64_t dt_us) {
    LOGI("%s: mode=0x%x area=[%d,%d,%d,%d] dither=%u fd=%d format=%u stride=%u height=%u rsvd0=%x,%x,%x,%x rsvd1=%x,%x rsvd2=%x ret=%d %" PRIu64 "us",
         what, u->mode, u->left, u->top, u->right, u->bottom, u->dither, u->fd, u->format, u->stride, u->height,
         u->rsvd0[0], u->rsvd0[1], u->rsvd0[2], u->rsvd0[3], u->rsvd1[0], u->rsvd1[1], u->rsvd2, ret, dt_us);
}

// Map the committed dma-buf and look at what the kernel is about to read.
static void preview_commit_buffer(const drm_eink_update* u) {
    if (u->fd < 0 || u->stride == 0 || u->height == 0) return;
    uint32_t w = (uint32_t)(u->right - u->left + 1);
    size_t want = (size_t)u->stride * u->height * 4;
    void* m = mmap(nullptr, want, PROT_READ, MAP_SHARED, u->fd, 0);
    if (m == MAP_FAILED) { want = (size_t)u->stride * u->height; m = mmap(nullptr, want, PROT_READ, MAP_SHARED, u->fd, 0); }
    if (m == MAP_FAILED) { LOGW("commit preview: mmap fd=%d failed: %s", u->fd, strerror(errno)); return; }
    const uint8_t* b = (const uint8_t*)m;
    LOGI("commit preview: mapped %zu B of fd=%d; row-continuity: @stride(%u):%.1f @stride*4(%u):%.1f @w(%u):%.1f",
         want, u->fd, u->stride, pitch_score(b, want, u->stride, w, u->height),
         u->stride * 4, pitch_score(b, want, u->stride * 4, w, u->height),
         w, pitch_score(b, want, w, w, u->height));
    stripe_stats("commit buffer head", b, want / 4);
    preview_gray("commit buffer @stride", b, want, u->stride, w, u->height);
    if (want >= (size_t)u->stride * 4 * u->height)
        preview_gray("commit buffer @stride*4", b, want, u->stride * 4, w, u->height);
    munmap(m, want);
}

// mtk_commit(drm_eink_update) -- 68-byte struct, passed by reference per AAPCS64.
static int (*real_mtk_commit)(const drm_eink_update*);
static int hook_mtk_commit(const drm_eink_update* orig) {
    static Limiter L = {0, 40, 120};
    refresh_knobs();
    // When SurfaceFlinger dies (init kills it at every shutdown), the composer destroys all layers and performs "a
    // final presentDisplay" with none: the vendor HWC renders that as an empty, black frame, and on power-off the
    // panel keeps it, replacing the shutdown picture (owner 2026-10-19: "black screen at shutdown"). A commit while
    // the display has no layer at all never carries content: skip it. vendor.debug.hwc.shim.keep_empty_commit=1 = off.
    {
        int layers = 0;
        pthread_mutex_lock(&g_lock);
        for (int i = 0; i < MAX_LAYERS; i++) if (g_layers[i].used) layers++;
        pthread_mutex_unlock(&g_lock);
        if (layers == 0 && prop_int("vendor.debug.hwc.shim.keep_empty_commit", 0) == 0) {
            LOGI("e-ink commit with no layers (SurfaceFlinger gone?) skipped: the panel keeps its picture");
            return 0;
        }
    }
    // Pen ink session: hold the vendor's commit; the ink thread re-commits the app's latest frame when it ends.
    if (__atomic_load_n(&g_ink_session, __ATOMIC_ACQUIRE)) {
        pthread_mutex_lock(&g_lock); g_ink_held_u = *orig; g_ink_held++; pthread_mutex_unlock(&g_lock);
        return 0;
    }
    // Sleep picture: nothing may paint over it. During the vendor's OFF after our commit (g_standby_hold) its own
    // commits are dropped; while the panel sleeps a commit would stall 4 s and fail anyway. SF re-presents at the wake.
    if (__atomic_load_n(&g_standby_hold, __ATOMIC_ACQUIRE) || __atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE)) {
        static Limiter LD = {0, 10, 100};
        if (lim(LD)) LOGI("vendor commit dropped: %s", __atomic_load_n(&g_standby_hold, __ATOMIC_ACQUIRE)
                          ? "the sleep picture is on its way to the panel" : "display off");
        return 0;
    }
    __atomic_fetch_add(&g_ink_inflight, 1, __ATOMIC_ACQ_REL);
    drm_eink_update mod = *orig; const drm_eink_update* u = orig;
    bool rewritten = false;
    if (g_commit_stride > 0 && mod.stride != (uint32_t)g_commit_stride) { mod.stride = g_commit_stride; rewritten = true; }
    if (g_commit_height > 0 && mod.height != (uint32_t)g_commit_height) { mod.height = g_commit_height; rewritten = true; }
    if (g_commit_format >= 0 && mod.format != (uint32_t)g_commit_format) { mod.format = g_commit_format; rewritten = true; }
    if (g_commit_mode >= 0 && mod.mode != (uint32_t)g_commit_mode) { mod.mode = g_commit_mode; rewritten = true; }
    // Scrolling (see g_scroll_mode): after the end, the vendor's cleaning GC16 (the first GC16 within 1.5 s of its
    // first commit after the end) either cleans or, if the helper did, goes out in the app's own mode; one that comes
    // while the next drag already runs is redundant too (that scroll gets its own clean) and goes out as the scroll's
    // mode. Then Extreme instead of the scroll's Fast.
    bool scrolling = __atomic_load_n(&g_scrolling, __ATOMIC_ACQUIRE);
    if (__atomic_load_n(&g_scroll_vendor, __ATOMIC_ACQUIRE)) {
        uint64_t ms = now_ns() / 1000000, t1 = __atomic_load_n(&g_scroll_t1_ms, __ATOMIC_ACQUIRE);
        if (!t1) { t1 = ms; __atomic_store_n(&g_scroll_t1_ms, t1, __ATOMIC_RELEASE); }
        if (ms - t1 > 1500) {
            __atomic_store_n(&g_scroll_vendor, 0, __ATOMIC_RELEASE);
        } else if ((mod.mode & 0xffff) == 4) {
            __atomic_store_n(&g_scroll_vendor, 0, __ATOMIC_RELEASE);
            uint32_t base = (uint32_t)g_global_mode & 0xffff;   // another app may be in front by now
            uint32_t to = scrolling ? 178 : base;
            if ((scrolling || __atomic_load_n(&g_scroll_cleaned, __ATOMIC_ACQUIRE)) && (to == 178 || to == 177 || to == 180)) {
                mod.mode = (mod.mode & ~0xffffu) | to;
                rewritten = true;
                LOGI("scroll: the vendor's full refresh after the scroll goes out as 0x%x (%s)", to,
                     scrolling ? "the next scroll runs" : "already cleaned");
            } else {
                __atomic_store_n(&g_scroll_pending, 0, __ATOMIC_RELEASE);
                LOGI("scroll: cleaned by the vendor's full refresh");
            }
        }
    }
    if (g_scroll_mode == 179 && scrolling && (mod.mode & 0xffff) == 178) {
        mod.mode = (mod.mode & ~0xffffu) | 179;
        rewritten = true;
    }
    // Navigation strip / video (see g_nav_mode): what changed since the last vendor commit, by layer
    bool nav_only = false, nav_split = false, video = false;
    hwc_rect_t nav = {0, 0, 0, 0};
    bool ime_only = false;               // see g_ime_mode
    bool ime_split = false;              // a swipe while the app changes too: keyboard area + the rest as 2 updates
    hwc_rect_t ime = {0, 0, 0, 0};       // the keyboard area in panel pixels (empty = none)
    uint32_t ime_mode = 0;
    int scr_w = 2480, scr_h = 1860;
    if ((g_nav_mode > 0 || g_video_mode > 0) && (mod.mode & 0xffff) != 4) {
        uint64_t ms = now_ns() / 1000000;
        pthread_mutex_lock(&g_lock);
        if (g_ct_buf.valid && g_ct_buf.w > 0 && g_ct_buf.h > 0) { scr_w = g_ct_buf.w; scr_h = g_ct_buf.h; }
        uint64_t since = g_commit_layer_seq;
        g_commit_layer_seq = g_layer_seq;
        int nav_i = -1, best = 0;
        for (int i = 0; i < MAX_LAYERS; i++) {
            const LayerState& l = g_layers[i];
            if (!l.used) continue;
            const hwc_rect_t& f = l.frame;
            int fw = f.right - f.left, fh = f.bottom - f.top, th = 0;
            if (f.top <= 0 && f.bottom >= scr_h && (f.left <= 0 || f.right >= scr_w)) th = fw;        // along a side edge
            else if (f.left <= 0 && f.right >= scr_w && (f.top <= 0 || f.bottom >= scr_h)) th = fh;   // along top / bottom
            if (th >= 80 && th <= 400 && th > best) { best = th; nav_i = i; }
            int fmt = l.buf.format;
            bool rgb = fmt == 1 || fmt == 2 || fmt == 3 || fmt == 4 || fmt == 5 || fmt == 0x16 || fmt == 0x2b;
            if (g_video_mode > 0 && l.buf.valid && fmt != 0 && !rgb && (int64_t)fw * fh * 20 >= (int64_t)scr_w * scr_h
                && ms - l.buf_ms < 600) video = true;
        }
        if (nav_i >= 0) nav = g_layers[nav_i].frame;
        // Other changes beside the strip: outside it (they go out as a second update in the app's mode) or small and
        // inside it (our refresh button: they go with the strip). Anything else (an app transition under a transparent
        // taskbar) keeps the one update in the app's mode, so app content is never drawn in the strip's mode.
        bool nav_chg = false, other = false, mixed = false;
        int64_t nav_area = (int64_t)(nav.right - nav.left) * (nav.bottom - nav.top);
        auto classify = [&](const hwc_rect_t& f) {
            bool apart = f.right <= nav.left || f.left >= nav.right || f.bottom <= nav.top || f.top >= nav.bottom;
            bool within = f.left >= nav.left && f.right <= nav.right && f.top >= nav.top && f.bottom <= nav.bottom;
            if (apart) other = true;
            else if (!within || (int64_t)(f.right - f.left) * (f.bottom - f.top) * 4 > nav_area) mixed = true;
        };
        char why[160]; int wl = 0; why[0] = 0;
        for (int i = 0; i < MAX_LAYERS; i++)
            if (g_layers[i].used && g_layers[i].chg_seq > since) {
                if (i == nav_i) { nav_chg = true; continue; }
                const hwc_rect_t& f = g_layers[i].frame;
                if (nav_i >= 0) classify(f); else mixed = true;
                if (wl < (int)sizeof(why) - 40) wl += snprintf(why + wl, sizeof(why) - wl, " [%d,%d,%d,%d]", f.left, f.top, f.right, f.bottom);
            }
        if (g_layer_gone_seq > since) {
            if (nav_i >= 0 && g_gone_box.right > g_gone_box.left) classify(g_gone_box); else mixed = true;
        }
        // Keyboard area: panel x = display y, panel y = panel height - display x (rotation 0; as the nav strip).
        bool ime_any = false, ime_out = false;
        pthread_mutex_lock(&g_ime_lock);
        if (g_ime_ok) { ime.left = g_ime_t; ime.right = g_ime_b; ime.top = scr_h - g_ime_r; ime.bottom = scr_h - g_ime_l; }
        pthread_mutex_unlock(&g_ime_lock);
        if (ime.left < 0) ime.left = 0;
        if (ime.top < 0) ime.top = 0;
        if (ime.right > scr_w) ime.right = scr_w;
        if (ime.bottom > scr_h) ime.bottom = scr_h;
        if (ime.right - ime.left >= 64 && ime.bottom - ime.top >= 64) {
            const int m = 8;   // a layer may overhang the window by a few pixels (rounding, shadows)
            auto touch = [&](const hwc_rect_t& f) {
                bool apart = f.right <= ime.left || f.left >= ime.right || f.bottom <= ime.top || f.top >= ime.bottom;
                bool within = f.left >= ime.left - m && f.right <= ime.right + m && f.top >= ime.top - m && f.bottom <= ime.bottom + m;
                if (!apart) ime_any = true;
                if (!within) ime_out = true;
            };
            // A window's layer can be far larger than what it draws (the keyboard window covers the screen below the
            // status bar, transparent above the keys): judge its new buffer by SF's damage in it, mapped to the panel
            // (no transform only; anything else, a geometry change or no damage known, by its frame).
            auto changed_box = [](const LayerState& l) -> hwc_rect_t {
                const hwc_rect_t& f = l.frame;
                float cw = l.crop.right - l.crop.left, ch = l.crop.bottom - l.crop.top;
                const hwc_rect_t& d = l.dmg;
                if (l.transform != 0 || l.dmg_buf != l.buffer || !l.buffer || cw <= 0 || ch <= 0
                    || d.right <= d.left || d.bottom <= d.top) return f;
                float sx = (f.right - f.left) / cw, sy = (f.bottom - f.top) / ch;
                hwc_rect_t r;
                r.left = f.left + (int32_t)floorf((d.left - l.crop.left) * sx);
                r.top = f.top + (int32_t)floorf((d.top - l.crop.top) * sy);
                r.right = f.left + (int32_t)ceilf((d.right - l.crop.left) * sx);
                r.bottom = f.top + (int32_t)ceilf((d.bottom - l.crop.top) * sy);
                if (r.left < f.left) r.left = f.left;
                if (r.top < f.top) r.top = f.top;
                if (r.right > f.right) r.right = f.right;
                if (r.bottom > f.bottom) r.bottom = f.bottom;
                return r.right > r.left && r.bottom > r.top ? r : f;
            };
            for (int i = 0; i < MAX_LAYERS; i++)
                if (g_layers[i].used && g_layers[i].chg_seq > since) touch(changed_box(g_layers[i]));
            if (g_layer_gone_seq > since && g_gone_box.right > g_gone_box.left) touch(g_gone_box);
        } else {
            ime.left = ime.top = ime.right = ime.bottom = 0;
        }
        g_gone_box.left = g_gone_box.top = g_gone_box.right = g_gone_box.bottom = 0;
        if (nav_chg && mixed) {
            static Limiter LW = {0, 20, 200};
            if (lim(LW)) LOGI("navigation strip changed with the screen under it:%s%s", why, g_layer_gone_seq > since ? " + a removed layer" : "");
        }
        pthread_mutex_unlock(&g_lock);
        nav_only = g_nav_mode > 0 && nav_i >= 0 && nav_chg && !mixed && !video && !scrolling;
        nav_split = nav_only && other;
        if (ime_any && !video) {
            // a key, the trail or the suggestion strip changed: clean the area once typing pauses
            pthread_mutex_lock(&g_ime_lock);
            g_ime_last_ms = ms;
            g_ime_clean_rect = ime;
            pthread_mutex_unlock(&g_ime_lock);
            if (g_ime_clean_ms > 0) __atomic_store_n(&g_ime_clean, 1, __ATOMIC_RELEASE);
        }
        static uint64_t s_ime_prev_ms;
        static int s_ime_burst;
        if (ime_any && !video) {
            // A swipe (gesture typing): einkd reports the drag as a scroll, or the keyboard area changes in a quick
            // run of commits (the trail). Apps that update while you swipe (live search results) change the screen
            // too: then the keyboard area and the rest go out as two updates, the rest in the app's mode.
            s_ime_burst = ms - s_ime_prev_ms <= (uint64_t)g_ime_swipe_gap ? s_ime_burst + 1 : 1;
            s_ime_prev_ms = ms;
            bool swipe = (scrolling && !ime_out) || s_ime_burst >= 3;
            if (!ime_out) {
                ime_mode = swipe && g_ime_swipe_mode > 0 ? (uint32_t)g_ime_swipe_mode : (uint32_t)(g_ime_mode > 0 ? g_ime_mode : 0);
                ime_only = ime_mode > 0;
            } else if (swipe && g_ime_swipe_mode > 0 && !scrolling) {
                ime_mode = (uint32_t)g_ime_swipe_mode;
                ime_only = ime_split = true;
            }
            if (ime_only) { nav_only = false; nav_split = false; }
        }
        if (video) {
            static uint64_t s_video_said;
            if (ms - __atomic_load_n(&g_video_ms, __ATOMIC_ACQUIRE) > 5000 && ms - s_video_said > 5000) {
                s_video_said = ms;
                LOGI("video playing: the screen goes out as 0x%x", g_video_mode);
            }
            __atomic_store_n(&g_video_ms, ms, __ATOMIC_RELEASE);
            mod.mode = (mod.mode & ~0xffffu) | (uint32_t)g_video_mode;
            rewritten = true;
        }
    }
    // (a swipe in the keyboard area is no scroll to clean: the keyboard area's own clean-up follows it)
    if ((scrolling || video) && !ime_only && ((mod.mode & 0xffff) == 178 || (mod.mode & 0xffff) == 179)) __atomic_store_n(&g_scroll_fast, 1, __ATOMIC_RELEASE);
    // Regal requested but the HWC fell back to 177: commit 180 (dither and flag bits kept). Logged at the
    // normal rate, not as a rewrite, since it happens on every commit while Regal is on.
    bool regal = false;
    {
        static bool s_regal_said = false;
        if (g_force_regal && (g_global_mode & 0xffff) == 180 && (mod.mode & 0xffff) == 177) {
            mod.mode = (mod.mode & ~0xffffu) | 180;
            regal = true;
            if (!s_regal_said) { s_regal_said = true; LOGI("Regal: HWC committed 177, forcing 180"); }
        } else if (s_regal_said && (g_global_mode & 0xffff) != 180) {
            s_regal_said = false;
        }
    }
    // Client target to read: the newest complete one (a frame still being rendered by the GPU is never read; with
    // nothing complete, wait up to 50 ms for the newest). Our own commit buffer below holds the processed frame, so
    // an older complete target is always a correct picture.
    int ct_fd = ct_pick(50);
    // The colour pass writes our buffer; the settled-GC16 helper re-sends it: one at a time.
    pthread_mutex_lock(&g_commit_lock);
    if (g_auto_gc16 && g_clean_freq > 0) {
        static uint64_t s_last_ms = 0;
        if ((mod.mode & 0xffff) == 4) {
            __atomic_store_n(&g_turns, 0, __ATOMIC_RELAXED);   // any full refresh (manual clear too) restarts the count
        } else if (g_check_commit) {
            uint64_t ms = now_ns() / 1000000;
            int pct = 100;
            if (ms - s_last_ms >= 800 && g_page_min_pct > 0) {
                pct = frame_change_pct(ct_fd, g_ct_buf.w, g_ct_buf.h, g_ct_buf.stride > 0 ? g_ct_buf.stride : g_ct_buf.w);
                static Limiter LS = {0, 20, 100};
                if (pct < g_page_min_pct && lim(LS)) LOGI("page update not counted: %d%% changed (< %d%%)", pct, g_page_min_pct);
            }
            if (ms - s_last_ms >= 800 && pct >= g_page_min_pct) {
                s_last_ms = ms;
                int n = __atomic_add_fetch(&g_turns, 1, __ATOMIC_RELAXED);
                static Limiter LP = {0, 20, 50};
                if (lim(LP)) LOGI("page update counted: %d%% changed (%d/%d)", pct, n, g_clean_freq);
                if (n >= g_clean_freq) {
                    __atomic_store_n(&g_turns, 0, __ATOMIC_RELAXED);
                    if (g_auto_gc16 == 2) {
                        mod.mode = 4;
                        rewritten = true;
                        LOGI("auto full refresh: GC16 after %d page updates", g_clean_freq);
                    } else {
                        __atomic_store_n(&g_gc16_internal, 1, __ATOMIC_RELEASE);
                        LOGI("auto full refresh: GC16 requested after %d page updates", g_clean_freq);
                    }
                }
            }
        }
    }
    bool colored = false;
    uint64_t color_us = 0;
    int rows = 0, copied = 0, out_fd = -1;
    __atomic_store_n(&g_last_vendor_mode, mod.mode, __ATOMIC_RELAXED);
    color_select(mod.mode);
    // Fast / Extreme: the kernel dither the knob asks for (the vendor sends 3, or 6 with colour restoration)
    bool dith = false;
    if (g_fast_dither >= 0 && ((mod.mode & 0xffff) == 178 || (mod.mode & 0xffff) == 179) && mod.dither != (uint32_t)g_fast_dither) {
        mod.dither = (uint32_t)g_fast_dither;
        dith = true;
    }
    bool smooth = smooth_mode(mod.mode);
    if (g_commit_use_ct && ct_fd >= 0) {
        // Colour pipeline: the processed client target in one of our own commit buffers (only the changed rows
        // converted), committed instead of the HWC's buffer.
        if ((color_active() || smooth) && mod.format != 0 && mod.fd >= 0 && mod.fd != ct_fd) {
            uint64_t c0 = now_ns();
            colored = color_apply_inc(ct_fd, mod.stride, mod.height, &out_fd, &rows, &copied, smooth);
            color_us = (now_ns() - c0) / 1000;
            if (colored) { mod.fd = out_fd; if (smooth) mod.dither = 0; }   // smoothed values are panel levels already
        }
        if (!colored && mod.fd != ct_fd) { mod.fd = ct_fd; rewritten = true; }
    }
    if (colored) {
        g_cstat_frames++; g_cstat_rows += (uint64_t)rows; g_cstat_us += color_us;
        static Limiter LC = {0, 10, 200};
        if (lim(LC) || (g_cstat_frames % 100) == 0)
            LOGI("colour: vivid=%d contrast=%d brightness=%d mode=%d rows=%d/%u copied=%d %s %" PRIu64 "us fd=%d (e-ink mode 0x%x) [frames=%" PRIu64 " mean rows=%" PRIu64 " mean %" PRIu64 "us]",
                 g_cp_built.sat, g_cp_built.con, g_cp_built.bri, g_cp_built.mode, rows, mod.height, copied,
                 g_use3d ? "3D" : "1D", color_us, out_fd, mod.mode, g_cstat_frames, g_cstat_rows / g_cstat_frames, g_cstat_us / g_cstat_frames);
    }
    // Navigation strip: the same pixels (stages and smoothing of the app's mode above), its own mode and area; with
    // changes elsewhere too, the rest of the screen follows as a second update in the app's mode.
    drm_eink_update rest;
    if (nav_split) {
        rest = mod;
        rest.left = 0; rest.top = 0; rest.right = scr_w - 1; rest.bottom = scr_h - 1;
        if (nav.right >= scr_w && nav.left > 0) rest.right = nav.left - 1;        // strip on the right edge
        else if (nav.left <= 0 && nav.right < scr_w) rest.left = nav.right;       // left edge
        else if (nav.bottom >= scr_h && nav.top > 0) rest.bottom = nav.top - 1;   // bottom
        else rest.top = nav.bottom;                                               // top
    }
    if (nav_only) {
        mod.mode = (mod.mode & ~0xffffu) | (uint32_t)g_nav_mode;
        mod.dither = 0;
        mod.left = nav.left > 0 ? nav.left : 0;
        mod.top = nav.top > 0 ? nav.top : 0;
        mod.right = (nav.right < scr_w ? nav.right : scr_w) - 1;
        mod.bottom = (nav.bottom < scr_h ? nav.bottom : scr_h) - 1;
        rewritten = true;
        static Limiter LN = {0, 10, 200};
        if (lim(LN)) LOGI("navigation strip: [%d,%d,%d,%d] as 0x%x%s", mod.left, mod.top, mod.right, mod.bottom, g_nav_mode,
                          nav_split ? ", the rest of the screen in the app's mode" : " alone");
    }
    if (ime_split) {   // the rest of the screen: the app's mode, everything outside the keyboard band
        rest = mod;
        rest.left = 0; rest.top = 0; rest.right = scr_w - 1; rest.bottom = scr_h - 1;
        if (ime.right >= scr_w && ime.left > 0) rest.right = ime.left - 1;          // the keyboard along the right edge
        else if (ime.left <= 0 && ime.right < scr_w) rest.left = ime.right;         // left edge
        else if (ime.bottom >= scr_h && ime.top > 0) rest.bottom = ime.top - 1;     // bottom
        else if (ime.top <= 0 && ime.bottom < scr_h) rest.top = ime.bottom;         // top
        else ime_split = false;                                                     // not a band: one update
        if (!ime_split) {
            ime_only = false;
        } else {   // the rest waits for the swipe to pause (see g_ime_rest_ms); the panel is the trail's meanwhile
            hwc_rect_t rr = {rest.left, rest.top, rest.right + 1, rest.bottom + 1};
            pthread_mutex_lock(&g_ime_lock);
            g_ime_rest_rect = rr;
            g_ime_rest_mode = rest.mode & 0xffff;
            g_ime_rest_dither = (int)rest.dither;
            pthread_mutex_unlock(&g_ime_lock);
            __atomic_store_n(&g_ime_rest, 1, __ATOMIC_RELEASE);
        }
    }
    if (ime_only) {
        mod.mode = (mod.mode & ~0xffffu) | ime_mode;
        mod.dither = 0;
        mod.left = ime.left;
        mod.top = ime.top;
        mod.right = ime.right - 1;
        mod.bottom = ime.bottom - 1;
        rewritten = true;
        static Limiter LK = {0, 10, 200};
        if (lim(LK)) LOGI("keyboard area: [%d,%d,%d,%d] as 0x%x%s%s%s", mod.left, mod.top, mod.right, mod.bottom, ime_mode,
                          ime_mode == (uint32_t)g_ime_swipe_mode && ime_mode != (uint32_t)g_ime_mode ? " (swipe trail)" : "",
                          scrolling ? ", einkd drag" : "", ime_split ? ", the rest of the screen after the swipe" : "");
    }
    bool use_mod = rewritten || regal || colored || dith;
    if (use_mod) u = &mod;
    bool pv = take_preview();
    if (pv) preview_commit_buffer(u);
    uint64_t t0 = now_ns();
    int ret = real_mtk_commit(u);
    if (nav_split) { int r2 = real_mtk_commit(&rest); if (ret == 0) ret = r2; }
    uint64_t t1 = now_ns(), dt = (t1 - t0) / 1000;
    pthread_mutex_lock(&g_lock); g_last_update = nav_split ? rest : *u; g_last_update_seq++; pthread_mutex_unlock(&g_lock);
    __atomic_store_n(&g_last_commit_ms, t1 / 1000000, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&g_commit_lock);
    if (lim(L) || pv || rewritten) {
        if (use_mod) log_eink_update("EINK_UPDATE(mtk_commit) ORIGINAL", orig, 0, 0);
        log_eink_update(use_mod ? "EINK_UPDATE(mtk_commit) REWRITTEN" : "EINK_UPDATE(mtk_commit)", u, ret, dt);
    }
    // First commit after a wake from the sleep picture: the screen change happened in the app's own mode, so one
    // settled GC16 cleans the keyguard (vendor.debug.hwc.shim.standby_wake_gc16=0 turns this off).
    if (ret == 0 && __atomic_exchange_n(&g_wake_gc16, 0, __ATOMIC_ACQ_REL)) {
        __atomic_store_n(&g_gc16_internal, 1, __ATOMIC_RELEASE);
        LOGI("wake after the sleep picture: one settled full refresh requested");
    }
    __atomic_fetch_sub(&g_ink_inflight, 1, __ATOMIC_ACQ_REL);
    return ret;
}

// Settled full refresh (see g_commit_lock above): re-send the last commit as GC16 (mode 4), or after a scroll in
// the app's own mode (no flash). stage_mode = the mode whose colour stages the frame gets.
static void recommit(const char* why, uint32_t stage_mode, uint32_t mode, const hwc_rect_t* area = nullptr, int dither = -1) {
    pthread_mutex_lock(&g_commit_lock);
    if (__atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE)) {   // the panel sleeps (sleep picture): nothing goes out
        pthread_mutex_unlock(&g_commit_lock);
        LOGI("full refresh (%s) skipped: display off", why);
        return;
    }
    pthread_mutex_lock(&g_lock); drm_eink_update u = g_last_update; uint64_t seq = g_last_update_seq; pthread_mutex_unlock(&g_lock);
    // The fd must still be a buffer of the committed size (the HWC keeps its commit buffers; SF keeps the client
    // target while the screen is static) -- otherwise skip rather than show something else.
    off_t size = u.fd >= 0 ? lseek(u.fd, 0, SEEK_END) : -1;
    if (seq == 0 || !real_mtk_commit || size < (off_t)u.stride * u.height) {
        pthread_mutex_unlock(&g_commit_lock);
        LOGW("full refresh (%s) skipped: no usable last commit (seq %" PRIu64 ", fd %d, size %lld)", why, seq, u.fd, (long long)size);
        return;
    }
    // Wave 3: the colour knobs may have changed since that frame (E-Ink Control or adb sets a prop and asks for a
    // full refresh): run the pass again on the newest complete client target, so the GC16 shows the new look. With
    // the pipeline off, the raw client target goes out. The app's own mode decides the stages, not the GC16.
    int rows = 0, copied = 0;
    const char* src = "last commit";
    if (g_commit_use_ct && u.stride > 0 && u.height > 0) {
        refresh_knobs();
        color_select(stage_mode);
        int ct = ct_pick(50);
        if (ct >= 0 && lseek(ct, 0, SEEK_END) >= (off_t)u.stride * u.height * 4) {
            int fd = -1;
            bool smooth = smooth_mode(stage_mode);
            if (color_active() || smooth) {
                if (color_apply_inc(ct, u.stride, u.height, &fd, &rows, &copied, smooth)) { u.fd = fd; src = "colour pass"; }
            } else {
                u.fd = ct; src = "client target";
            }
        }
    }
    // The GC16 re-sends the last commit, dither field included: after Fast (3) / Extreme (6) the full refresh came out
    // 1-bit (dmesg 2026-10-26 22:4x: mode 0x4 dither=6(def=6), also after a GU16 with dither 0, so the kernel takes
    // the field, it does not inherit the last update's dither as KERNEL-DITHER.md assumed). gc16_prep 1: the GC16
    // gets dither 0 after a fast-mode update; 2: also one GU16 with dither 0 before it (the first attempt).
    uint32_t lm = u.mode & 0xffff;
    bool fast_last = lm == 178 || lm == 179 || lm == 0x10 || lm == 0x2;
    if (mode == 4 && g_gc16_prep >= 2 && fast_last) {
        drm_eink_update pu = u;
        pu.mode = 0x84; pu.dither = 0;
        int pr = real_mtk_commit(&pu);
        LOGI("full refresh (%s): GU16 dither 0 first (last update 0x%x) ret=%d", why, lm, pr);
    }
    if ((mode != 4 || g_gc16_prep >= 1) && fast_last) u.dither = 0;   // the fast commit's 3 / 6 would be 1-bit
    u.mode = mode;
    u.left = 0; u.top = 0; u.right = (int32_t)u.stride - 1; u.bottom = (int32_t)u.height - 1;   // the last may be the nav strip
    if (dither >= 0) u.dither = (uint32_t)dither;   // a deferred app update keeps the app's own dither
    if (area && area->right > area->left && area->bottom > area->top) {   // one part of the screen (the keyboard area)
        u.left = area->left > 0 ? area->left : 0;
        u.top = area->top > 0 ? area->top : 0;
        if (area->right - 1 < u.right) u.right = area->right - 1;
        if (area->bottom - 1 < u.bottom) u.bottom = area->bottom - 1;
    }
    uint64_t t0 = now_ns();
    int ret = real_mtk_commit(&u);
    uint64_t t1 = now_ns();
    pthread_mutex_lock(&g_lock); g_last_update = u; g_last_update_seq++; pthread_mutex_unlock(&g_lock);
    __atomic_store_n(&g_last_commit_ms, t1 / 1000000, __ATOMIC_RELEASE);
    if (mode == 4) __atomic_store_n(&g_turns, 0, __ATOMIC_RELAXED);
    pthread_mutex_unlock(&g_commit_lock);
    LOGI("full refresh (%s): %s re-sent as 0x%x area=[%d,%d,%d,%d] fd=%d rows=%d/%d ret=%d %" PRIu64 "us",
         why, src, mode, u.left, u.top, u.right, u.bottom, u.fd, rows, copied, ret, (t1 - t0) / 1000);
}

static void recommit_gc16(const char* why) { recommit(why, __atomic_load_n(&g_last_vendor_mode, __ATOMIC_RELAXED), 4); }

// ---------------------------------------------------------------- sleep picture (standby frame)
static void standby_path(char* out, size_t cap) {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("vendor.debug.hwc.shim.standby_path", v) > 0 && v[0]) snprintf(out, cap, "%s", v);
    else snprintf(out, cap, "%s", "/data/vendor/b1051c/sleep/frame.rgbx");
}

// Page pixel (x right, y down on the portrait page) -> byte offset in the raw frame (see Lock & Sleep's Renderer).
static size_t clock_raw_off(uint32_t flags, int x, int y) {
    int X = (flags & 2) ? STANDBY_W - 1 - y : y, Y = (flags & 2) ? x : STANDBY_H - 1 - x;
    return ((size_t)Y * STANDBY_W + X) * 4;
}

static void clock_free_locked() {
    free(g_clock.file); free(g_clock.bg);
    g_clock.file = nullptr; g_clock.bg = nullptr; g_clock.valid = false;
}

// clock.bin for frame #seq, right after the frame itself was loaded. Caller holds g_standby_lock. No file = no time.
static void clock_load_locked(int seq) {
    clock_free_locked();
    char path[PROP_VALUE_MAX + 16];
    standby_path(path, sizeof(path));
    char* slash = strrchr(path, '/');
    if (!slash) return;
    snprintf(slash + 1, sizeof(path) - (size_t)(slash + 1 - path), "clock.bin");
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        if (errno != ENOENT) LOGW("standby clock: open(%s): %s", path, strerror(errno));
        return;
    }
    struct stat st; memset(&st, 0, sizeof(st));
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < (off_t)sizeof(ClockHdr) || st.st_size > (1 << 20)) {
        LOGW("standby clock: %s is not a clock file (%lld B)", path, (long long)st.st_size);
        close(fd);
        return;
    }
    size_t len = (size_t)st.st_size, got = 0;
    uint8_t* f = (uint8_t*)malloc(len);
    while (f && got < len) {
        ssize_t n = read(fd, f + got, len - got);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        got += (size_t)n;
    }
    close(fd);
    if (!f || got != len) { free(f); LOGW("standby clock: short read of %s", path); return; }
    ClockHdr h; memcpy(&h, f, sizeof(h));
    const char* bad = nullptr;
    size_t need = sizeof(ClockHdr) + (size_t)(h.nglyph > 0 ? h.nglyph : 0) * sizeof(ClockGlyph);
    if (memcmp(h.magic, CLOCK_MAGIC, 8) != 0) bad = "bad magic";
    else if ((int)h.seq != seq) bad = "written for another frame";
    else if (h.nglyph < 1 || h.nglyph > CLOCK_MAX_GLYPHS || need > len) bad = "glyph table";
    else if (h.box_w < 1 || h.box_h < 1 || h.box_w > PAGE_W || h.box_h > 512 || h.box_x < 0 || h.box_y < 0
             || h.box_x > PAGE_W || h.box_y > PAGE_H || h.box_x + h.box_w > PAGE_W || h.box_y + h.box_h > PAGE_H
             || h.x0 < -PAGE_W || h.x0 > 2 * PAGE_W) bad = "box outside the page";   // bounds first: no int overflow
    if (!bad) {
        memcpy(g_clock.g, f + sizeof(ClockHdr), (size_t)h.nglyph * sizeof(ClockGlyph));
        size_t at = need;
        for (int i = 0; i < h.nglyph && !bad; i++) {
            const ClockGlyph& g = g_clock.g[i];
            if (g.w < 1 || g.w > 1024 || g.left < -512 || g.left > 512 || g.adv_q6 < 0 || g.adv_q6 > 1024 * 64) {
                bad = "glyph size";
                break;
            }
            size_t sz = (size_t)g.w * h.box_h;
            if (at + sz > len) { bad = "glyph bitmaps truncated"; break; }
            g_clock.bits[i] = f + at;
            at += sz;
        }
        if (!bad && at != len) bad = "trailing bytes";
    }
    if (bad) { free(f); LOGW("standby clock: %s ignored (%s)", path, bad); return; }
    uint8_t* bg = (uint8_t*)malloc((size_t)h.box_w * h.box_h * 4);
    if (!bg) { free(f); return; }
    dmabuf_sync(g_standby.fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ);
    for (int y = 0; y < h.box_h; y++)
        for (int x = 0; x < h.box_w; x++)
            memcpy(bg + ((size_t)y * h.box_w + x) * 4, g_standby.map + clock_raw_off(h.flags, h.box_x + x, h.box_y + y), 4);
    dmabuf_sync(g_standby.fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);
    g_clock.h = h; g_clock.file = f; g_clock.bg = bg; g_clock.valid = true;
    LOGI("standby clock: %d glyphs for #%d, box [%d,%d %dx%d] x0=%d %s", h.nglyph, seq, h.box_x, h.box_y, h.box_w, h.box_h,
         h.x0, (h.flags & 1) ? "24h" : "12h");
}

// Write the local time into the frame's box, just before the sleep commit. Caller holds g_standby_lock.
static void clock_draw_locked(const char* why) {
    if (!g_clock.valid || (int)g_clock.h.seq != g_standby.loaded_seq || !g_standby.valid) return;
    if (!prop_int("vendor.debug.hwc.shim.standby_clock", 1)) return;
    uint64_t t0 = now_ns();
    const ClockHdr& h = g_clock.h;
    int64_t t = (int64_t)time(nullptr);
    time_t lt = (time_t)(t + ((h.trans_s && t >= h.trans_s) ? h.off2_s : h.off_s));
    struct tm tm; memset(&tm, 0, sizeof(tm));
    gmtime_r(&lt, &tm);
    char s[16];
    if (h.flags & 1) snprintf(s, sizeof(s), "%02d:%02d", tm.tm_hour, tm.tm_min);
    else snprintf(s, sizeof(s), "%d:%02d %s", (tm.tm_hour + 11) % 12 + 1, tm.tm_min, tm.tm_hour < 12 ? "AM" : "PM");
    const uint32_t fc[3] = {h.fg & 0xff, (h.fg >> 8) & 0xff, (h.fg >> 16) & 0xff};
    uint8_t* m = g_standby.map;
    dmabuf_sync(g_standby.fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_RW);
    for (int y = 0; y < h.box_h; y++)   // the previous sleep's time goes first
        for (int x = 0; x < h.box_w; x++)
            memcpy(m + clock_raw_off(h.flags, h.box_x + x, h.box_y + y), g_clock.bg + ((size_t)y * h.box_w + x) * 4, 4);
    int64_t pen = (int64_t)h.x0 * 64;
    int n = 0;
    for (const char* c = s; *c; c++) {
        int gi = -1;
        for (int i = 0; i < h.nglyph; i++) if (g_clock.g[i].ch == (uint8_t)*c) { gi = i; break; }
        if (gi < 0) continue;
        const ClockGlyph& g = g_clock.g[gi];
        int gx = (int)((pen + 32) / 64) + g.left;
        for (int y = 0; y < h.box_h; y++) {
            const uint8_t* row = g_clock.bits[gi] + (size_t)y * g.w;
            for (int x = 0; x < g.w; x++) {
                uint32_t a = row[x];
                int px = gx + x;
                if (!a || px < h.box_x || px >= h.box_x + h.box_w) continue;
                uint8_t* d = m + clock_raw_off(h.flags, px, h.box_y + y);
                for (int k = 0; k < 3; k++) d[k] = (uint8_t)((d[k] * (255 - a) + fc[k] * a + 127) / 255);
            }
        }
        pen += g.adv_q6;
        n++;
    }
    dmabuf_sync(g_standby.fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_RW);
    g_clock.draws++;
    LOGI("standby clock (%s): \"%s\" drawn, %d glyphs, %" PRIu64 " us", why, s, n, (now_ns() - t0) / 1000);
}

// Load frame #seq from the file into our buffer. Caller holds g_standby_lock.
static bool standby_load_locked(int seq) {
    char path[PROP_VALUE_MAX + 1];
    standby_path(path, sizeof(path));
    uint64_t t0 = now_ns();
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        g_standby.last_errno = errno; g_standby.fails++;
        LOGW("standby: #%d not loaded: open(%s): %s", seq, path, strerror(errno));
        return false;
    }
    struct stat st; memset(&st, 0, sizeof(st));
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || (size_t)st.st_size != STANDBY_BYTES) {
        LOGW("standby: #%d not loaded: %s is %s %lld B (need a regular file of %zu B)", seq, path,
             S_ISREG(st.st_mode) ? "a file of" : "not a regular file,", (long long)st.st_size, STANDBY_BYTES);
        close(fd); g_standby.fails++;
        return false;
    }
    if (g_standby.fd < 0) {
        int bfd = dmabuf_alloc(STANDBY_BYTES, ION_FLAG_CACHED_);
        void* m = bfd >= 0 ? mmap(nullptr, STANDBY_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, bfd, 0) : MAP_FAILED;
        if (m == MAP_FAILED) {
            if (bfd >= 0) close(bfd);
            close(fd); g_standby.fails++;
            LOGE("standby: no buffer for the sleep picture (%zu B): %s", STANDBY_BYTES, strerror(errno));
            return false;
        }
        g_standby.fd = bfd; g_standby.map = (uint8_t*)m;
        LOGI("standby: buffer %dx%d RGBA, dma-buf fd %d", STANDBY_W, STANDBY_H, bfd);
    }
    g_standby.valid = false;
    dmabuf_sync(g_standby.fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE);
    size_t got = 0;
    while (got < STANDBY_BYTES) {
        size_t want = STANDBY_BYTES - got; if (want > (2u << 20)) want = 2u << 20;
        ssize_t n = read(fd, g_standby.map + got, want);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        got += (size_t)n;
    }
    dmabuf_sync(g_standby.fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE);
    close(fd);
    if (got != STANDBY_BYTES) {
        g_standby.fails++;
        LOGW("standby: #%d not loaded: short read %zu/%zu B from %s", seq, got, STANDBY_BYTES, path);
        return false;
    }
    g_standby.loaded_seq = seq; g_standby.valid = true; g_standby.load_ms = now_ns() / 1000000;
    LOGI("standby: loaded #%d from %s (%zu B) in %" PRIu64 " ms", seq, path, got, (now_ns() - t0) / 1000000);
    clock_load_locked(seq);
    return true;
}

// The counter changed: load the new frame, or drop it at 0. False = try again at the next poll.
static bool standby_poll(int seq) {
    pthread_mutex_lock(&g_standby_lock);
    bool done = true;
    if (seq <= 0) {
        if (g_standby.valid) LOGI("standby: off (frame #%d dropped)", g_standby.loaded_seq);
        g_standby.valid = false; g_standby.loaded_seq = 0;
    } else if (g_standby.commit_ns && now_ns() - g_standby.commit_ns < 1500ull * 1000000) {
        done = false;   // the kernel may still read the buffer of the last standby commit
    } else {
        standby_load_locked(seq);   // a failure is final for this value: the app bumps the counter at its next publish
    }
    pthread_mutex_unlock(&g_standby_lock);
    return done;
}

// Commit the loaded frame as one GC16 of the whole panel: at setPowerMode(OFF), and for standby_test while on.
static bool standby_commit(const char* why) {
    int seq = prop_int("vendor.debug.hwc.shim.standby", 0);
    if (seq <= 0) return false;
    char sd[PROP_VALUE_MAX] = {0};
    if (__system_property_get("sys.shutdown.requested", sd) > 0 && sd[0]) {
        LOGI("standby (%s): shutdown/reboot in progress (%s): the sleep picture is not shown", why, sd);
        return false;
    }
    refresh_knobs();   // the preview budget
    uint64_t t0 = now_ns();
    pthread_mutex_lock(&g_standby_lock);
    if (!g_standby.valid || g_standby.loaded_seq != seq) standby_load_locked(seq);   // the poll has not caught up yet
    if (!g_standby.valid) {
        int fails = g_standby.fails, err = g_standby.last_errno;
        pthread_mutex_unlock(&g_standby_lock);
        static Limiter L = {0, 10, 50};
        if (lim(L)) LOGW("standby (%s): frame #%d requested but none loaded (%d failures, last errno %d)", why, seq, fails, err);
        return false;
    }
    if (g_standby.commit_ns && now_ns() - g_standby.commit_ns < 1500ull * 1000000) {
        // the kernel may still read the buffer of the last standby commit (see standby_poll): no clock rewrite now
    } else {
        clock_draw_locked(why);
    }
    drm_eink_update u;
    memset(&u, 0, sizeof(u));
    pthread_mutex_lock(&g_commit_lock);
    uint64_t t1 = now_ns();
    pthread_mutex_lock(&g_lock); uint32_t last_dither = g_last_update.dither; pthread_mutex_unlock(&g_lock);
    int dither = prop_int("vendor.debug.hwc.shim.standby_dither", -1);
    u.mode = 4; u.left = 0; u.top = 0; u.right = STANDBY_W - 1; u.bottom = STANDBY_H - 1;
    u.dither = dither >= 0 ? (uint32_t)dither : last_dither;
    u.fd = g_standby.fd; u.format = 2; u.stride = STANDBY_W; u.height = STANDBY_H;
    if (take_preview()) preview_rgba_lum("standby frame", g_standby.map, STANDBY_BYTES, STANDBY_W * 4, STANDBY_W, STANDBY_H);
    uint64_t t2 = now_ns();
    int ret = real_mtk_commit ? real_mtk_commit(&u) : -1;
    int err = ret != 0 ? errno : 0;
    uint64_t t3 = now_ns();
    pthread_mutex_lock(&g_lock); g_last_update = u; g_last_update_seq++; pthread_mutex_unlock(&g_lock);
    __atomic_store_n(&g_last_commit_ms, t3 / 1000000, __ATOMIC_RELEASE);
    __atomic_store_n(&g_turns, 0, __ATOMIC_RELAXED);
    pthread_mutex_unlock(&g_commit_lock);
    g_standby.commit_ns = t3; g_standby.commits++; g_standby.last_ret = ret; g_standby.last_errno = err;
    pthread_mutex_unlock(&g_standby_lock);
    LOGI("standby (%s): GC16 committed #%d fd=%d dither=%u ret=%d%s lock-wait %" PRIu64 " us, ioctl %" PRIu64 " us",
         why, seq, u.fd, u.dither, ret, ret ? " FAILED" : "", (t1 - t0) / 1000, (t3 - t2) / 1000);
    return ret == 0;
}

static void* gc16_thread(void*) {
    int seen = prop_int("vendor.debug.hwc.shim.gc16_req", 0);   // a value left from before a restart is not a request
    uint64_t req_ms = 0;                                         // 0 = nothing pending
    int standby_seen = 0;                                        // a value present at start IS a request (the frame outlives a composer restart)
    int test_seen = prop_int("vendor.debug.hwc.shim.standby_test", 0);
    for (;;) {
        usleep(50 * 1000);
        uint64_t now = now_ns() / 1000000;
        // sleep picture: load a newly published frame (or drop it at 0); standby_test commits it while the display is on
        int sb = prop_int("vendor.debug.hwc.shim.standby", 0);
        if (sb != standby_seen && standby_poll(sb)) standby_seen = sb;
        int st = prop_int("vendor.debug.hwc.shim.standby_test", 0);
        if (st != test_seen) {
            test_seen = st;
            if (st > 0) {
                if (__atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE)) LOGI("standby (test): display off, skipped");
                else standby_commit("test");
            }
        }
        // scrolling (see g_scroll_mode): clean the end of a scroll once, unless the vendor's own GC16 did
        int sc = prop_int("vendor.xrz.is_scrolling", 0) == 1;
        int was = __atomic_exchange_n(&g_scrolling, sc, __ATOMIC_ACQ_REL);
        uint64_t vms = __atomic_load_n(&g_video_ms, __ATOMIC_ACQUIRE);
        bool vid = vms && now - vms < 1000;                     // a video played in the last second
        static bool s_vid, s_sc;                                // s_sc: the vendor's is_scrolling was part of it
        bool vid_was = s_vid;
        s_vid = vid;
        if (sc || vid) {
            __atomic_store_n(&g_scroll_pending, 0, __ATOMIC_RELEASE);   // a new drag: its own end counts
            if (sc) s_sc = true;
        } else if (was || vid_was) {
            uint32_t base = (uint32_t)prop_int("vendor.xrz.global_refresh_mode", 0) & 0xffff;
            bool fast = __atomic_exchange_n(&g_scroll_fast, 0, __ATOMIC_ACQ_REL);   // a drag at the end of a list: nothing
            bool quality = (base == 177 || base == 180) && fast;
            int kind = !fast ? 0 : quality ? (g_scroll_clean == 1 ? 4 : g_scroll_clean == 2 ? (int)base : 0)
                               : (g_scroll_mode == 179 ? 178 : 0);   // an Extreme scroll in a Fast app: Fast again
            __atomic_store_n(&g_scroll_cleaned, 0, __ATOMIC_RELEASE);
            __atomic_store_n(&g_scroll_t1_ms, 0, __ATOMIC_RELEASE);
            __atomic_store_n(&g_scroll_end_ms, now, __ATOMIC_RELEASE);
            __atomic_store_n(&g_scroll_vendor, quality && s_sc ? 1 : 0, __ATOMIC_RELEASE);   // the vendor cleans only scrolls
            s_sc = false;
            __atomic_store_n(&g_scroll_pending, kind, __ATOMIC_RELEASE);
        }
        int sp = __atomic_load_n(&g_scroll_pending, __ATOMIC_ACQUIRE);
        if (sp && !__atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE) && !__atomic_load_n(&g_ink_session, __ATOMIC_ACQUIRE)) {
            uint64_t quiet = (uint64_t)prop_int("vendor.debug.hwc.shim.gc16_quiet_ms", 350);
            uint64_t last = __atomic_load_n(&g_last_commit_ms, __ATOMIC_ACQUIRE);
            if (now - __atomic_load_n(&g_scroll_end_ms, __ATOMIC_ACQUIRE) >= 700 && now - last >= quiet
                && __atomic_exchange_n(&g_scroll_pending, 0, __ATOMIC_ACQ_REL)) {
                uint32_t base = (uint32_t)prop_int("vendor.xrz.global_refresh_mode", 0) & 0xffff;
                recommit("scroll end", base == 177 || base == 180 ? base : 178, (uint32_t)sp);
                __atomic_store_n(&g_scroll_cleaned, 1, __ATOMIC_RELEASE);
            }
        }
        if (__atomic_load_n(&g_ime_rest, __ATOMIC_ACQUIRE) && !__atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE)
            && !__atomic_load_n(&g_ink_session, __ATOMIC_ACQUIRE)) {
            uint64_t last = __atomic_load_n(&g_last_commit_ms, __ATOMIC_ACQUIRE);
            if (now - last >= (uint64_t)g_ime_rest_ms && __atomic_exchange_n(&g_ime_rest, 0, __ATOMIC_ACQ_REL)) {
                pthread_mutex_lock(&g_ime_lock);
                hwc_rect_t rr = g_ime_rest_rect;
                uint32_t rm = g_ime_rest_mode ? g_ime_rest_mode : 178;
                int rd = g_ime_rest_dither;
                pthread_mutex_unlock(&g_ime_lock);
                recommit("after the swipe", rm, rm, &rr, rd);
            }
        }
        if (g_ime_clean_ms > 0 && __atomic_load_n(&g_ime_clean, __ATOMIC_ACQUIRE)
            && !__atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE) && !__atomic_load_n(&g_ink_session, __ATOMIC_ACQUIRE)) {
            pthread_mutex_lock(&g_ime_lock);
            uint64_t il = g_ime_last_ms;
            hwc_rect_t ir = g_ime_clean_rect;
            pthread_mutex_unlock(&g_ime_lock);
            uint64_t quiet = (uint64_t)prop_int("vendor.debug.hwc.shim.gc16_quiet_ms", 350);
            uint64_t last = __atomic_load_n(&g_last_commit_ms, __ATOMIC_ACQUIRE);
            if (now - il >= (uint64_t)g_ime_clean_ms && now - last >= quiet
                && __atomic_exchange_n(&g_ime_clean, 0, __ATOMIC_ACQ_REL)) {
                uint32_t base = (uint32_t)prop_int("vendor.xrz.global_refresh_mode", 0) & 0xffff;
                recommit("keyboard pause", base == 177 || base == 180 ? base : 178, 4, &ir);
            }
        }
        int r = prop_int("vendor.debug.hwc.shim.gc16_req", 0);
        if (r != seen) { seen = r; if (!req_ms) req_ms = now; }
        if (__atomic_exchange_n(&g_gc16_internal, 0, __ATOMIC_ACQ_REL) && !req_ms) req_ms = now;
        if (!req_ms) continue;
        if (__atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE)) { req_ms = now; continue; }   // asleep: after the wake, once settled
        uint64_t quiet = (uint64_t)prop_int("vendor.debug.hwc.shim.gc16_quiet_ms", 350);
        uint64_t last = __atomic_load_n(&g_last_commit_ms, __ATOMIC_ACQUIRE);
        bool settled = now - req_ms >= quiet && now - last >= quiet;
        if (__atomic_load_n(&g_ink_session, __ATOMIC_ACQUIRE)) continue;   // pen session: its settle commit first
        if (!settled && now - req_ms < 2500) continue;
        req_ms = 0;
        recommit_gc16(settled ? "settled" : "timeout");
    }
    return nullptr;
}

static void start_gc16_thread() {
    static bool started;
    if (started) return;
    pthread_t th;
    started = pthread_create(&th, nullptr, gc16_thread, nullptr) == 0;
    if (started) pthread_detach(th);
    LOGI("settled full refresh helper %s", started ? "running" : "NOT started");
}

// ---------------------------------------------------------------- pen ink layer
// "Pen" refresh mode (owner 2026-10-20; stock parity with handwrittenservice and the Launcher's ink layer over WPS
// & co.). AOSP draws a stroke through app -> SurfaceFlinger -> vendor HWC -> full-screen commit, which on this
// panel is far too slow to write with (Keep: 13 commits in a 9.6 s stroke, research/phase-d/pen/). Like stock, ink
// reaches the panel on its own fast path instead:
//   - einkd (root) forwards every Wacom report over the abstract seqpacket socket "b1051c_ink" while
//     vendor.debug.hwc.shim.ink is 1 (E-Ink Control sets it while the app in front uses the Pen mode);
//   - pen in range: copy the picture on the panel into our own RGBA ION canvas, keep a copy of SF's raw client
//     target (what the settle compares against) and warm the kernel's handwriting path up with an 8x8 commit of
//     unchanged pixels (the first 1029 update takes ~110 ms, later ones ~13 ms);
//   - pen down: draw black, pressure-width segments into the canvas and commit only their box in the stock
//     handwriting mode 1029 (DU waveform, no colour conversion); what arrives during one ioctl goes into the next;
//     the vendor's commits are held meanwhile (see g_ink_session);
//   - while writing (owner 2026-10-20: "can it draw with the app's own pen?"), every ink_real_every_ms the stroke
//     parts the app has had ink_real_lag_ms to draw, and that lie at least ink_real_gap_px from the pen, are taken
//     from SF's latest client target (colour pipeline) and committed as one box in Fast (0xb2, dither 3): the app's
//     own colour, width and brush replace our black preview behind the pen. Far from the pen, so that box never
//     collides in the kernel with the ink being written (owner: "slower than before"; ~500 collisions seen). Newer
//     preview inside the box is drawn again on top; the app's stroke width measured there becomes the preview width;
//   - pen lifted for ink_idle_ms: settle only what changed, not the whole screen (a full-screen settle took 270-450
//     ms and kept the panel busy under the next word): 32 px tiles where SF's raw client target differs from the
//     copy taken when the pen came in, plus every tile our commits touched, as up to 8 boxes, each through the
//     colour pipeline and committed in the held vendor mode. A new pen contact interrupts the settle: the pen first,
//     the remaining boxes are worked out again at the next pause.
// Knobs (vendor.debug.hwc.shim.*): ink 0/1, ink_mode (1029), ink_idle_ms (2000), ink_wmin / ink_wmax (stroke width
// in px at no / full pressure: 2 / 5), ink_map (pen -> framebuffer: bit 0 mirror x, bit 1 mirror y, bit 2 swap),
// ink_start_px (4: movement before a contact draws, so taps on buttons leave no dot), ink_thread (0 = off),
// ink_real (1; 0 = preview only until the pen rests), ink_real_lag_ms (300), ink_real_every_ms (100),
// ink_real_gap_px (150), ink_real_mode (178) + ink_real_dither (3), ink_auto_w (1 = preview width measured from the
// app's stroke).
struct InkSample {                  // = work/einkd/einkd.c; one per Wacom SYN_REPORT
    uint32_t ver;                   // 1
    uint32_t flags;                 // INK_*
    int32_t x, y, p;                // raw ABS_X (0..2480), ABS_Y (0..1860), ABS_PRESSURE (0..4095)
    uint32_t rsvd;
};
enum { INK_TOUCH = 1, INK_RANGE = 2, INK_RUBBER = 4, INK_BUTTON = 8 };
#define INK_W 2480
#define INK_H 1860
#define INK_PMAX 4095
#define INK_TILE 32
#define INK_TW ((INK_W + INK_TILE - 1) / INK_TILE)
#define INK_TH ((INK_H + INK_TILE - 1) / INK_TILE)
#define INK_MAX_BOXES 8

struct InkBox { int l, t, r, b; };   // inclusive; empty while r < l
static void box_clear(InkBox& b) { b = {INK_W, INK_H, -1, -1}; }
static void box_add(InkBox& b, int l, int t, int r, int bt) {
    if (l < b.l) b.l = l; if (t < b.t) b.t = t; if (r > b.r) b.r = r; if (bt > b.b) b.b = bt;
}
static float box_dist2(const InkBox& b, float x, float y) {   // squared distance from (x, y) to the box (0 inside)
    float dx = x < b.l ? b.l - x : (x > b.r ? x - b.r : 0.0f), dy = y < b.t ? b.t - y : (y > b.b ? y - b.b : 0.0f);
    return dx * dx + dy * dy;
}

// The kernel needs a dma-buf; this kernel's ION only takes the legacy handle API (alloc + share), see work/inktest.
static int dmabuf_alloc(size_t len, uint32_t flags) {
    int ion = open("/dev/ion", O_RDONLY | O_CLOEXEC);
    if (ion < 0) { LOGE("pen ink layer: open /dev/ion: %s", strerror(errno)); return -1; }
    struct { size_t len, align; uint32_t heap_id_mask, flags; int32_t handle; } a = {len, 4096, 1u << 0, flags, 0};   // ion_system_heap
    struct { int32_t handle; int32_t fd; } share = {0, -1};
    int fd = -1;
    if (ioctl(ion, _IOWR('I', 0, a), &a) == 0) {
        share.handle = a.handle;
        if (ioctl(ion, _IOWR('I', 4, share), &share) == 0) fd = share.fd;
        struct { int32_t handle; } fr = {a.handle};
        ioctl(ion, _IOWR('I', 1, fr), &fr);   // the dma-buf fd keeps the buffer
    }
    if (fd < 0) LOGE("pen ink layer: ION alloc of %zu B failed: %s", len, strerror(errno));
    close(ion);
    return fd;
}
static int ink_alloc(size_t len) { return dmabuf_alloc(len, 0); }   // uncached, as the pen canvas always was

static int ink_commit_u(drm_eink_update& u, uint64_t* us) {
    if (__atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE)) { *us = 0; return -1; }   // the panel sleeps (sleep picture)
    pthread_mutex_lock(&g_commit_lock);
    uint64_t t0 = now_ns();
    int ret = real_mtk_commit ? real_mtk_commit(&u) : -1;
    *us = (now_ns() - t0) / 1000;
    pthread_mutex_unlock(&g_commit_lock);
    return ret;
}

static int ink_commit(int fd, uint32_t mode, uint32_t dither, const InkBox& b, uint64_t* us) {
    drm_eink_update u;
    memset(&u, 0, sizeof(u));
    u.mode = mode; u.dither = dither; u.left = b.l; u.top = b.t; u.right = b.r; u.bottom = b.b;
    u.fd = fd; u.format = 2; u.stride = INK_W; u.height = INK_H;
    return ink_commit_u(u, us);
}

static int ink_ct_fd() {   // SF's newest complete client target (raw, before the colour pipeline); caller holds g_lock
    return ct_pick_locked();
}

// Copy a whole RGBA dma-buf of our geometry into memory (the canvas or the raw copy).
static bool ink_copy_buf(int src_fd, uint8_t* dst, int dst_fd) {
    size_t len = (size_t)INK_W * INK_H * 4;
    if (src_fd < 0 || lseek(src_fd, 0, SEEK_END) < (off_t)len) return false;
    void* m = mmap(nullptr, len, PROT_READ, MAP_SHARED, src_fd, 0);
    if (m == MAP_FAILED) return false;
    dmabuf_sync(src_fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ);
    if (dst_fd >= 0) dmabuf_sync(dst_fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE);
    memcpy(dst, m, len);
    if (dst_fd >= 0) dmabuf_sync(dst_fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE);
    dmabuf_sync(src_fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);
    munmap(m, len);
    return true;
}

// Round-capped black segment of radius r.
static void ink_segment(uint8_t* px, float x0, float y0, float x1, float y1, float r, InkBox& dirty) {
    int l = clampi((int)floorf(fminf(x0, x1) - r), 0, INK_W - 1), rr = clampi((int)ceilf(fmaxf(x0, x1) + r), 0, INK_W - 1);
    int t = clampi((int)floorf(fminf(y0, y1) - r), 0, INK_H - 1), b = clampi((int)ceilf(fmaxf(y0, y1) + r), 0, INK_H - 1);
    float dx = x1 - x0, dy = y1 - y0, l2 = dx * dx + dy * dy, r2 = r * r;
    for (int y = t; y <= b; y++) {
        uint8_t* row = px + (size_t)y * INK_W * 4;
        for (int x = l; x <= rr; x++) {
            float fx = x + 0.5f - x0, fy = y + 0.5f - y0;
            float k = l2 > 0 ? clampf01((fx * dx + fy * dy) / l2) : 0.0f;
            float ex = fx - k * dx, ey = fy - k * dy;
            if (ex * ex + ey * ey <= r2) { uint8_t* q = row + x * 4; q[0] = q[1] = q[2] = 0; q[3] = 0xff; }
        }
    }
    box_add(dirty, l, t, rr, b);
}

struct InkState {
    int fd = -1;                    // canvas dma-buf: what the panel shows (+ our preview)
    uint8_t* px = nullptr;
    uint8_t* raw = nullptr;         // SF's raw client target when the pen came in (the settle diffs against it)
    bool raw_ok = false;
    uint64_t seq = 0;               // g_last_update_seq the canvas was copied at (0 = stale)
    bool session = false, in_range = false, down = false, drawing = false;
    float down_x = 0, down_y = 0, last_x = 0, last_y = 0, pen_x = -1e6f, pen_y = -1e6f;
    uint64_t up_ms = 0, warm_ms = 0, start_ns = 0;
    uint64_t sample_ms = 0;          // last report from einkd (pen-up watchdog)
    uint64_t begin_fail_ms = 0;      // ink_begin found no picture: no new try for a second
    drm_eink_update pre;            // last commit before the session (settle-mode fallback)
    InkBox dirty;
    int commits = 0; uint64_t commit_us = 0, commit_max_us = 0;
    // the session's preview segments, for handing each part over to the app's own stroke (ink_realize)
    struct Seg { float x0, y0, x1, y1, r; uint64_t ms; uint8_t state; };   // 0 preview, 1 being handed over, 2 app's
    static const int kSegs = 8192;
    Seg segs[kSegs];
    int nseg = 0;
    uint64_t real_ms = 0;
    int reals = 0;
    float meas[9]; int nmeas = 0, imeas = 0;   // widths of the app's stroke (px), newest last; median -> preview
    uint8_t touched[INK_TH][INK_TW];           // tiles our commits changed in this session
    // settle, one box per step so that the pen can interrupt it
    bool settling = false;
    InkBox sreg[INK_MAX_BOXES];
    int nsreg = 0, sidx = 0, sct = -1, sheld = 0, spx = 0, sbreaks = 0;
    drm_eink_update su;
    uint64_t s0_ns = 0;
    const char* swhy = "";
};

struct InkKnobs { int enable, mode, idle_ms, wmin, wmax, map, start_px, real, real_lag_ms, real_every_ms, real_gap_px,
                  real_mode, real_dither, auto_w; };
static InkKnobs ink_knobs() {
    InkKnobs k;
    k.enable = prop_int("vendor.debug.hwc.shim.ink", 0);
    k.mode = prop_int("vendor.debug.hwc.shim.ink_mode", 1029);
    k.idle_ms = prop_int("vendor.debug.hwc.shim.ink_idle_ms", 2000);
    k.wmin = prop_int("vendor.debug.hwc.shim.ink_wmin", 2);
    k.wmax = prop_int("vendor.debug.hwc.shim.ink_wmax", 5);
    k.map = prop_int("vendor.debug.hwc.shim.ink_map", 0);
    k.start_px = prop_int("vendor.debug.hwc.shim.ink_start_px", 4);
    k.real = prop_int("vendor.debug.hwc.shim.ink_real", 1);
    k.real_lag_ms = prop_int("vendor.debug.hwc.shim.ink_real_lag_ms", 300);
    k.real_every_ms = prop_int("vendor.debug.hwc.shim.ink_real_every_ms", 100);
    k.real_gap_px = prop_int("vendor.debug.hwc.shim.ink_real_gap_px", 150);
    k.real_mode = prop_int("vendor.debug.hwc.shim.ink_real_mode", 178);
    k.real_dither = prop_int("vendor.debug.hwc.shim.ink_real_dither", 3);
    k.auto_w = prop_int("vendor.debug.hwc.shim.ink_auto_w", 1);
    return k;
}

static void ink_touch_tiles(InkState& st, const InkBox& b) {
    for (int ty = b.t / INK_TILE; ty <= b.b / INK_TILE && ty < INK_TH; ty++)
        for (int tx = b.l / INK_TILE; tx <= b.r / INK_TILE && tx < INK_TW; tx++) st.touched[ty][tx] = 1;
}

// Canvas = the panel's picture (last commit buffer), raw = SF's latest client target; both only when stale.
static bool ink_prepare(InkState& st) {
    pthread_mutex_lock(&g_lock); drm_eink_update u = g_last_update; uint64_t seq = g_last_update_seq; int ct = ink_ct_fd(); pthread_mutex_unlock(&g_lock);
    if (st.seq != seq) {
        if (seq == 0 || u.format == 0 || u.stride != INK_W || u.height != INK_H || !ink_copy_buf(u.fd, st.px, st.fd)) return false;
        st.seq = seq;
        st.raw_ok = false;
    }
    if (!st.raw_ok && st.raw) st.raw_ok = ink_copy_buf(ct, st.raw, -1);
    return true;
}

// Vendor commits the session held and nothing showed (review 2026-10-21: a frame drawn while the settle ran, or
// while a failed begin waited, was lost until the next redraw): the last one goes out now through the normal path,
// which takes the newest client target. Called with the session already over.
static void ink_replay_held(const char* why) {
    pthread_mutex_lock(&g_lock); int held = g_ink_held; drm_eink_update u = g_ink_held_u; g_ink_held = 0; pthread_mutex_unlock(&g_lock);
    if (!held) return;
    int ret = hook_mtk_commit(&u);
    LOGI("pen session %s: %d vendor commit(s) held meanwhile, the newest frame sent now (mode 0x%x) ret=%d", why, held, u.mode, ret);
}

// Hold the vendor's commits; wait (<= 100 ms) for one already past the hold check, then make sure the canvas
// shows what the panel shows.
static bool ink_begin(InkState& st) {
    __atomic_store_n(&g_ink_session, 1, __ATOMIC_RELEASE);
    for (int i = 0; i < 50 && __atomic_load_n(&g_ink_inflight, __ATOMIC_ACQUIRE) > 0; i++) usleep(2000);
    pthread_mutex_lock(&g_lock); st.pre = g_last_update; g_ink_held = 0; pthread_mutex_unlock(&g_lock);
    if (!ink_prepare(st)) {
        __atomic_store_n(&g_ink_session, 0, __ATOMIC_RELEASE);
        st.begin_fail_ms = now_ns() / 1000000;
        ink_replay_held("not started");
        static Limiter L = {0, 5, 100};
        if (lim(L)) LOGW("pen ink layer: no usable picture of the panel to draw on (last commit fd %d format %u) -> stroke left to the app",
             st.pre.fd, st.pre.format);
        return false;
    }
    st.session = true;
    st.start_ns = now_ns();
    st.commits = 0; st.commit_us = st.commit_max_us = 0;
    st.nseg = 0; st.reals = 0; st.sheld = 0; st.sbreaks = 0;
    st.real_ms = st.start_ns / 1000000;
    st.su.stride = 0;   // no settle mode yet
    memset(st.touched, 0, sizeof(st.touched));
    return true;
}

// Median of the app-stroke widths measured so far (0 = none yet).
static float ink_measured_w(const InkState& st) {
    if (st.nmeas == 0) return 0.0f;
    float v[9];
    int n = st.nmeas;
    memcpy(v, st.meas, sizeof(float) * n);
    for (int i = 1; i < n; i++) for (int j = i; j > 0 && v[j - 1] > v[j]; j--) { float t = v[j]; v[j] = v[j - 1]; v[j - 1] = t; }
    return v[n / 2];
}

// Settle, step 1: what changed (see the header comment) as up to INK_MAX_BOXES boxes.
static void ink_settle_start(InkState& st, const char* why) {
    pthread_mutex_lock(&g_lock);
    int held = g_ink_held; g_ink_held = 0;
    drm_eink_update u = held ? g_ink_held_u : (st.su.stride ? st.su : st.pre);
    int ct = ink_ct_fd();
    pthread_mutex_unlock(&g_lock);
    if ((u.mode & 0xffff) == 4 || (u.mode & 0xff) == 5 || u.stride != INK_W || u.height != INK_H) {   // GC16 / ours / none
        memset(&u, 0, sizeof(u));
        u.mode = 178; u.dither = 3; u.format = 2; u.stride = INK_W; u.height = INK_H;
    }
    st.su = u; st.sheld += held; st.sct = ct; st.swhy = why; st.s0_ns = now_ns();
    static uint8_t mark[INK_TH][INK_TW], grow[INK_TH][INK_TW];
    memcpy(mark, st.touched, sizeof(mark));
    bool diffed = false;
    size_t len = (size_t)INK_W * INK_H * 4;
    if (st.raw_ok && ct >= 0 && lseek(ct, 0, SEEK_END) >= (off_t)len) {
        void* m = mmap(nullptr, len, PROT_READ, MAP_SHARED, ct, 0);
        if (m != MAP_FAILED) {
            dmabuf_sync(ct, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ);
            const uint8_t* now = (const uint8_t*)m;
            for (int y = 0; y < INK_H; y++) {
                uint8_t* trow = mark[y / INK_TILE];
                size_t o = (size_t)y * INK_W * 4;
                for (int tx = 0; tx < INK_TW; tx++) {
                    if (trow[tx]) continue;
                    size_t a = o + (size_t)tx * INK_TILE * 4, n = (size_t)(tx == INK_TW - 1 ? INK_W - tx * INK_TILE : INK_TILE) * 4;
                    if (memcmp(now + a, st.raw + a, n) != 0) trow[tx] = 1;
                }
            }
            dmabuf_sync(ct, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);
            munmap(m, len);
            diffed = true;
        }
    }
    if (!diffed) memset(mark, 1, sizeof(mark));   // nothing to compare with: the whole screen
    // boxes = groups of marked tiles at most one tile apart (flood fill over the grid grown by one tile)
    memset(grow, 0, sizeof(grow));
    for (int ty = 0; ty < INK_TH; ty++) for (int tx = 0; tx < INK_TW; tx++) if (mark[ty][tx])
        for (int yy = ty - 1; yy <= ty + 1; yy++) for (int xx = tx - 1; xx <= tx + 1; xx++)
            if (yy >= 0 && yy < INK_TH && xx >= 0 && xx < INK_TW) grow[yy][xx] = 1;
    static uint16_t stack[INK_TH * INK_TW];
    st.nsreg = 0; st.sidx = 0; st.spx = 0;
    for (int ty = 0; ty < INK_TH; ty++) for (int tx = 0; tx < INK_TW; tx++) {
        if (grow[ty][tx] != 1) continue;
        InkBox t; box_clear(t);
        int sp = 0;
        stack[sp++] = (uint16_t)(ty * INK_TW + tx); grow[ty][tx] = 2;
        while (sp) {
            int c = stack[--sp], cy = c / INK_TW, cx = c % INK_TW;
            if (mark[cy][cx]) box_add(t, cx, cy, cx, cy);
            for (int yy = cy - 1; yy <= cy + 1; yy++) for (int xx = cx - 1; xx <= cx + 1; xx++)
                if (yy >= 0 && yy < INK_TH && xx >= 0 && xx < INK_TW && grow[yy][xx] == 1) { grow[yy][xx] = 2; stack[sp++] = (uint16_t)(yy * INK_TW + xx); }
        }
        if (t.r < t.l) continue;
        InkBox px = {t.l * INK_TILE, t.t * INK_TILE, clampi((t.r + 1) * INK_TILE - 1, 0, INK_W - 1), clampi((t.b + 1) * INK_TILE - 1, 0, INK_H - 1)};
        if (st.nsreg < INK_MAX_BOXES) st.sreg[st.nsreg++] = px;
        else box_add(st.sreg[INK_MAX_BOXES - 1], px.l, px.t, px.r, px.b);   // too many groups: merge the rest
    }
    st.settling = true;
}

// Settle, one step: the next box; after the last one the vendor's commits flow again.
static void ink_settle_step(InkState& st) {
    if (st.sidx < st.nsreg) {
        InkBox b = st.sreg[st.sidx++];
        bool ok = st.sct >= 0 && (color_active() ? color_apply(st.sct, st.fd, INK_W, INK_H, b.l, b.t, b.r, b.b)
                                                 : [&]() {
                                                       size_t len = (size_t)INK_W * INK_H * 4;
                                                       if (lseek(st.sct, 0, SEEK_END) < (off_t)len) return false;
                                                       void* m = mmap(nullptr, len, PROT_READ, MAP_SHARED, st.sct, 0);
                                                       if (m == MAP_FAILED) return false;
                                                       dmabuf_sync(st.sct, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ);
                                                       dmabuf_sync(st.fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE);
                                                       for (int y = b.t; y <= b.b; y++) {
                                                           size_t o = ((size_t)y * INK_W + b.l) * 4;
                                                           memcpy(st.px + o, (const uint8_t*)m + o, (size_t)(b.r - b.l + 1) * 4);
                                                       }
                                                       dmabuf_sync(st.fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE);
                                                       dmabuf_sync(st.sct, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);
                                                       munmap(m, len);
                                                       return true;
                                                   }());
        if (ok) {
            drm_eink_update u = st.su;
            u.left = b.l; u.top = b.t; u.right = b.r; u.bottom = b.b; u.fd = st.fd; u.format = 2;
            uint64_t us; ink_commit_u(u, &us);
            st.spx += (b.r - b.l + 1) * (b.b - b.t + 1);
        }
        if (st.sidx < st.nsreg) return;
    }
    // done: the canvas is now the panel's picture
    drm_eink_update u = st.su;
    u.left = 0; u.top = 0; u.right = INK_W - 1; u.bottom = INK_H - 1; u.fd = st.fd; u.format = 2;
    pthread_mutex_lock(&g_commit_lock);
    pthread_mutex_lock(&g_lock); g_last_update = u; st.seq = ++g_last_update_seq; pthread_mutex_unlock(&g_lock);
    __atomic_store_n(&g_last_commit_ms, now_ns() / 1000000, __ATOMIC_RELEASE);
    __atomic_store_n(&g_ink_session, 0, __ATOMIC_RELEASE);   // inside the lock: the next vendor commit goes after ours
    pthread_mutex_unlock(&g_commit_lock);
    ink_replay_held("end");                                    // a frame the app drew while the boxes went out
    uint64_t t1 = now_ns();
    LOGI("pen session %s: %d ink commits (avg %" PRIu64 " us, max %" PRIu64 " us) + %d app-stroke commits over %" PRIu64 " ms, "
         "%d vendor commits held, preview width %.1f px; settle %d boxes / %d px mode=0x%x dither=%u%s in %" PRIu64 " ms%s",
         st.swhy, st.commits, st.commits ? st.commit_us / st.commits : 0, st.commit_max_us, st.reals, (st.s0_ns - st.start_ns) / 1000000,
         st.sheld, ink_measured_w(st), st.nsreg, st.spx, st.su.mode, st.su.dither, color_active() ? " (colour)" : "",
         (t1 - st.s0_ns) / 1000000, st.sbreaks ? " (resumed after the pen)" : "");
    st.session = st.settling = st.down = st.drawing = false;
    st.nseg = 0;
    st.raw_ok = false;   // the next pen-in copies SF's picture again
}

static void ink_settle_abort(InkState& st) {
    if (!st.settling) return;
    st.settling = false;
    st.sbreaks++;
    LOGV("pen settle interrupted after %d/%d boxes: the pen comes first", st.sidx, st.nsreg);
}

static void ink_sample(InkState& st, const InkKnobs& k, const InkSample& s) {
    if (__atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE)) return;   // a pen on the sleeping panel starts nothing
    float x = (float)s.x, y = (float)s.y;
    if (k.map & 4) { float t = x; x = y * INK_W / INK_H; y = t * INK_H / INK_W; }
    if (k.map & 1) x = INK_W - 1 - x;
    if (k.map & 2) y = INK_H - 1 - y;
    x = fminf(fmaxf(x, 0.0f), INK_W - 1.0f); y = fminf(fmaxf(y, 0.0f), INK_H - 1.0f);
    bool rubber = s.flags & INK_RUBBER, range = s.flags & INK_RANGE;
    bool touch = (s.flags & INK_TOUCH) && s.p > 0 && !rubber;
    uint64_t ms = now_ns() / 1000000;
    if (range || touch) { st.pen_x = x; st.pen_y = y; }
    if (rubber && st.session && !st.settling) { ink_settle_start(st, "eraser"); return; }   // erasing is the app's job
    if (range && !st.in_range && !st.session) {
        // pen coming in: have the panel's picture ready and the handwriting path warm before the first contact
        if (ink_prepare(st) && ms - st.warm_ms > 2000) {
            InkBox w = {(int)x & ~7, (int)y & ~7, ((int)x & ~7) + 7, ((int)y & ~7) + 7};
            uint64_t us; int ret = ink_commit(st.fd, (uint32_t)k.mode, 0, w, &us);
            st.warm_ms = ms;
            LOGV("pen in range: warm-up commit ret=%d %" PRIu64 " us", ret, us);
        }
    }
    st.in_range = range;
    if (touch) {
        ink_settle_abort(st);
        if (!st.down) {
            if (!st.session && (ms - st.begin_fail_ms < 1000 || !ink_begin(st))) return;
            st.down = true; st.drawing = false;
            st.down_x = st.last_x = x; st.down_y = st.last_y = y;
            return;
        }
        float r = (k.wmin + (k.wmax - k.wmin) * (float)clampi(s.p, 0, INK_PMAX) / INK_PMAX) * 0.5f;   // a bogus p never paints the screen
        float mw = k.auto_w ? ink_measured_w(st) : 0.0f;
        if (mw > 0) r = mw * 0.5f;
        if (!st.drawing) {
            float dx = x - st.down_x, dy = y - st.down_y;
            if (dx * dx + dy * dy < (float)(k.start_px * k.start_px)) return;
            st.drawing = true;
            st.last_x = st.down_x; st.last_y = st.down_y;
        }
        if (st.dirty.r < st.dirty.l) dmabuf_sync(st.fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE);
        if (r < 0.5f) r = 0.5f;
        ink_segment(st.px, st.last_x, st.last_y, x, y, r, st.dirty);
        if (st.nseg < InkState::kSegs) st.segs[st.nseg++] = {st.last_x, st.last_y, x, y, r, ms, 0};
        st.last_x = x; st.last_y = y;
    } else if (st.down) {
        st.down = st.drawing = false;
        st.up_ms = ms;
    }
}

static void ink_flush(InkState& st, const InkKnobs& k) {
    if (st.dirty.r < st.dirty.l) return;
    dmabuf_sync(st.fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE);
    InkBox b = {clampi((st.dirty.l - 1) & ~7, 0, INK_W - 1), clampi((st.dirty.t - 1) & ~7, 0, INK_H - 1),
                clampi(((st.dirty.r + 1) | 7), 0, INK_W - 1), clampi(((st.dirty.b + 1) | 7), 0, INK_H - 1)};
    box_clear(st.dirty);
    ink_touch_tiles(st, b);
    uint64_t us;
    int ret = ink_commit(st.fd, (uint32_t)k.mode, 0, b, &us);
    st.commits++; st.commit_us += us; if (us > st.commit_max_us) st.commit_max_us = us;
    static Limiter L = {0, 10, 200};
    if (ret != 0 || lim(L)) LOGI("ink commit mode=0x%x area=[%d,%d,%d,%d] ret=%d %" PRIu64 " us", k.mode, b.l, b.t, b.r, b.b, ret, us);
}

static int ink_luma(const uint8_t* q) { return (q[0] * 77 + q[1] * 150 + q[2] * 29) >> 8; }

// Width of the app's stroke across the middle of segment g (canvas now holds the app's pixels there): walk both
// ways along the normal while the luma stays within 48 of the centre. 0 = no stroke there (or not measurable).
static float ink_measure(const InkState& st, const InkState::Seg& g) {
    float dx = g.x1 - g.x0, dy = g.y1 - g.y0, len = sqrtf(dx * dx + dy * dy);
    if (len < 3.0f) return 0.0f;
    float nx = -dy / len, ny = dx / len, cx = (g.x0 + g.x1) * 0.5f, cy = (g.y0 + g.y1) * 0.5f;
    auto at = [&](float d) -> const uint8_t* {
        int x = (int)(cx + nx * d), y = (int)(cy + ny * d);
        return (x < 0 || y < 0 || x >= INK_W || y >= INK_H) ? nullptr : st.px + ((size_t)y * INK_W + x) * 4;
    };
    const uint8_t* c = at(0);
    if (!c) return 0.0f;
    int lc = ink_luma(c), side[2] = {0, 0};
    for (int k = 0; k < 2; k++) {
        float sgn = k ? -1.0f : 1.0f;
        int d = 1;
        for (; d <= 40; d++) { const uint8_t* q = at(sgn * d); if (!q || abs(ink_luma(q) - lc) > 48) break; }
        if (d > 40) return 0.0f;   // no edge: no stroke, or a fill
        side[k] = d;
    }
    const uint8_t* o1 = at(side[0] + 3.0f); const uint8_t* o2 = at(-(side[1] + 3.0f));
    if (!o1 || !o2 || abs(ink_luma(o1) - lc) <= 48 || abs(ink_luma(o2) - lc) <= 48) return 0.0f;   // ink on both sides
    return (float)(side[0] + side[1] - 1);
}

// Hand the stroke parts the app has drawn by now, away from the pen, over to the app's own pixels.
static void ink_realize(InkState& st, const InkKnobs& k) {
    pthread_mutex_lock(&g_lock);
    int ct = ink_ct_fd();
    uint64_t ct_ms = g_ct_ms;
    pthread_mutex_unlock(&g_lock);
    if (ct < 0 || ct_ms < (uint64_t)k.real_lag_ms) return;
    uint64_t cutoff = ct_ms - (uint64_t)k.real_lag_ms;
    float mw = ink_measured_w(st);
    int pad = 4 + (int)(mw > 0 ? mw : (float)k.wmax);   // the app's stroke may be wider than our preview
    float gap2 = (float)k.real_gap_px * k.real_gap_px;
    InkBox b; box_clear(b);
    int picked = 0;
    for (int i = 0; i < st.nseg; i++) {
        InkState::Seg& g = st.segs[i];
        if (g.state != 0 || g.ms > cutoff) continue;
        InkBox nb = b;
        box_add(nb, (int)fminf(g.x0, g.x1) - pad, (int)fminf(g.y0, g.y1) - pad, (int)fmaxf(g.x0, g.x1) + pad, (int)fmaxf(g.y0, g.y1) + pad);
        if (box_dist2(nb, st.pen_x, st.pen_y) < gap2) continue;   // would reach the pen: later, or at the settle
        b = nb; g.state = 1; picked++;
    }
    if (!picked) return;
    b = {clampi(b.l & ~7, 0, INK_W - 1), clampi(b.t & ~7, 0, INK_H - 1), clampi(b.r | 7, 0, INK_W - 1), clampi(b.b | 7, 0, INK_H - 1)};
    bool ok = color_active() ? color_apply(ct, st.fd, INK_W, INK_H, b.l, b.t, b.r, b.b) : false;
    if (!color_active()) {
        size_t len = (size_t)INK_W * INK_H * 4;
        void* m = lseek(ct, 0, SEEK_END) >= (off_t)len ? mmap(nullptr, len, PROT_READ, MAP_SHARED, ct, 0) : MAP_FAILED;
        if (m != MAP_FAILED) {
            dmabuf_sync(ct, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ);
            dmabuf_sync(st.fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE);
            for (int y = b.t; y <= b.b; y++) {
                size_t o = ((size_t)y * INK_W + b.l) * 4;
                memcpy(st.px + o, (const uint8_t*)m + o, (size_t)(b.r - b.l + 1) * 4);
            }
            dmabuf_sync(st.fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE);
            dmabuf_sync(ct, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);
            munmap(m, len);
            ok = true;
        }
    }
    if (!ok) { for (int i = 0; i < st.nseg; i++) if (st.segs[i].state == 1) st.segs[i].state = 0; return; }
    dmabuf_sync(st.fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ | DMA_BUF_SYNC_WRITE);
    int got = 0;
    for (int i = st.nseg - 1; i >= 0; i--) {
        InkState::Seg& g = st.segs[i];
        if (g.state != 1) continue;
        g.state = 2;
        if (k.auto_w && got < 3) {   // the app's width, on up to 3 of the newest segments just handed over
            float w = ink_measure(st, g);
            if (w >= 1.0f && w <= 40.0f) { st.meas[st.imeas] = w; st.imeas = (st.imeas + 1) % 9; if (st.nmeas < 9) st.nmeas++; got++; }
        }
    }
    InkBox junk; box_clear(junk);
    for (int i = 0; i < st.nseg; i++) {   // the preview the app has not caught up with yet stays on top
        const InkState::Seg& g = st.segs[i];
        if (g.state != 0) continue;
        if (fmaxf(g.x0, g.x1) + g.r < b.l || fminf(g.x0, g.x1) - g.r > b.r || fmaxf(g.y0, g.y1) + g.r < b.t || fminf(g.y0, g.y1) - g.r > b.b) continue;
        ink_segment(st.px, g.x0, g.y0, g.x1, g.y1, g.r, junk);
    }
    dmabuf_sync(st.fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ | DMA_BUF_SYNC_WRITE);
    ink_touch_tiles(st, b);
    uint64_t us;
    int ret = ink_commit(st.fd, (uint32_t)k.real_mode, (uint32_t)k.real_dither, b, &us);
    st.reals++;
    static Limiter L = {0, 10, 100};
    if (ret != 0 || lim(L)) LOGI("app-stroke commit mode=0x%x area=[%d,%d,%d,%d] %d segments, %.0f px from the pen, width %.1f px, ret=%d %" PRIu64 " us",
                                 k.real_mode, b.l, b.t, b.r, b.b, picked, sqrtf(box_dist2(b, st.pen_x, st.pen_y)), ink_measured_w(st), ret, us);
}

static InkState g_ink_state;   // ~250 KB of segments and tiles: not on the thread stack; ink thread only

static void* ink_thread(void*) {
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    static const char kName[] = "b1051c_ink";   // abstract: sun_path[0] = 0
    memcpy(a.sun_path + 1, kName, sizeof(kName) - 1);
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(kName) - 1);
    // Abstract names are first come, first served (review 2026-10-21): an app may hold it while the composer restarts.
    // Retry every 5 s instead of leaving the pen layer off until the next restart.
    int lfd = -1;
    for (int tries = 0; lfd < 0; tries++) {
        lfd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        if (lfd >= 0 && bind(lfd, (struct sockaddr*)&a, alen) == 0 && listen(lfd, 2) == 0) break;
        if (tries == 0 || tries % 60 == 0) LOGE("pen ink layer waiting: socket @%s: %s (taken? sepolicy?)", kName, strerror(errno));
        if (lfd >= 0) close(lfd);
        lfd = -1;
        sleep(5);
    }
    LOGI("pen ink layer: listening on @%s", kName);
    InkState& st = g_ink_state;
    box_clear(st.dirty);
    InkKnobs k = ink_knobs();
    uint64_t knobs_ms = 0;
    int cfd = -1;
    bool no_canvas = false;
    for (;;) {
        struct pollfd p[2] = {{lfd, POLLIN, 0}, {cfd, POLLIN, 0}};
        poll(p, cfd >= 0 ? 2 : 1, st.session ? 20 : 250);
        uint64_t ms = now_ns() / 1000000;
        if (ms - knobs_ms >= 250) { k = ink_knobs(); knobs_ms = ms; }
        if (no_canvas) k.enable = 0;
        if (p[0].revents & POLLIN) {
            int n = accept4(lfd, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
            struct ucred cr; socklen_t crl = sizeof(cr);
            if (n >= 0 && (getsockopt(n, SOL_SOCKET, SO_PEERCRED, &cr, &crl) != 0 || cr.uid != 0)) {   // only einkd (root)
                LOGW("pen ink layer: refused a peer that is not root (pid %d uid %d)", (int)cr.pid, (int)cr.uid);
                close(n);
                n = -1;
            }
            if (n >= 0) { if (cfd >= 0) close(cfd); cfd = n; LOGI("pen ink layer: einkd connected"); }
        }
        if (k.enable && st.fd < 0) {
            size_t len = (size_t)INK_W * INK_H * 4;
            st.fd = ink_alloc(len);
            void* m = st.fd >= 0 ? mmap(nullptr, len, PROT_READ | PROT_WRITE, MAP_SHARED, st.fd, 0) : MAP_FAILED;
            st.raw = (uint8_t*)malloc(len);
            if (m == MAP_FAILED || !st.raw) {
                if (m != MAP_FAILED) munmap(m, len);
                if (st.fd >= 0) close(st.fd);
                free(st.raw); st.raw = nullptr;
                st.fd = -1;
                LOGE("pen ink layer: no canvas; ink off until the composer restarts");
                no_canvas = true;
                k.enable = 0;
            } else {
                st.px = (uint8_t*)m;
                LOGI("pen ink layer: canvas %dx%d RGBA, dma-buf fd %d", INK_W, INK_H, st.fd);
            }
        }
        if (cfd >= 0 && (p[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            InkSample smp;
            ssize_t r;
            while ((r = recv(cfd, &smp, sizeof(smp), MSG_DONTWAIT)) == (ssize_t)sizeof(smp)) {
                st.sample_ms = ms;
                if (k.enable && st.px && smp.ver == 1) ink_sample(st, k, smp);
            }
            if (r == 0 || (r < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) {
                close(cfd); cfd = -1; LOGI("pen ink layer: einkd gone");
                if (st.down) { st.down = st.drawing = false; st.up_ms = ms; LOGI("pen session: no more reports, pen treated as lifted"); }
            }
        }
        // review 2026-10-21: the session (and the hold on every vendor commit) ended only with a pen-up report. A lost
        // one (einkd killed, kernel SYN_DROPPED) froze the panel. The Wacom digitizer reports continuously while the pen
        // touches, so a silent "down" pen is treated as lifted after max(ink_idle_ms, 3 s).
        if (st.session && st.down && ms - st.sample_ms >= (uint64_t)(k.idle_ms > 3000 ? k.idle_ms : 3000)) {
            st.down = st.drawing = false; st.up_ms = ms;
            LOGI("pen session: no report for %" PRIu64 " ms while down, pen treated as lifted", ms - st.sample_ms);
        }
        if (st.px) ink_flush(st, k);
        if (st.session && !st.settling && k.real && ms - st.real_ms >= (uint64_t)k.real_every_ms) { st.real_ms = ms; ink_realize(st, k); }
        if (!k.enable) st.nmeas = st.imeas = 0;   // another app, maybe another pen
        if (__atomic_exchange_n(&g_ink_cancel, 0, __ATOMIC_ACQ_REL) && st.session) {
            __atomic_store_n(&g_ink_session, 0, __ATOMIC_RELEASE);
            st.session = st.settling = st.down = st.drawing = false;
            st.seq = 0; st.raw_ok = false;
            pthread_mutex_lock(&g_lock); g_ink_held = 0; pthread_mutex_unlock(&g_lock);   // asleep: SF presents again at the wake
            LOGI("pen session dropped: display off");
        }
        if (st.session && !st.settling && ((!st.down && ms - st.up_ms >= (uint64_t)k.idle_ms) || !k.enable))
            ink_settle_start(st, k.enable ? "settled" : "ink off");
        while (st.settling) {   // box by box; the pen first
            if (__atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE)) { ink_settle_abort(st); break; }   // the cancel above drops the session next
            if (cfd >= 0 && k.enable) { struct pollfd q = {cfd, POLLIN, 0}; if (poll(&q, 1, 0) > 0) break; }
            ink_settle_step(st);
        }
    }
    return nullptr;
}

static void start_ink_thread() {
    if (prop_int("vendor.debug.hwc.shim.ink_thread", 1) == 0) return;
    pthread_t th;
    if (pthread_create(&th, nullptr, ink_thread, nullptr) == 0) pthread_detach(th);
    else LOGE("pen ink layer: thread not started");
}

// ioctl -- catches the eink ioctls whether or not they go through mtk_commit.
static int (*real_ioctl)(int, int, ...);
static int hook_ioctl(int fd, int req, ...) {
    va_list ap; va_start(ap, req); void* arg = va_arg(ap, void*); va_end(ap);
    uint32_t r = (uint32_t)req;
    if (r == DRM_IOCTL_EINK_UPDATE) {
        static Limiter L = {0, 4, 600};
        uint64_t t0 = now_ns();
        int ret = real_ioctl(fd, req, arg);
        __atomic_fetch_add(&g_ct.eink_update, 1, __ATOMIC_RELAXED);
        if (lim(L)) log_eink_update("EINK_UPDATE(ioctl)", (const drm_eink_update*)arg, ret, (now_ns() - t0) / 1000);
        return ret;
    }
    if (r == DRM_IOCTL_EINK_WAIT_VSYNC) {
        int ret = real_ioctl(fd, req, arg);
        __atomic_fetch_add(&g_ct.eink_vsync, 1, __ATOMIC_RELAXED);
        LOGV("EINK_WAIT_VSYNC: id=%d ret=%d", arg ? *(int*)arg : -1, ret);
        return ret;
    }
    // Other MTK/eink private DRM ioctls (nr 0x8f..0x9f) -- count and show once.
    if ((r & 0xff00) == 0x6400 && (r & 0xff) >= 0x8f && (r & 0xff) <= 0x9f) {
        static Limiter L = {0, 16, 1000};
        int ret = real_ioctl(fd, req, arg);
        __atomic_fetch_add(&g_ct.eink_other, 1, __ATOMIC_RELAXED);
        if (lim(L)) LOGI("DRM private ioctl 0x%08x (nr 0x%x size %u) ret=%d", r, r & 0xff, (r >> 16) & 0x3fff, ret);
        return ret;
    }
    return real_ioctl(fd, req, arg);
}

// libdrm
static int (*real_drmModeAddFB2WithModifiers)(int, uint32_t, uint32_t, uint32_t, const uint32_t*, const uint32_t*, const uint32_t*, const uint64_t*, uint32_t*, uint32_t);
static int hook_drmModeAddFB2WithModifiers(int fd, uint32_t w, uint32_t h, uint32_t fmt, const uint32_t* handles,
                                           const uint32_t* pitches, const uint32_t* offsets, const uint64_t* mods,
                                           uint32_t* id, uint32_t flags) {
    static Limiter L = {0, 40, 200};
    int ret = real_drmModeAddFB2WithModifiers(fd, w, h, fmt, handles, pitches, offsets, mods, id, flags);
    __atomic_fetch_add(&g_ct.addfb2, 1, __ATOMIC_RELAXED);
    if (lim(L)) {
        char fc[5]; fourcc_str(fmt, fc);
        LOGI("drmModeAddFB2: %ux%u fmt=%s(0x%08x) handles=[%u,%u] pitches=[%u,%u] offsets=[%u,%u] mod=0x%" PRIx64 " flags=0x%x -> fb_id=%u ret=%d  (pitch/w=%.2f B/px)",
             w, h, fc, fmt, handles ? handles[0] : 0, handles ? handles[1] : 0, pitches ? pitches[0] : 0, pitches ? pitches[1] : 0,
             offsets ? offsets[0] : 0, offsets ? offsets[1] : 0, mods ? mods[0] : 0, flags, id ? *id : 0, ret,
             (pitches && w) ? (double)pitches[0] / w : 0.0);
    }
    return ret;
}

// libdrm's drmModeAtomicReq is private but stable: {cursor, size_items, items*}, item = {obj, prop, u64 value}.
struct AtomicItem { uint32_t object_id, property_id; uint64_t value; };
struct AtomicReq { uint32_t cursor, size_items; AtomicItem* items; };
static int (*real_drmModeAtomicCommit)(int, AtomicReq*, uint32_t, void*);
static int hook_drmModeAtomicCommit(int fd, AtomicReq* req, uint32_t flags, void* ud) {
    static Limiter L = {0, 20, 300};
    int ret = real_drmModeAtomicCommit(fd, req, flags, ud);
    __atomic_fetch_add(&g_ct.atomic_commit, 1, __ATOMIC_RELAXED);
    if (lim(L)) {
        char line[1536]; int n = snprintf(line, sizeof(line), "drmModeAtomicCommit flags=0x%x ret=%d items=%u:", flags, ret, req ? req->cursor : 0);
        for (uint32_t i = 0; req && i < req->cursor && n < (int)sizeof(line) - 48; i++)
            n += snprintf(line + n, sizeof(line) - n, " %u.%u=%" PRIu64, req->items[i].object_id, req->items[i].property_id, req->items[i].value);
        LOGI("%s", line);
    }
    return ret;
}

static int (*real_drmModeSetCrtc)(int, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t*, int, void*);
static int hook_drmModeSetCrtc(int fd, uint32_t crtc, uint32_t fb, uint32_t x, uint32_t y, uint32_t* conns, int count, void* mode) {
    int ret = real_drmModeSetCrtc(fd, crtc, fb, x, y, conns, count, mode);
    __atomic_fetch_add(&g_ct.set_crtc, 1, __ATOMIC_RELAXED);
    LOGI("drmModeSetCrtc crtc=%u fb=%u pos=%u,%u conns=%d ret=%d", crtc, fb, x, y, count, ret);
    return ret;
}

// property_set -- the vendor HWC writes three status props for Bigme's own framework layer, which the GSI does not have:
// vendor.xrz.fps_limit on almost every commit (AnTuTu frame cap), hwc_idle (DisplayPolicyService's 4 s wakelock while
// a panel update runs) and gray_256_dither (the stock pen service). Nobody reads them here and SELinux denies them; each
// attempt cost an init round trip plus three log lines (kernel avc, init, libc). Allowing them was measured as no gain
// (2026-10-21). Dropped before the call; every other property passes through.
static int (*real_property_set)(const char*, const char*);
static int hook_property_set(const char* key, const char* value) {
    if (key && (!strcmp(key, "vendor.xrz.fps_limit") || !strcmp(key, "vendor.xrz.hwc_idle")
                || !strcmp(key, "vendor.xrz.gray_256_dither"))) {
        if (__atomic_fetch_add(&g_ct.status_props_dropped, 1, __ATOMIC_RELAXED) == 0)
            LOGI("status prop writes dropped (first: %s=%s); nothing on the GSI reads them", key, value ? value : "");
        return 0;
    }
    return real_property_set ? real_property_set(key, value) : -1;
}

static void install_out_hooks() {
    real_gralloc_extra_query = (int (*)(buffer_handle_t, int, void*))dlsym(g_real_handle, "gralloc_extra_query");
    if (!real_gralloc_extra_query) LOGW("gralloc_extra_query not resolvable; buffer geometry logging disabled");

    HookSpec specs[] = {
        {"_ZN3xrz13EffectHandler13convertToGrayEPvS1_N7android4RectEii", (void*)hook_convertToGray, (void**)&real_convertToGray, 0},
        {"_ZN3xrz13EffectHandler14imageSmoothingEPvS1_S1_S1_N7android4RectEii", (void*)hook_imageSmoothing, (void**)&real_imageSmoothing, 0},
        {"_ZN13OverlayEngine18convertLayerToGrayEP16OverlayPortParamP10CommitInfo", (void*)hook_convertLayerToGray, (void**)&real_convertLayerToGray, 0},
        {"_ZN13OverlayEngine12processImageEP16OverlayPortParamP10CommitInfo", (void*)hook_processImage, (void**)&real_processImage, 0},
        {"_Z10mtk_commit15drm_eink_update", (void*)hook_mtk_commit, (void**)&real_mtk_commit, 0},
        {"ioctl", (void*)hook_ioctl, (void**)&real_ioctl, 0},
        {"drmModeAddFB2WithModifiers", (void*)hook_drmModeAddFB2WithModifiers, (void**)&real_drmModeAddFB2WithModifiers, 0},
        {"drmModeAtomicCommit", (void*)hook_drmModeAtomicCommit, (void**)&real_drmModeAtomicCommit, 0},
        {"drmModeSetCrtc", (void*)hook_drmModeSetCrtc, (void**)&real_drmModeSetCrtc, 0},
        {"property_set", (void*)hook_property_set, (void**)&real_property_set, 0},
    };
    const size_t n = sizeof(specs) / sizeof(specs[0]);
    // Originals via dlsym on the real handle (searches it and its DT_NEEDED
    // closure), never via the GOT slot, so BIND_NOW vs lazy does not matter.
    for (size_t i = 0; i < n; i++) {
        *specs[i].orig = dlsym(g_real_handle, specs[i].sym);
        if (!*specs[i].orig) LOGW("dlsym(%s) failed: %s", specs[i].sym, dlerror());
    }
    GotCtx ctx = {specs, n, 0};
    dl_iterate_phdr(got_cb, &ctx);
    for (size_t i = 0; i < n; i++)
        LOGI("hook %-70s slots=%d orig=%p", specs[i].sym, specs[i].hits, *specs[i].orig);
    LOGI("GOT hooks installed: %d slots", ctx.total);
}

// ---------------------------------------------------------------- IN hooks (HWC2)

static LayerState* find_layer(hwc2_layer_t id, bool create) {
    for (int i = 0; i < MAX_LAYERS; i++) if (g_layers[i].used && g_layers[i].id == id) return &g_layers[i];
    if (!create) return nullptr;
    for (int i = 0; i < MAX_LAYERS; i++) if (!g_layers[i].used) { memset(&g_layers[i], 0, sizeof(LayerState)); g_layers[i].used = true; g_layers[i].id = id; g_layers[i].refresh_mode = -1; return &g_layers[i]; }
    return nullptr;
}

static LayerState* get_layer(hwc2_display_t display, hwc2_layer_t id) {
    LayerState* l = find_layer(id, true);
    if (l) l->display = display;
    return l;
}

static void push_refresh_mode(hwc2_display_t display, LayerState* l, int mode, const char* why) {
    PFN_bigmeSetLayerRefreshMode fn = (PFN_bigmeSetLayerRefreshMode)g_real_fn[FN_BIGME_SET_LAYER_REFRESH_MODE];
    if (!fn) return;
    int32_t err = fn(g_dev, display, l->id, mode);
    l->refresh_mode = mode;
    LOGI("setLayerRefreshMode(display=%" PRIu64 " layer=%" PRIu64 " mode=%d) [%s] -> %d", display, l->id, mode, why, err);
}

static int32_t hook_createLayer(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t* out) {
    int32_t err = ((PFN_createLayer)g_real_fn[FN_CREATE_LAYER])(dev, display, out);
    __atomic_fetch_add(&g_ct.create_layer, 1, __ATOMIC_RELAXED);
    if (err == 0 && out) {
        pthread_mutex_lock(&g_lock);
        LayerState* l = find_layer(*out, true);
        if (l) { l->display = display; l->comp_changed_to = -1; l->chg_seq = ++g_layer_seq; }
        LOGV("createLayer display=%" PRIu64 " -> layer=%" PRIu64, display, *out);
        if (l && g_refresh_mode >= 0) push_refresh_mode(display, l, g_refresh_mode, "create");
        pthread_mutex_unlock(&g_lock);
    }
    return err;
}

static int32_t hook_destroyLayer(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer) {
    pthread_mutex_lock(&g_lock);
    LayerState* l = find_layer(layer, false);
    if (l) {
        l->used = false;
        g_layer_gone_seq = ++g_layer_seq;
        const hwc_rect_t& f = l->frame;
        if (g_gone_box.right <= g_gone_box.left) g_gone_box = f;
        else {
            if (f.left < g_gone_box.left) g_gone_box.left = f.left;
            if (f.top < g_gone_box.top) g_gone_box.top = f.top;
            if (f.right > g_gone_box.right) g_gone_box.right = f.right;
            if (f.bottom > g_gone_box.bottom) g_gone_box.bottom = f.bottom;
        }
    }
    pthread_mutex_unlock(&g_lock);
    __atomic_fetch_add(&g_ct.destroy_layer, 1, __ATOMIC_RELAXED);
    LOGV("destroyLayer display=%" PRIu64 " layer=%" PRIu64, display, layer);
    return ((PFN_destroyLayer)g_real_fn[FN_DESTROY_LAYER])(dev, display, layer);
}

static int32_t hook_setLayerBuffer(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer, buffer_handle_t buf, int32_t fence) {
    pthread_mutex_lock(&g_lock);
    LayerState* l = get_layer(display, layer);
    if (l) {
        BufInfo bi; query_buf(buf, &bi);
        bool changed = !l->buf.valid || bufinfo_differs(&l->buf, &bi);
        if (changed && buf) {
            char what[64]; snprintf(what, sizeof(what), "layer %" PRIu64 " buffer", layer);
            log_bufinfo(what, &bi);
            if (g_log >= 2 || l->buffer_changes < 2) log_handle_raw(what, buf);
            l->buffer_changes++;
        }
        if (buf != l->buffer) { l->chg_seq = ++g_layer_seq; l->buf_ms = now_ns() / 1000000; }
        l->buffer = buf; l->buf = bi;
    }
    pthread_mutex_unlock(&g_lock);
    return ((PFN_setLayerBuffer)g_real_fn[FN_SET_LAYER_BUFFER])(dev, display, layer, buf, fence);
}

static int32_t hook_setLayerCompositionType(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer, int32_t type) {
    pthread_mutex_lock(&g_lock);
    LayerState* l = get_layer(display, layer); if (l) l->comp_requested = type;
    pthread_mutex_unlock(&g_lock);
    return ((PFN_setLayerCompositionType)g_real_fn[FN_SET_LAYER_COMPOSITION_TYPE])(dev, display, layer, type);
}
static int32_t hook_setLayerDataspace(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer, int32_t ds) {
    pthread_mutex_lock(&g_lock); LayerState* l = get_layer(display, layer); if (l) l->dataspace = ds; pthread_mutex_unlock(&g_lock);
    return ((PFN_setLayerDataspace)g_real_fn[FN_SET_LAYER_DATASPACE])(dev, display, layer, ds);
}
static int32_t hook_setLayerDisplayFrame(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer, hwc_rect_t f) {
    pthread_mutex_lock(&g_lock); LayerState* l = get_layer(display, layer);
    if (l && memcmp(&l->frame, &f, sizeof(f)) != 0) { l->frame = f; l->chg_seq = ++g_layer_seq; }
    pthread_mutex_unlock(&g_lock);
    return ((PFN_setLayerDisplayFrame)g_real_fn[FN_SET_LAYER_DISPLAY_FRAME])(dev, display, layer, f);
}
static int32_t hook_setLayerSourceCrop(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer, hwc_frect_t c) {
    pthread_mutex_lock(&g_lock); LayerState* l = get_layer(display, layer);
    if (l && memcmp(&l->crop, &c, sizeof(c)) != 0) { l->crop = c; l->chg_seq = ++g_layer_seq; }
    pthread_mutex_unlock(&g_lock);
    return ((PFN_setLayerSourceCrop)g_real_fn[FN_SET_LAYER_SOURCE_CROP])(dev, display, layer, c);
}
// HWC2: "If damage.numRects == 0, then the whole source buffer must be treated as if it has been modified"; one
// empty rect means unchanged. The vendor HWC treats 0 rects as no change ("[POLICY] skip: No dirty region find for
// commit", dirty ratio 0), so CPU-drawn windows that post full damage (KOReader: ANativeWindow lock/unlockAndPost)
// never reached the panel (owner 2026-10-18). Hand it the whole source crop instead.
// vendor.debug.hwc.shim.full_damage 0 = pass through.
static int32_t hook_setLayerSurfaceDamage(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer, hwc_region_t damage) {
    PFN_setLayerSurfaceDamage real = (PFN_setLayerSurfaceDamage)g_real_fn[FN_SET_LAYER_SURFACE_DAMAGE];
    hwc_rect_t whole = {0, 0, 16384, 16384};
    pthread_mutex_lock(&g_lock);
    LayerState* l = find_layer(layer, false);
    if (l && l->crop.right > l->crop.left && l->crop.bottom > l->crop.top)
        whole = {(int32_t)l->crop.left, (int32_t)l->crop.top, (int32_t)ceilf(l->crop.right), (int32_t)ceilf(l->crop.bottom)};
    else if (l && l->buf.valid && l->buf.w > 0 && l->buf.h > 0)
        whole = {0, 0, l->buf.w, l->buf.h};
    if (l) {   // what changed in this buffer: the keyboard area tells a key or a swipe trail from a popup above it
        hwc_rect_t u = whole;
        if (damage.numRects > 0 && damage.rects) {
            u = damage.rects[0];
            for (size_t i = 1; i < damage.numRects; i++) {
                const hwc_rect_t& r = damage.rects[i];
                if (r.left < u.left) u.left = r.left;
                if (r.top < u.top) u.top = r.top;
                if (r.right > u.right) u.right = r.right;
                if (r.bottom > u.bottom) u.bottom = r.bottom;
            }
        }
        l->dmg = u;
        l->dmg_buf = l->buffer;
    }
    pthread_mutex_unlock(&g_lock);
    if (damage.numRects != 0 || prop_int("vendor.debug.hwc.shim.full_damage", 1) == 0) return real(dev, display, layer, damage);
    static Limiter L = {0, 5, 600};
    if (lim(L)) LOGI("surface damage L%" PRIu64 ": full (0 rects) -> [%d,%d,%d,%d]", layer, whole.left, whole.top, whole.right, whole.bottom);
    hwc_region_t full = {1, &whole};
    return real(dev, display, layer, full);
}

static int32_t hook_setLayerTransform(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer, int32_t t) {
    pthread_mutex_lock(&g_lock); LayerState* l = get_layer(display, layer); if (l) l->transform = t; pthread_mutex_unlock(&g_lock);
    return ((PFN_setLayerTransform)g_real_fn[FN_SET_LAYER_TRANSFORM])(dev, display, layer, t);
}
static int32_t hook_setLayerBlendMode(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer, int32_t b) {
    pthread_mutex_lock(&g_lock); LayerState* l = get_layer(display, layer); if (l) l->blend = b; pthread_mutex_unlock(&g_lock);
    return ((PFN_setLayerBlendMode)g_real_fn[FN_SET_LAYER_BLEND_MODE])(dev, display, layer, b);
}
static int32_t hook_setLayerPlaneAlpha(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer, float a) {
    pthread_mutex_lock(&g_lock); LayerState* l = get_layer(display, layer);
    if (l && l->alpha != a) { l->alpha = a; l->chg_seq = ++g_layer_seq; }
    pthread_mutex_unlock(&g_lock);
    return ((PFN_setLayerPlaneAlpha)g_real_fn[FN_SET_LAYER_PLANE_ALPHA])(dev, display, layer, a);
}
static int32_t hook_setLayerZOrder(hwc2_device_t* dev, hwc2_display_t display, hwc2_layer_t layer, uint32_t z) {
    pthread_mutex_lock(&g_lock); LayerState* l = get_layer(display, layer);
    if (l && l->z != z) { l->z = z; l->chg_seq = ++g_layer_seq; }
    pthread_mutex_unlock(&g_lock);
    return ((PFN_setLayerZOrder)g_real_fn[FN_SET_LAYER_Z_ORDER])(dev, display, layer, z);
}

static int32_t hook_setClientTarget(hwc2_device_t* dev, hwc2_display_t display, buffer_handle_t target, int32_t fence, int32_t dataspace, hwc_region_t damage) {
    static Limiter L = {0, 20, 300};
    BufInfo bi; query_buf(target, &bi);
    pthread_mutex_lock(&g_lock);
    // SF cycles through ~3 client-target buffers, so a new handle every frame is normal: log only when the
    // buffer geometry/format or dataspace changes (logging every handle flooded logcat in ~1 min).
    bool changed = !g_ct_buf.valid || bufinfo_differs(&g_ct_buf, &bi) || dataspace != g_ct_dataspace ||
                   (g_log >= 2 && target != g_ct_handle);
    g_ct_handle = target; g_ct_buf = bi; g_ct_dataspace = dataspace;
    { int32_t v = -1; g_ct_ion_fd = (target && real_gralloc_extra_query && real_gralloc_extra_query(target, 1, &v) == 0 && v >= 0) ? v : -1; }
    g_ct_handle_fd0 = (target && target->numFds > 0) ? target->data[0] : -1;
    g_ct_ms = now_ns() / 1000000;
    {   // remember this target with its acquire fence (the device owns `fence` itself: keep a dup)
        CtRef& old = g_ctring[CT_RING - 1];
        if (old.fence >= 0) close(old.fence);
        for (int i = CT_RING - 1; i > 0; i--) g_ctring[i] = g_ctring[i - 1];
        g_ctring[0] = {g_ct_ion_fd, (fence >= 0 && g_ct_ion_fd >= 0) ? dup(fence) : -1, ++g_ct_seq};
    }
    pthread_mutex_unlock(&g_lock);
    __atomic_fetch_add(&g_ct.set_client_target, 1, __ATOMIC_RELAXED);
    bool say = lim(L);
    if (changed || say) {
        LOGI("setClientTarget display=%" PRIu64 " handle=%p fence=%d dataspace=0x%x damage=%zu rects",
             display, target, fence, dataspace, damage.numRects);
        if (damage.numRects && damage.rects)
            LOGI("  damage[0]=[%d,%d,%d,%d]", damage.rects[0].left, damage.rects[0].top, damage.rects[0].right, damage.rects[0].bottom);
        log_bufinfo("client target", &bi);
        if (changed) log_handle_raw("client target", target);
        if (changed) LOGI("ct fd sources: gralloc_ion_fd=%d handle_fd0=%d ct_src=%d build_id_ok=%d", g_ct_ion_fd, g_ct_handle_fd0, g_ct_src, g_build_id_ok);
    }
    return ((PFN_setClientTarget)g_real_fn[FN_SET_CLIENT_TARGET])(dev, display, target, fence, dataspace, damage);
}

static int32_t hook_getClientTargetSupport(hwc2_device_t* dev, hwc2_display_t display, uint32_t w, uint32_t h, int32_t format, int32_t dataspace) {
    int32_t err = ((PFN_getClientTargetSupport)g_real_fn[FN_GET_CLIENT_TARGET_SUPPORT])(dev, display, w, h, format, dataspace);
    LOGI("getClientTargetSupport display=%" PRIu64 " %ux%u fmt=0x%x(%s) dataspace=0x%x -> %d", display, w, h, format, hal_format_name(format), dataspace, err);
    return err;
}

static void log_layer_table(hwc2_display_t display, const char* when) {
    pthread_mutex_lock(&g_lock);
    int count = 0;
    for (int i = 0; i < MAX_LAYERS; i++) if (g_layers[i].used && g_layers[i].display == display) count++;
    LOGI("--- %s: display %" PRIu64 " %d layers; client target %p %dx%d s=%d fmt=0x%x(%s) ds=0x%x", when, display, count,
         g_ct_handle, g_ct_buf.w, g_ct_buf.h, g_ct_buf.stride, g_ct_buf.format, hal_format_name(g_ct_buf.format), g_ct_dataspace);
    for (int i = 0; i < MAX_LAYERS; i++) {
        const LayerState* l = &g_layers[i];
        if (!l->used || l->display != display) continue;
        LOGI("  L%-4" PRIu64 " z=%-3u req=%-6s got=%-6s buf=%p %dx%d s=%d fmt=0x%x(%s) u=0x%x ds=0x%x tr=%d bl=%d a=%.2f "
             "frame=[%d,%d,%d,%d] crop=[%.0f,%.0f,%.0f,%.0f] rm=%d",
             l->id, l->z, comp_name(l->comp_requested), l->comp_changed_to < 0 ? "-" : comp_name(l->comp_changed_to),
             l->buffer, l->buf.w, l->buf.h, l->buf.stride, l->buf.format, hal_format_name(l->buf.format), l->buf.usage,
             l->dataspace, l->transform, l->blend, l->alpha,
             l->frame.left, l->frame.top, l->frame.right, l->frame.bottom,
             l->crop.left, l->crop.top, l->crop.right, l->crop.bottom, l->refresh_mode);
    }
    pthread_mutex_unlock(&g_lock);
}

// Boot only (vendor.debug.hwc.shim.boot_damage=1, set by the module's post-fs-data and cleared at boot_completed):
// for its first ~2 s SurfaceFlinger sends no surface damage for a new layer, so the vendor HWC saw "No dirty region"
// and skipped the first boot-animation frames (2026-10-19: frames 1-2 of 4 never reached the panel). Every layer that
// got a new buffer since the last validate gets the whole source as damage. Off after boot: normal partial updates.
static void force_boot_damage(hwc2_device_t* dev, hwc2_display_t display) {
    PFN_setLayerSurfaceDamage real = (PFN_setLayerSurfaceDamage)g_real_fn[FN_SET_LAYER_SURFACE_DAMAGE];
    if (!real) return;
    hwc2_layer_t ids[MAX_LAYERS];
    hwc_rect_t rects[MAX_LAYERS];
    int n = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_LAYERS; i++) {
        LayerState* l = &g_layers[i];
        if (!l->used || l->display != display || !l->buffer || l->buffer == l->damaged_for) continue;
        l->damaged_for = l->buffer;
        hwc_rect_t whole = {0, 0, 16384, 16384};
        if (l->crop.right > l->crop.left && l->crop.bottom > l->crop.top)
            whole = {(int32_t)l->crop.left, (int32_t)l->crop.top, (int32_t)ceilf(l->crop.right), (int32_t)ceilf(l->crop.bottom)};
        else if (l->buf.valid && l->buf.w > 0 && l->buf.h > 0)
            whole = {0, 0, l->buf.w, l->buf.h};
        ids[n] = l->id; rects[n] = whole; n++;
    }
    pthread_mutex_unlock(&g_lock);
    static Limiter L = {0, 6, 600};
    for (int i = 0; i < n; i++) {
        hwc_region_t full = {1, &rects[i]};
        real(dev, display, ids[i], full);
        if (lim(L)) LOGI("boot damage L%" PRIu64 ": new buffer -> [%d,%d,%d,%d]", ids[i], rects[i].left, rects[i].top, rects[i].right, rects[i].bottom);
    }
}

static int32_t hook_validateDisplay(hwc2_device_t* dev, hwc2_display_t display, uint32_t* outNumTypes, uint32_t* outNumRequests) {
    static Limiter L = {0, 12, 240};
    refresh_knobs();
    // Apply/refresh the Bigme per-layer refresh mode if the knob changed.
    if (g_refresh_mode != g_refresh_mode_applied) {
        pthread_mutex_lock(&g_lock);
        if (g_refresh_mode >= 0)
            for (int i = 0; i < MAX_LAYERS; i++) if (g_layers[i].used) push_refresh_mode(g_layers[i].display, &g_layers[i], g_refresh_mode, "knob");
        g_refresh_mode_applied = g_refresh_mode;
        pthread_mutex_unlock(&g_lock);
        LOGI("refresh_mode knob now %d", g_refresh_mode);
    }
    bool say = lim(L);
    if (g_boot_damage) force_boot_damage(dev, display);
    if (say) log_layer_table(display, "validateDisplay IN");
    int32_t err = ((PFN_validateDisplay)g_real_fn[FN_VALIDATE_DISPLAY])(dev, display, outNumTypes, outNumRequests);
    __atomic_fetch_add(&g_ct.validate, 1, __ATOMIC_RELAXED);
    if (say) LOGI("validateDisplay OUT: err=%d (5=HAS_CHANGES) numTypes=%u numRequests=%u", err, outNumTypes ? *outNumTypes : 0, outNumRequests ? *outNumRequests : 0);
    return err;
}

static int32_t hook_getChangedCompositionTypes(hwc2_device_t* dev, hwc2_display_t display, uint32_t* outNum, hwc2_layer_t* outLayers, int32_t* outTypes) {
    static Limiter L = {0, 12, 240};
    int32_t err = ((PFN_getChangedCompositionTypes)g_real_fn[FN_GET_CHANGED_COMPOSITION_TYPES])(dev, display, outNum, outLayers, outTypes);
    if (err == 0 && outNum && outLayers && outTypes) {
        pthread_mutex_lock(&g_lock);
        for (int i = 0; i < MAX_LAYERS; i++) if (g_layers[i].used && g_layers[i].display == display) g_layers[i].comp_changed_to = -1;
        for (uint32_t i = 0; i < *outNum; i++) { LayerState* l = find_layer(outLayers[i], false); if (l) l->comp_changed_to = outTypes[i]; }
        pthread_mutex_unlock(&g_lock);
        if (lim(L)) {
            char line[1024]; int n = snprintf(line, sizeof(line), "changedCompositionTypes display=%" PRIu64 " n=%u:", display, *outNum);
            for (uint32_t i = 0; i < *outNum && n < (int)sizeof(line) - 40; i++)
                n += snprintf(line + n, sizeof(line) - n, " L%" PRIu64 "->%s", outLayers[i], comp_name(outTypes[i]));
            LOGI("%s", line);
        }
    }
    return err;
}

static int32_t hook_presentDisplay(hwc2_device_t* dev, hwc2_display_t display, int32_t* outFence) {
    static Limiter L = {0, 12, 240};
    uint64_t t0 = now_ns();
    int32_t err = ((PFN_presentDisplay)g_real_fn[FN_PRESENT_DISPLAY])(dev, display, outFence);
    uint64_t dt = (now_ns() - t0) / 1000;
    __atomic_fetch_add(&g_ct.present, 1, __ATOMIC_RELAXED);
    g_ct.last_present_ns = t0;
    if (dt > g_ct.present_max_us) g_ct.present_max_us = dt;
    if (lim(L)) LOGI("presentDisplay display=%" PRIu64 " err=%d fence=%d %" PRIu64 "us  [totals: validate=%" PRIu64 " present=%" PRIu64 " eink_update=%" PRIu64 " c2g=%" PRIu64 " cl2g=%" PRIu64 " pimg=%" PRIu64 " addfb2=%" PRIu64 " atomic=%" PRIu64 "]",
                    display, err, outFence ? *outFence : -1, dt, g_ct.validate, g_ct.present, g_ct.eink_update,
                    g_ct.convert_to_gray, g_ct.convert_layer_to_gray, g_ct.process_image, g_ct.addfb2, g_ct.atomic_commit);
    return err;
}

// HWC2 PowerMode: 0 OFF, 1 DOZE, 2 ON, 3 DOZE_SUSPEND. No lock of ours is held across the vendor call: its OFF path
// waits for the OverlayEngine job, which may be inside mtk_commit (g_commit_lock).
static int32_t hook_setPowerMode(hwc2_device_t* dev, hwc2_display_t display, int32_t mode) {
    LOGI("setPowerMode display=%" PRIu64 " mode=%d", display, mode);
    if (mode != 2) __atomic_store_n(&g_ink_cancel, 1, __ATOMIC_RELEASE);
    PFN_setPowerMode real = (PFN_setPowerMode)g_real_fn[FN_SET_POWER_MODE];
    if (display != 0) return real(dev, display, mode);
    if (mode == 0) {
        // Sleep picture first (one GC16 the kernel waits for in eink_suspend), then the vendor's OFF with its own
        // commits dropped. Without a frame the vendor's OFF runs as before and the panel keeps the last screen.
        bool shown = standby_commit("sleep");
        __atomic_store_n(&g_standby_shown, shown ? 1 : 0, __ATOMIC_RELEASE);
        if (shown) {
            __atomic_store_n(&g_display_off, 1, __ATOMIC_RELEASE);
            __atomic_store_n(&g_standby_hold, 1, __ATOMIC_RELEASE);
            int hold = prop_int("vendor.debug.hwc.shim.standby_hold_ms", 0);
            if (hold > 0) usleep((useconds_t)hold * 1000);
        }
        uint64_t t0 = now_ns();
        int32_t err = real(dev, display, mode);
        __atomic_store_n(&g_standby_hold, 0, __ATOMIC_RELEASE);
        __atomic_store_n(&g_display_off, 1, __ATOMIC_RELEASE);
        LOGI("setPowerMode OFF: %s, vendor OFF %" PRIu64 " ms err=%d", shown ? "sleep picture shown" : "no sleep picture",
             (now_ns() - t0) / 1000000, err);
        return err;
    }
    if (mode == 2) {
        // Commits the vendor makes inside its ON path must pass: the gates open before the call.
        __atomic_store_n(&g_display_off, 0, __ATOMIC_RELEASE);
        if (__atomic_exchange_n(&g_standby_shown, 0, __ATOMIC_ACQ_REL) && prop_int("vendor.debug.hwc.shim.standby_wake_gc16", 1))
            __atomic_store_n(&g_wake_gc16, 1, __ATOMIC_RELEASE);
        uint64_t t0 = now_ns();
        int32_t err = real(dev, display, mode);
        LOGI("setPowerMode ON: vendor ON %" PRIu64 " ms err=%d", (now_ns() - t0) / 1000000, err);
        return err;
    }
    return real(dev, display, mode);
}

// Append our state to the HWC's dump so it shows up in `dumpsys SurfaceFlinger`.
static int render_summary(char* buf, size_t cap) {
    pthread_mutex_lock(&g_lock);
    drm_eink_update u = g_last_update; uint64_t seq = g_last_update_seq;
    pthread_mutex_unlock(&g_lock);
    pthread_mutex_lock(&g_standby_lock);
    Standby sb = g_standby;
    pthread_mutex_unlock(&g_standby_lock);
    uint64_t sb_age_s = sb.commit_ns ? (now_ns() - sb.commit_ns) / 1000000000ull : 0;
    return snprintf(buf, cap,
        "\n[hwcshim] build_id_ok=%d log=%d refresh_mode=%d(applied %d) fn71=%p\n"
        "[hwcshim] counts: validate=%" PRIu64 " present=%" PRIu64 " (max %" PRIu64 "us) setCT=%" PRIu64 " layers +%" PRIu64 "/-%" PRIu64 "\n"
        "[hwcshim] out: eink_update=%" PRIu64 " vsync=%" PRIu64 " other=%" PRIu64 " convertToGray=%" PRIu64 " imageSmoothing=%" PRIu64
        " convertLayerToGray=%" PRIu64 " processImage=%" PRIu64 " addfb2=%" PRIu64 " atomic=%" PRIu64 " setcrtc=%" PRIu64 "\n"
        "[hwcshim] client target: %dx%d stride=%d fmt=0x%x(%s) usage=0x%x alloc=%d ds=0x%x\n"
        "[hwcshim] last EINK_UPDATE #%" PRIu64 ": mode=0x%x area=[%d,%d,%d,%d] dither=%u fd=%d format=%u stride=%u height=%u\n"
        "[hwcshim] colour: b1051c_build=%d own_fd=%d frames=%" PRIu64 " rows=%" PRIu64 " avg=%" PRIu64 "us vivid_fast=%d eff=%d/%d/%d/%d"
        " ct_waits=%" PRIu64 " ct_older=%" PRIu64 "\n"
        "[hwcshim] standby: knob=%d loaded=#%d valid=%d fd=%d commits=%d last_ret=%d errno=%d fails=%d last_commit=%" PRIu64 "s ago"
        " display_off=%d hold=%d wake_gc16=%d\n",
        g_build_id_ok, g_log, g_refresh_mode, g_refresh_mode_applied, (void*)g_real_fn[FN_BIGME_SET_LAYER_REFRESH_MODE],
        g_ct.validate, g_ct.present, g_ct.present_max_us, g_ct.set_client_target, g_ct.create_layer, g_ct.destroy_layer,
        g_ct.eink_update, g_ct.eink_vsync, g_ct.eink_other, g_ct.convert_to_gray, g_ct.image_smoothing,
        g_ct.convert_layer_to_gray, g_ct.process_image, g_ct.addfb2, g_ct.atomic_commit, g_ct.set_crtc,
        g_ct_buf.w, g_ct_buf.h, g_ct_buf.stride, g_ct_buf.format, hal_format_name(g_ct_buf.format), g_ct_buf.usage, g_ct_buf.alloc_size, g_ct_dataspace,
        seq, u.mode, u.left, u.top, u.right, u.bottom, u.dither, u.fd, u.format, u.stride, u.height,
        g_b1051c_ok, g_cout_last, g_cstat_frames, g_cstat_rows, g_cstat_frames ? g_cstat_us / g_cstat_frames : 0, g_vivid_fast,
        g_cp_eff.sat, g_cp_eff.con, g_cp_eff.bri, g_cp_eff.mode, g_ct_waits, g_ct_fallbacks,
        prop_int("vendor.debug.hwc.shim.standby", 0), sb.loaded_seq, sb.valid ? 1 : 0, sb.fd, sb.commits, sb.last_ret, sb.last_errno, sb.fails,
        sb_age_s, __atomic_load_n(&g_display_off, __ATOMIC_ACQUIRE), __atomic_load_n(&g_standby_hold, __ATOMIC_ACQUIRE),
        __atomic_load_n(&g_wake_gc16, __ATOMIC_ACQUIRE));
}

static void hook_dump(hwc2_device_t* dev, uint32_t* outSize, char* outBuffer) {
    PFN_dump real = (PFN_dump)g_real_fn[FN_DUMP];
    char ours[3072]; int ourLen = render_summary(ours, sizeof(ours));
    if (ourLen < 0) ourLen = 0; if (ourLen >= (int)sizeof(ours)) ourLen = sizeof(ours) - 1;
    if (!outBuffer) {
        uint32_t realLen = 0; if (real) real(dev, &realLen, nullptr);
        *outSize = realLen + (uint32_t)ourLen;
        return;
    }
    uint32_t cap = *outSize, realLen = 0;
    if (real) { realLen = cap > (uint32_t)ourLen ? cap - (uint32_t)ourLen : 0; real(dev, &realLen, outBuffer); if (realLen > cap) realLen = cap; }
    uint32_t room = cap - realLen; uint32_t n = (uint32_t)ourLen < room ? (uint32_t)ourLen : room;
    memcpy(outBuffer + realLen, ours, n);
    *outSize = realLen + n;
}

// ---------------------------------------------------------------- getFunction

static hwc2_function_pointer_t shim_getFunction(hwc2_device_t* dev, int32_t desc) {
    hwc2_function_pointer_t real = g_real_getFunction(dev, desc);
    if (desc >= 0 && desc < FN_MAX_PROBE) g_real_fn[desc] = real;
    LOGV("getFunction(%d) -> %p", desc, (void*)real);
    if (g_disabled || !real) return real;
    switch (desc) {
        case FN_CREATE_LAYER:                 return (hwc2_function_pointer_t)hook_createLayer;
        case FN_DESTROY_LAYER:                return (hwc2_function_pointer_t)hook_destroyLayer;
        case FN_DUMP:                         return (hwc2_function_pointer_t)hook_dump;
        case FN_GET_CHANGED_COMPOSITION_TYPES:return (hwc2_function_pointer_t)hook_getChangedCompositionTypes;
        case FN_GET_CLIENT_TARGET_SUPPORT:    return (hwc2_function_pointer_t)hook_getClientTargetSupport;
        case FN_PRESENT_DISPLAY:              return (hwc2_function_pointer_t)hook_presentDisplay;
        case FN_SET_CLIENT_TARGET:            return (hwc2_function_pointer_t)hook_setClientTarget;
        case FN_SET_LAYER_BLEND_MODE:         return (hwc2_function_pointer_t)hook_setLayerBlendMode;
        case FN_SET_LAYER_BUFFER:             return (hwc2_function_pointer_t)hook_setLayerBuffer;
        case FN_SET_LAYER_COMPOSITION_TYPE:   return (hwc2_function_pointer_t)hook_setLayerCompositionType;
        case FN_SET_LAYER_DATASPACE:          return (hwc2_function_pointer_t)hook_setLayerDataspace;
        case FN_SET_LAYER_DISPLAY_FRAME:      return (hwc2_function_pointer_t)hook_setLayerDisplayFrame;
        case FN_SET_LAYER_PLANE_ALPHA:        return (hwc2_function_pointer_t)hook_setLayerPlaneAlpha;
        case FN_SET_LAYER_SOURCE_CROP:        return (hwc2_function_pointer_t)hook_setLayerSourceCrop;
        case FN_SET_LAYER_SURFACE_DAMAGE:     return g_real_fn[FN_SET_LAYER_SURFACE_DAMAGE] ? (hwc2_function_pointer_t)hook_setLayerSurfaceDamage : nullptr;
        case FN_SET_LAYER_TRANSFORM:          return (hwc2_function_pointer_t)hook_setLayerTransform;
        case FN_SET_LAYER_Z_ORDER:            return (hwc2_function_pointer_t)hook_setLayerZOrder;
        case FN_SET_POWER_MODE:               return (hwc2_function_pointer_t)hook_setPowerMode;
        case FN_VALIDATE_DISPLAY:             return (hwc2_function_pointer_t)hook_validateDisplay;
        default:                              return real;
    }
}

static void probe_functions(hwc2_device_t* dev) {
    char line[1024]; int n = snprintf(line, sizeof(line), "real HWC descriptors present:");
    int extra = 0;
    for (int d = 1; d < FN_MAX_PROBE; d++) {
        hwc2_function_pointer_t f = g_real_getFunction(dev, d);
        g_real_fn[d] = f;
        if (!f) continue;
        if (d > 61) { n += snprintf(line + n, sizeof(line) - n, " %d", d); extra++; }
    }
    LOGI("%s%s (beyond composer-2.3's 61: %d; 71 = Bigme setLayerRefreshMode is %s)", line, extra ? "" : " (none beyond 61)", extra,
         g_real_fn[FN_BIGME_SET_LAYER_REFRESH_MODE] ? "PRESENT" : "ABSENT");
    g_probed = true;
}

// ---------------------------------------------------------------- module entry

static int shim_open(const hw_module_t* /*module*/, const char* name, hw_device_t** device) {
    refresh_knobs();
    g_disabled = prop_int("vendor.debug.hwc.shim.disable", 0) != 0;
    LOGI("hwcshim loading real HWC %s (name=%s, log=%d, refresh_mode=%d, disabled=%d)", REAL_HWC, name ? name : "?", g_log, g_refresh_mode, g_disabled);

    g_real_handle = dlopen(REAL_HWC, RTLD_NOW | RTLD_LOCAL);
    if (!g_real_handle) { LOGE("dlopen(%s) failed: %s", REAL_HWC, dlerror()); return -ENOENT; }
    g_real_module = (hw_module_t*)dlsym(g_real_handle, "HMI");
    if (!g_real_module) { LOGE("real HMI not found: %s", dlerror()); return -EINVAL; }
    LOGI("real module id=%s name=%s author=%s api=0x%x hal=0x%x", g_real_module->id, g_real_module->name,
         g_real_module->author, g_real_module->module_api_version, g_real_module->hal_api_version);

    uint8_t bid[21] = {0};
    if (dl_iterate_phdr(buildid_cb, bid) == 1) {
        char hex[41]; for (int i = 0; i < 16; i++) snprintf(hex + 2*i, 3, "%02x", bid[i]); hex[32] = 0;
        g_build_id_ok = bid[20] >= 16 && memcmp(bid, KNOWN_BUILD_ID, 16) == 0;
        g_b1051c_ok = bid[20] >= 16 && memcmp(bid, KNOWN_BUILD_ID_B1051C, 16) == 0;
        LOGI("real HWC BuildID %s -> struct peeks %s, B1051C processImage guard %s", hex,
             g_build_id_ok ? "ENABLED" : "DISABLED (unknown build)", g_b1051c_ok ? "ENABLED" : "DISABLED (not the B1051C 4.9.0 build)");
    } else {
        LOGW("could not read real HWC BuildID; struct peeks disabled");
    }

    hw_device_t* dev = nullptr;
    int err = g_real_module->methods->open(g_real_module, name, &dev);
    if (err != 0 || !dev) { LOGE("real open failed: %d", err); return err ? err : -EINVAL; }
    g_dev = (hwc2_device_t*)dev;
    LOGI("real device version=0x%x (major %u)", dev->version, (dev->version >> 24) & 0xf);

    if (!g_disabled) {
        g_real_getFunction = g_dev->getFunction;
        g_dev->getFunction = shim_getFunction;
        probe_functions(g_dev);
        install_out_hooks();
        start_gc16_thread();
        start_ink_thread();
    }
    *device = dev;
    return 0;
}

static hw_module_methods_t shim_methods = { shim_open };

extern "C" __attribute__((visibility("default"))) hw_module_t HMI = {
    HARDWARE_MODULE_TAG,
    /*module_api_version*/ 0x0300,     // HWC_MODULE_API_VERSION_0_1 semantics are unused by hwc2 loaders
    /*hal_api_version*/    0x0100,
    "hwcomposer",
    "hwcshim interposer for hwcomposer.mt6877.so",
    "hibreak",
    &shim_methods,
    nullptr,
    {0},
};
