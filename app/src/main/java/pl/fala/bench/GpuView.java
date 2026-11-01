package pl.fala.bench;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Test grafiki: w każdej klatce rysuje kilka razy ciężki shader (fraktal 96 iteracji)
 * do tekstury 512×512 poza ekranem, a potem pokazuje ją na ekranie.
 * Liczy przebiegi na sekundę – dzięki temu wynik nie jest ograniczony do 60/90 Hz ekranu.
 */
public class GpuView extends GLSurfaceView {

    public interface Listener {
        void onProgress(float fraction, double passesPerSec);

        void onDone(double passesPerSec, String renderer);
    }

    static final int FBO = 512;
    static final int PASSES = 4;
    static final long WARMUP_MS = 1000, TEST_MS = 9000;

    private static final String VS =
            "attribute vec2 aPos; varying vec2 vUv;" +
            "void main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }";

    private static final String FS_HEAVY =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform vec2 uRes; uniform float uT;" +
            "void main(){" +
            "  vec2 uv = (gl_FragCoord.xy / uRes) * 2.0 - 1.0;" +
            "  float zoom = 0.35 + 0.3 * sin(uT * 0.35);" +
            "  vec2 c = vec2(-0.7436, 0.1318) + uv * zoom;" +
            "  vec2 z = vec2(0.0); float it = 0.0;" +
            "  for (int i = 0; i < 96; i++) {" +
            "    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;" +
            "    if (dot(z, z) > 16.0) break;" +
            "    it += 1.0;" +
            "  }" +
            "  float f = it / 96.0;" +
            "  vec3 col = 0.5 + 0.5 * cos(6.2831 * (f * 2.0 + vec3(0.0, 0.33, 0.67)) + uT);" +
            "  col *= 1.0 - step(0.999, f);" +
            "  gl_FragColor = vec4(col, 1.0);" +
            "}";

    private static final String FS_BLIT =
            "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; uniform float uAspect;" +
            "void main(){ vec2 uv = vUv; uv.y = (uv.y - 0.5) * uAspect + 0.5;" +
            "  if (uv.y < 0.0 || uv.y > 1.0) { gl_FragColor = vec4(0.03, 0.03, 0.06, 1.0); return; }" +
            "  gl_FragColor = texture2D(uTex, uv); }";

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    public GpuView(Context c, Listener l) {
        super(c);
        listener = l;
        setEGLContextClientVersion(2);
        setRenderer(new R());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    private class R implements Renderer {
        int heavy, blit, fbo, tex;
        int w = 1, h = 1;
        FloatBuffer quad;
        long start = 0, measureStart = 0, lastReport = 0;
        long passes = 0;
        boolean done = false;
        String renderer = "";

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
            quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(v).position(0);
            heavy = program(VS, FS_HEAVY);
            blit = program(VS, FS_BLIT);
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            tex = ids[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, FBO, FBO, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glGenFramebuffers(1, ids, 0);
            fbo = ids[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            String r = GLES20.glGetString(GLES20.GL_RENDERER);
            renderer = r != null ? r : "";
            start = System.nanoTime();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            w = Math.max(1, width);
            h = Math.max(1, height);
        }

        private void draw(int prog) {
            int a = GLES20.glGetAttribLocation(prog, "aPos");
            GLES20.glEnableVertexAttribArray(a);
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            long now = System.nanoTime();
            float t = (now - start) / 1e9f;
            if (!done) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
                GLES20.glViewport(0, 0, FBO, FBO);
                GLES20.glUseProgram(heavy);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(heavy, "uRes"), FBO, FBO);
                for (int k = 0; k < PASSES; k++) {
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(heavy, "uT"), t + k * 0.013f);
                    draw(heavy);
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, w, h);
            GLES20.glUseProgram(blit);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(blit, "uTex"), 0);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(blit, "uAspect"), h / (float) w);
            draw(blit);
            GLES20.glFinish();
            if (done) return;

            long el = (now - start) / 1_000_000L;
            if (el >= WARMUP_MS) {
                if (measureStart == 0) measureStart = System.nanoTime();
                else passes += PASSES;
            }
            double secs = measureStart == 0 ? 0 : (System.nanoTime() - measureStart) / 1e9;
            final double pps = secs > 0 ? passes / secs : 0;
            if (now - lastReport > 250_000_000L) {
                lastReport = now;
                final float frac = Math.min(1f, el / (float) (WARMUP_MS + TEST_MS));
                main.post(() -> listener.onProgress(frac, pps));
            }
            if (el >= WARMUP_MS + TEST_MS) {
                done = true;
                final String rn = renderer;
                main.post(() -> listener.onDone(pps, rn));
            }
        }

        private int program(String vs, String fs) {
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glLinkProgram(p);
            return p;
        }

        private int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }
    }
}
