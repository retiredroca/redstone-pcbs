package com.retiredroca.redstonepcbs.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.chip.ChipSerializer;
import com.retiredroca.redstonepcbs.chip.ChipWorld;
import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.Part;
import com.retiredroca.redstonepcbs.net.C2SEditPayload;
import com.retiredroca.redstonepcbs.net.C2SLibraryPayload;
import com.retiredroca.redstonepcbs.net.S2CLibraryPayload;
import com.retiredroca.redstonepcbs.net.S2CSnapshotPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * PCB editor: a single perspective 3D view of the board, rendered with vanilla block models, that
 * can be grabbed and spun. Parts are placed/erased/interacted/rotated by ray-picking the grid.
 */
public class PcbEditorScreen extends Screen {
    private static final int GRID = 16;
    private static final int PAL_COLS = 3;
    private static final int SLOT = 22;
    private static final int BTN_H = 14;
    private static final float FOV = 60.0F;
    /** Camera presets: 4 corner-overhead, 4 side-on (pitch 0), top. */
    private static final float[][] PRESETS = {
            {45, 35.264F}, {135, 35.264F}, {225, 35.264F}, {315, 35.264F},
            {0, 0}, {90, 0}, {180, 0}, {270, 0},
            {0, 90}
    };
    private static final String[] PRESET_LABELS = {"NE", "SE", "SW", "NW", "N", "E", "S", "W", "Top"};
    private static final float[][] AXES = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};


    private final int kind;
    private final BlockPos pos;
    private final int slot;

    private ChipWorld local = new ChipWorld();
    private Part selected = Part.DUST;
    private Dir pendingFacing = Dir.NORTH;
    private int activeLayer;

    private float yaw = 45.0F;
    private float pitch = 35.264F;
    private float zoom = 0.9F;
    private float panX;
    private float panY;

    private int[] hoverBlock;
    private int[] placeBlock;
    private int localTickCounter;

    private boolean pressed;
    private boolean dragging;
    private int pressButton;
    private double pressX;
    private double pressY;

    // layout
    private int gridX;
    private int gridY;
    private int gridSize;
    private int panelX;
    private int panelY;
    private int panelW;

    private final List<Button> buttons = new ArrayList<>();
    private final List<PaletteSlot> paletteSlots = new ArrayList<>();
    private double lastMouseX;
    private double lastMouseY;

    private final int[] paletteCounts = new int[Part.VALUES.length];
    private final boolean[] paletteAvailable = new boolean[Part.VALUES.length];
    private boolean creative;

    private final List<S2CLibraryPayload.Design> designs = new ArrayList<>();
    private boolean canSaveDesign;
    private boolean libraryOpen;
    private int libraryScroll;

    public PcbEditorScreen(int kind, BlockPos pos, int slot, int faceOrdinal) {
        super(Component.translatable("screen.redstonepcbs.editor"));
        this.kind = kind;
        this.pos = pos;
        this.slot = slot;
        this.activeLayer = 0;
    }

    public BlockPos pos() {
        return pos;
    }

    public boolean matches(S2CSnapshotPayload payload) {
        if (payload.kind() != kind) {
            return false;
        }
        return kind == C2SEditPayload.KIND_ITEM ? payload.slot() == slot : payload.pos().equals(pos);
    }

    public void acceptSnapshot(byte[] data) {
        local = ChipSerializer.read(data);
        local.settleNow();
    }

    public void acceptLibrary(S2CLibraryPayload payload) {
        designs.clear();
        designs.addAll(payload.designs());
        canSaveDesign = payload.canSave();
        libraryOpen = true;
        libraryScroll = 0;
    }

    // --- networking -------------------------------------------------------------------------------

    private void send(int action, int index, Part part, Dir dir, boolean subtractFlag) {
        int flags = subtractFlag ? C2SEditPayload.FLAG_SUBTRACT : 0;
        int p = part == null ? 0 : part.ordinal();
        int f = dir == null ? 0 : dir.ordinal();
        RedstonePcbs.platform().sendToServer(kind == C2SEditPayload.KIND_ITEM
                ? C2SEditPayload.item(slot, action, index, p, f, flags)
                : C2SEditPayload.block(pos, action, index, p, f, flags));
    }

    private void sendLibrary(int action, int index) {
        RedstonePcbs.platform().sendToServer(kind == C2SEditPayload.KIND_ITEM
                ? C2SLibraryPayload.item(slot, action, index)
                : C2SLibraryPayload.block(pos, action, index));
    }

    // --- layout -----------------------------------------------------------------------------------

    private void recalcLayout() {
        int top = 26;
        int margin = 8;
        int panelIdeal = Math.max(PAL_COLS * SLOT, 140);
        int availH = Math.max(80, this.height - top - margin);
        int availW = this.width - panelIdeal - margin * 3;
        int size = Math.max(80, Math.min(availH, availW));
        int contentW = size + 12 + panelIdeal;
        gridX = (this.width - contentW) / 2;
        gridY = top + Math.max(0, (availH - size) / 2);
        gridSize = size;
        panelX = gridX + size + 12;
        panelY = gridY;
        panelW = panelIdeal;
    }

    // --- rendering --------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        recalcLayout();
        computeInventory();
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, 0xB0101010);
        graphics.drawString(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 8,
                0xFFFFFF, false);

        buttons.clear();
        paletteSlots.clear();

        if (libraryOpen) {
            graphics.fill(0, 0, this.width, this.height, 0xC0000000);
            renderLibrary(graphics, mouseX, mouseY);
            return;
        }

        raycast(mouseX, mouseY);
        render3D(graphics);
        renderPanel(graphics, mouseX, mouseY);
        drawTooltips(graphics, mouseX, mouseY);
    }

    private boolean loggedModelError;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("redstonepcbs");

    /**
     * Orthographic projection over the whole framebuffer, in GUI pixels. The camera then scales the
     * board into pixels and places it at the canvas centre, so NDC maps 1:1 to GUI pixels with no
     * projection-side remap (the previous perspective+remap double-scaled and never fit).
     */
    private Matrix4f projection() {
        float halfW = this.width / 2.0F;
        float halfH = this.height / 2.0F;
        // Positive near/far so depth increases with distance from the camera (with the camera
        // placing the board in front at negative view Z). A symmetric +/- range would invert depth.
        return new Matrix4f().ortho(-halfW, halfW, -halfH, halfH, 0.1F, 8000.0F);
    }

    /** GUI pixels per board unit; {@code zoom} 1.0 makes the whole board touch the canvas border. */
    private float pixelsPerUnit() {
        float boardRadius = (float) (Math.sqrt(3.0) * GRID / 2.0);
        return (gridSize / 2.0F) / boardRadius * zoom;
    }

    /**
     * Board -> pixel transform: rotate about the board centre, scale to pixels, then place the
     * centre at the canvas centre (plus pan). This is the only place the board is sized/positioned.
     */
    private Matrix4f cameraMatrix() {
        float s = pixelsPerUnit();
        float halfW = this.width / 2.0F;
        float halfH = this.height / 2.0F;
        Matrix4f m = new Matrix4f();
        m.identity();
        // Outermost: push the board to negative view Z so it sits in front of the camera with the
        // positive-near/far ortho projection (depth then increases correctly with distance).
        m.translate((gridX + gridSize / 2.0F + panX) - halfW, halfH - (gridY + gridSize / 2.0F + panY),
                -2000.0F);
        m.scale(s, s, s);
        m.rotateX((float) Math.toRadians(pitch));
        m.rotateY((float) Math.toRadians(yaw));
        m.translate(-GRID / 2.0F, -GRID / 2.0F, -GRID / 2.0F);
        return m;
    }

    /** Projects a board point through proj*cam into GUI pixels. */
    private float[] projectWith(Matrix4f proj, Matrix4f cam, float x, float y, float z) {
        Vector4f v = new Matrix4f(proj).mul(cam).transform(new Vector4f(x, y, z, 1.0F));
        return new float[]{(v.x + 1.0F) * 0.5F * this.width, (1.0F - v.y) * 0.5F * this.height};
    }

    private Matrix4f mvp() {
        return new Matrix4f(projection()).mul(cameraMatrix());
    }

    private void render3D(GuiGraphics graphics) {
        graphics.flush();
        graphics.enableScissor(gridX, gridY, gridX + gridSize, gridY + gridSize);
        try {
            renderModels(graphics);
        } catch (Throwable t) {
            if (!loggedModelError) {
                loggedModelError = true;
                LOGGER.error("PCB 3D render failed", t);
            }
        }
        drawBoundsAndGizmo(graphics);
        graphics.disableScissor();
    }

    /** Board bounding-box wireframe plus a small fixed-size corner axis gizmo. */
    private void drawBoundsAndGizmo(GuiGraphics graphics) {
        Matrix4f mvp = mvp();
        drawBoxWireframe(graphics, mvp, 0, 0, 0, GRID, GRID, GRID, 0x60FFFFFF);
        // Highlight the active layer so the layer selection has a visible purpose.
        drawBoxWireframe(graphics, mvp, 0, activeLayer, 0, GRID, activeLayer + 1, GRID, 0x9000E0FF);

        // Axis gizmo: constant GUI size, directions taken from the camera rotation, with labels.
        float ox = gridX + 22;
        float oy = gridY + gridSize - 22;
        int[][] colors = {{0xFFFF4040, 0}, {0xFF40FF40, 1}, {0xFF4040FF, 2}};
        String[] labels = {"X", "Y", "Z"};
        float c = GRID / 2.0F;
        float[] origin = project(mvp, c, c, c);
        for (int i = 0; i < colors.length; i++) {
            int[] axis = colors[i];
            float[] tip = project(mvp, c + AXES[axis[1]][0] * 4, c + AXES[axis[1]][1] * 4,
                    c + AXES[axis[1]][2] * 4);
            float dx = tip[0] - origin[0];
            float dy = tip[1] - origin[1];
            float len = (float) Math.hypot(dx, dy);
            if (len < 0.001F) {
                continue;
            }
            float scale = 18.0F / len;
            float ex = ox + dx * scale;
            float ey = oy + dy * scale;
            drawLine(graphics, ox, oy, ex, ey, axis[0]);
            graphics.drawString(this.font, labels[i], (int) ex + 2, (int) ey - 4, axis[0], true);
        }
    }

    private void drawBoxWireframe(GuiGraphics graphics, Matrix4f mvp, float x0, float y0, float z0,
            float x1, float y1, float z1, int color) {
        float[] l = {
                x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1,
                x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1
        };
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        float[][] p = new float[8][];
        for (int i = 0; i < 8; i++) {
            p[i] = project(mvp, l[i * 3], l[i * 3 + 1], l[i * 3 + 2]);
        }
        for (int[] e : edges) {
            drawLine(graphics, p[e[0]][0], p[e[0]][1], p[e[1]][0], p[e[1]][1], color);
        }
    }

    /** Real vanilla block models via the world renderer. */
    private void renderModels(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        Matrix4f projection = projection();
        Matrix4f camera = cameraMatrix();

        // RenderSystem carries the camera; the PoseStack carries only the per-cell translation, so
        // the camera is applied exactly once (applying it in both places put geometry off-screen).
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.enableDepthTest();
        RenderSystem.clearDepth(1.0);

        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        try {
            mv.identity();
            mv.mul(camera);
            RenderSystem.applyModelViewMatrix();

            BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            PoseStack pose = new PoseStack();

            for (int i = 0; i < local.cellCount(); i++) {
                if (local.cell(i).isEmpty() || local.cell(i).part == Part.GLASS || enclosed(i)) {
                    continue;
                }
                drawModelCell(dispatcher, buffers, pose, i);
            }
            buffers.endBatch();
            for (int i = 0; i < local.cellCount(); i++) {
                if (local.cell(i).part == Part.GLASS) {
                    drawModelCell(dispatcher, buffers, pose, i);
                }
            }
            buffers.endBatch();

            // Selection outline drawn in the world pass with depth test off, so adjacent blocks
            // cannot occlude it.
            RenderSystem.disableDepthTest();
            if (hoverBlock != null) {
                VertexConsumer lines = buffers.getBuffer(RenderType.lines());
                LevelRenderer.renderLineBox(pose, lines, hoverBlock[0], hoverBlock[1], hoverBlock[2],
                        hoverBlock[0] + 1, hoverBlock[1] + 1, hoverBlock[2] + 1, 1.0F, 0.82F, 0.38F, 1.0F);
                buffers.endBatch();
            } else if (placeBlock != null) {
                VertexConsumer lines = buffers.getBuffer(RenderType.lines());
                LevelRenderer.renderLineBox(pose, lines, placeBlock[0], placeBlock[1], placeBlock[2],
                        placeBlock[0] + 1, placeBlock[1] + 1, placeBlock[2] + 1, 0.25F, 1.0F, 0.25F, 1.0F);
                buffers.endBatch();
            }
        } finally {
            // Always restore the stack/projection, even if a render step throws.
            mv.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.disableDepthTest();
        }
    }

    private void drawModelCell(BlockRenderDispatcher dispatcher, MultiBufferSource.BufferSource buffers,
            PoseStack pose, int index) {
        pose.pushPose();
        pose.translate(local.xOf(index), local.yOf(index), local.zOf(index));
        dispatcher.renderSingleBlock(PcbBlockStates.stateFor(local.cell(index)), pose, buffers,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /** Projects a board-space point to GUI pixels (matches projectWith). */
    private float[] project(Matrix4f mvp, float x, float y, float z) {
        Vector4f p = mvp.transform(new Vector4f(x, y, z, 1.0F));
        return new float[]{(p.x + 1.0F) * 0.5F * this.width, (1.0F - p.y) * 0.5F * this.height};
    }

    private void drawLine(GuiGraphics graphics, float x0, float y0, float x1, float y1, int color) {
        int steps = Math.max(1, (int) Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        for (int s = 0; s <= steps; s++) {
            float t = s / (float) steps;
            int px = (int) (x0 + (x1 - x0) * t);
            int py = (int) (y0 + (y1 - y0) * t);
            graphics.fill(px, py, px + 1, py + 1, color);
        }
    }

    private boolean enclosed(int index) {
        for (Dir d : Dir.VALUES) {
            int x = local.xOf(index) + d.dx;
            int y = local.yOf(index) + d.dy;
            int z = local.zOf(index) + d.dz;
            if (!local.inBounds(x, y, z) || !isFullOpaque(local.cell(local.index(x, y, z)).part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFullOpaque(Part part) {
        return part == Part.SOLID || part == Part.REDSTONE_BLOCK || part == Part.LAMP
                || part == Part.OBSERVER || part == Part.NOTE_BLOCK || part == Part.HOPPER;
    }

    // --- picking ----------------------------------------------------------------------------------

    private void raycast(double mouseX, double mouseY) {
        hoverBlock = null;
        placeBlock = null;
        Matrix4f inv = mvp().invert();
        // Pixel -> NDC over the whole framebuffer (matches project()).
        float nx = (float) (mouseX / this.width) * 2.0F - 1.0F;
        float ny = 1.0F - (float) (mouseY / this.height) * 2.0F;
        Vector4f near = inv.transform(new Vector4f(nx, ny, -1.0F, 1.0F));
        Vector4f far = inv.transform(new Vector4f(nx, ny, 1.0F, 1.0F));
        // Orthographic: w == 1, so the inverse already gives board-space points.
        Vector3f a = new Vector3f(near.x, near.y, near.z);
        Vector3f b = new Vector3f(far.x, far.y, far.z);
        Vector3f dir = new Vector3f(b).sub(a);
        float len = dir.length();
        if (len < 0.0001F) {
            return;
        }
        dir.div(len);

        // Clip the ray to the board AABB so we only march the (small) part near the grid.
        float tMin = 0.0F;
        float tMax = len;
        float[] o = {a.x, a.y, a.z};
        float[] d = {dir.x, dir.y, dir.z};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0E-6F) {
                if (o[axis] < 0.0F || o[axis] > GRID) {
                    return;
                }
                continue;
            }
            float t1 = (0.0F - o[axis]) / d[axis];
            float t2 = (GRID - o[axis]) / d[axis];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        if (tMax < tMin) {
            return;
        }

        int steps = 256;
        int px = Integer.MIN_VALUE;
        int py = Integer.MIN_VALUE;
        int pz = Integer.MIN_VALUE;
        for (int step = 0; step <= steps; step++) {
            float t = tMin + (tMax - tMin) * step / steps;
            int cx = (int) Math.floor(a.x + dir.x * t);
            int cy = (int) Math.floor(a.y + dir.y * t);
            int cz = (int) Math.floor(a.z + dir.z * t);
            if (!local.inBounds(cx, cy, cz) || (cx == px && cy == py && cz == pz)) {
                continue;
            }
            px = cx;
            py = cy;
            pz = cz;
            if (!local.cell(local.index(cx, cy, cz)).isEmpty()) {
                hoverBlock = new int[]{cx, cy, cz};
                break;
            }
            placeBlock = new int[]{cx, cy, cz};
        }
    }

    // --- interaction ------------------------------------------------------------------------------

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (libraryOpen) {
            for (Button b : buttons) {
                if (b.contains(mouseX, mouseY)) {
                    b.action.run();
                    return true;
                }
            }
            return true;
        }
        for (Button b : buttons) {
            if (b.contains(mouseX, mouseY)) {
                b.action.run();
                return true;
            }
        }
        for (PaletteSlot s : paletteSlots) {
            if (s.contains(mouseX, mouseY)) {
                if (creative || paletteAvailable[s.part.ordinal()]) {
                    selectPart(s.part);
                }
                return true;
            }
        }
        if (inCanvas(mouseX, mouseY)) {
            pressed = true;
            dragging = false;
            pressButton = button;
            pressX = mouseX;
            pressY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (pressed && inCanvas(mouseX, mouseY)) {
            if (Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) > 4) {
                dragging = true;
            }
            if (dragging) {
                if (hasControlDown()) {
                    panX += dragX;
                    panY += dragY;
                } else {
                    yaw = (float) ((yaw + dragX * 0.6) % 360.0);
                    pitch = (float) Math.max(-90.0, Math.min(90.0, pitch + dragY * 0.6));
                }
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (pressed && !dragging && button == pressButton) {
            pressed = false;
            act(mouseX, mouseY, button);
            return true;
        }
        pressed = false;
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void act(double mouseX, double mouseY, int button) {
        raycast(mouseX, mouseY);
        if (button == 1) {
            if (hoverBlock != null) {
                int index = local.index(hoverBlock[0], hoverBlock[1], hoverBlock[2]);
                Part part = local.cell(index).part;
                boolean hopperFilter = part == Part.HOPPER && local.isSimpleHopperMode();
                if (hopperFilter && kind == C2SEditPayload.KIND_BLOCK) {
                    if (hasShiftDown()) {
                        send(C2SEditPayload.ACTION_CLEAR_FILTER, index, null, null, false);
                    } else {
                        send(C2SEditPayload.ACTION_SET_FILTER, index, selected, null, false);
                    }
                } else if (part.isContainer() && kind == C2SEditPayload.KIND_BLOCK) {
                    EditorReturn.stash(this);
                    send(C2SEditPayload.ACTION_OPEN_UI, index, null, null, false);
                } else {
                    send(C2SEditPayload.ACTION_INTERACT, index, null, null, false);
                    local.interact(index);
                    local.settleNow();
                }
            }
            return;
        }
        if (hasShiftDown()) {
            if (hoverBlock != null) {
                int index = local.index(hoverBlock[0], hoverBlock[1], hoverBlock[2]);
                send(C2SEditPayload.ACTION_CLEAR, index, null, null, false);
                local.clear(hoverBlock[0], hoverBlock[1], hoverBlock[2]);
                local.settleNow();
            }
            return;
        }
        if (placeBlock != null) {
            placeAt(placeBlock[0], placeBlock[1], placeBlock[2]);
        }
    }

    private void placeAt(int x, int y, int z) {
        int index = local.index(x, y, z);
        Part part = selected;
        if (part == Part.AIR) {
            return;
        }
        Dir facing = resolveFacing(part, x, y, z);
        if (facing == null) {
            return;
        }
        send(C2SEditPayload.ACTION_SET, index, part, facing, false);
        local.set(index, part, facing);
        local.settleNow();
    }

    private Dir resolveFacing(Part part, int x, int y, int z) {
        int index = local.index(x, y, z);
        Dir hit = adjacentDir(x, y, z);
        return switch (part.facingFamily()) {
            case TORCH -> {
                // Attach to the clicked face if it can support a torch, else any supported side.
                if (hit != null && local.hasSupport(index, hit)) {
                    yield hit;
                }
                for (Dir d : Part.SUPPORTED_ORDER) {
                    if (local.hasSupport(index, d)) {
                        yield d;
                    }
                }
                yield null;
            }
            case HOPPER -> part.sanitizeFacing(hit != null ? hit : Dir.DOWN);
            case HORIZONTAL -> {
                // Face the opposite side of the clicked face, like vanilla placement.
                if (hit != null && hit.isHorizontal()) {
                    yield hit;
                }
                yield part.sanitizeFacing(pendingFacing);
            }
            case SIX_WAY -> part.sanitizeFacing(hit != null ? hit : Dir.UP);
            // A lever/button with nothing adjacent (e.g. on the board floor) sits on the floor.
            case FACE_ATTACHED -> part.sanitizeFacing(hit != null ? hit : Dir.DOWN);
            case NONE -> Dir.UP;
        };
    }

    /** Direction from the cell at (x,y,z) to the currently hovered block, or null if not adjacent. */
    private Dir adjacentDir(int x, int y, int z) {
        if (hoverBlock == null) {
            return null;
        }
        int dx = hoverBlock[0] - x;
        int dy = hoverBlock[1] - y;
        int dz = hoverBlock[2] - z;
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
            return null;
        }
        return dirFor(dx, dy, dz);
    }

    private static Dir dirFor(int dx, int dy, int dz) {
        if (dx == 1) {
            return Dir.EAST;
        }
        if (dx == -1) {
            return Dir.WEST;
        }
        if (dy == 1) {
            return Dir.UP;
        }
        if (dy == -1) {
            return Dir.DOWN;
        }
        if (dz == 1) {
            return Dir.SOUTH;
        }
        if (dz == -1) {
            return Dir.NORTH;
        }
        return null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (hasControlDown()) {
            zoomBy(scrollY > 0 ? 0.05F : -0.05F, mouseX, mouseY);
        } else {
            activeLayer = Math.floorMod(activeLayer + (scrollY > 0 ? 1 : -1), GRID);
        }
        return true;
    }

    private static float clampZoom(float z) {
        return Math.max(0.2F, Math.min(3.0F, z));
    }

    /** Zooms while keeping the board point under the cursor fixed on screen. */
    private void zoomBy(float delta, double mouseX, double mouseY) {
        float nx = (float) (mouseX / this.width) * 2.0F - 1.0F;
        float ny = 1.0F - (float) (mouseY / this.height) * 2.0F;
        Vector4f anchored = mvp().invert().transform(new Vector4f(nx, ny, 0.0F, 1.0F));
        float next = clampZoom(zoom + delta);
        if (next == zoom) {
            return;
        }
        zoom = next;
        Vector4f after = mvp().transform(new Vector4f(anchored.x, anchored.y, anchored.z, 1.0F));
        float sx = (after.x + 1.0F) * 0.5F * this.width;
        float sy = (1.0F - after.y) * 0.5F * this.height;
        panX += mouseX - sx;
        panY += sy - mouseY;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int index = hoverBlock == null ? -1 : local.index(hoverBlock[0], hoverBlock[1], hoverBlock[2]);
        switch (keyCode) {
            case 82 -> { // R
                if (index >= 0) {
                    send(C2SEditPayload.ACTION_ROTATE, index, null, null, false);
                    local.rotate(index);
                } else {
                    rotatePendingFacing();
                }
                return true;
            }
            case 68 -> { // D
                if (index >= 0) {
                    send(C2SEditPayload.ACTION_CYCLE_DELAY, index, null, null, false);
                    local.cycleRepeaterDelay(index);
                }
                return true;
            }
            case 77 -> { // M
                if (index >= 0) {
                    send(C2SEditPayload.ACTION_TOGGLE_MODE, index, null, null, false);
                    local.toggleComparatorMode(index);
                }
                return true;
            }
            case 265, 264, 263, 262 -> { // arrow keys: no-op (pan is Ctrl/Alt + drag)
                return true;
            }
            default -> {
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean inCanvas(double mouseX, double mouseY) {
        return mouseX >= gridX && mouseX < gridX + gridSize && mouseY >= gridY && mouseY < gridY + gridSize;
    }

    private void rotatePendingFacing() {
        pendingFacing = switch (pendingFacing) {
            case NORTH -> Dir.EAST;
            case EAST -> Dir.SOUTH;
            case SOUTH -> Dir.WEST;
            case WEST -> Dir.NORTH;
            case DOWN -> Dir.UP;
            case UP -> Dir.DOWN;
        };
    }

    private void selectPart(Part part) {
        selected = part;
        pendingFacing = part.needsSupport() || part.pointsAtNeighbour() ? Dir.DOWN : Dir.NORTH;
    }

    // --- panel ------------------------------------------------------------------------------------

    private void renderPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int px = panelX;
        int py = panelY;
        int slots = (int) Math.ceil(PcbIcons.PALETTE.length / (double) PAL_COLS);
        int slotsH = slots * SLOT;

        for (int i = 0; i < PcbIcons.PALETTE.length; i++) {
            Part part = PcbIcons.PALETTE[i];
            int sx = px + (i % PAL_COLS) * SLOT;
            int sy = py + (i / PAL_COLS) * SLOT;
            boolean active = selected == part;
            drawSlot(graphics, sx, sy, active, paletteAvailable[part.ordinal()]);
            drawItem(graphics, PcbIcons.stackFor(part), sx + 3, sy + 3, 16);
            if (!creative && paletteCounts[part.ordinal()] > 1) {
                graphics.drawString(this.font, String.valueOf(paletteCounts[part.ordinal()]), sx + 13, sy + 12,
                        0xFFFFFF, true);
            }
            paletteSlots.add(new PaletteSlot(part, sx, sy, SLOT, SLOT));
        }

        int y = py + slotsH + 6;
        graphics.drawString(this.font, "View", px, y, 0xFFFFFF, false);
        y += 10;
        for (int i = 0; i < PRESETS.length; i++) {
            int col = i % 4;
            int row = i / 4;
            int bx = px + col * 26;
            int by = y + row * (BTN_H + 1);
            final int idx = i;
            addButton(graphics, bx, by, 24, BTN_H, PRESET_LABELS[i], () -> {
                yaw = PRESETS[idx][0];
                pitch = PRESETS[idx][1];
            });
        }
        y += (BTN_H + 1) * 3 + 4;

        addButton(graphics, px, y, 20, BTN_H, "-",
                () -> zoomBy(-0.05F, gridX + gridSize / 2.0F, gridY + gridSize / 2.0F));
        addButton(graphics, px + 22, y, 20, BTN_H, "+",
                () -> zoomBy(0.05F, gridX + gridSize / 2.0F, gridY + gridSize / 2.0F));
        addButton(graphics, px + 44, y, 46, BTN_H, "Reset", () -> {
            zoom = 0.9F;
            panX = 0.0F;
            panY = 0.0F;
        });
        graphics.drawString(this.font, Math.round(zoom * 100) + "%", px + 94, y + 3, 0xFFFFFF, false);
        y += BTN_H + 2;
        addButton(graphics, px, y, 20, BTN_H, "-", () -> activeLayer = Math.max(0, activeLayer - 1));
        addButton(graphics, px + 22, y, 20, BTN_H, "+", () -> activeLayer = Math.min(GRID - 1, activeLayer + 1));
        graphics.drawString(this.font, "Layer " + (activeLayer + 1) + "/16", px + 46, y + 3, 0xFFFFFF, false);
        y += BTN_H + 2;
        addButton(graphics, px, y, 100, BTN_H, "Pulse Layer", () -> {
            send(C2SEditPayload.ACTION_PULSE_LAYER, activeLayer, null, null, false);
            local.pulseLayer(activeLayer);
            local.settleNow();
        });
        y += BTN_H + 4;

        int libW = 56;
        String hopperLabel = local.isSimpleHopperMode() ? "Hopper: Easy" : "Hopper: Normal";
        int hopW = Math.max(56, this.font.width(hopperLabel) + 6);
        addButton(graphics, px, y, libW, BTN_H, "Library", () -> {
            libraryOpen = true;
            sendLibrary(C2SLibraryPayload.ACTION_LIST, 0);
        });
        addButton(graphics, px + libW + 2, y, hopW, BTN_H, hopperLabel,
                () -> send(C2SEditPayload.ACTION_TOGGLE_HOPPER_MODE, 0, null, null, false));
        y += BTN_H + 4;

        graphics.drawString(this.font, "Drag: spin  L: place", px, y, 0x9F9F9F, false);
        graphics.drawString(this.font, "Shift+L: erase  R: interact", px, y + 10, 0x9F9F9F, false);
        graphics.drawString(this.font, "D: delay  M: mode  scroll: layer", px, y + 20, 0x9F9F9F, false);
    }

    private void drawSlot(GuiGraphics graphics, int x, int y, boolean active, boolean hasStock) {
        graphics.fill(x, y, x + SLOT - 2, y + SLOT - 2, active ? 0xFFFFE080 : 0xFF373737);
        graphics.fill(x + 1, y + 1, x + SLOT - 3, y + SLOT - 3, 0xFF8B8B8B);
        if (!hasStock) {
            graphics.fill(x + 1, y + 1, x + SLOT - 3, y + SLOT - 3, 0x80000000);
        }
    }

    private void addButton(GuiGraphics graphics, int x, int y, int w, int h, String label, Runnable action) {
        boolean hovered = lastMouseX >= x && lastMouseX < x + w && lastMouseY >= y && lastMouseY < y + h;
        graphics.fill(x, y, x + w, y + h, hovered ? 0xFF505050 : 0xFF303030);
        graphics.drawString(this.font, fit(label, w - 6), x + 3, y + 3, 0xE0E0E0, false);
        buttons.add(new Button(x, y, w, h, action));
    }

    /** Truncates a label with an ellipsis so it can never spill outside its button. */
    private String fit(String label, int maxWidth) {
        if (this.font.width(label) <= maxWidth) {
            return label;
        }
        String s = label;
        while (s.length() > 1 && this.font.width(s + "...") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }

    private void renderLibrary(GuiGraphics graphics, int mouseX, int mouseY) {
        int rows = Math.min(designs.size(), 8);
        int w = 250;
        int h = 46 + Math.max(rows, 1) * 18 + 22;
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        graphics.fill(x, y, x + w, y + h, 0xFF202020);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF3C3C3C);
        graphics.drawString(this.font, "Saved Designs", x + 8, y + 8, 0xFFFFFF, false);
        graphics.drawString(this.font, "saved per player; paper is consumed on save", x + 8, y + 19, 0x909090,
                false);
        int rowY = y + 32;
        for (int i = 0; i < rows; i++) {
            final int idx = libraryScroll + i;
            if (idx >= designs.size()) {
                break;
            }
            graphics.drawString(this.font, designs.get(idx).name(), x + 8, rowY + 3, 0xE0E0E0, false);
            addButton(graphics, x + w - 72, rowY, 30, 14, "Use", () -> {
                sendLibrary(C2SLibraryPayload.ACTION_APPLY, idx);
                libraryOpen = false;
            });
            addButton(graphics, x + w - 38, rowY, 30, 14, "Del",
                    () -> sendLibrary(C2SLibraryPayload.ACTION_DELETE, idx));
            rowY += 18;
        }
        if (designs.isEmpty()) {
            graphics.drawString(this.font, "(no designs saved yet)", x + 8, rowY + 3, 0x909090, false);
        }
        int by = y + h - 18;
        if (designs.size() > 8) {
            addButton(graphics, x + 84, by, 18, 14, "-", () -> libraryScroll = Math.max(0, libraryScroll - 1));
            addButton(graphics, x + 104, by, 18, 14, "+", () -> libraryScroll = Math.min(
                    Math.max(0, designs.size() - 8), libraryScroll + 1));
        }
        addButton(graphics, x + 8, by, 78, 14, canSaveDesign ? "Save Design" : "Need Paper", () -> {
            if (canSaveDesign) {
                sendLibrary(C2SLibraryPayload.ACTION_SAVE, 0);
            }
        });
        addButton(graphics, x + w - 60, by, 52, 14, "Close", () -> libraryOpen = false);
    }

    private void drawItem(GuiGraphics graphics, ItemStack stack, int x, int y, int size) {
        if (stack.isEmpty()) {
            return;
        }
        float s = size / 16.0F;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(s, s, 1);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }

    private void drawTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        for (PaletteSlot slot : paletteSlots) {
            if (slot.contains(mouseX, mouseY)) {
                List<Component> lines = new ArrayList<>();
                lines.add(PcbIcons.nameOf(slot.part));
                graphics.renderTooltip(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
                return;
            }
        }
        if (hoverBlock != null) {
            var c = local.cell(local.index(hoverBlock[0], hoverBlock[1], hoverBlock[2]));
            List<Component> lines = new ArrayList<>();
            lines.add(PcbIcons.nameOf(c.part));
            if (c.part == Part.DUST) {
                lines.add(Component.literal("power " + c.power));
            } else if (c.part == Part.REPEATER) {
                lines.add(Component.literal("delay " + (c.delay + 1)));
            } else if (c.part == Part.COMPARATOR) {
                lines.add(Component.literal(c.subtract ? "subtract" : "compare"));
            }
            graphics.renderTooltip(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    private void computeInventory() {
        creative = this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.isCreative();
        for (int i = 0; i < paletteCounts.length; i++) {
            paletteCounts[i] = 0;
            paletteAvailable[i] = false;
        }
        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.level == null) {
            return;
        }
        Inventory inv = this.minecraft.player.getInventory();
        countInto(inv.items);
        countInto(inv.offhand);
        for (Part part : PcbIcons.PALETTE) {
            paletteAvailable[part.ordinal()] = com.retiredroca.redstonepcbs.craft.Crafting.available(
                    this.minecraft.player, PcbIcons.itemFor(part));
        }
    }

    private void countInto(List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            for (Part part : PcbIcons.PALETTE) {
                if (PcbIcons.itemFor(part) == item) {
                    paletteCounts[part.ordinal()] += stack.getCount();
                }
            }
        }
    }

    @Override
    public void init() {
        super.init();
        recalcLayout();
        send(C2SEditPayload.ACTION_REQUEST, 0, null, null, false);
    }

    @Override
    public void tick() {
        super.tick();
        // Advance the local engine every two game ticks (one redstone tick) so delayed parts -
        // buttons releasing, torches/repeaters/observers firing - animate while editing.
        if (++localTickCounter >= 2) {
            localTickCounter = 0;
            if (local.isActive()) {
                local.tick();
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Button(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record PaletteSlot(Part part, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
