import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { VRMLoaderPlugin, VRMUtils } from "@pixiv/three-vrm";

const host = document.getElementById("stage");
const hint = document.getElementById("hint");
const errorBox = document.getElementById("error");

function showError(message) {
  errorBox.style.display = "flex";
  errorBox.textContent = message;
  hint.style.display = "none";
}

let speaking = false;
let ready = false;
let vrm = null;
let mouthPhase = 0;

try {
  if (!("WebGLRenderingContext" in window)) {
    throw new Error("WebGL is unavailable in this WebView.");
  }

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(27, 1, 0.1, 100);
  camera.position.set(0, 1.43, 1.52);

  const renderer = new THREE.WebGLRenderer({
    alpha: true,
    antialias: true,
    powerPreference: "low-power"
  });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
  renderer.setSize(Math.max(1, host.clientWidth), Math.max(1, host.clientHeight));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.1;
  host.appendChild(renderer.domElement);

  scene.add(new THREE.HemisphereLight(0xdbe7ff, 0x22223b, 2.0));
  const key = new THREE.DirectionalLight(0xffe4ee, 2.5);
  key.position.set(1.5, 2.5, 3);
  scene.add(key);
  const rim = new THREE.DirectionalLight(0x7b8dff, 2.2);
  rim.position.set(-2, 1, -2);
  scene.add(rim);

  const loader = new GLTFLoader();
  loader.register(parser => new VRMLoaderPlugin(parser));
  loader.load(
    "./yara_anime_girl.vrm",
    gltf => {
      try {
        vrm = gltf.userData.vrm;
        if (!vrm) throw new Error("VRM avatar metadata missing");
        VRMUtils.rotateVRM0(vrm);
        scene.add(vrm.scene);

        const bounds = new THREE.Box3().setFromObject(vrm.scene);
        const height = bounds.max.y - bounds.min.y;
        if (!Number.isFinite(height) || height <= 0) {
          throw new Error("VRM avatar has invalid dimensions");
        }
        const targetY = bounds.min.y + height * 0.83;
        camera.position.set(0, targetY, Math.max(1.05, height * 0.78));
        camera.lookAt(0, targetY, 0);
        ready = true;
        hint.textContent = "یارا";
      } catch (error) {
        console.error("Yara avatar initialization failed", error);
        showError("راه‌اندازی آواتار سه‌بعدی ناموفق بود.");
      }
    },
    undefined,
    error => {
      console.error("Yara VRM load failed", error);
      showError("بارگذاری مدل سه‌بعدی ناموفق بود. فایل مدل داخل برنامه را بررسی کنید.");
    }
  );

  window.yaraSetState = (isSpeaking, hasConversation = false) => {
    speaking = Boolean(isSpeaking);
    if (ready) {
      hint.textContent = speaking
        ? "در حال صحبت…"
        : hasConversation ? "در کنار شما" : "یارا";
    }
  };

  const clock = new THREE.Clock();
  function animate() {
    requestAnimationFrame(animate);
    const dt = Math.min(clock.getDelta(), 0.05);
    const t = clock.elapsedTime;

    if (vrm) {
      vrm.update(dt);
      const expressions = vrm.expressionManager;
      if (expressions) {
        const blinkPhase = (t % 4.2) - 4.0;
        let blinkValue = 0;
        if (blinkPhase >= 0 && blinkPhase < 0.16) {
          const progress = blinkPhase / 0.16;
          blinkValue = progress < 0.5 ? progress * 2 : (1 - progress) * 2;
        }
        expressions.setValue("blink", blinkValue);
        mouthPhase += dt * 13;
        expressions.setValue("aa", speaking ? 0.12 + 0.35 * Math.abs(Math.sin(mouthPhase)) : 0.015);
        expressions.setValue("ih", speaking ? 0.08 + 0.15 * Math.abs(Math.sin(mouthPhase * 0.7)) : 0);
      }
      const neck = vrm.humanoid?.getNormalizedBoneNode("neck");
      if (neck) {
        neck.rotation.z = Math.sin(t * 0.55) * 0.025;
        neck.rotation.y = Math.sin(t * 0.32) * 0.035;
      }
    }
    renderer.render(scene, camera);
  }
  animate();

  const resize = () => {
    const width = Math.max(1, host.clientWidth);
    const height = Math.max(1, host.clientHeight);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
    renderer.setSize(width, height);
  };
  if ("ResizeObserver" in window) {
    new ResizeObserver(resize).observe(host);
  } else {
    window.addEventListener("resize", resize);
  }

  renderer.domElement.addEventListener("webglcontextlost", event => {
    event.preventDefault();
    showError("ارتباط گرافیکی WebGL قطع شد. صفحه را دوباره باز کنید.");
  });
} catch (error) {
  console.error("Yara WebGL initialization failed", error);
  showError("نمایش سه‌بعدی در این دستگاه در دسترس نیست: " + (error?.message || "خطای ناشناخته"));
}
