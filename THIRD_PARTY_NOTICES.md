Yara Android 0.3.7 — Third-party asset and model notices

1) Anime avatar
- File: app/src/main/assets/yara_anime_girl.vrm (downloaded by CI at build time)
- Source: GY19A/jev-stage, static/vrm/anime_girl.vrm
- Upstream revision: 14168affb0c187441c0fe5fe3e67fbaaa4b6d2cb
- Original model: VRoid Studio legacy base model, pixiv Inc.
- License: CC0 1.0 Universal, as documented by the upstream repository's NOTICE.
- Source code / viewer: https://github.com/GY19A/jev-stage
- Model license: https://creativecommons.org/publicdomain/zero/1.0/

2) Persian speech synthesis
- Runtime: sherpa-onnx v2.0.1 Android AAR, Apache License 2.0
- Voice: vits-piper-fa_IR-amir-medium, converted Piper model
- Model archive: https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-amir-medium.tar.bz2
- Model card and samples: https://k2-fsa.github.io/sherpa/onnx/tts/all/Persian/vits-piper-fa_IR-amir-medium.html
- Voice: fa_IR-amir-medium from rhasspy/piper-voices; its model card identifies the source speech dataset as CC0. The sherpa-onnx converted archive is used for Android inference.
- The model is downloaded to app-private storage on first use; generated speech is processed locally.

3) Viewer dependencies (bundled at build time)
- three.js 0.170.0: MIT — https://threejs.org/
- @pixiv/three-vrm 3.3.0: MIT — https://github.com/pixiv/three-vrm
- esbuild 0.24.2 (build-time bundler; not shipped as a runtime): MIT — https://github.com/evanw/esbuild

The JavaScript viewer bundle is generated from pinned dependency versions by the Android CI workflow. No CDN request is required by the avatar renderer at runtime.


4) Web search HTML parser
- Jsoup 1.18.3: MIT License — https://jsoup.org/license
- Used to parse DuckDuckGo HTML result titles, snippets, and source links.
