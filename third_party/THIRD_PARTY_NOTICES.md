# Feelime third-party notices

The audited component, version, archive hash, build recipe, APK path,
modification statement and corresponding-source method are recorded in
[`manifest.json`](manifest.json). Complete license and upstream NOTICE texts
are stored under [`licenses/`](licenses/); [`NOTICE.md`](NOTICE.md) is the
human-readable index.

The shipped closure includes librime and its static/embedded dependencies (including
RapidJSON 1.1.0 and its bundled msinttypes notice through OpenCC), Rime
prelude/essay/luna-pinyin data, Hunspell, the selected French and Russian
dictionaries, Mozc, the Mozc dictionary notices, and the Abseil, Protobuf and
zlib code materialized into Mozc's Android library. Build-only Bazel modules
and their exact cache records are bound by the two manifests under
`scripts/research/builder-provenance/`.

No code, native library, model, dictionary or extracted asset is taken from
any third-party input method. The double-pinyin schema was authored in this
repository and implements the natural-code (自然码) mapping, a public scheme.

The streaming bilingual Zipformer hotword tokenizer vocabulary is distributed
unchanged under Apache-2.0. Its upstream revision, SHA-256, matching model token
hash, and model-card license evidence are recorded in
[`asr-hotwords.json`](asr-hotwords.json). This small vocabulary is included in
both full and thin APKs and is distinct from the punctuation model vocabulary.

OkHttp 4.12.0 and Okio JVM 3.6.0 are distributed unmodified under Apache-2.0.
Their fixed coordinates, upstream sources and artifact hashes are in
[`model-network.json`](model-network.json); complete licenses are in `licenses/`.

Thin-build streaming ASR encoder downloads use the official Apache-2.0
sherpa-onnx mobile archive. Its URL, archive/entry sizes, SHA-256 values and
exact tar entry are recorded in [`model-archive.json`](model-archive.json).
The mixed Chinese-English OfflinePunctuation CT-Transformer is also sourced
from the official sherpa-onnx Apache-2.0 punctuation archive; its archive and
extracted model hashes are recorded in the same manifest. Only its
`model.int8.onnx` entry is packaged.
The archive is streamed through Apache Commons Compress 1.28.0, whose resolved
runtime dependencies are commons-codec 1.19.0, commons-io 2.20.0 and
commons-lang3 3.18.0. Their upstream sources, artifact hashes, and complete
license/NOTICE texts are recorded in `model-archive.json` and `licenses/`.
