// Feelime's original minimal C ABI for the isolated Android feasibility spike.
// Hunspell itself remains unmodified and is built from the pinned upstream tree.

#include <cstddef>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include "hunspell.hxx"
#include "hunversion.h"

#define FEELIME_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

struct FeelimeHunspell {
  explicit FeelimeHunspell(const char* aff_path, const char* dic_path)
      : engine(aff_path, dic_path) {}

  Hunspell engine;
};

bool copy_result(const std::string& value, char* output, std::size_t output_size) {
  if (output == nullptr || output_size == 0 || value.size() >= output_size) {
    return false;
  }
  std::memcpy(output, value.data(), value.size());
  output[value.size()] = '\0';
  return true;
}

}  // namespace

FEELIME_EXPORT const char* feelime_hunspell_version() {
  return HUNSPELL_VERSION_STRING;
}

FEELIME_EXPORT void* feelime_hunspell_create(const char* aff_path,
                                           const char* dic_path) {
  if (aff_path == nullptr || dic_path == nullptr) {
    return nullptr;
  }
  try {
    return new FeelimeHunspell(aff_path, dic_path);
  } catch (...) {
    return nullptr;
  }
}

FEELIME_EXPORT void feelime_hunspell_destroy(void* handle) {
  delete static_cast<FeelimeHunspell*>(handle);
}

FEELIME_EXPORT int feelime_hunspell_spell(void* handle, const char* word) {
  if (handle == nullptr || word == nullptr) {
    return -1;
  }
  return static_cast<FeelimeHunspell*>(handle)->engine.spell(std::string(word)) ? 1 : 0;
}

// Returns UTF-8 suggestions separated by '\n'. The output is deterministic and
// bounded so the JNI layer never owns allocator memory from this library.
FEELIME_EXPORT int feelime_hunspell_suggest(void* handle,
                                         const char* word,
                                         char* output,
                                         std::size_t output_size) {
  if (handle == nullptr || word == nullptr || output == nullptr || output_size == 0) {
    return -1;
  }
  try {
    const std::vector<std::string> suggestions =
        static_cast<FeelimeHunspell*>(handle)->engine.suggest(std::string(word));
    std::string joined;
    for (const std::string& suggestion : suggestions) {
      if (!joined.empty()) {
        joined.push_back('\n');
      }
      joined.append(suggestion);
    }
    if (!copy_result(joined, output, output_size)) {
      output[0] = '\0';
      return -2;
    }
    return static_cast<int>(suggestions.size());
  } catch (...) {
    output[0] = '\0';
    return -3;
  }
}
