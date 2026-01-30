#include <android/log.h>
#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

#include "llama/llama.h"

#define LOG_TAG "LLM_DEBUG"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static std::mutex g_mutex;

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static const llama_vocab *g_vocab = nullptr;

static llama_context_params g_cparams{};
static int32_t g_pos = 0;
static llama_token g_token_bos = -1;
static llama_token g_token_eos = -1;

// ---------------- Scoped Lock ----------------
struct ScopedLock {
  std::mutex &m;
  explicit ScopedLock(std::mutex &m_) : m(m_) { m.lock(); }
  ~ScopedLock() { m.unlock(); }
};

// ---------------- Helpers ----------------
static std::string jstring_to_std(JNIEnv *env, jstring js) {
  if (!js)
    return {};
  const char *utf = env->GetStringUTFChars(js, nullptr);
  std::string out(utf ? utf : "");
  if (utf)
    env->ReleaseStringUTFChars(js, utf);
  return out;
}

static std::string apply_chat_template(const std::string &user_prompt) {
  return "You are a helpful assistant.\nInstruction:\n" + user_prompt +
         "\nAnswer:\n";
}

static std::string token_to_piece(const llama_vocab *vocab, llama_token token,
                                  bool special) {
  if (!vocab || token == LLAMA_TOKEN_NULL)
    return "";
  char buf[512];
  int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, special);
  if (n < 0)
    return "";
  return std::string(buf, n);
}

// ---------------- JNI Functions ----------------
extern "C" {

// ---------------- Load Model ----------------
JNIEXPORT jlong JNICALL Java_com_example_llmserverapp_LlamaBridge_loadModel(
    JNIEnv *env, jclass, jstring j_model_path) {

  ScopedLock lock(g_mutex);
  LOGD("Loading model...");

  if (g_ctx) {
    llama_free(g_ctx);
    g_ctx = nullptr;
    LOGD("Freed old context");
  }
  if (g_model) {
    llama_model_free(g_model);
    g_model = nullptr;
    LOGD("Freed old model");
  }

  g_vocab = nullptr;
  g_pos = 0;

  std::string path = jstring_to_std(env, j_model_path);
  LOGD("Model path: %s", path.c_str());

  llama_backend_init();
  llama_model_params mparams = llama_model_default_params();
  mparams.vocab_only = false;

  g_model = llama_model_load_from_file(path.c_str(), mparams);
  if (!g_model) {
    LOGD("Failed to load model!");
    return 0;
  }
  LOGD("Model loaded successfully");

  g_cparams = llama_context_default_params();
  g_cparams.n_ctx = 2048;
  g_cparams.n_threads = 4;

  g_ctx = llama_init_from_model(g_model, g_cparams);
  if (!g_ctx) {
    LOGD("Failed to initialize context!");
    llama_model_free(g_model);
    g_model = nullptr;
    return 0;
  }
  LOGD("Context initialized");

  g_vocab = llama_model_get_vocab(g_model);
  g_token_bos = llama_vocab_bos(g_vocab);
  g_token_eos = llama_vocab_eos(g_vocab);
  LOGD("Vocab loaded, BOS=%d, EOS=%d", g_token_bos, g_token_eos);

  return (jlong)(uintptr_t)g_ctx;
}

// ---------------- Unload Model ----------------
JNIEXPORT void JNICALL
Java_com_example_llmserverapp_LlamaBridge_unloadModel(JNIEnv *, jclass) {
  ScopedLock lock(g_mutex);
  LOGD("Unloading model...");
  if (g_ctx) {
    llama_free(g_ctx);
    g_ctx = nullptr;
    LOGD("Context freed");
  }
  if (g_model) {
    llama_model_free(g_model);
    g_model = nullptr;
    LOGD("Model freed");
  }
  g_vocab = nullptr;
  g_pos = 0;
  llama_backend_free();
  LOGD("Backend freed");
}

// ---------------- Reset Context ----------------
JNIEXPORT void JNICALL
Java_com_example_llmserverapp_LlamaBridge_resetContext(JNIEnv *, jclass) {
  ScopedLock lock(g_mutex);
  LOGD("Resetting context...");
  if (!g_model) {
    LOGD("No model loaded, cannot reset");
    return;
  }
  if (g_ctx) {
    llama_free(g_ctx);
    g_ctx = nullptr;
    LOGD("Old context freed");
  }
  g_ctx = llama_init_from_model(g_model, g_cparams);
  g_vocab = llama_model_get_vocab(g_model);
  g_pos = 0;
  LOGD("New context initialized, position reset to 0");
}

// ---------------- Tokenize ----------------
JNIEXPORT jintArray JNICALL Java_com_example_llmserverapp_LlamaBridge_tokenize(
    JNIEnv *env, jclass, jstring j_text, jboolean add_bos) {
  ScopedLock lock(g_mutex);
  if (!g_ctx || !g_vocab) {
    LOGD("Tokenize called but model not loaded");
    return nullptr;
  }

  std::string text = jstring_to_std(env, j_text);
  LOGD("Tokenizing text: %s", text.c_str());

  std::vector<llama_token> buf(text.size() + 8);
  int32_t n = llama_tokenize(g_vocab, text.c_str(), text.size(), buf.data(),
                             buf.size(), false, true);
  if (n < 0)
    n = 0;
  LOGD("Tokenized into %d tokens", n);

  std::vector<llama_token> out;
  if (add_bos && g_token_bos != -1)
    out.push_back(g_token_bos);
  out.insert(out.end(), buf.begin(), buf.begin() + n);

  jintArray arr = env->NewIntArray(out.size());
  if (!arr)
    return nullptr;
  env->SetIntArrayRegion(arr, 0, out.size(),
                         reinterpret_cast<const jint *>(out.data()));
  return arr;
}

// ---------------- Generate with Stats ----------------
JNIEXPORT jstring JNICALL
Java_com_example_llmserverapp_LlamaBridge_generateWithStats(JNIEnv *env, jclass,
                                                            jstring j_prompt) {

  ScopedLock lock(g_mutex);
  if (!g_ctx || !g_vocab) {
    LOGD("Generate called but model not loaded");
    return env->NewStringUTF("{\"error\":\"model not loaded\"}");
  }

  std::string prompt = apply_chat_template(jstring_to_std(env, j_prompt));
  LOGD("Prompt after template:\n%s", prompt.c_str());

  // Reset context for generation
  if (g_ctx) {
    llama_free(g_ctx);
    g_ctx = llama_init_from_model(g_model, g_cparams);
    g_vocab = llama_model_get_vocab(g_model);
    g_pos = 0;
    LOGD("Context reset for generation");
  }

  // Tokenize prompt
  std::vector<llama_token> tokens(prompt.size() + 8);
  int32_t n = llama_tokenize(g_vocab, prompt.c_str(), prompt.size(),
                             tokens.data(), tokens.size(), false, false);
  if (n < 0)
    n = 0;
  LOGD("Prompt tokenized into %d tokens", n);

  std::vector<llama_token> input_tokens;
  if (g_token_bos != -1)
    input_tokens.push_back(g_token_bos);
  input_tokens.insert(input_tokens.end(), tokens.begin(), tokens.begin() + n);

  llama_batch batch = llama_batch_init(input_tokens.size(), 0, 1);
  for (size_t i = 0; i < input_tokens.size(); ++i) {
    batch.token[i] = input_tokens[i];
    batch.pos[i] = g_pos + i;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = 1;
  }

  if (llama_decode(g_ctx, batch) != 0) {
    llama_batch_free(batch);
    LOGD("Error: decode prompt failed");
    return env->NewStringUTF("{\"error\":\"decode prompt failed\"}");
  }
  llama_batch_free(batch);
  g_pos += input_tokens.size();
  LOGD("Prompt decoded successfully, position now %d", g_pos);

  // Generation loop
  const int n_gen = 16;
  std::string generated;
  for (int step = 0; step < n_gen; ++step) {
    const float *logits = llama_get_logits(g_ctx);
    if (!logits) {
      LOGD("Logits null, stopping generation");
      break;
    }

    int n_vocab = llama_vocab_n_tokens(g_vocab);
    llama_token tok = LLAMA_TOKEN_NULL;
    float max_logit = -1e30f;

    for (int i = 0; i < n_vocab; ++i) {
      if (logits[i] > max_logit) {
        max_logit = logits[i];
        tok = (llama_token)i;
      }
    }

    if (tok == LLAMA_TOKEN_NULL || tok == g_token_eos) {
      LOGD("EOS or NULL token reached, stopping at step %d", step);
      break;
    }

    std::string piece = token_to_piece(g_vocab, tok, false);
    LOGD("Step %d, token %d -> piece '%s'", step, tok, piece.c_str());
    generated += piece;

    llama_batch b = llama_batch_init(1, 0, 1);
    b.token[0] = tok;
    b.pos[0] = g_pos;
    b.n_seq_id[0] = 1;
    b.seq_id[0][0] = 0;

    if (llama_decode(g_ctx, b) != 0) {
      llama_batch_free(b);
      LOGD("Error: decode generated token failed at step %d", step);
      break;
    }

    llama_batch_free(b);
    g_pos++;
  }

  LOGD("Generation complete, total generated chars: %zu", generated.size());
  std::string json = "{\"text\":\"" + generated + "\"}";
  return env->NewStringUTF(json.c_str());
}

} // extern "C"
