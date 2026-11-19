# Yara Avatar Identity Specification

Status: reference images received in the development conversation; the binary image files are not yet committed to this repository.

## Identity source of truth

The two user-supplied reference images are the authoritative visual identity for Yara. They depict the same stylized young woman in several expressions and poses. The first image is a three-panel composition (approach/affectionate closed-eye smile/bright open-eye expression); the second is a three-panel comic-style composition with profile, closed-eye smile, and open-mouth happy expression.

Do not use the cat, scenery, clothing, panel layout, speech bubbles, or lighting as mandatory avatar identity features. They are scene/context elements unless separately requested.

## Stable facial and appearance attributes

Preserve these attributes across the 3D model, renders, and animation:
- Dark brown to near-black hair, long, loosely tied/back gathered, with soft irregular bangs and face-framing strands.
- Large transparent/light-clear eyeglass frames with a delicate bridge.
- Large expressive gray-green eyes, with dark lashes and natural eyelid shape.
- Light warm skin tone and soft blush.
- Small delicate nose; softly defined jaw and chin; natural pink lips.
- Youthful adult appearance; do not make the character look childlike.
- Overall gentle, warm, calm, empathetic expression.

## Variable presentation attributes

Clothing, scarf, pose, gaze direction, facial expression, lighting, and background may vary. The burgundy outerwear and gray knitted scarf are recognizable reference styling, but are not required in every UI state.

## Required facial expression targets

Use the reference panels to guide:
1. neutral/attentive, eyes open;
2. relaxed closed-eye smile;
3. bright, happy open-eye expression;
4. speaking/open-mouth expression;
5. gentle empathetic expression.

## Modeling and rigging acceptance criteria

- Create a custom Yara mesh and facial design from the supplied references; a generic stock VRM is not an acceptable final identity model.
- Preserve facial proportions and glasses as modeled geometry/accessories.
- Provide a humanoid rig plus facial blendshapes/VRM expressions for blink, happy, relaxed, surprised, concerned, and phoneme/viseme mouth shapes where feasible.
- Add subtle eye-look, head, breathing, and idle-body motion without changing the authored rest pose.
- Audio-reactive mouth amplitude is an interim fallback only; phoneme/viseme-driven lip sync is the target.
- Export GLB or VRM, optimize for mid-range Android hardware, and validate on a physical Galaxy A52-class device.

## Repository integration note

The current renderer in `app/src/main/assets/yara_avatar.js` loads `yara_anime_girl.vrm`. The current CI workflow downloads a generic CC0 anime VRM from an external repository. That asset is only a temporary renderer/build placeholder and must not be represented as the final Yara character. Replace it only after the custom Yara model has been created, licensed/owned appropriately, and validated. Keep the renderer fallback behavior while the custom asset is being prepared.

## Reference file handling

The source images are currently attached to the ChatGPT development conversation as:
- `1000199120.jpg`
- `1000199116.jpg`

They must be copied into a controlled project asset location before a reproducible model-generation workflow can consume them. Do not commit user reference images to a public repository without explicit confirmation.
