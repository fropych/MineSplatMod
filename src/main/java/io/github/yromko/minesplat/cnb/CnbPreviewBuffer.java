package io.github.yromko.minesplat.cnb;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;

/** A static GPU copy of a preview mesh; upload and close on the client thread. */
public final class CnbPreviewBuffer implements AutoCloseable {
    private static final int PREVIEW_ALPHA = 115;

    private final VertexBuffer buffer;

    private CnbPreviewBuffer(VertexBuffer buffer) {
        this.buffer = buffer;
    }

    public static CnbPreviewBuffer upload(CnbPreviewMesh mesh) {
        RenderSystem.assertOnRenderThread();
        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        builder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        mesh.write(builder, PREVIEW_ALPHA);

        VertexBuffer uploaded = new VertexBuffer(VertexBuffer.Usage.STATIC);
        uploaded.bind();
        try {
            uploaded.upload(builder.end());
        } catch (Throwable throwable) {
            uploaded.close();
            throw throwable;
        } finally {
            VertexBuffer.unbind();
        }
        return new CnbPreviewBuffer(uploaded);
    }

    public void draw(Matrix4f position, Matrix4f projection, boolean valid) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.setShaderColor(
                1.0f,
                valid ? 1.0f : 0.18f,
                valid ? 1.0f : 0.18f,
                1.0f);
        ShaderProgram shader = GameRenderer.getPositionColorProgram();
        buffer.bind();
        try {
            buffer.draw(position, projection, shader);
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    @Override
    public void close() {
        buffer.close();
    }
}
