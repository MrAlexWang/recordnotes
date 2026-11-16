// 腾讯 HY-MT1.5-1.8B（GGUF）离线翻译的 JNI 封装，基于 llama.cpp
#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include <android/log.h>

#include "llama.h"

#define TAG "hytrans"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *       model  = nullptr;
    const llama_vocab * vocab  = nullptr;
    const char *        tmpl   = nullptr;
    int                 threads = 4;
};

Engine * as_engine(jlong h) { return reinterpret_cast<Engine *>(h); }

// 用模型的 chat template 包一层；没有模板就退回简单拼接
std::string wrap_prompt(Engine * e, const std::string & user) {
    if (e->tmpl != nullptr) {
        llama_chat_message msg;
        msg.role    = "user";
        msg.content = user.c_str();
        std::vector<char> buf(user.size() * 2 + 1024);
        int n = llama_chat_apply_template(e->tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
        if (n > 0 && n <= (int32_t) buf.size()) {
            return std::string(buf.data(), n);
        }
        LOGI("chat template apply failed n=%d, fallback", n);
    }
    return "User: " + user + "\nAssistant:";
}

std::string generate(Engine * e, const std::string & prompt, int n_predict) {
    const int n_prompt = -llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(),
                                         nullptr, 0, true, true);
    if (n_prompt <= 0) {
        LOGE("tokenize failed");
        return "";
    }
    std::vector<llama_token> toks(n_prompt);
    if (llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(),
                       toks.data(), (int32_t) toks.size(), true, true) < 0) {
        LOGE("tokenize fill failed");
        return "";
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = n_prompt + n_predict + 16;
    cp.n_batch         = std::max<int>(n_prompt, 64);
    cp.n_threads       = e->threads;
    cp.n_threads_batch = e->threads;
    cp.no_perf         = true;

    llama_context * ctx = llama_init_from_model(e->model, cp);
    if (ctx == nullptr) {
        LOGE("init context failed");
        return "";
    }

    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_batch_ext * batch = llama_batch_ext_init(ctx);

    auto fill = [&](const llama_token * t, int n, llama_pos pos0) {
        llama_batch_ext_clear(batch);
        for (int i = 0; i < n; ++i) {
            const int32_t idx = llama_batch_ext_add_token(batch, 0, t[i]);
            llama_pos p = pos0 + i;
            llama_batch_ext_set_pos(batch, idx, &p);
        }
        llama_batch_ext_set_output_logits(batch, n - 1, true);
    };

    fill(toks.data(), n_prompt, 0);

    std::string out;
    int n_tokens = n_prompt;
    int n_pos    = 0;
    const int limit = n_prompt + n_predict;

    while (n_pos + n_tokens < limit) {
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch)) {
            LOGE("decode failed");
            break;
        }
        n_pos += n_tokens;

        llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(e->vocab, id)) break;

        char buf[512];
        int n = llama_token_to_piece(e->vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);
        if ((int) out.size() > n_predict * 8) break;

        fill(&id, 1, n_pos);
        n_tokens = 1;
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(smpl);
    llama_free(ctx);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_phonesar_notes_TranslationEngine_nativeInit(JNIEnv * env, jclass, jstring path, jint threads) {
    llama_backend_init();

    const char * cpath = env->GetStringUTFChars(path, nullptr);
    std::string model_path(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), mp);
    if (model == nullptr) {
        LOGE("load model failed: %s", model_path.c_str());
        return 0;
    }

    Engine * e = new Engine();
    e->model   = model;
    e->vocab   = llama_model_get_vocab(model);
    e->threads = threads > 0 ? threads : 4;
    e->tmpl    = llama_model_chat_template(model, nullptr);

    LOGI("model loaded: %s threads=%d tmpl=%s", model_path.c_str(), e->threads,
         e->tmpl ? "yes" : "no");
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phonesar_notes_TranslationEngine_nativeTranslate(JNIEnv * env, jclass, jlong handle, jstring prompt) {
    Engine * e = as_engine(handle);
    if (e == nullptr) return env->NewStringUTF("");

    const char * cp = env->GetStringUTFChars(prompt, nullptr);
    std::string user(cp ? cp : "");
    if (cp) env->ReleaseStringUTFChars(prompt, cp);

    const std::string wrapped = wrap_prompt(e, user);
    std::string out = generate(e, wrapped, 512);

    // 去掉可能带出来的前后缀
    auto trim = [](std::string s) {
        const char * ws = " \t\r\n";
        size_t b = s.find_first_not_of(ws);
        if (b == std::string::npos) return std::string();
        size_t e2 = s.find_last_not_of(ws);
        return s.substr(b, e2 - b + 1);
    };
    out = trim(out);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_phonesar_notes_TranslationEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    Engine * e = as_engine(handle);
    if (e == nullptr) return;
    if (e->model) llama_model_free(e->model);
    delete e;
}
