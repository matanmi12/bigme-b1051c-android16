// einkd — small root daemon for the Bigme B1051C Pro e-ink layer (started by the b1051c-eink-compat module).
//
// Job 1 (stock parity): the vendor HWC counts "big" updates (>= 40 % of the screen, >= 800 ms apart) for the
// automatic full refresh (vendor.xrz.clean_frequency = every N) only while vendor.xrz.check_commit != 0. On the
// stock ROM, PowerManager raised check_commit for 800 ms after each touch/key so that only user-driven page turns
// count, not animations or video. We do the same from the kernel input devices (touch, pen, keys).
//
// Reads /dev/input/event* (no grabbing, except jobs 2 and 5: events still reach Android), sets the property with
// __system_property_set. Only pulses while clean_frequency > 0. Devices that appear later (a Bluetooth page turner
// or keyboard, re-created on every reconnect) are picked up through inotify, like Android's EventHub.
//
// Job 2 (owner 2026-10-19): the magnetic cover switch. hall_key.kl maps cover close/open to SLEEP/WAKEUP. While
// persist.b1051c.cover_wake is 0 (QS tile "Cover" in E-Ink Control), einkd grabs the hall device (EVIOCGRAB): its
// events reach only einkd and Android ignores the cover. Re-checked every 2 s; if einkd stops, the grab ends with it.
//
// Job 3 (owner 2026-10-20): the "Pen" refresh mode's ink layer. While vendor.debug.hwc.shim.ink is 1, every Wacom
// report (SYN_REPORT) goes to the HWC shim over the abstract seqpacket socket "b1051c_ink"; the shim draws the
// stroke itself and commits only its box in the stock handwriting mode. The composer cannot open /dev/input itself.
// Abstract names are first come, first served: the peer must be the composer (uid system, its SELinux domain).
//
// Job 4 (wave 4, stock "Page sliding optimization"): a finger drag of more than 10 px sets vendor.xrz.is_scrolling,
// which the vendor HWC honours in HD 256 / Regal (Fast, 0xb2, while it is 1). After the lift it goes back to 0 after
// 400..2000 ms, longer for a faster fling (stock). The HWC shim cleans the Fast residue once (scroll_clean).
// vendor.debug.hwc.shim.scroll (E-Ink Control, per app): 0 = off, 178 = Fast (default), 179 = Extreme (the shim
// turns the scroll's Fast updates into Extreme).
//
// Job 5 (wave 4, stock Control Center "Touch"): touch off. The touch screen is grabbed while
// vendor.debug.b1051c.touch_off is 1 (QS tile; E-Ink Control clears it when the screen goes off), and while the pen
// is near in a Pen-mode app (vendor.debug.hwc.shim.ink 1; persist.b1051c.pen_palm, default 1), until 500 ms after it
// leaves. Fingers still on the glass are lifted for Android first (events written to the device reach every
// reader), and the grab ends only with no finger down, so Android never keeps a finger it cannot see go up.
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <sys/un.h>
#include <stddef.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "einkd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define MAXDEV 32
#define HOLD_MS 800
#define COVER_CHECK_MS 2000
#define PROP_CHECK_MS 1000
#define COVER_PROP "persist.b1051c.cover_wake"
#define SCROLL_PX 10
#define PALM_AFTER_MS 500

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}

static int prop_int(const char* name, int def) {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get(name, v) <= 0) return def;
    char* end;
    long r = strtol(v, &end, 10);
    return end == v ? def : (int) r;
}

// ---- input devices
enum { K_OTHER, K_TOUCH, K_PEN };
struct Dev { int fd; int kind; char node[16]; };   // node = "event5"
static struct Dev dev[MAXDEV];
static int ndev;
static char hall_path[64];

// Touchscreen, pen digitizer and keys; not sensors or the virtual devices.
static int wanted(int fd, char* name, size_t len) {
    if (ioctl(fd, EVIOCGNAME(len), name) < 0) return 0;
    unsigned long evbits = 0;
    if (ioctl(fd, EVIOCGBIT(0, sizeof(evbits)), &evbits) < 0) return 0;
    int has_abs = (evbits >> EV_ABS) & 1, has_key = (evbits >> EV_KEY) & 1;
    if (strstr(name, "accel") || strstr(name, "gyro") || strstr(name, "hall") || strstr(name, "virtual")) return 0;
    return has_abs || has_key;
}

static int is_touch(int fd) {
    unsigned char abs[ABS_MAX / 8 + 1];
    memset(abs, 0, sizeof(abs));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs)), abs) < 0) return 0;
    return (abs[ABS_MT_POSITION_X / 8] >> (ABS_MT_POSITION_X % 8)) & 1;
}

// Open /dev/input/<node> if it is one of ours. Read-write: job 5 writes the lift to the touch screen.
static void dev_add(const char* node) {
    if (strncmp(node, "event", 5) != 0 || ndev >= MAXDEV) return;
    for (int i = 0; i < ndev; i++) if (strcmp(dev[i].node, node) == 0) return;
    char path[64], name[128] = {0};
    snprintf(path, sizeof(path), "/dev/input/%s", node);
    int fd = open(path, O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) { LOGI("open %s: %s", path, strerror(errno)); return; }
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 && strcmp(name, "hall_key") == 0) {
        snprintf(hall_path, sizeof(hall_path), "%s", path);
        LOGI("cover sensor %s", path);
    }
    if (!wanted(fd, name, sizeof(name) - 1)) { close(fd); return; }
    int kind = strstr(name, "Wacom") ? K_PEN : is_touch(fd) ? K_TOUCH : K_OTHER;
    dev[ndev].fd = fd;
    dev[ndev].kind = kind;
    snprintf(dev[ndev].node, sizeof(dev[ndev].node), "%s", node);
    ndev++;
    LOGI("watching %s (%s)%s", path, name, kind == K_PEN ? " [pen]" : kind == K_TOUCH ? " [touch]" : "");
}

static void dev_drop(int i);

// ---- ink layer (Job 3)
struct InkSample {                  // = work/shim/hwcshim.cpp
    uint32_t ver;                   // 1
    uint32_t flags;                 // INK_*
    int32_t x, y, p;                // raw ABS_X, ABS_Y, ABS_PRESSURE
    uint32_t rsvd;
};
enum { INK_TOUCH = 1, INK_RANGE = 2, INK_RUBBER = 4, INK_BUTTON = 8 };
static struct InkSample pen = {1, 0, 0, 0, 0, 0};
static int ink_on, ink_fd = -1;
static long long ink_checked, ink_retry, pen_left;
static int pen_dropped;             // SYN_DROPPED seen: discard until SYN_REPORT, then re-read the device state

// The listener must be the composer: uid system and, when the kernel tells, its SELinux domain.
static int ink_peer_ok(int fd) {
    struct ucred cr;
    socklen_t len = sizeof(cr);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cr, &len) < 0) { LOGI("ink: peer: %s", strerror(errno)); return 0; }
    char ctx[128] = {0};
    socklen_t cl = sizeof(ctx) - 1;
    int have_ctx = getsockopt(fd, SOL_SOCKET, SO_PEERSEC, ctx, &cl) == 0;
    if (cr.uid == 1000 && (!have_ctx || strcmp(ctx, "u:r:hal_graphics_composer_default:s0") == 0)) return 1;
    LOGI("ink: @b1051c_ink is held by pid %d uid %d (%s), not the composer: not sending", (int) cr.pid, (int) cr.uid,
         have_ctx ? ctx : "?");
    return 0;
}

static void ink_send(void) {
    long long now = now_ms();
    if (ink_fd < 0) {
        if (now - ink_retry < 1000) return;
        ink_retry = now;
        int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        struct sockaddr_un a;
        memset(&a, 0, sizeof(a));
        a.sun_family = AF_UNIX;
        static const char name[] = "b1051c_ink";
        memcpy(a.sun_path + 1, name, sizeof(name) - 1);
        socklen_t len = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(name) - 1);
        if (fd < 0 || connect(fd, (struct sockaddr*) &a, len) < 0) {
            LOGI("ink: connect @%s: %s", name, strerror(errno));
            if (fd >= 0) close(fd);
            return;
        }
        if (!ink_peer_ok(fd)) { close(fd); return; }
        ink_fd = fd;
        LOGI("ink: connected to the HWC shim");
    }
    if (send(ink_fd, &pen, sizeof(pen), MSG_DONTWAIT | MSG_NOSIGNAL) < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
        LOGI("ink: send: %s", strerror(errno));   // composer restarted: reconnect on the next report
        close(ink_fd);
        ink_fd = -1;
    }
}

// After SYN_DROPPED the kernel's buffer overflowed and the queued events are incomplete: take the pen's state from the
// device itself (evdev docs), so a lost pen-up cannot leave the shim's session "down" (vendor commits held).
static void pen_resync(int fd) {
    unsigned char keys[KEY_MAX / 8 + 1];
    memset(keys, 0, sizeof(keys));
    if (ioctl(fd, EVIOCGKEY(sizeof(keys)), keys) >= 0) {
#define KEYBIT(c) ((keys[(c) / 8] >> ((c) % 8)) & 1)
        uint32_t f = 0;
        if (KEYBIT(BTN_TOUCH)) f |= INK_TOUCH;
        if (KEYBIT(BTN_TOOL_PEN)) f |= INK_RANGE;
        if (KEYBIT(BTN_TOOL_RUBBER)) f |= INK_RANGE | INK_RUBBER;
        if (KEYBIT(BTN_STYLUS)) f |= INK_BUTTON;
#undef KEYBIT
        pen.flags = f;
    }
    struct input_absinfo ai;
    if (ioctl(fd, EVIOCGABS(ABS_X), &ai) >= 0) pen.x = ai.value;
    if (ioctl(fd, EVIOCGABS(ABS_Y), &ai) >= 0) pen.y = ai.value;
    if (ioctl(fd, EVIOCGABS(ABS_PRESSURE), &ai) >= 0) pen.p = ai.value;
    LOGI("pen: events dropped by the kernel, state re-read (flags 0x%x)", pen.flags);
}

static void pen_event(int fd, const struct input_event* e) {
    if (e->type == EV_SYN && e->code == SYN_DROPPED) { pen_dropped = 1; return; }
    if (pen_dropped) {
        if (!(e->type == EV_SYN && e->code == SYN_REPORT)) return;
        pen_dropped = 0;
        pen_resync(fd);
    }
    uint32_t before = pen.flags;
    if (e->type == EV_ABS) {
        if (e->code == ABS_X) pen.x = e->value;
        else if (e->code == ABS_Y) pen.y = e->value;
        else if (e->code == ABS_PRESSURE) pen.p = e->value;
    } else if (e->type == EV_KEY) {
        uint32_t bit = e->code == BTN_TOUCH ? INK_TOUCH : e->code == BTN_TOOL_PEN ? INK_RANGE :
                       e->code == BTN_TOOL_RUBBER ? (INK_RANGE | INK_RUBBER) : e->code == BTN_STYLUS ? INK_BUTTON : 0;
        if (bit) pen.flags = e->value ? (pen.flags | bit) : (pen.flags & ~bit);
        // pen coming in range: look at the switch now, not up to a second late
        if (e->code == BTN_TOOL_PEN && e->value) { ink_on = prop_int("vendor.debug.hwc.shim.ink", 0) == 1; ink_checked = now_ms(); }
    } else if (e->type == EV_SYN && e->code == SYN_REPORT && ink_on) {
        ink_send();
    }
    if ((before & INK_RANGE) && !(pen.flags & INK_RANGE)) pen_left = now_ms();
}

// ---- cover (Job 2)
static int hall_fd = -1;

// Grab the cover sensor while the switch is off, release it while on (default on, also when the prop is unset).
static void cover_update(void) {
    if (!hall_path[0]) return;
    int ignore = prop_int(COVER_PROP, 1) == 0;
    if (ignore && hall_fd < 0) {
        int fd = open(hall_path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) { LOGI("cover: open %s: %s", hall_path, strerror(errno)); return; }
        if (ioctl(fd, EVIOCGRAB, 1) < 0) { LOGI("cover: grab: %s", strerror(errno)); close(fd); return; }
        hall_fd = fd;
        LOGI("cover: ignored (grabbed %s)", hall_path);
    } else if (!ignore && hall_fd >= 0) {
        ioctl(hall_fd, EVIOCGRAB, 0);
        close(hall_fd);
        hall_fd = -1;
        LOGI("cover: wakes and sleeps again");
    }
}

// ---- touch: scrolling (Job 4) and touch off (Job 5)
static int touch_down;              // BTN_TOUCH of the touch screen (protocol A: no slots)
static int t_x, t_y, t_have;        // first contact of the current frame
static int s_x0, s_y0, s_started;   // where the finger came down
static int scrolling;               // we set vendor.xrz.is_scrolling 1
static long long scroll_reset_at;   // 0 = none pending
static int scroll_mode = 178;       // vendor.debug.hwc.shim.scroll
#define VN 8
static struct { int x, y; long long t; } vel[VN];
static int vel_n;
static int touch_grab_fd = -1;      // the touch device fd while grabbed
static int touch_off, pen_palm = 1;

static void set_scrolling(int on) {
    __system_property_set("vendor.xrz.is_scrolling", on ? "1" : "0");
    scrolling = on;
}

// Finger speed over the last 100 ms of the drag, px/s.
static int fling_speed(long long now) {
    int i0 = -1, i1 = -1;
    for (int i = 0; i < vel_n; i++) {
        if (now - vel[i].t > 100) continue;
        if (i0 < 0) i0 = i;
        i1 = i;
    }
    if (i0 < 0 || i1 == i0 || vel[i1].t == vel[i0].t) return 0;
    long long dx = vel[i1].x - vel[i0].x, dy = vel[i1].y - vel[i0].y;
    double d = __builtin_sqrt((double) (dx * dx + dy * dy));
    return (int) (d * 1000.0 / (double) (vel[i1].t - vel[i0].t));
}

static void touch_frame(void) {
    if (!touch_down || !t_have) return;
    long long now = now_ms();
    if (vel_n == VN) { memmove(vel, vel + 1, sizeof(vel[0]) * (VN - 1)); vel_n--; }
    vel[vel_n].x = t_x; vel[vel_n].y = t_y; vel[vel_n].t = now; vel_n++;
    if (!s_started) { s_x0 = t_x; s_y0 = t_y; s_started = 1; return; }
    if (scrolling || scroll_mode == 0 || touch_grab_fd >= 0) return;   // a palm on the grabbed screen is no scroll
    int dx = t_x - s_x0, dy = t_y - s_y0;
    if (dx * dx + dy * dy > SCROLL_PX * SCROLL_PX) set_scrolling(1);
}

static void touch_event(const struct input_event* e) {
    if (e->type == EV_KEY && e->code == BTN_TOUCH) {
        if (e->value && !touch_down) { s_started = 0; vel_n = 0; scroll_reset_at = 0; }
        if (!e->value && touch_down && scrolling) {
            long long now = now_ms();
            int v = fling_speed(now);
            int delay = 400 + v / 2;                        // stock: 400..2000 ms, longer for a faster fling
            if (delay > 2000) delay = 2000;
            scroll_reset_at = now + delay;
        }
        touch_down = e->value != 0;
    } else if (e->type == EV_ABS && !t_have) {
        if (e->code == ABS_MT_POSITION_X) t_x = e->value;
        else if (e->code == ABS_MT_POSITION_Y) t_y = e->value;
    } else if (e->type == EV_SYN && e->code == SYN_MT_REPORT) {
        t_have = 1;                                         // the first contact of the frame is complete
    } else if (e->type == EV_SYN && e->code == SYN_REPORT) {
        touch_frame();
        t_have = 0;
    }
}

static void scroll_check(long long now) {
    if (!scrolling || touch_down || !scroll_reset_at || now < scroll_reset_at) return;
    scroll_reset_at = 0;
    set_scrolling(0);
}

static int touch_index(void) {
    for (int i = 0; i < ndev; i++) if (dev[i].kind == K_TOUCH && dev[i].fd >= 0) return i;
    return -1;
}

// Android sees every finger go up (an empty protocol-A frame), then only einkd reads the touch screen.
static void touch_grab(int on) {
    int ti = touch_index();
    if (on && touch_grab_fd < 0 && ti >= 0) {
        int fd = dev[ti].fd;
        if (touch_down) {
            struct input_event lift[4];
            memset(lift, 0, sizeof(lift));
            lift[0].type = EV_KEY; lift[0].code = BTN_TOUCH; lift[0].value = 0;
            lift[1].type = EV_KEY; lift[1].code = BTN_TOOL_FINGER; lift[1].value = 0;
            lift[2].type = EV_SYN; lift[2].code = SYN_MT_REPORT;
            lift[3].type = EV_SYN; lift[3].code = SYN_REPORT;
            if (write(fd, lift, sizeof(lift)) != (ssize_t) sizeof(lift)) LOGI("touch: lift: %s", strerror(errno));
        }
        if (ioctl(fd, EVIOCGRAB, 1) < 0) { LOGI("touch: grab: %s", strerror(errno)); return; }
        touch_grab_fd = fd;
        if (scrolling) { scroll_reset_at = 0; set_scrolling(0); }
        LOGI("touch: off (%s)", touch_off ? "tile" : "pen near");
    } else if (!on && touch_grab_fd >= 0 && !touch_down) {
        ioctl(touch_grab_fd, EVIOCGRAB, 0);
        touch_grab_fd = -1;
        LOGI("touch: on");
    }
}

static void touch_update(long long now) {
    int pen_near = (pen.flags & INK_RANGE) || (pen_left && now - pen_left < PALM_AFTER_MS);
    touch_grab(touch_off || (pen_palm && ink_on && pen_near));
}

static void dev_drop(int i) {
    LOGI("input device gone: %s", dev[i].node);
    if (dev[i].fd == touch_grab_fd) touch_grab_fd = -1;
    if (dev[i].kind == K_PEN) { pen.flags = 0; if (ink_on) ink_send(); }   // a lost pen ends its stroke
    if (dev[i].kind == K_TOUCH) { touch_down = 0; t_have = 0; }
    close(dev[i].fd);
    dev[i] = dev[--ndev];
}

int main(void) {
    int ino = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (ino < 0 || inotify_add_watch(ino, "/dev/input", IN_CREATE | IN_DELETE | IN_ATTRIB) < 0)
        LOGI("inotify /dev/input: %s (devices added later are not watched)", strerror(errno));
    DIR* d = opendir("/dev/input");
    if (!d) { LOGI("no /dev/input: %s", strerror(errno)); return 1; }
    struct dirent* e;
    while ((e = readdir(d))) dev_add(e->d_name);
    closedir(d);
    if (ndev == 0) LOGI("no input devices yet");
    set_scrolling(0);                                       // a flag left by an earlier einkd

    int raised = 0;
    long long until = 0, cover_checked = 0, props_checked = 0;
    struct input_event ev[64];
    struct pollfd pfd[MAXDEV + 2];
    for (;;) {
        long long now = now_ms();
        if (now - cover_checked >= COVER_CHECK_MS) { cover_update(); cover_checked = now; }
        if (now - props_checked >= PROP_CHECK_MS) {
            props_checked = now;
            ink_on = prop_int("vendor.debug.hwc.shim.ink", 0) == 1;
            ink_checked = now;
            if (!ink_on && ink_fd >= 0) { close(ink_fd); ink_fd = -1; }
            scroll_mode = prop_int("vendor.debug.hwc.shim.scroll", 178);
            touch_off = prop_int("vendor.debug.b1051c.touch_off", 0) == 1;
            pen_palm = prop_int("persist.b1051c.pen_palm", 1) != 0;
        }
        scroll_check(now);
        touch_update(now);
        int timeout = ink_on ? PROP_CHECK_MS : COVER_CHECK_MS;
        if (raised) {
            long long left = until - now;
            if (left <= 0) {
                __system_property_set("vendor.xrz.check_commit", "0");
                raised = 0;
                continue;
            }
            if (left < timeout) timeout = (int) left;
        }
        if (scroll_reset_at && scroll_reset_at - now < timeout) timeout = (int) (scroll_reset_at > now ? scroll_reset_at - now : 0);
        if (touch_grab_fd >= 0 && !touch_off && timeout > 100) timeout = 100;   // the pen's 500 ms after leaving
        // devices 0..ndev-1, then inotify, then the grabbed cover sensor (its events are only drained)
        int np = 0;
        for (int i = 0; i < ndev; i++) { pfd[np].fd = dev[i].fd; pfd[np].events = POLLIN; pfd[np].revents = 0; np++; }
        int ino_i = -1, hall_i = -1;
        if (ino >= 0) { ino_i = np; pfd[np].fd = ino; pfd[np].events = POLLIN; pfd[np].revents = 0; np++; }
        if (hall_fd >= 0) { hall_i = np; pfd[np].fd = hall_fd; pfd[np].events = POLLIN; pfd[np].revents = 0; np++; }
        int r = poll(pfd, np, timeout);
        if (r < 0 && errno != EINTR) { LOGI("poll: %s", strerror(errno)); return 1; }
        if (r <= 0) continue;
        int activity = 0;
        if (hall_i >= 0 && pfd[hall_i].revents) {
            if (pfd[hall_i].revents & (POLLERR | POLLHUP | POLLNVAL)) { close(hall_fd); hall_fd = -1; }
            else while (read(hall_fd, ev, sizeof(ev)) > 0) {}
        }
        // devices first (a gone device reports POLLHUP/POLLERR forever: drop it, or poll() returns at once in a loop)
        for (int i = ndev - 1; i >= 0; i--) {
            if (pfd[i].revents & (POLLERR | POLLHUP | POLLNVAL)) { dev_drop(i); continue; }
            if (!(pfd[i].revents & POLLIN)) continue;
            ssize_t got;
            int grabbed = dev[i].fd == touch_grab_fd;
            while ((got = read(dev[i].fd, ev, sizeof(ev))) > 0) {
                for (size_t k = 0; k < (size_t) got / sizeof(ev[0]); k++) {
                    if (!grabbed && (ev[k].type == EV_KEY || ev[k].type == EV_ABS)) activity = 1;
                    if (dev[i].kind == K_PEN) pen_event(dev[i].fd, &ev[k]);
                    else if (dev[i].kind == K_TOUCH) touch_event(&ev[k]);
                }
            }
        }
        if (ino_i >= 0 && (pfd[ino_i].revents & POLLIN)) {
            char buf[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
            ssize_t len;
            while ((len = read(ino, buf, sizeof(buf))) > 0) {
                for (char* p = buf; p < buf + len; ) {
                    struct inotify_event* ie = (struct inotify_event*) p;
                    if (ie->len && (ie->mask & (IN_CREATE | IN_ATTRIB))) dev_add(ie->name);   // ATTRIB: ueventd's chmod
                    if (ie->len && (ie->mask & IN_DELETE))
                        for (int i = 0; i < ndev; i++) if (strcmp(dev[i].node, ie->name) == 0) { dev_drop(i); break; }
                    p += sizeof(struct inotify_event) + ie->len;
                }
            }
        }
        if (!activity) continue;
        until = now_ms() + HOLD_MS;
        if (!raised && prop_int("vendor.xrz.clean_frequency", 0) > 0) {
            __system_property_set("vendor.xrz.check_commit", "1");
            raised = 1;
        }
    }
}
