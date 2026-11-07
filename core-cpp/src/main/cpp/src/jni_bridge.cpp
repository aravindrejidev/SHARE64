// JNI bridge: Kotlin <-> engine. Native threads call back into Kotlin through TransferEngine.onNativeEvent.
#include <jni.h>

#include <memory>
#include <string>

#include "share64/engine.h"

namespace {

JavaVM* g_vm = nullptr;

struct ThreadEnv {
  JNIEnv* env = nullptr;
  bool attached = false;
  ~ThreadEnv() {
    if (attached && g_vm) g_vm->DetachCurrentThread();
  }
};
thread_local ThreadEnv t_env;

JNIEnv* current_env() {
  if (t_env.env) return t_env.env;
  JNIEnv* env = nullptr;
  const jint r = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (r == JNI_OK) {
    t_env.env = env;
    return env;
  }
  if (r == JNI_EDETACHED) {
    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_6;
    args.name = const_cast<char*>("share64-native");
    args.group = nullptr;
    if (g_vm->AttachCurrentThread(&env, &args) == JNI_OK) {
      t_env.env = env;
      t_env.attached = true;
      return env;
    }
  }
  return nullptr;
}

// JNI strings are *modified* UTF-8 (CESU-8 for emoji). Always go through UTF-16 so names like
// "photo 😀.jpg" survive both ways.
std::string to_utf8(JNIEnv* env, jstring js) {
  std::string out;
  if (!js) return out;
  const jsize n = env->GetStringLength(js);
  const jchar* c = env->GetStringChars(js, nullptr);
  if (!c) return out;
  for (jsize i = 0; i < n; ++i) {
    uint32_t cp = c[i];
    if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && c[i + 1] >= 0xDC00 && c[i + 1] <= 0xDFFF) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + (c[i + 1] - 0xDC00);
      ++i;
    } else if (cp >= 0xD800 && cp <= 0xDFFF) {
      cp = 0xFFFD;
    }
    if (cp < 0x80) {
      out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
      out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
      out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
      out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
      out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
      out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
      out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
      out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
  }
  env->ReleaseStringChars(js, c);
  return out;
}

jstring from_utf8(JNIEnv* env, const std::string& s) {
  if (s.empty()) return env->NewString(nullptr, 0);
  std::u16string u;
  u.reserve(s.size());
  const unsigned char* p = reinterpret_cast<const unsigned char*>(s.data());
  const size_t n = s.size();
  for (size_t i = 0; i < n;) {
    uint32_t cp = 0xFFFD;
    size_t len = 1;
    const unsigned char b = p[i];
    if (b < 0x80) {
      cp = b;
    } else if ((b & 0xE0) == 0xC0 && i + 1 < n && (p[i + 1] & 0xC0) == 0x80) {
      cp = ((b & 0x1F) << 6) | (p[i + 1] & 0x3F);
      len = 2;
      if (cp < 0x80) cp = 0xFFFD;
    } else if ((b & 0xF0) == 0xE0 && i + 2 < n && (p[i + 1] & 0xC0) == 0x80 && (p[i + 2] & 0xC0) == 0x80) {
      cp = ((b & 0x0F) << 12) | ((p[i + 1] & 0x3F) << 6) | (p[i + 2] & 0x3F);
      len = 3;
      if (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
    } else if ((b & 0xF8) == 0xF0 && i + 3 < n && (p[i + 1] & 0xC0) == 0x80 && (p[i + 2] & 0xC0) == 0x80 &&
               (p[i + 3] & 0xC0) == 0x80) {
      cp = ((b & 0x07) << 18) | ((p[i + 1] & 0x3F) << 12) | ((p[i + 2] & 0x3F) << 6) | (p[i + 3] & 0x3F);
      len = 4;
      if (cp < 0x10000 || cp > 0x10FFFF) cp = 0xFFFD;
    }
    i += len;
    if (cp >= 0x10000) {
      cp -= 0x10000;
      u.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
      u.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    } else {
      u.push_back(static_cast<char16_t>(cp));
    }
  }
  return env->NewString(reinterpret_cast<const jchar*>(u.data()), static_cast<jsize>(u.size()));
}

class JniSink : public s64::EventSink {
 public:
  JniSink(JNIEnv* env, jobject owner) {
    owner_ = env->NewGlobalRef(owner);
    jclass cls = env->GetObjectClass(owner);
    method_ = env->GetMethodID(cls, "onNativeEvent",
                               "(IJIIIJJJJLjava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
  }
  void release(JNIEnv* env) {
    if (owner_) env->DeleteGlobalRef(owner_);
    owner_ = nullptr;
  }

  void on_event(const s64::Event& e) override {
    JNIEnv* env = current_env();
    if (!env || !owner_ || !method_) return;
    jstring jname = e.name.empty() ? nullptr : from_utf8(env, e.name);
    jstring jmsg = e.message.empty() ? nullptr : from_utf8(env, e.message);
    env->CallVoidMethod(owner_, method_, static_cast<jint>(e.kind), static_cast<jlong>(e.transfer_id),
                        static_cast<jint>(e.dir), static_cast<jint>(e.err), static_cast<jint>(e.attempt),
                        static_cast<jlong>(e.total), static_cast<jlong>(e.done),
                        static_cast<jlong>(e.verified), static_cast<jlong>(e.bps), jname, jmsg);
    if (env->ExceptionCheck()) env->ExceptionClear();  // a listener bug must never kill a worker thread
    if (jname) env->DeleteLocalRef(jname);
    if (jmsg) env->DeleteLocalRef(jmsg);
  }

 private:
  jobject owner_ = nullptr;
  jmethodID method_ = nullptr;
};

struct Handle {
  std::unique_ptr<JniSink> sink;
  std::unique_ptr<s64::Engine> engine;
};

Handle* H(jlong h) { return reinterpret_cast<Handle*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  g_vm = vm;
  return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_com_share64_core_TransferEngine_nativeCreate(
    JNIEnv* env, jobject self, jstring device, jint chunk, jint slots, jint workers, jboolean auto_accept) {
  try {
    auto h = std::make_unique<Handle>();
    h->sink = std::make_unique<JniSink>(env, self);
    s64::Config cfg;
    cfg.device_name = to_utf8(env, device);
    if (chunk > 0) cfg.chunk_size = static_cast<uint32_t>(chunk);
    if (slots > 0) cfg.slots = static_cast<uint32_t>(slots);
    if (workers > 0) cfg.workers = static_cast<uint32_t>(workers);
    cfg.auto_accept = auto_accept == JNI_TRUE;
    h->engine = std::make_unique<s64::Engine>(cfg, h->sink.get());
    return reinterpret_cast<jlong>(h.release());
  } catch (...) {
    return 0;
  }
}

JNIEXPORT void JNICALL Java_com_share64_core_TransferEngine_nativeDestroy(JNIEnv* env, jobject, jlong handle) {
  std::unique_ptr<Handle> h(H(handle));
  if (!h) return;
  h->engine.reset();  // cancels + joins every worker thread before the sink goes away
  if (h->sink) h->sink->release(env);
}

// Returns the bound port (>0) or the negative s64::Err code.
JNIEXPORT jint JNICALL Java_com_share64_core_TransferEngine_nativeStartReceiver(
    JNIEnv* env, jobject, jlong handle, jstring dir, jint port) {
  Handle* h = H(handle);
  if (!h) return -static_cast<jint>(s64::Err::Internal);
  uint16_t bound = 0;
  const s64::Err e = h->engine->start_receiver(to_utf8(env, dir), static_cast<uint16_t>(port), &bound);
  return e == s64::Err::Ok ? static_cast<jint>(bound) : -static_cast<jint>(e);
}

JNIEXPORT void JNICALL Java_com_share64_core_TransferEngine_nativeStopReceiver(JNIEnv*, jobject, jlong handle) {
  if (Handle* h = H(handle)) h->engine->stop_receiver();
}

JNIEXPORT jlong JNICALL Java_com_share64_core_TransferEngine_nativeSendFile(
    JNIEnv* env, jobject, jlong handle, jstring host, jint port, jstring path, jstring name, jstring peer) {
  Handle* h = H(handle);
  if (!h) return 0;
  return static_cast<jlong>(h->engine->send_file(to_utf8(env, host), static_cast<uint16_t>(port),
                                                 to_utf8(env, path), to_utf8(env, name), to_utf8(env, peer)));
}

JNIEXPORT jlong JNICALL Java_com_share64_core_TransferEngine_nativeSendFd(
    JNIEnv* env, jobject, jlong handle, jstring host, jint port, jint fd, jstring name, jstring peer) {
  Handle* h = H(handle);
  if (!h) return 0;
  return static_cast<jlong>(h->engine->send_fd(to_utf8(env, host), static_cast<uint16_t>(port), fd,
                                               to_utf8(env, name), to_utf8(env, peer)));
}

JNIEXPORT void JNICALL Java_com_share64_core_TransferEngine_nativeRespond(JNIEnv*, jobject, jlong handle,
                                                                          jlong id, jboolean accept) {
  if (Handle* h = H(handle)) h->engine->respond(static_cast<uint64_t>(id), accept == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_share64_core_TransferEngine_nativeCancel(JNIEnv*, jobject, jlong handle, jlong id) {
  if (Handle* h = H(handle)) h->engine->cancel(static_cast<uint64_t>(id));
}

}  // extern "C"
