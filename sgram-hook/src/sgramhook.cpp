// sGram: ART method hooking for the plugin engine (Xposed-style API on top of LSPlant).
// LSPlant needs an inline hooker (Dobby) and a resolver for libart.so symbols; most of
// those symbols are hidden and live only in .gnu_debugdata (xz-compressed MiniDebugInfo).

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>
#include <mutex>
#include <thread>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "dobby.h"
#include "lsplant.hpp"

extern "C" {
#include "xz/xz.h"
}

#define TAG "sGramHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if defined(__LP64__)
using Ehdr = Elf64_Ehdr;
using Shdr = Elf64_Shdr;
using Sym = Elf64_Sym;
#else
using Ehdr = Elf32_Ehdr;
using Shdr = Elf32_Shdr;
using Sym = Elf32_Sym;
#endif

namespace {

struct LibInfo {
    std::string path;
    uintptr_t base = 0;
};

int FindLib(struct dl_phdr_info *info, size_t, void *data) {
    auto *out = static_cast<LibInfo *>(data);
    if (!info->dlpi_name) return 0;
    std::string_view name(info->dlpi_name);
    if (name.size() >= 10 && name.substr(name.size() - 10) == "/libart.so") {
        out->path = info->dlpi_name;
        out->base = info->dlpi_addr;
        return 1;
    }
    return 0;
}

class ArtSymbols {
public:
    bool Load() {
        LibInfo lib;
        dl_iterate_phdr(FindLib, &lib);
        if (lib.path.empty()) {
            LOGE("libart.so not found");
            return false;
        }
        bias_ = lib.base;
        debug_.reserve(4);
        int fd = open(lib.path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            LOGE("open %s failed", lib.path.c_str());
            return false;
        }
        struct stat st{};
        fstat(fd, &st);
        void *map = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (map == MAP_FAILED) return false;
        file_ = std::string(static_cast<const char *>(map), st.st_size);
        munmap(map, st.st_size);
        ParseElf(file_.data(), file_.size(), true);
        LOGI("libart: %zu symbols, bias %p", symbols_.size(), (void *) bias_);
        return !symbols_.empty();
    }

    void *Find(std::string_view name) const {
        auto it = symbols_.find(name);
        return it == symbols_.end() ? nullptr : reinterpret_cast<void *>(it->second);
    }

    void *FindPrefix(std::string_view prefix) const {
        for (auto &[name, addr] : symbols_) {
            if (name.size() >= prefix.size() && name.substr(0, prefix.size()) == prefix) {
                return reinterpret_cast<void *>(addr);
            }
        }
        return nullptr;
    }

private:
    void ParseElf(const char *data, size_t size, bool outer) {
        if (size < sizeof(Ehdr) || memcmp(data, ELFMAG, SELFMAG) != 0) return;
        auto *eh = reinterpret_cast<const Ehdr *>(data);
        if (eh->e_shoff == 0 || eh->e_shoff + eh->e_shnum * sizeof(Shdr) > size) return;
        auto *sh = reinterpret_cast<const Shdr *>(data + eh->e_shoff);
        const char *shstr = eh->e_shstrndx < eh->e_shnum ? data + sh[eh->e_shstrndx].sh_offset : nullptr;
        for (int i = 0; i < eh->e_shnum; i++) {
            const Shdr &s = sh[i];
            if ((s.sh_type == SHT_SYMTAB || s.sh_type == SHT_DYNSYM) && s.sh_link < eh->e_shnum) {
                const Shdr &strs = sh[s.sh_link];
                if (s.sh_offset + s.sh_size > size || strs.sh_offset + strs.sh_size > size) continue;
                auto *syms = reinterpret_cast<const Sym *>(data + s.sh_offset);
                const char *names = data + strs.sh_offset;
                size_t count = s.sh_size / sizeof(Sym);
                for (size_t j = 0; j < count; j++) {
                    if (syms[j].st_value == 0 || syms[j].st_shndx == SHN_UNDEF) continue;
                    if (syms[j].st_name >= strs.sh_size) continue;
                    std::string_view name(names + syms[j].st_name);
                    if (name.empty()) continue;
                    uintptr_t addr = bias_ + syms[j].st_value;
                    symbols_.emplace(name, addr);
                }
            } else if (outer && shstr && strcmp(shstr + s.sh_name, ".gnu_debugdata") == 0) {
                std::string inner;
                if (Unxz(data + s.sh_offset, s.sh_size, inner)) {
                    debug_.push_back(std::move(inner));
                    ParseElf(debug_.back().data(), debug_.back().size(), false);
                } else {
                    LOGE("failed to unpack .gnu_debugdata");
                }
            }
        }
    }

    static bool Unxz(const char *in, size_t in_size, std::string &out) {
        xz_crc32_init();
        xz_crc64_init();
        xz_dec *dec = xz_dec_init(XZ_DYNALLOC, 1 << 26);
        if (!dec) return false;
        std::vector<uint8_t> buf(1 << 20);
        xz_buf b{};
        b.in = reinterpret_cast<const uint8_t *>(in);
        b.in_pos = 0;
        b.in_size = in_size;
        b.out = buf.data();
        b.out_pos = 0;
        b.out_size = buf.size();
        bool ok = false;
        while (true) {
            xz_ret ret = xz_dec_run(dec, &b);
            if (b.out_pos == b.out_size || ret == XZ_STREAM_END) {
                out.append(reinterpret_cast<char *>(buf.data()), b.out_pos);
                b.out_pos = 0;
            }
            if (ret == XZ_OK || ret == XZ_UNSUPPORTED_CHECK) continue;
            ok = ret == XZ_STREAM_END;
            break;
        }
        xz_dec_end(dec);
        return ok;
    }

    // symbols_ keys point into these buffers, so they live as long as the resolver
    std::string file_;
    std::vector<std::string> debug_;
    std::unordered_map<std::string_view, uintptr_t> symbols_;
    uintptr_t bias_ = 0;  // dlpi_addr: runtime address = bias + st_value
};

ArtSymbols *g_art = nullptr;
std::mutex g_lock;
int g_state = 0;  // 0 = not tried, 1 = ok, -1 = failed

void *InlineHook(void *target, void *hooker) {
    void *orig = nullptr;
    return DobbyHook(target, hooker, &orig) == 0 ? orig : nullptr;
}

bool InlineUnhook(void *func) {
    return DobbyDestroy(func) == 0;
}

// LSPlant needs hidden APIs (DexFile.<init>, ...). ART treats JNI calls from a native thread with
// no Java frames on the stack as trusted, so exempt everything from such a thread.
bool BypassHiddenApi(JNIEnv *env) {
    JavaVM *vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) return false;
    bool ok = false;
    std::thread worker([vm, &ok] {
        JNIEnv *t = nullptr;
        JavaVMAttachArgs args{JNI_VERSION_1_6, "sGramHook", nullptr};
        if (vm->AttachCurrentThread(&t, &args) != JNI_OK) return;
        jclass runtime = t->FindClass("dalvik/system/VMRuntime");
        if (runtime) {
            jmethodID get = t->GetStaticMethodID(runtime, "getRuntime", "()Ldalvik/system/VMRuntime;");
            jmethodID exempt = t->GetMethodID(runtime, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
            if (get && exempt) {
                jobject rt = t->CallStaticObjectMethod(runtime, get);
                jclass string = t->FindClass("java/lang/String");
                jobjectArray prefixes = t->NewObjectArray(1, string, t->NewStringUTF("L"));
                t->CallVoidMethod(rt, exempt, prefixes);
                ok = !t->ExceptionCheck();
            }
        }
        if (t->ExceptionCheck()) t->ExceptionClear();
        vm->DetachCurrentThread();
    });
    worker.join();
    LOGI("hidden API exemption: %d", ok);
    return ok;
}

bool EnsureInit(JNIEnv *env) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_state != 0) return g_state > 0;
    BypassHiddenApi(env);
    g_art = new ArtSymbols();
    if (!g_art->Load()) {
        g_state = -1;
        return false;
    }
    lsplant::InitInfo info{
        .inline_hooker = InlineHook,
        .inline_unhooker = InlineUnhook,
        .art_symbol_resolver = [](std::string_view symbol) -> void * { return g_art->Find(symbol); },
        .art_symbol_prefix_resolver = [](std::string_view symbol) -> void * { return g_art->FindPrefix(symbol); },
        .generated_class_name = "SGHooker_",
        .generated_source_name = "SG",
    };
    bool ok = lsplant::Init(env, info);
    LOGI("LSPlant init: %d", ok);
    g_state = ok ? 1 : -1;
    return ok;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_SgramHookNative_init(JNIEnv *env, jclass) {
    return EnsureInit(env) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_SgramHookNative_hook(JNIEnv *env, jclass, jobject target, jobject hooker, jobject callback) {
    if (!EnsureInit(env)) return nullptr;
    return lsplant::Hook(env, target, hooker, callback);
}

JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_SgramHookNative_unhook(JNIEnv *env, jclass, jobject target) {
    if (!EnsureInit(env)) return JNI_FALSE;
    return lsplant::UnHook(env, target) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_SgramHookNative_isHooked(JNIEnv *env, jclass, jobject target) {
    if (!EnsureInit(env)) return JNI_FALSE;
    return lsplant::IsHooked(env, target) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_SgramHookNative_deoptimize(JNIEnv *env, jclass, jobject target) {
    if (!EnsureInit(env)) return JNI_FALSE;
    return lsplant::Deoptimize(env, target) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_SgramHookNative_makeClassInheritable(JNIEnv *env, jclass, jclass target) {
    if (!EnsureInit(env)) return JNI_FALSE;
    return lsplant::MakeClassInheritable(env, target) ? JNI_TRUE : JNI_FALSE;
}

}
