#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <cstdio>
#include <jni.h>
#include <mutex>
#include <random>
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

static std::string json_escape(const std::string &s) {
  std::string out;
  out.reserve(s.size() + 16);
  for (char c : s) {
    switch (c) {
    case '\\':
      out += "\\\\";
      break;
    case '"':
      out += "\\\"";
      break;
    case '\n':
      out += "\\n";
      break;
    case '\r':
      out += "\\r";
      break;
    case '\t':
      out += "\\t";
      break;
    default:
      if (static_cast<unsigned char>(c) < 0x20) {
        char buf[7];
        std::snprintf(buf, sizeof(buf), "\\u%04x", (unsigned char)c);
        out += buf;
      } else {
        out += c;
      }
    }
  }
  return out;
}

// ---------------- Manual sampling (temp, top_p, top_k) ----------------
static llama_token sample_token_from_logits(const llama_vocab *vocab,
                                            const float *logits, float temp,
                                            float top_p, int top_k) {
  int n_vocab = llama_vocab_n_tokens(vocab);
  if (n_vocab <= 0 || !logits) {
    return LLAMA_TOKEN_NULL;
  }

  // Greedy fallback if temp <= 0
  if (temp <= 0.0f) {
    float max_logit = -1e30f;
    llama_token best = LLAMA_TOKEN_NULL;
    for (int i = 0; i < n_vocab; ++i) {
      if (logits[i] > max_logit) {
        max_logit = logits[i];
        best = (llama_token)i;
      }
    }
    return best;
  }

  struct Candidate {
    llama_token id;
    float logit;
    float p;
  };

  std::vector<Candidate> cands;
  cands.reserve(n_vocab);
  for (int i = 0; i < n_vocab; ++i) {
    Candidate c;
    c.id = (llama_token)i;
    c.logit = logits[i];
    c.p = 0.0f;
    cands.push_back(c);
  }

  // Top-k: keep only the k highest logits
  if (top_k > 0 && top_k < n_vocab) {
    std::nth_element(cands.begin(), cands.begin() + top_k, cands.end(),
                     [](const Candidate &a, const Candidate &b) {
                       return a.logit > b.logit;
                     });
    cands.resize(top_k);
  }

  // Temperature: scale logits
  for (auto &c : cands) {
    c.logit /= temp;
  }

  // Softmax over remaining candidates
  float max_logit = -1e30f;
  for (const auto &c : cands) {
    if (c.logit > max_logit) {
      max_logit = c.logit;
    }
  }

  double sum = 0.0;
  for (auto &c : cands) {
    double v = std::exp((double)c.logit - (double)max_logit);
    c.p = (float)v;
    sum += v;
  }
  if (sum <= 0.0) {
    // fallback: greedy
    llama_token best = LLAMA_TOKEN_NULL;
    float best_logit = -1e30f;
    for (const auto &c : cands) {
      if (c.logit > best_logit) {
        best_logit = c.logit;
        best = c.id;
      }
    }
    return best;
  }
  for (auto &c : cands) {
    c.p = (float)((double)c.p / sum);
  }

  // Top-p: sort by prob desc, keep smallest prefix with cumulative >= top_p
  if (top_p > 0.0f && top_p < 1.0f) {
    std::sort(cands.begin(), cands.end(),
              [](const Candidate &a, const Candidate &b) { return a.p > b.p; });
    double cum = 0.0;
    size_t cut = cands.size();
    for (size_t i = 0; i < cands.size(); ++i) {
      cum += cands[i].p;
      if (cum >= top_p) {
        cut = i + 1;
        break;
      }
    }
    if (cut < cands.size()) {
      cands.resize(cut);
    }
    // renormalize after truncation
    double sum2 = 0.0;
    for (auto &c : cands) {
      sum2 += c.p;
    }
    if (sum2 > 0.0) {
      for (auto &c : cands) {
        c.p = (float)(c.p / sum2);
      }
    }
  }

  // Sample from categorical distribution
  static thread_local std::mt19937 rng{std::random_device{}()};
  std::vector<double> weights;
  weights.reserve(cands.size());
  for (const auto &c : cands) {
    weights.push_back((double)c.p);
  }

  std::discrete_distribution<size_t> dist(weights.begin(), weights.end());
  size_t idx = dist(rng);
  if (idx >= cands.size()) {
    return LLAMA_TOKEN_NULL;
  }
  return cands[idx].id;
}

// ---------------- JNI Functions ----------------
extern "C" {

// ---------------- Load Model ----------------
JNIEXPORT jlong JNICALL Java_com_example_llmserverapp_LlamaBridge_loadModel(
        JNIEnv *env, jobject thiz, jstring j_model_path, jint j_threads)
 {
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
  g_token_bos = -1;
  g_token_eos = -1;

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
  g_cparams.n_threads = j_threads;
  g_cparams.n_threads_batch = j_threads;

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

extern "C" JNIEXPORT jint JNICALL
Java_com_example_llmserverapp_LlamaBridge_getThreadCount(JNIEnv*, jobject thiz) {
    return g_cparams.n_threads;
}


// ---------------- Unload Model ----------------
JNIEXPORT void JNICALL
Java_com_example_llmserverapp_LlamaBridge_unloadModel(JNIEnv *, jobject thiz) {
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
  g_token_bos = -1;
  g_token_eos = -1;
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
  if (!g_ctx) {
    LOGD("Failed to reinitialize context");
    return;
  }
  g_vocab = llama_model_get_vocab(g_model);
  g_pos = 0;
  g_token_bos = llama_vocab_bos(g_vocab);
  g_token_eos = llama_vocab_eos(g_vocab);
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
  int32_t n = llama_tokenize(g_vocab, text.c_str(), (int32_t)text.size(),
                             buf.data(), (int32_t)buf.size(), false, true);
  if (n < 0)
    n = 0;
  LOGD("Tokenized into %d tokens", n);

  std::vector<llama_token> out;
  out.reserve(n + 1);
  if (add_bos && g_token_bos != -1) {
    out.push_back(g_token_bos);
  }
  out.insert(out.end(), buf.begin(), buf.begin() + n);

  jintArray arr = env->NewIntArray((jsize)out.size());
  if (!arr)
    return nullptr;
  env->SetIntArrayRegion(arr, 0, (jsize)out.size(),
                         reinterpret_cast<const jint *>(out.data()));
  return arr;
}

// ---------------- Generate with Stats ----------------
JNIEXPORT jstring JNICALL
Java_com_example_llmserverapp_LlamaBridge_generateWithStats(JNIEnv *env, jobject thiz,
                                                            jstring j_prompt) {
  ScopedLock lock(g_mutex);
  if (!g_ctx || !g_vocab) {
    LOGD("Generate called but model not loaded");
    return env->NewStringUTF("{\"error\":\"model not loaded\"}");
  }

  std::string user = jstring_to_std(env, j_prompt);
  std::string prompt = apply_chat_template(user);
  LOGD("Prompt after template:\n%s", prompt.c_str());

  // Reset context for generation
  if (g_ctx) {
    llama_free(g_ctx);
    g_ctx = llama_init_from_model(g_model, g_cparams);
    if (!g_ctx) {
      LOGD("Failed to reinitialize context for generation");
      return env->NewStringUTF("{\"error\":\"context init failed\"}");
    }
    g_vocab = llama_model_get_vocab(g_model);
    g_pos = 0;
    g_token_bos = llama_vocab_bos(g_vocab);
    g_token_eos = llama_vocab_eos(g_vocab);
    LOGD("Context reset for generation");
  }

  // Tokenize prompt
  std::vector<llama_token> tokens(prompt.size() + 8);
  int32_t n =
      llama_tokenize(g_vocab, prompt.c_str(), (int32_t)prompt.size(),
                     tokens.data(), (int32_t)tokens.size(), false, false);
  if (n < 0)
    n = 0;
  LOGD("Prompt tokenized into %d tokens", n);

  std::vector<llama_token> input_tokens;
  input_tokens.reserve(n + 1);
  if (g_token_bos != -1) {
    input_tokens.push_back(g_token_bos);
  }
  input_tokens.insert(input_tokens.end(), tokens.begin(), tokens.begin() + n);

  if (input_tokens.empty()) {
    LOGD("No input tokens after tokenization");
    return env->NewStringUTF("{\"error\":\"empty prompt tokens\"}");
  }

  // Decode prompt
  llama_batch batch = llama_batch_init((int32_t)input_tokens.size(), 0, 1);
  for (size_t i = 0; i < input_tokens.size(); ++i) {
    batch.token[i] = input_tokens[i];
    batch.pos[i] = g_pos + (int32_t)i;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = 1;
  }
  batch.n_tokens = (int32_t)input_tokens.size();

  if (llama_decode(g_ctx, batch) != 0) {
    llama_batch_free(batch);
    LOGD("Error: decode prompt failed");
    return env->NewStringUTF("{\"error\":\"decode prompt failed\"}");
  }
  llama_batch_free(batch);
  g_pos += (int32_t)input_tokens.size();
  LOGD("Prompt decoded successfully, position now %d", g_pos);

  // Generation loop with temp / top_p / top_k
  const int n_gen = 16;
  const float temp = 0.7f;
  const float top_p = 0.9f;
  const int top_k = 40;

  std::string generated;
  int generated_tokens = 0;

  for (int step = 0; step < n_gen; ++step) {
    const float *logits = llama_get_logits(g_ctx);
    if (!logits) {
      LOGD("Logits null, stopping generation");
      break;
    }

    llama_token tok =
        sample_token_from_logits(g_vocab, logits, temp, top_p, top_k);

    if (tok == LLAMA_TOKEN_NULL || tok == g_token_eos) {
      LOGD("EOS or NULL token reached, stopping at step %d", step);
      break;
    }

    std::string piece = token_to_piece(g_vocab, tok, false);
    LOGD("Step %d, token %d -> piece '%s'", step, tok, piece.c_str());
    generated += piece;
    generated_tokens++;

    llama_batch b = llama_batch_init(1, 0, 1);
    b.token[0] = tok;
    b.pos[0] = g_pos;
    b.n_seq_id[0] = 1;
    b.seq_id[0][0] = 0;
    b.logits[0] = 1;
    b.n_tokens = 1;

    if (llama_decode(g_ctx, b) != 0) {
      llama_batch_free(b);
      LOGD("Error: decode generated token failed at step %d", step);
      break;
    }

    llama_batch_free(b);
    g_pos++;
  }

  LOGD("Generation complete, total generated chars: %zu", generated.size());
  std::string escaped = json_escape(generated);
  std::string json = "{\"text\":\"" + escaped +
                     "\",\"generated\":" + std::to_string(generated_tokens) +
                     "}";

  return env->NewStringUTF(json.c_str());
}

} // extern "C"
