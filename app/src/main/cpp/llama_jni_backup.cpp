#include <android/log.h>
#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

#include "llama/llama.h"

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

// Load model
JNIEXPORT jlong JNICALL Java_com_example_llmserverapp_LlamaBridge_loadModel(
    JNIEnv *env, jclass, jstring j_model_path) {
  ScopedLock lock(g_mutex);

  if (g_ctx) {
    llama_free(g_ctx);
    g_ctx = nullptr;
  }
  if (g_model) {
    llama_model_free(g_model);
    g_model = nullptr;
  }
  g_vocab = nullptr;
  g_pos = 0;

  std::string path = jstring_to_std(env, j_model_path);

  llama_backend_init();
  llama_model_params mparams = llama_model_default_params();
  mparams.vocab_only = false;

  g_model = llama_model_load_from_file(path.c_str(), mparams);
  if (!g_model)
    return 0;

  g_cparams = llama_context_default_params();
  g_cparams.n_ctx = 2048;
  g_cparams.n_threads = 4;

  g_ctx = llama_init_from_model(g_model, g_cparams);
  if (!g_ctx) {
    llama_model_free(g_model);
    g_model = nullptr;
    return 0;
  }

  g_vocab = llama_model_get_vocab(g_model);
  g_token_bos = llama_vocab_bos(g_vocab);
  g_token_eos = llama_vocab_eos(g_vocab);

  return (jlong)(uintptr_t)g_ctx;
}

// Unload model
JNIEXPORT void JNICALL
Java_com_example_llmserverapp_LlamaBridge_unloadModel(JNIEnv *, jclass) {
  ScopedLock lock(g_mutex);
  if (g_ctx) {
    llama_free(g_ctx);
    g_ctx = nullptr;
  }
  if (g_model) {
    llama_model_free(g_model);
    g_model = nullptr;
  }
  g_vocab = nullptr;
  g_pos = 0;
  llama_backend_free();
}

// Reset context
JNIEXPORT void JNICALL
Java_com_example_llmserverapp_LlamaBridge_resetContext(JNIEnv *, jclass) {
  ScopedLock lock(g_mutex);
  if (!g_model)
    return;
  if (g_ctx) {
    llama_free(g_ctx);
    g_ctx = nullptr;
  }
  g_ctx = llama_init_from_model(g_model, g_cparams);
  g_vocab = llama_model_get_vocab(g_model);
  g_pos = 0;
}

// Tokenize text
JNIEXPORT jintArray JNICALL Java_com_example_llmserverapp_LlamaBridge_tokenize(
    JNIEnv *env, jclass, jstring j_text, jboolean add_bos) {
  ScopedLock lock(g_mutex);
  if (!g_ctx || !g_vocab)
    return nullptr;

  std::string text = jstring_to_std(env, j_text);
  std::vector<llama_token> buf(text.size() + 8);
  int32_t n = llama_tokenize(g_vocab, text.c_str(), text.size(), buf.data(),
                             buf.size(), false, true);
  if (n < 0)
    n = 0;

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

JNIEXPORT jstring JNICALL
Java_com_example_llmserverapp_LlamaBridge_generateWithStats(JNIEnv *env, jclass,
                                                            jstring j_prompt) {
  ScopedLock lock(g_mutex);
  if (!g_ctx || !g_vocab)
    return env->NewStringUTF("{\"error\":\"model not loaded\"}");

  std::string prompt = apply_chat_template(jstring_to_std(env, j_prompt));

  // Reset context
  if (g_ctx) {
    llama_free(g_ctx);
    g_ctx = llama_init_from_model(g_model, g_cparams);
    g_vocab = llama_model_get_vocab(g_model);
    g_pos = 0;
  }

  // Tokenize prompt
  std::vector<llama_token> tokens(prompt.size() + 8);
  int32_t n = llama_tokenize(g_vocab, prompt.c_str(), prompt.size(),
                             tokens.data(), tokens.size(), false, true);
  if (n < 0)
    n = 0;

  std::vector<llama_token> input_tokens;
  if (g_token_bos != -1)
    input_tokens.push_back(g_token_bos);
  input_tokens.insert(input_tokens.end(), tokens.begin(), tokens.begin() + n);

  // ---------------- Evaluate prompt ----------------
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
    return env->NewStringUTF("{\"error\":\"decode prompt failed\"}");
  }

  llama_batch_free(batch);
  g_pos += input_tokens.size();

  // ---------------- Generation loop ----------------
  const int n_gen = 16;
  std::string generated;

  for (int step = 0; step < n_gen; ++step) {
    const float *logits = llama_get_logits(g_ctx);
    if (!logits)
      break;

    int n_vocab = llama_vocab_n_tokens(g_vocab);
    llama_token tok = LLAMA_TOKEN_NULL;
    float max_logit = -1e30f;

    for (int i = 0; i < n_vocab; ++i) {
      if (logits[i] > max_logit) {
        max_logit = logits[i];
        tok = (llama_token)i;
      }
    }

    if (tok == LLAMA_TOKEN_NULL || tok == g_token_eos)
      break;

    generated += token_to_piece(g_vocab, tok, false);

    // Feed token back
    llama_batch b = llama_batch_init(1, 0, 1);
    b.token[0] = tok;
    b.pos[0] = g_pos;
    b.n_seq_id[0] = 1;
    b.seq_id[0][0] = 0;

    if (llama_decode(g_ctx, b) != 0) {
      llama_batch_free(b);
      break;
    }

    llama_batch_free(b);
    g_pos++;
  }

  std::string json = "{\"text\":\"" + generated + "\"}";
  return env->NewStringUTF(json.c_str());
}

// Generate with stats using llama_sampler_apply
/*JNIEXPORT jstring JNICALL
Java_com_example_llmserverapp_LlamaBridge_generateWithStats(JNIEnv *env, jclass,
                                                            jstring j_prompt) {

  ScopedLock lock(g_mutex);
  if (!g_ctx || !g_vocab)
    return env->NewStringUTF("{\"error\":\"model not loaded\"}");

  std::string prompt = apply_chat_template(jstring_to_std(env, j_prompt));

  // Reset context
  if (g_ctx) {
    llama_free(g_ctx);
    g_ctx = llama_init_from_model(g_model, g_cparams);
    g_vocab = llama_model_get_vocab(g_model);
    g_pos = 0;
  }

  // Tokenize prompt
  std::vector<llama_token> buf(prompt.size() + 8);
  int32_t n = llama_tokenize(g_vocab, prompt.c_str(), prompt.size(), buf.data(),
                             buf.size(), false, true);
  if (n < 0)
    n = 0;

  std::vector<llama_token> tokens;
  if (g_token_bos != -1)
    tokens.push_back(g_token_bos);
  tokens.insert(tokens.end(), buf.begin(), buf.begin() + n);

  // Feed prompt
  llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
  for (size_t i = 0; i < tokens.size(); ++i) {
    batch.token[i] = tokens[i];
    batch.pos[i] = g_pos + i;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = 1;
  }

  if (llama_decode(g_ctx, batch) != 0) {
    llama_batch_free(batch);
    return env->NewStringUTF("{\"error\":\"decode prompt failed\"}");
  }
  llama_batch_free(batch);
  g_pos += tokens.size();

  // ------------------ Generation Loop ------------------
  const int n_gen = 16;
  std::string generated;

  for (int step = 0; step < n_gen; ++step) {
    const float *logits = llama_get_logits(g_ctx);
    if (!logits)
      break;

    int n_vocab = llama_vocab_n_tokens(g_vocab);
    llama_token tok = g_token_eos;
    float max_logit = -1e30f;

    for (int i = 0; i < n_vocab; ++i) {
      if (logits[i] > max_logit) {
        max_logit = logits[i];
        tok = (llama_token)i;
      }
    }

    if (tok == LLAMA_TOKEN_NULL || tok == g_token_eos)
      break;

    generated += token_to_piece(g_vocab, tok, false);

    // Feed token back into context
    llama_batch b = llama_batch_init(1, 0, 1);
    b.token[0] = tok;
    b.pos[0] = g_pos;
    b.n_seq_id[0] = 1;
    b.seq_id[0][0] = 0;
    if (llama_decode(g_ctx, b) != 0) {
      llama_batch_free(b);
      break;
    }
    llama_batch_free(b);
    g_pos++;
  }

  std::string json = "{\"text\":\"" + generated + "\"}";
  return env->NewStringUTF(json.c_str());
}*/

} // extern "C"
