package com.ultrax26.recorder.effects.gl

/** GLSL ES 3.00 sources. Scene convention: texcoord (0,0) = image top-left; positions in [0,1]² y-down. */
object Shaders {
    const val VERTEX = """#version 300 es
        in vec2 aPos; in vec2 aTex;
        uniform float uFlipY;      // 1 = window output (clip y up), 0 = FBO pass
        uniform float uMirrorX;    // 1 = mirror horizontally (front camera preview)
        uniform mat4 uStMatrix;    // camera SurfaceTexture transform (identity otherwise)
        out vec2 vTex;
        void main() {
            vec2 p = aPos;
            if (uMirrorX > 0.5) p.x = 1.0 - p.x;
            float y = uFlipY > 0.5 ? 1.0 - 2.0 * p.y : 2.0 * p.y - 1.0;
            gl_Position = vec4(2.0 * p.x - 1.0, y, 0.0, 1.0);
            vTex = (uStMatrix * vec4(aTex, 0.0, 1.0)).xy;
        }
    """

    const val VERTEX_SOLID = """#version 300 es
        in vec2 aPos;
        uniform float uFlipY;
        void main() {
            float y = uFlipY > 0.5 ? 1.0 - 2.0 * aPos.y : 2.0 * aPos.y - 1.0;
            gl_Position = vec4(2.0 * aPos.x - 1.0, y, 0.0, 1.0);
        }
    """

    const val FRAG_SOLID = """#version 300 es
        precision mediump float; uniform vec4 uColor; out vec4 o; void main() { o = uColor; }
    """

    const val FRAG_EXTERNAL = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        in vec2 vTex; uniform samplerExternalOES uTex; out vec4 o;
        void main() { o = texture(uTex, vTex); }
    """

    const val FRAG_COPY = """#version 300 es
        precision mediump float;
        in vec2 vTex; uniform sampler2D uTex; uniform float uOpacity; out vec4 o;
        void main() { vec4 c = texture(uTex, vTex); o = vec4(c.rgb, c.a) * uOpacity; }
    """

    /** Separable Gaussian blur (9 taps), direction in texels. */
    const val FRAG_BLUR = """#version 300 es
        precision mediump float;
        in vec2 vTex; uniform sampler2D uTex; uniform vec2 uDir; out vec4 o;
        void main() {
            vec4 c = texture(uTex, vTex) * 0.2270270270;
            c += texture(uTex, vTex + uDir * 1.3846153846) * 0.3162162162;
            c += texture(uTex, vTex - uDir * 1.3846153846) * 0.3162162162;
            c += texture(uTex, vTex + uDir * 3.2307692308) * 0.0702702703;
            c += texture(uTex, vTex - uDir * 3.2307692308) * 0.0702702703;
            o = c;
        }
    """

    /** Common helpers shared by the big passes. */
    private const val COMMON = """
        float hash21(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
        float noise2(vec2 p) {
            vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash21(i), hash21(i + vec2(1, 0)), u.x), mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), u.x), u.y);
        }
        float fbm(vec2 p) { float v = 0.0, a = 0.5; for (int i = 0; i < 5; i++) { v += a * noise2(p); p *= 2.02; a *= 0.5; } return v; }
        float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
        vec3 rgb2hsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y); float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }
        vec3 hsv2rgb(vec3 c) { vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0); vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www); return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y); }
        // scene uv (sensor orientation, y down) -> upright analysis uv, given rotation code 0..3 (0/90/180/270) and fit scale
        vec2 toUpright(vec2 p, int rot, vec2 fit) {
            vec2 q;
            if (rot == 1) q = vec2(1.0 - p.y, p.x);
            else if (rot == 2) q = vec2(1.0 - p.x, 1.0 - p.y);
            else if (rot == 3) q = vec2(p.y, 1.0 - p.x);
            else q = p;
            return 0.5 + (q - 0.5) * fit;
        }
    """

    /** Background composite: person from the frame over a background (blur / color / texture), light wrap. */
    val FRAG_COMPOSITE = """#version 300 es
        precision mediump float;
        in vec2 vTex;
        uniform sampler2D uFrame;      // camera frame (scene)
        uniform sampler2D uBackground; // background already rendered in scene orientation
        uniform sampler2D uMask;       // RGBA masks, upright orientation
        uniform int uRot; uniform vec2 uFit; uniform float uFlipMask;
        uniform float uFeather; uniform float uEdgeShift; uniform float uLightWrap; uniform float uHasMask;
        uniform vec2 uMaskTexel;
        out vec4 o;
        $COMMON
        float personAt(vec2 uv) { vec2 m = toUpright(uv, uRot, uFit); if (uFlipMask > 0.5) m.y = 1.0 - m.y; return texture(uMask, clamp(m, 0.0, 1.0)).r; }
        void main() {
            vec4 f = texture(uFrame, vTex);
            vec4 b = texture(uBackground, vTex);
            if (uHasMask < 0.5) { o = f; return; }
            // 5-tap softened mask
            float m = personAt(vTex) * 0.4 + personAt(vTex + vec2(uMaskTexel.x, 0.0)) * 0.15 + personAt(vTex - vec2(uMaskTexel.x, 0.0)) * 0.15
                    + personAt(vTex + vec2(0.0, uMaskTexel.y)) * 0.15 + personAt(vTex - vec2(0.0, uMaskTexel.y)) * 0.15;
            float lo = 0.5 - uEdgeShift * 0.3 - uFeather * 0.25;
            float hi = 0.5 - uEdgeShift * 0.3 + uFeather * 0.25;
            float a = smoothstep(lo, hi, m);
            vec3 wrapped = mix(f.rgb, f.rgb * 0.7 + b.rgb * 0.3, uLightWrap * (1.0 - smoothstep(0.0, 0.35, a - 0.65)) * a);
            o = vec4(mix(b.rgb, wrapped, a), 1.0);
        }
    """

    /** Procedural animated backgrounds. */
    val FRAG_PROCEDURAL = """#version 300 es
        precision highp float;
        in vec2 vTex; uniform float uTime; uniform int uMode; uniform vec2 uParallax; uniform float uAspect; uniform int uRot;
        out vec4 o;
        $COMMON
        vec3 palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) { return a + b * cos(6.28318 * (c * t + d)); }
        void main() {
            // work in upright coordinates so scenes look upright on screen
            vec2 up = toUpright(vTex, uRot, vec2(1.0));
            float A = (uRot == 1 || uRot == 3) ? 1.0 / uAspect : uAspect;
            vec2 p = (up - 0.5) * vec2(A, 1.0) + uParallax * 0.15;
            float t = uTime;
            vec3 col = vec3(0.0);
            if (uMode == 0) { // hyperspace
                vec2 d = p; float r = length(d) + 1e-3; float ang = atan(d.y, d.x);
                float stars = 0.0;
                for (int i = 0; i < 3; i++) { float fi = float(i); float k = hash21(vec2(floor(ang * 40.0 + fi * 7.0), fi)); float s = fract(k * 13.0 - t * (0.4 + k) ); stars += smoothstep(0.02, 0.0, abs(r - s * 1.2 - 0.02)) * (0.4 + 0.6 * k) * smoothstep(0.0, 0.3, s); }
                col = vec3(0.02, 0.02, 0.06) + stars * vec3(0.7, 0.8, 1.0) + smoothstep(0.6, 0.0, r) * vec3(0.05, 0.1, 0.25);
            } else if (uMode == 1) { // nebula
                float n = fbm(p * 2.2 + t * 0.03); float n2 = fbm(p * 4.0 - t * 0.02 + 3.0);
                col = mix(vec3(0.03, 0.0, 0.08), vec3(0.45, 0.1, 0.6), n) + n2 * n2 * vec3(0.2, 0.5, 0.9) * 0.9;
                col += step(0.995, hash21(floor(up * 300.0))) * 0.8;
            } else if (uMode == 2) { // aurora
                float sky = smoothstep(-0.5, 0.5, -p.y);
                col = mix(vec3(0.0, 0.02, 0.08), vec3(0.02, 0.05, 0.15), sky);
                for (int i = 0; i < 3; i++) { float fi = float(i); float w = sin(p.x * (2.0 + fi) + t * (0.3 + fi * 0.1) + fbm(vec2(p.x * 2.0, t * 0.1 + fi)) * 3.0) * 0.15 - 0.15 + fi * 0.08; float band = exp(-pow((p.y - w) * (6.0 + fi * 2.0), 2.0)); col += band * mix(vec3(0.1, 0.9, 0.5), vec3(0.6, 0.2, 0.9), fi * 0.5) * 0.45; }
                col += step(0.996, hash21(floor(up * 400.0))) * 0.7 * step(p.y, 0.1);
            } else if (uMode == 3) { // ocean sunset
                float horizon = 0.05; vec3 sky = mix(vec3(0.98, 0.55, 0.2), vec3(0.25, 0.1, 0.35), smoothstep(horizon, -0.5, p.y));
                float sun = smoothstep(0.16, 0.14, length(p - vec2(0.0, horizon + 0.05)));
                sky += sun * vec3(1.0, 0.85, 0.5);
                vec3 sea = vec3(0.1, 0.2, 0.4) + 0.25 * (sin(p.x * 30.0 + t * 2.0 + p.y * 80.0) * 0.5 + 0.5) * smoothstep(0.0, 0.4, p.y);
                sea += vec3(1.0, 0.7, 0.4) * smoothstep(0.08, 0.0, abs(p.x)) * (0.3 + 0.2 * sin(t * 3.0 + p.y * 60.0)) * smoothstep(horizon, horizon + 0.4, p.y);
                col = p.y > horizon ? sea : sky;
            } else if (uMode == 4) { // cyber grid tunnel
                float depth = 1.0 / (abs(p.y) + 0.05); vec2 g = vec2(p.x * depth, depth + t * 2.0);
                float line = smoothstep(0.9, 1.0, max(abs(fract(g.x) - 0.5) * 2.0, abs(fract(g.y) - 0.5) * 2.0));
                col = vec3(0.02, 0.0, 0.06) + line * mix(vec3(1.0, 0.1, 0.6), vec3(0.1, 0.8, 1.0), 0.5 + 0.5 * sin(t + p.x)) * smoothstep(0.0, 0.25, abs(p.y));
                col += smoothstep(0.3, 0.0, length(p - vec2(0.0, 0.1))) * vec3(0.9, 0.2, 0.7) * 0.6;
            } else if (uMode == 5) { // digital rain
                vec2 c = vec2(floor(up.x * 40.0), 0.0); float speed = 0.5 + hash21(c) * 1.5; float y = fract(up.y * 1.0 + t * speed * 0.3 + hash21(c + 1.0));
                float trail = pow(y, 3.0); float glyph = step(0.5, hash21(vec2(c.x, floor(up.y * 40.0 + t * speed * 12.0))));
                col = vec3(0.0, 0.02, 0.0) + trail * glyph * vec3(0.2, 1.0, 0.3);
            } else if (uMode == 6) { // disco
                col = vec3(0.02);
                for (int i = 0; i < 8; i++) { float fi = float(i); vec2 c = vec2(sin(t * 0.7 + fi * 2.1), cos(t * 0.9 + fi * 1.3)) * 0.4; float d = length(p - c); col += palette(fi / 8.0, vec3(0.5), vec3(0.5), vec3(1.0), vec3(0.0, 0.33, 0.67)) * smoothstep(0.35, 0.0, d) * 0.5; }
                float tiles = step(0.95, fract(up.x * 30.0)) + step(0.95, fract(up.y * 30.0)); col *= 1.0 - tiles * 0.3;
            } else if (uMode == 7) { // clouds
                float n = fbm(p * 3.0 + vec2(t * 0.05, 0.0)); col = mix(vec3(0.35, 0.6, 0.95), vec3(1.0), smoothstep(0.45, 0.75, n));
            } else if (uMode == 8) { // underwater
                float c1 = sin(p.x * 12.0 + t + fbm(p * 3.0 + t * 0.2) * 4.0); float c2 = sin(p.y * 10.0 - t * 0.7 + fbm(p * 2.0 - t * 0.1) * 4.0);
                float caustic = pow(abs(c1 * c2), 3.0);
                col = mix(vec3(0.0, 0.15, 0.3), vec3(0.0, 0.5, 0.7), smoothstep(0.5, -0.5, p.y)) + caustic * vec3(0.5, 0.9, 1.0) * 0.5;
                float bubble = smoothstep(0.02, 0.0, length(fract(p * 6.0 + vec2(0.0, -t * 0.4)) - 0.5) - 0.02 * hash21(floor(p * 6.0 + vec2(0.0, -t * 0.4)))); col += bubble * 0.6;
            } else if (uMode == 9) { // lava lamp
                float v = 0.0; for (int i = 0; i < 5; i++) { float fi = float(i); vec2 c = vec2(sin(t * 0.3 + fi * 1.7) * 0.35, cos(t * 0.25 + fi * 2.3) * 0.4); v += 0.03 / (dot(p - c, p - c) + 0.01); }
                col = mix(vec3(0.15, 0.0, 0.2), vec3(1.0, 0.35, 0.1), smoothstep(0.6, 1.6, v)) + smoothstep(1.6, 2.4, v) * vec3(1.0, 0.8, 0.3);
            } else if (uMode == 10) { // snowfall
                col = mix(vec3(0.05, 0.08, 0.15), vec3(0.15, 0.2, 0.35), up.y);
                for (int i = 0; i < 3; i++) { float fi = float(i) + 1.0; vec2 q = up * (8.0 * fi) + vec2(sin(t * 0.5 + fi) * 0.5, t * (0.8 + fi * 0.3)); vec2 id = floor(q); float d = length(fract(q) - 0.5 - 0.3 * (hash21(id) - 0.5)); col += smoothstep(0.08 / fi, 0.0, d) * (0.9 / fi); }
            } else if (uMode == 11) { // fireflies
                col = mix(vec3(0.0, 0.03, 0.02), vec3(0.02, 0.1, 0.05), smoothstep(-0.5, 0.5, p.y));
                for (int i = 0; i < 12; i++) { float fi = float(i); vec2 c = vec2(sin(t * (0.2 + fi * 0.05) + fi) * 0.5 * A, cos(t * (0.17 + fi * 0.04) + fi * 2.0) * 0.45); float d = length(p - c); float blink = 0.5 + 0.5 * sin(t * 3.0 + fi * 5.0); col += smoothstep(0.03, 0.0, d) * blink * vec3(0.9, 1.0, 0.3); col += smoothstep(0.12, 0.0, d) * blink * vec3(0.3, 0.5, 0.1) * 0.5; }
            } else if (uMode == 12) { // gradient wave
                float w = sin(p.x * 3.0 + t) * 0.2 + sin(p.y * 4.0 - t * 0.7) * 0.2; col = palette(up.y * 0.6 + w + t * 0.05, vec3(0.5), vec3(0.5), vec3(1.0), vec3(0.0, 0.1, 0.2));
            } else if (uMode == 13) { // bokeh
                col = vec3(0.03, 0.02, 0.05);
                for (int i = 0; i < 16; i++) { float fi = float(i); vec2 c = vec2(hash21(vec2(fi, 1.0)) - 0.5, hash21(vec2(fi, 2.0)) - 0.5) * vec2(A, 1.0) * 1.1 + vec2(0.0, -fract(t * 0.03 + fi * 0.1) * 0.05); float r = 0.04 + hash21(vec2(fi, 3.0)) * 0.08; float d = length(p - c); col += palette(hash21(vec2(fi, 4.0)), vec3(0.6), vec3(0.4), vec3(1.0), vec3(0.0, 0.33, 0.67)) * smoothstep(r, r - 0.01, d) * 0.35; }
            } else if (uMode == 14) { // retro sun
                vec3 sky = mix(vec3(0.1, 0.0, 0.2), vec3(0.9, 0.2, 0.5), smoothstep(0.4, -0.2, p.y));
                float sunR = length(p - vec2(0.0, -0.1)); float sun = smoothstep(0.3, 0.29, sunR) * step(0.3, fract(p.y * 18.0 + 0.5) + step(-0.1, -p.y) * 0.4);
                sky = mix(sky, vec3(1.0, 0.7, 0.2), sun);
                float depth = 1.0 / (p.y + 0.02); vec2 g = vec2(p.x * depth, depth + t * 3.0); float line = smoothstep(0.92, 1.0, max(abs(fract(g.x) - 0.5) * 2.0, abs(fract(g.y) - 0.5) * 2.0));
                col = p.y > 0.05 ? vec3(0.05, 0.0, 0.1) + line * vec3(1.0, 0.2, 0.8) : sky;
            } else { // checker room
                float depth = 1.0 / (abs(p.y) + 0.08); vec2 g = vec2(p.x * depth * 0.5, depth * 0.5 + t * 0.2); float ch = mod(floor(g.x) + floor(g.y), 2.0);
                col = mix(vec3(0.85), vec3(0.15), ch) * smoothstep(0.0, 0.3, abs(p.y)) + vec3(0.6, 0.65, 0.7) * (1.0 - smoothstep(0.0, 0.3, abs(p.y)));
            }
            o = vec4(col, 1.0);
        }
    """

    /** Face pass: liquify warps, beauty, face modes, age. Uniform-driven; all masks in scene orientation. */
    val FRAG_FACE = """#version 300 es
        precision highp float;
        in vec2 vTex;
        uniform sampler2D uFrame; uniform sampler2D uBlur; uniform sampler2D uFaceMask; uniform sampler2D uSegMask; uniform sampler2D uBackground;
        uniform int uRot; uniform vec2 uFit; uniform float uFlipMask; uniform float uHasSeg; uniform float uHasFace;
        uniform float uAspect; uniform float uTime;
        uniform int uWarpCount; uniform vec4 uWarp[10]; uniform vec4 uWarpDir[10]; // xy center (scene uv), z radius (height units), w strength; dir: xy pinch direction, z type
        uniform float uSmooth, uBright, uTeeth, uLipstick, uBlush, uEyeBright, uSharpen, uGlow;
        uniform vec3 uLipColor, uBlushColor; uniform vec4 uCheeks; uniform float uCheekRadius;
        uniform int uFaceMode; uniform float uFaceIntensity; uniform int uAge; uniform float uAgeIntensity;
        uniform vec2 uFaceCenter; uniform vec2 uFaceAxisX; uniform vec2 uFaceAxisY; // face-local frame (scene uv, height units)
        uniform vec2 uLeftEye, uRightEye, uNose, uMouth; // scene uv
        out vec4 o;
        $COMMON
        vec2 warp(vec2 uv) {
            vec2 p = uv;
            for (int i = 0; i < 10; i++) {
                if (i >= uWarpCount) break;
                vec2 c = uWarp[i].xy; float R = uWarp[i].z; float s = uWarp[i].w; int type = int(uWarpDir[i].z);
                vec2 d = (p - c) * vec2(uAspect, 1.0); float r = length(d) / R;
                if (r < 1.0) {
                    float k = (1.0 - r) * (1.0 - r);
                    if (type == 0) { // bulge (s>0) / shrink (s<0)
                        float f = 1.0 - s * k; p = c + d * f / vec2(uAspect, 1.0);
                    } else if (type == 1) { // directional push
                        p -= uWarpDir[i].xy * s * k * R / vec2(uAspect, 1.0);
                    } else { // swirl
                        float a = s * k * 3.0; mat2 m = mat2(cos(a), -sin(a), sin(a), cos(a)); p = c + (m * d) / vec2(uAspect, 1.0);
                    }
                }
            }
            return p;
        }
        vec4 masks(vec2 uv) { vec2 m = toUpright(uv, uRot, uFit); if (uFlipMask > 0.5) m.y = 1.0 - m.y; return texture(uSegMask, clamp(m, 0.0, 1.0)); }
        // face-local coords: x across the face (-1..1 ear to ear), y down (-1 forehead .. 1 chin)
        vec2 faceLocal(vec2 uv) { vec2 d = (uv - uFaceCenter) * vec2(uAspect, 1.0); return vec2(dot(d, uFaceAxisX), dot(d, uFaceAxisY)); }
        void main() {
            vec2 uv = warp(vTex);
            vec4 fm = texture(uFaceMask, uv);          // r face oval, g lips outer, b eyes, a mouth interior
            vec4 seg = masks(uv);                       // r person, g hair, b face skin, a clothes
            vec3 c = texture(uFrame, uv).rgb;
            vec3 blur = texture(uBlur, uv).rgb;
            float face = uHasFace > 0.5 ? fm.r : (uHasSeg > 0.5 ? seg.b : 0.0);
            float skin = face * (1.0 - fm.b) * (1.0 - fm.a) * (1.0 - clamp(fm.g - fm.a, 0.0, 1.0));
            float lips = clamp(fm.g - fm.a, 0.0, 1.0);
            // ---- beauty ----
            if (uSmooth > 0.0) {
                vec3 high = c - blur; float detail = length(high);
                float keep = smoothstep(0.02, 0.18, detail);   // keep strong edges
                vec3 smooth = blur + high * mix(1.0 - uSmooth, 1.0, keep);
                c = mix(c, smooth, skin);
            }
            if (uBright > 0.0) c = mix(c, pow(c, vec3(1.0 - 0.35 * uBright)) * (1.0 + 0.1 * uBright), skin);
            if (uTeeth > 0.0) { float l = luma(c); vec3 white = mix(c, vec3(l) * 1.15 + 0.05, 0.8); c = mix(c, white, fm.a * uTeeth * smoothstep(0.35, 0.6, l)); }
            if (uLipstick > 0.0) { vec3 lipc = uLipColor * (0.6 + 0.8 * luma(c)); c = mix(c, lipc, lips * uLipstick * 0.85); }
            if (uBlush > 0.0) {
                float b1 = exp(-pow(length((uv - uCheeks.xy) * vec2(uAspect, 1.0)) / uCheekRadius, 2.0) * 2.0);
                float b2 = exp(-pow(length((uv - uCheeks.zw) * vec2(uAspect, 1.0)) / uCheekRadius, 2.0) * 2.0);
                c = mix(c, c * 0.6 + uBlushColor * 0.4, clamp(b1 + b2, 0.0, 1.0) * uBlush * skin * 0.9);
            }
            if (uEyeBright > 0.0) c = mix(c, c * 1.25 + 0.05, fm.b * uEyeBright);
            if (uSharpen > 0.0) c += (c - blur) * uSharpen * 0.8;
            if (uGlow > 0.0) c = mix(c, max(c, blur * 1.1), uGlow * 0.6);
            // ---- age ----
            if (uAge != 0) {
                vec2 fl = faceLocal(uv); float k = uAgeIntensity;
                if (uAge == 1 || uAge == 2) { // older / much older
                    float strength = uAge == 2 ? 1.6 : 1.0;
                    float lines = 0.0;
                    // forehead
                    float fh = smoothstep(-0.95, -0.7, fl.y) * smoothstep(-0.25, -0.45, fl.y) * smoothstep(0.85, 0.5, abs(fl.x));
                    lines += fh * pow(0.5 + 0.5 * sin(fl.y * 44.0 + noise2(fl * 9.0) * 3.0), 12.0);
                    // crow's feet
                    for (int s = -1; s <= 1; s += 2) { vec2 e = fl - vec2(float(s) * 0.62, -0.12); float ang = atan(e.y, e.x * float(s)); float rad = length(e); lines += smoothstep(0.32, 0.05, rad) * smoothstep(0.02, 0.09, rad) * pow(0.5 + 0.5 * sin(ang * 14.0 + noise2(e * 20.0)), 16.0) * step(0.0, e.x * float(s)); }
                    // nasolabial folds
                    for (int s = -1; s <= 1; s += 2) { vec2 a = vec2(float(s) * 0.16, 0.12), b = vec2(float(s) * 0.36, 0.5); vec2 pa = fl - a, ba = b - a; float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0); float d = length(pa - ba * h); lines += smoothstep(0.03, 0.0, d - 0.005 * strength) * 0.9; }
                    // under-eye
                    for (int s = -1; s <= 1; s += 2) { vec2 e = fl - vec2(float(s) * 0.34, 0.02); lines += smoothstep(0.16, 0.0, length(e * vec2(1.0, 2.2))) * 0.35; }
                    // mouth corner lines (much older)
                    if (uAge == 2) { for (int s = -1; s <= 1; s += 2) { vec2 a = vec2(float(s) * 0.34, 0.5), b = vec2(float(s) * 0.42, 0.8); vec2 pa = fl - a, ba = b - a; float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0); lines += smoothstep(0.03, 0.0, length(pa - ba * h) - 0.003) * 0.7; } lines += fh * pow(0.5 + 0.5 * sin(fl.y * 70.0 + 1.7), 14.0) * 0.6; }
                    lines = clamp(lines, 0.0, 1.0) * skin * k;
                    c *= 1.0 - lines * 0.42 * strength;
                    // skin tone: desaturate, slightly yellow, lower contrast, age spots
                    float l = luma(c); vec3 aged = mix(c, vec3(l), 0.25 * k) * mix(vec3(1.0), vec3(1.04, 0.98, 0.9), k);
                    aged = mix(aged, vec3(0.5), 0.12 * k * strength);
                    float spots = smoothstep(0.86, 0.9, noise2(fl * 26.0)) * 0.25 * k * strength;
                    aged *= 1.0 - spots;
                    c = mix(c, aged, skin);
                    // gray hair
                    if (uHasSeg > 0.5) { float hl = luma(c); vec3 gray = mix(vec3(hl * 1.4 + 0.25), vec3(0.85), 0.3); c = mix(c, gray, seg.g * (0.8 + 0.2 * strength) * k); }
                } else if (uAge == 3) { // younger
                    vec3 high = c - blur; vec3 sm = blur + high * 0.3; c = mix(c, sm, skin * 0.8 * k);
                    c = mix(c, c * vec3(1.06, 1.0, 0.96) + 0.02, skin * k);
                    vec3 hsv = rgb2hsv(c); hsv.y *= 1.0 + 0.15 * k; c = hsv2rgb(hsv);
                } else { // baby
                    vec3 high = c - blur; vec3 sm = blur + high * 0.2; c = mix(c, sm, skin * 0.9 * k);
                    float b1 = exp(-pow(length((uv - uCheeks.xy) * vec2(uAspect, 1.0)) / (uCheekRadius * 1.4), 2.0) * 2.0);
                    float b2 = exp(-pow(length((uv - uCheeks.zw) * vec2(uAspect, 1.0)) / (uCheekRadius * 1.4), 2.0) * 2.0);
                    c = mix(c, c * 0.65 + vec3(1.0, 0.55, 0.6) * 0.35, clamp(b1 + b2, 0.0, 1.0) * 0.6 * skin * k);
                    c = mix(c, c * 1.05 + 0.03, skin * k);
                }
            }
            // ---- face modes ----
            if (uFaceMode != 0) {
                float f = face * uFaceIntensity; float l = luma(c); vec2 fl = faceLocal(uv);
                vec3 t = c;
                if (uFaceMode == 1) { t = mix(c, vec3(0.35, 0.9, 0.35) * (l + 0.15), 0.85); t *= 1.0 - smoothstep(0.35, 0.0, length(fl - vec2(-0.34, -0.1))) * 0.5 - smoothstep(0.35, 0.0, length(fl - vec2(0.34, -0.1))) * 0.5; }
                else if (uFaceMode == 2) { float veins = smoothstep(0.62, 0.7, fbm(fl * 12.0 + 3.0)) * 0.6; t = mix(vec3(l), vec3(0.45, 0.55, 0.42) * (l + 0.2), 0.7) * (1.0 - veins); t *= 1.0 - smoothstep(0.3, 0.0, length(fl - vec2(-0.34, -0.05))) * 0.7 - smoothstep(0.3, 0.0, length(fl - vec2(0.34, -0.05))) * 0.7; t = mix(t, vec3(0.45, 0.05, 0.05), smoothstep(0.25, 0.0, length((fl - vec2(0.0, 0.55)) * vec2(0.7, 1.4))) * 0.5); }
                else if (uFaceMode == 3) { float brushed = 0.85 + 0.15 * noise2(vec2(fl.x * 3.0, fl.y * 200.0)); t = (vec3(0.65, 0.7, 0.8) * pow(l, 0.8) * 1.4 + 0.1) * brushed; t += smoothstep(0.55, 0.9, l) * 0.35; }
                else if (uFaceMode == 4) { t = mix(c, vec3(0.97), 0.85); vec2 n = fl - vec2(0.0, 0.15); t = mix(t, vec3(0.9, 0.05, 0.05), smoothstep(0.14, 0.09, length(n))); for (int s = -1; s <= 1; s += 2) { t = mix(t, vec3(0.1, 0.45, 0.95), smoothstep(0.18, 0.12, length((fl - vec2(float(s) * 0.33, -0.12)) * vec2(1.0, 0.8))) * 0.9); t = mix(t, vec3(0.95, 0.2, 0.3), smoothstep(0.16, 0.1, length(fl - vec2(float(s) * 0.5, 0.35))) * 0.8); } t = mix(t, vec3(0.9, 0.05, 0.1), lips * 0.9); }
                else if (uFaceMode == 5) { vec3 bg = texture(uBackground, uv).rgb; t = mix(vec3(l) * 1.2 + 0.15, bg, 0.35); }
                else if (uFaceMode == 6) { t = mix(c, vec3(l) * vec3(0.95, 0.95, 1.05) + 0.12, 0.7); t *= 1.0 - smoothstep(0.3, 0.0, length(fl - vec2(-0.34, -0.05))) * 0.6 - smoothstep(0.3, 0.0, length(fl - vec2(0.34, -0.05))) * 0.6; t = mix(t, vec3(0.55, 0.0, 0.05), lips * 0.95); }
                else if (uFaceMode == 7) { t = vec3(1.0, 0.78, 0.25) * pow(l, 0.7) * 1.25 + pow(l, 6.0) * 0.6; }
                else if (uFaceMode == 8) { float grain = noise2(fl * 40.0) * 0.25 + 0.75; t = vec3(l) * vec3(0.72, 0.7, 0.66) * grain * 1.1; float cracks = smoothstep(0.66, 0.7, fbm(fl * 7.0)); t *= 1.0 - cracks * 0.5; }
                else if (uFaceMode == 9) { t = mix(c, vec3(0.3, 0.55, 0.95) * (l + 0.2) * 1.3, 0.85); }
                else if (uFaceMode == 10) { t = mix(c, vec3(0.25, 0.75, 0.25) * (l + 0.2) * 1.3, 0.85); }
                else { t = mix(c, vec3(0.7, 0.9, 1.0) * (l + 0.25), 0.55); t += step(0.985, hash21(floor(uv * 400.0))) * 0.7 * f; }
                float region = (uFaceMode == 5) ? max(face, uHasSeg > 0.5 ? seg.r : 0.0) : face;
                c = mix(c, t, region * uFaceIntensity);
            }
            o = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    /** Style pass: fun modes (non-face), color looks, vignette, grain. */
    val FRAG_STYLE = """#version 300 es
        precision highp float;
        in vec2 vTex;
        uniform sampler2D uFrame; uniform sampler2D uBlur; uniform sampler2D uFaceMask;
        uniform float uAspect; uniform float uTime; uniform vec2 uTexel;
        uniform int uFun; uniform float uFunK; uniform vec2 uFaceCenter; uniform float uFaceRadius;
        uniform int uLook; uniform float uLookK;
        uniform float uVignette; uniform float uGrain;
        // look parameters: temperature, tint, saturation, contrast, lift, gamma
        uniform vec4 uLookA; uniform vec4 uLookB; uniform vec3 uShadowTint; uniform vec3 uHighTint;
        out vec4 o;
        $COMMON
        vec3 sampleAt(vec2 uv) { return texture(uFrame, clamp(uv, 0.0, 1.0)).rgb; }
        float edge(vec2 uv) {
            float tl = luma(sampleAt(uv + uTexel * vec2(-1, -1))), t = luma(sampleAt(uv + uTexel * vec2(0, -1))), tr = luma(sampleAt(uv + uTexel * vec2(1, -1)));
            float l = luma(sampleAt(uv + uTexel * vec2(-1, 0))), r = luma(sampleAt(uv + uTexel * vec2(1, 0)));
            float bl = luma(sampleAt(uv + uTexel * vec2(-1, 1))), b = luma(sampleAt(uv + uTexel * vec2(0, 1))), br = luma(sampleAt(uv + uTexel * vec2(1, 1)));
            float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br; float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
            return length(vec2(gx, gy));
        }
        void main() {
            vec2 uv = vTex; vec3 c;
            float faceM = texture(uFaceMask, uv).r;
            // ---- UV remaps ----
            if (uFun == 5) { vec2 d = (uv - 0.5) * vec2(uAspect, 1.0); float r = length(d); float k = 1.0 + uFunK * 0.9 * r * r; uv = 0.5 + d / k / vec2(uAspect, 1.0); }
            else if (uFun == 6) { if (uv.x > 0.5) uv.x = 1.0 - uv.x; }
            else if (uFun == 18) { vec2 d = (uv - 0.5) * vec2(uAspect, 1.0); float a = atan(d.y, d.x); float r = length(d); float seg = 3.14159 / 3.0; a = mod(a, seg * 2.0); a = abs(a - seg); uv = 0.5 + vec2(cos(a), sin(a)) * r / vec2(uAspect, 1.0); uv = clamp(abs(uv), 0.0, 1.0); }
            else if (uFun == 20) { vec2 d = (uv - uFaceCenter) * vec2(uAspect, 1.0); float r = length(d) / max(uFaceRadius * 1.6, 0.05); if (r < 1.0) { float a = uFunK * (1.0 - r) * (1.0 - r) * 4.0; mat2 m = mat2(cos(a), -sin(a), sin(a), cos(a)); uv = uFaceCenter + (m * d) / vec2(uAspect, 1.0); } }
            if (uFun == 7 || uFun == 8) { // pixelate (face only / all)
                float blocks = mix(160.0, 30.0, uFunK); vec2 g = vec2(blocks * uAspect, blocks);
                vec2 q = (floor(uv * g) + 0.5) / g;
                if (uFun == 8 || faceM > 0.3) uv = q;
            }
            c = sampleAt(uv);
            // ---- stylizations ----
            if (uFun == 9) { // cartoon
                float lv = mix(8.0, 4.0, uFunK); vec3 q = floor(sampleAt(uv) * lv + 0.5) / lv; vec3 hsv = rgb2hsv(q); hsv.y *= 1.2; q = hsv2rgb(hsv);
                float e = smoothstep(0.15, 0.4, edge(uv)); c = mix(q, vec3(0.05), e * 0.9);
            } else if (uFun == 10) { // thermal
                float l = luma(c); vec3 pal = l < 0.25 ? mix(vec3(0.0, 0.0, 0.2), vec3(0.0, 0.2, 1.0), l * 4.0) : l < 0.5 ? mix(vec3(0.0, 0.2, 1.0), vec3(0.9, 0.0, 0.8), (l - 0.25) * 4.0) : l < 0.75 ? mix(vec3(0.9, 0.0, 0.8), vec3(1.0, 0.9, 0.0), (l - 0.5) * 4.0) : mix(vec3(1.0, 0.9, 0.0), vec3(1.0), (l - 0.75) * 4.0); c = pal;
            } else if (uFun == 11) { c = 1.0 - c; }
            else if (uFun == 12) { // VHS
                float jitter = (hash21(vec2(floor(uv.y * 240.0), floor(uTime * 15.0))) - 0.5) * 0.004 * uFunK;
                float band = smoothstep(0.02, 0.0, abs(fract(uv.y - uTime * 0.13) - 0.5) - 0.48) * 0.02 * uFunK;
                vec2 u2 = uv + vec2(jitter + band, 0.0);
                float sh = 0.004 * uFunK;
                c = vec3(sampleAt(u2 + vec2(sh, 0.0)).r, sampleAt(u2).g, sampleAt(u2 - vec2(sh, 0.0)).b);
                c = mix(c, vec3(luma(c)), 0.25 * uFunK); c *= 0.9 + 0.1 * sin(uv.y * 900.0); c += (hash21(uv * 800.0 + uTime) - 0.5) * 0.12 * uFunK; c = c * 1.05 + 0.02;
            } else if (uFun == 13) { // glitch
                float row = floor(uv.y * 40.0); float t = floor(uTime * 12.0);
                float on = step(0.86 - uFunK * 0.25, hash21(vec2(row, t)));
                float shift = (hash21(vec2(row + 7.0, t)) - 0.5) * 0.25 * uFunK * on;
                vec2 u2 = vec2(fract(uv.x + shift), uv.y);
                c = vec3(sampleAt(u2 + vec2(0.01 * on, 0.0)).r, sampleAt(u2).g, sampleAt(u2 - vec2(0.01 * on, 0.0)).b);
                if (on > 0.5 && hash21(vec2(row, t + 1.0)) > 0.7) c = 1.0 - c;
            } else if (uFun == 14) { // halftone
                float l = luma(c); float ang = 0.6; mat2 m = mat2(cos(ang), -sin(ang), sin(ang), cos(ang));
                float freq = mix(160.0, 70.0, uFunK); vec2 g = m * (uv * vec2(uAspect, 1.0)) * freq; float d = length(fract(g) - 0.5);
                float dot_ = smoothstep(0.5 * (1.0 - l) + 0.02, 0.5 * (1.0 - l) - 0.02, d);
                vec3 ink = floor(c * 3.0 + 0.5) / 3.0; c = mix(vec3(0.97, 0.95, 0.9), ink, dot_);
            } else if (uFun == 15) { // sketch
                float e = edge(uv); float paper = 0.92 + 0.08 * noise2(uv * 300.0); float shade = smoothstep(0.7, 0.1, luma(c)) * 0.25;
                c = vec3(paper) * (1.0 - smoothstep(0.08, 0.35, e) * uFunK - shade * uFunK);
            } else if (uFun == 16) { // night vision
                float l = luma(c) * 1.4 + (hash21(uv * 700.0 + uTime) - 0.5) * 0.15; c = vec3(0.1, 1.0, 0.2) * l * (0.9 + 0.1 * sin(uv.y * 600.0));
            } else if (uFun == 17) { // rainbow hue
                vec3 hsv = rgb2hsv(c); hsv.x = fract(hsv.x + uTime * 0.15 + uv.x * 0.5 * uFunK); hsv.y = min(1.0, hsv.y + 0.3); c = hsv2rgb(hsv);
            } else if (uFun == 19) { // painterly
                vec3 b = texture(uBlur, uv).rgb; float lv = mix(12.0, 6.0, uFunK); c = floor(mix(c, b, 0.6) * lv + 0.5) / lv; c = mix(c, c * (1.0 - smoothstep(0.3, 0.6, edge(uv)) * 0.5), uFunK);
            }
            // ---- color look ----
            if (uLook != 0) {
                vec3 g = c; float l = luma(g);
                if (uLook == 4) g = vec3(l);                                   // BW
                else if (uLook == 5) g = vec3(l) * vec3(1.2, 1.0, 0.8);          // sepia
                else if (uLook == 10) g = vec3(pow(l, 1.2)) * 1.1;              // noir
                else {
                    // temperature/tint
                    g *= vec3(1.0 + uLookA.x, 1.0 + uLookA.y, 1.0 - uLookA.x);
                    // saturation
                    g = mix(vec3(luma(g)), g, uLookA.z);
                    // contrast around mid grey
                    g = (g - 0.5) * uLookA.w + 0.5;
                    // lift / gamma
                    g = g * (1.0 - uLookB.x) + uLookB.x;
                    g = pow(max(g, 0.0), vec3(1.0 / uLookB.y));
                    // split toning
                    float lg = luma(g); g += uShadowTint * (1.0 - lg) * uLookB.z + uHighTint * lg * uLookB.z;
                }
                c = mix(c, g, uLookK);
            }
            // ---- vignette / grain ----
            if (uVignette > 0.0) { vec2 d = (vTex - 0.5) * vec2(uAspect, 1.0) * 1.2; c *= 1.0 - smoothstep(0.35, 1.1, length(d)) * uVignette; }
            if (uGrain > 0.0) c += (hash21(vTex * 1000.0 + fract(uTime)) - 0.5) * 0.18 * uGrain;
            o = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    /** Textured sticker/background quad (premultiplied alpha). */
    const val FRAG_TEXTURE = """#version 300 es
        precision mediump float;
        in vec2 vTex; uniform sampler2D uTex; uniform float uOpacity; out vec4 o;
        void main() { o = texture(uTex, vTex) * uOpacity; }
    """

    /** Debug: draw the face mesh mask as an overlay. */
    const val FRAG_DEBUG_MASK = """#version 300 es
        precision mediump float;
        in vec2 vTex; uniform sampler2D uTex; out vec4 o;
        void main() { vec4 m = texture(uTex, vTex); o = vec4(m.r * 0.6, m.g * 0.8 + m.a * 0.5, m.b, max(max(m.r, m.g), max(m.b, m.a)) * 0.45); }
    """
}
