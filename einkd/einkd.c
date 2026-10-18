// einkd — small root daemon for the Bigme B1051C Pro e-ink layer (started by the b1051c-eink-compat module).
//
// Job 1 (stock parity): the vendor HWC counts "big" updates (>= 40 % of the screen, >= 800 ms apart) for the
// automatic full refresh (vendor.xrz.clean_frequency = every N) only while vendor.xrz.check_commit != 0. On the
// stock ROM, PowerManager raised check_commit for 800 ms after each touch/key so that only user-driven page turns
// count, not animations or video. We do the same from the kernel input devices (touch, pen, keys).
//
// Reads /dev/input/event* (no grabbing: events still reach Android), sets the property with
// __system_property_set. Only pulses while clean_frequency > 0.
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "einkd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define MAXDEV 16
#define HOLD_MS 800

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

// Touchscreen, pen digitizer and keys; not sensors or the virtual devices.
static int wanted(int fd, char* name, size_t len) {
    if (ioctl(fd, EVIOCGNAME(len), name) < 0) return 0;
    unsigned long evbits = 0;
    if (ioctl(fd, EVIOCGBIT(0, sizeof(evbits)), &evbits) < 0) return 0;
    int has_abs = (evbits >> EV_ABS) & 1, has_key = (evbits >> EV_KEY) & 1;
    if (strstr(name, "accel") || strstr(name, "gyro") || strstr(name, "hall") || strstr(name, "virtual")) return 0;
    return has_abs || has_key;
}

int main(void) {
    struct pollfd pfd[MAXDEV];
    int n = 0;
    DIR* d = opendir("/dev/input");
    if (!d) { LOGI("no /dev/input: %s", strerror(errno)); return 1; }
    struct dirent* e;
    while ((e = readdir(d)) && n < MAXDEV) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64], name[128] = {0};
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) continue;
        if (!wanted(fd, name, sizeof(name) - 1)) { close(fd); continue; }
        LOGI("watching %s (%s)", path, name);
        pfd[n].fd = fd;
        pfd[n].events = POLLIN;
        n++;
    }
    closedir(d);
    if (n == 0) { LOGI("no input devices"); return 1; }

    int raised = 0;
    long long until = 0;
    struct input_event ev[64];
    for (;;) {
        int timeout = raised ? (int) (until - now_ms()) : -1;
        if (raised && timeout <= 0) {
            __system_property_set("vendor.xrz.check_commit", "0");
            raised = 0;
            continue;
        }
        int r = poll(pfd, n, timeout);
        if (r < 0 && errno != EINTR) { LOGI("poll: %s", strerror(errno)); return 1; }
        int activity = 0;
        for (int i = 0; i < n && r > 0; i++) {
            if (!(pfd[i].revents & POLLIN)) continue;
            ssize_t got;
            while ((got = read(pfd[i].fd, ev, sizeof(ev))) > 0) {
                for (size_t k = 0; k < (size_t) got / sizeof(ev[0]); k++)
                    if (ev[k].type == EV_KEY || ev[k].type == EV_ABS) activity = 1;
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
