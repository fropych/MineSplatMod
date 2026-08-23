package io.github.yromko.minesplat.cnb;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/** A static GPU copy of a preview mesh; upload and close on the render thread. */
public final class CnbPreviewBuffer implements AutoCloseable {
    private static final int PREVIEW_ALPHA = 115;

    private final GpuBuffer buffer;
    private final int indexCount;

    private CnbPreviewBuffer(GpuBuffer buffer, int indexCount) {
        this.buffer = buffer;
        this.indexCount = indexCount;
    }

    public static CnbPreviewBuffer upload(CnbPreviewMesh mesh) {
        RenderSystem.assertOnRenderThread();
        int vertexCount = Math.multiplyExact(mesh.quadCount(), 4);
        if (vertexCount == 0) {
            throw new IllegalArgumentException("Preview mesh is empty");
        }
        int bufferBytes = Math.multiplyExact(
                vertexCount, VertexFormats.POSITION_COLOR.getVertexSize());
        try (BufferAllocator allocator = BufferAllocator.fixedSized(bufferBytes)) {
            BufferBuilder builder = new BufferBuilder(
                    allocator,
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_COLOR);
            mesh.write(builder, PREVIEW_ALPHA);
            try (BuiltBuffer built = builder.end()) {
                int indices = built.getDrawParameters().indexCount();
                GpuBuffer uploaded = RenderSystem.getDevice().createBuffer(
                        () -> "MineSplat C&B preview",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                        built.getBuffer());
                return new CnbPreviewBuffer(uploaded, indices);
            }
        }
    }

    public void draw(double x, double y, double z, boolean valid) {
        RenderSystem.assertOnRenderThread();
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix())
                .translate((float) x, (float) y, (float) z);
        Vector4f tint = valid
                ? new Vector4f(1.0f)
                : new Vector4f(1.0f, 0.18f, 0.18f, 1.0f);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(
                modelView, tint, new Vector3f(), new Matrix4f());
        RenderSystem.ShapeIndexBuffer sequential = RenderSystem.getSequentialBuffer(
                VertexFormat.DrawMode.QUADS);
        GpuBuffer indices = sequential.getIndexBuffer(indexCount);
        Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = framebuffer.getDepthAttachmentView() == null
                ? encoder.createRenderPass(
                        () -> "MineSplat C&B preview",
                        framebuffer.getColorAttachmentView(),
                        OptionalInt.empty())
                : encoder.createRenderPass(
                        () -> "MineSplat C&B preview",
                        framebuffer.getColorAttachmentView(),
                        OptionalInt.empty(),
                        framebuffer.getDepthAttachmentView(),
                        OptionalDouble.empty())) {
            pass.setPipeline(RenderPipelines.DEBUG_QUADS);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, buffer);
            pass.setIndexBuffer(indices, sequential.getIndexType());
            pass.drawIndexed(0, 0, indexCount, 1);
        }
    }

    @Override
    public void close() {
        buffer.close();
    }
}
