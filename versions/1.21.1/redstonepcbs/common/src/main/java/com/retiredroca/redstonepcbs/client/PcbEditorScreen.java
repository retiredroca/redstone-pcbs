package com.retiredroca.redstonepcbs.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.block.BoardEdit;
import com.retiredroca.redstonepcbs.block.BoardSpace;
import com.retiredroca.redstonepcbs.block.BoardStates;
import com.retiredroca.redstonepcbs.block.GridSerializer;
import com.retiredroca.redstonepcbs.block.PcbAttach;
import com.retiredroca.redstonepcbs.block.PcbBlockEntity;
import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.Part;
import com.retiredroca.redstonepcbs.config.PcbsConfig;
import com.retiredroca.redstonepcbs.data.Blueprint;
import com.retiredroca.redstonepcbs.data.LibraryData;
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
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PCB editor: a single perspective 3D view of the board, rendered with vanilla block models, that
 * can be grabbed and spun. Parts are placed/erased/interacted/rotated by ray-picking the grid.
 */
public class PcbEditorScreen extends Screen {
    private static final int GRID = BoardSpace.SIZE;
    /** Palette grid: 9 columns x 3 rows, the creative-inventory layout. */
    private static final int PAL_COLS = 9;
    private static final int PAL_ROWS = 3;
    private static final int PAL_PAGE = PAL_COLS * PAL_ROWS;
    /** Base widget metrics at 1.0 UI scale; the live values scale with the game resolution. */
    private static final int BASE_SLOT = 18;
    private static final int BASE_BTN_H = 14;
    /** The panel's non-grid chrome (tabs, search box, lower controls, help) in base units. */
    private static final int CHROME_H = 170;
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

    private BlockState[] local = GridSerializer.emptyGrid();
    /** Gateway attachments mirrored from the server: grid cell index -> PCB face. */
    private final java.util.Map<Integer, Dir> attachFaces = new java.util.LinkedHashMap<>();
    private boolean inputOn;
    private boolean outputOn;
    /** The part the selected palette entry corresponds to, or AIR for an arbitrary block item. */
    private Part selected = Part.DUST;
    private PcbIcons.Entry selectedEntry;
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

    private boolean creative;

    /** How many of each item the player holds, refreshed each frame (survival palette gating). */
    private final java.util.Map<Item, Integer> itemCounts = new java.util.HashMap<>();

    // palette tabs + search
    private PcbIcons.Tab activeTab = PcbIcons.Tab.REDSTONE;
    private String search = "";
    private boolean searching;
    private int searchCursor;
    private int searchX;
    private int searchY;
    private int searchW;
    private int searchH;
    private final List<TabRect> tabRects = new ArrayList<>();

    // palette paging / scrolling
    private int palettePage;
    private int paletteScroll;
    private int palRows = PAL_ROWS;
    /** Live widget metrics, scaled from the base sizes to fit the current resolution. */
    private int cell = BASE_SLOT;
    private int rowH = BASE_BTN_H;
    private int paletteGridX;
    private int paletteGridY;
    private int paletteGridW;
    private int paletteGridH;
    private final List<Button> pageButtons = new ArrayList<>();

    private final List<S2CLibraryPayload.Design> designs = new ArrayList<>();
    private boolean canSaveDesign;
    private boolean canImport;
    private int designLimit = PcbsConfig.DEFAULT_MAX_DESIGNS;
    private boolean libraryOpen;
    private int libraryScroll;
    private String designName = "";
    private boolean naming;
    private int nameCursor;
    private int nameFieldX;
    private int nameFieldY;
    private int nameFieldW;
    private int nameFieldH;
    private String libraryMessage = "";
    private boolean importOpen;
    private final List<String> importNames = new ArrayList<>();
    private final List<Path> importPaths = new ArrayList<>();
    private int importScroll;

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

    public void acceptSnapshot(byte[] data, byte[] faces) {
        local = GridSerializer.read(data, blockLookup());
        attachFaces.clear();
        attachFaces.putAll(PcbAttach.decode(faces));
    }

    private HolderGetter<Block> blockLookup() {
        return this.minecraft != null && this.minecraft.level != null
                ? this.minecraft.level.holderLookup(Registries.BLOCK)
                : null;
    }

    public void acceptLibrary(S2CLibraryPayload payload) {
        designs.clear();
        designs.addAll(payload.designs());
        canSaveDesign = payload.canSave();
        canImport = payload.canImport();
        designLimit = payload.limit();
        libraryOpen = true;
        importOpen = false;
        libraryScroll = 0;
        naming = false;
        searching = false;
        designName = "";
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
        sendLibrary(action, index, "");
    }

    private void sendLibrary(int action, int index, String name) {
        RedstonePcbs.platform().sendToServer(kind == C2SEditPayload.KIND_ITEM
                ? C2SLibraryPayload.item(slot, action, index, name)
                : C2SLibraryPayload.block(pos, action, index, name));
    }

    private void confirmSave() {
        naming = false;
        if (canSaveDesign) {
            sendLibrary(C2SLibraryPayload.ACTION_SAVE, 0, designName.strip());
        }
    }

    // --- layout -----------------------------------------------------------------------------------

    private void recalcLayout() {
        int top = 26;
        int margin = 8;
        int availH = Math.max(80, this.height - top - margin);

        // Autoscale the widgets to the resolution: the full 9x3 layout targets the base metrics, but
        // shrinks (or grows) so the panel chrome plus three grid rows always fit the window height.
        int targetRows = PAL_ROWS;
        int fullContent = CHROME_H + targetRows * BASE_SLOT;
        float scale = Math.min(1.35F, Math.max(0.6F, availH / (float) fullContent));
        cell = Math.max(10, Math.round(BASE_SLOT * scale));
        rowH = Math.max(9, Math.round(BASE_BTN_H * scale));

        int panelIdeal = Math.max(PAL_COLS * cell, 140);
        int availW = this.width - panelIdeal - margin * 3;
        int size = Math.max(80, Math.min(availH, availW));
        int contentW = size + 12 + panelIdeal;
        gridX = (this.width - contentW) / 2;
        gridY = top + Math.max(0, (availH - size) / 2);
        gridSize = size;
        panelX = gridX + size + 12;
        panelY = gridY;
        panelW = panelIdeal;

        // Fit the palette grid into whatever row budget remains; at least one row is always shown.
        int chrome = Math.round(CHROME_H * (cell / (float) BASE_SLOT));
        int gridSpace = Math.max(cell, availH - chrome);
        palRows = Math.max(1, Math.min(targetRows, gridSpace / cell));
    }

    // --- rendering --------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        recalcLayout();
        computeInventory();
        graphics.fill(0, 0, this.width, this.height, 0xB0101010);
        graphics.drawString(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 8,
                0xFFFFFF, false);

        buttons.clear();
        paletteSlots.clear();

        if (importOpen) {
            graphics.fill(0, 0, this.width, this.height, 0xC0000000);
            renderImportPanel(graphics, mouseX, mouseY);
            // Deferred tooltips are flushed by Screen.renderWithTooltip after render() returns.
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        if (libraryOpen) {
            graphics.fill(0, 0, this.width, this.height, 0xC0000000);
            renderLibrary(graphics, mouseX, mouseY);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        raycast(mouseX, mouseY);
        render3D(graphics);
        renderPanel(graphics, mouseX, mouseY);
        // Queued rather than drawn here, so renderWithTooltip emits it after the whole screen render.
        drawTooltip();
    }

    private void drawTooltip() {
        Component tip = buildTooltip();
        if (tip == null) {
            clearTooltipForNextRenderPass();
        } else {
            setTooltipForNextRenderPass(tip);
        }
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
        // Reset the depth buffer so the wireframe, panel and tooltips draw over the block models.
        graphics.flush();
        RenderSystem.clearDepth(1.0);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
        RenderSystem.disableDepthTest();
        drawBoundsAndGizmo(graphics);
        graphics.disableScissor();
    }

    /** Board bounding-box wireframe plus a small fixed-size corner axis gizmo. */
    private void drawBoundsAndGizmo(GuiGraphics graphics) {
        Matrix4f mvp = mvp();
        drawBoxWireframe(graphics, mvp, 0, 0, 0, GRID, GRID, GRID, 0x60FFFFFF);
        // Highlight the active layer so the layer selection has a visible purpose.
        drawBoxWireframe(graphics, mvp, 0, activeLayer, 0, GRID, activeLayer + 1, GRID, 0x9000E0FF);
        // Highlight each attached PCB face in a single neutral colour (the gateway is attachment-only;
        // the in-board container's own face rules decide insert/extract, not the PCB face).
        int nudge = 1;
        for (Dir face : attachFaces.values()) {
            int color = 0xC060C0FF;
            switch (face) {
                case DOWN -> drawBoxWireframe(graphics, mvp, 0, -nudge, 0, GRID, 0, GRID, color);
                case UP -> drawBoxWireframe(graphics, mvp, 0, GRID, 0, GRID, GRID + nudge, GRID, color);
                case NORTH -> drawBoxWireframe(graphics, mvp, 0, 0, -nudge, GRID, GRID, 0, color);
                case SOUTH -> drawBoxWireframe(graphics, mvp, 0, 0, GRID, GRID, GRID, GRID + nudge, color);
                case WEST -> drawBoxWireframe(graphics, mvp, -nudge, 0, 0, 0, GRID, GRID, color);
                case EAST -> drawBoxWireframe(graphics, mvp, GRID, 0, 0, GRID + nudge, GRID, GRID, color);
            }
        }

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

            for (int i = 0; i < GridSerializer.COUNT; i++) {
                if (local[i].isAir() || local[i].is(Blocks.GLASS) || enclosed(i)) {
                    continue;
                }
                drawModelCell(dispatcher, buffers, pose, i);
            }
            buffers.endBatch();
            for (int i = 0; i < GridSerializer.COUNT; i++) {
                if (local[i].is(Blocks.GLASS)) {
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
        pose.translate(BoardSpace.xOf(index), BoardSpace.yOf(index), BoardSpace.zOf(index));
        dispatcher.renderSingleBlock(local[index], pose, buffers,
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
            int x = BoardSpace.xOf(index) + d.dx;
            int y = BoardSpace.yOf(index) + d.dy;
            int z = BoardSpace.zOf(index) + d.dz;
            if (!inGrid(x, y, z) || !local[BoardSpace.index(x, y, z)].canOcclude()) {
                return false;
            }
        }
        return true;
    }

    private static boolean inGrid(int x, int y, int z) {
        return x >= 0 && x < BoardSpace.SIZE && y >= 0 && y < BoardSpace.SIZE && z >= 0 && z < BoardSpace.SIZE;
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
            if (!inGrid(cx, cy, cz) || (cx == px && cy == py && cz == pz)) {
                continue;
            }
            px = cx;
            py = cy;
            pz = cz;
            if (!local[BoardSpace.index(cx, cy, cz)].isAir()) {
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
        if (importOpen) {
            for (Button b : buttons) {
                if (b.contains(mouseX, mouseY)) {
                    b.action.run();
                    return true;
                }
            }
            return true;
        }
        if (libraryOpen) {
            if (inNameField(mouseX, mouseY)) {
                searching = false;
                naming = true;
                nameCursor = designName.length();
                return true;
            }
            for (Button b : buttons) {
                if (b.contains(mouseX, mouseY)) {
                    naming = false;
                    b.action.run();
                    return true;
                }
            }
            naming = false;
            return true;
        }
        for (Button b : buttons) {
            if (b.contains(mouseX, mouseY)) {
                b.action.run();
                return true;
            }
        }
        for (Button b : pageButtons) {
            if (b.contains(mouseX, mouseY)) {
                b.action.run();
                return true;
            }
        }
        for (TabRect t : tabRects) {
            if (t.contains(mouseX, mouseY)) {
                naming = false;
                activeTab = t.tab();
                palettePage = 0;
                paletteScroll = 0;
                if (t.tab() == PcbIcons.Tab.SEARCH) {
                    searching = true;
                    searchCursor = search.length();
                } else {
                    // Match the creative inventory: leaving the search tab clears the query.
                    searching = false;
                    search = "";
                    searchCursor = 0;
                }
                return true;
            }
        }
        if (inSearchField(mouseX, mouseY)) {
            naming = false;
            searching = true;
            searchCursor = search.length();
            return true;
        }
        searching = false;
        for (PaletteSlot s : paletteSlots) {
            if (s.contains(mouseX, mouseY)) {
                if (creative || countOf(s.entry().item()) > 0) {
                    selectEntry(s.entry());
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
                int index = BoardSpace.index(hoverBlock[0], hoverBlock[1], hoverBlock[2]);
                Part part = BoardStates.partOf(local[index]);
                if (part.isContainer() && kind == C2SEditPayload.KIND_BLOCK) {
                    EditorReturn.stash(this);
                    send(C2SEditPayload.ACTION_OPEN_UI, index, null, null, false);
                } else {
                    send(C2SEditPayload.ACTION_INTERACT, index, null, null, false);
                    local[index] = BoardEdit.interact(local[index]);
                }
            }
            return;
        }
        if (hasShiftDown()) {
            if (hoverBlock != null) {
                int index = BoardSpace.index(hoverBlock[0], hoverBlock[1], hoverBlock[2]);
                send(C2SEditPayload.ACTION_CLEAR, index, null, null, false);
                local[index] = Blocks.AIR.defaultBlockState();
            }
            return;
        }
        if (placeBlock != null) {
            placeAt(placeBlock[0], placeBlock[1], placeBlock[2]);
        }
    }

    private void placeAt(int x, int y, int z) {
        if (selectedEntry == null) {
            return;
        }
        int index = BoardSpace.index(x, y, z);
        Dir facing = resolveFacing(selected, x, y, z);
        if (facing == null) {
            return;
        }
        String id = PcbIcons.idOf(selectedEntry.item());
        sendPlace(index, id, facing);
        BlockState preview = BoardStates.forItem(selectedEntry.item(), facing);
        if (preview != null) {
            local[index] = preview;
        }
    }

    private void sendPlace(int index, String itemId, Dir facing) {
        RedstonePcbs.platform().sendToServer(kind == C2SEditPayload.KIND_ITEM
                ? C2SEditPayload.placeItem(slot, index, itemId, facing.ordinal(), 0)
                : C2SEditPayload.place(pos, index, itemId, facing.ordinal(), 0));
    }

    private Dir resolveFacing(Part part, int x, int y, int z) {
        Dir hit = adjacentDir(x, y, z);
        // An arbitrary block (no known part) has no facing family; use the pending/adjacent face.
        if (part == Part.AIR) {
            return hit != null ? hit : pendingFacing;
        }
        return switch (part.facingFamily()) {
            case TORCH -> {
                // Attach to the clicked face if it can support a torch, else any supported side.
                if (hit != null && hasSupport(x, y, z, hit)) {
                    yield hit;
                }
                for (Dir d : Part.SUPPORTED_ORDER) {
                    if (hasSupport(x, y, z, d)) {
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

    private boolean hasSupport(int x, int y, int z, Dir dir) {
        if (dir == Dir.DOWN && y == 0) {
            return true;
        }
        int nx = x + dir.dx;
        int ny = y + dir.dy;
        int nz = z + dir.dz;
        return inGrid(nx, ny, nz) && local[BoardSpace.index(nx, ny, nz)].canOcclude();
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
        if (overPaletteGrid(mouseX, mouseY) && !hasControlDown()) {
            scrollPalette(scrollY > 0 ? -PAL_COLS : PAL_COLS);
        } else if (hasControlDown()) {
            zoomBy(scrollY > 0 ? 0.05F : -0.05F, mouseX, mouseY);
        } else {
            activeLayer = Math.floorMod(activeLayer + (scrollY > 0 ? 1 : -1), GRID);
        }
        return true;
    }

    private boolean overPaletteGrid(double mouseX, double mouseY) {
        return mouseX >= paletteGridX && mouseX < paletteGridX + paletteGridW
                && mouseY >= paletteGridY && mouseY < paletteGridY + paletteGridH;
    }

    /** Scrolls the palette window by {@code delta} entries, advancing the page at the ends. */
    private void scrollPalette(int delta) {
        int total = visibleEntries().size();
        int page = pageSize();
        if (total <= page) {
            paletteScroll = 0;
            return;
        }
        int abs = palettePage * page + paletteScroll + delta;
        abs = Math.max(0, Math.min(abs, total - page));
        palettePage = abs / page;
        paletteScroll = abs % page;
    }

    private int pageSize() {
        return PAL_COLS * palRows;
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
    public boolean charTyped(char codePoint, int modifiers) {
        if (naming) {
            if (codePoint >= ' ' && codePoint != '\u00a7' && designName.length() < LibraryData.MAX_NAME_LENGTH) {
                int cursor = Math.max(0, Math.min(nameCursor, designName.length()));
                designName = designName.substring(0, cursor) + codePoint + designName.substring(cursor);
                nameCursor = cursor + 1;
            }
            return true;
        }
        if (searching) {
            if (codePoint >= ' ' && codePoint != '\u00a7' && search.length() < 32) {
                int cursor = Math.max(0, Math.min(searchCursor, search.length()));
                search = search.substring(0, cursor) + codePoint + search.substring(cursor);
                searchCursor = cursor + 1;
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (importOpen) {
            if (keyCode == 256) { // Escape
                importOpen = false;
            }
            return true;
        }
        if (searching) {
            switch (keyCode) {
                case 256 -> { // Escape
                    searching = false;
                    return true;
                }
                case 259 -> { // Backspace
                    if (searchCursor > 0 && !search.isEmpty()) {
                        int cursor = Math.min(searchCursor, search.length());
                        search = search.substring(0, cursor - 1) + search.substring(cursor);
                        searchCursor = cursor - 1;
                    }
                    return true;
                }
                case 261 -> { // Delete
                    if (searchCursor < search.length()) {
                        search = search.substring(0, searchCursor) + search.substring(searchCursor + 1);
                    }
                    return true;
                }
                case 262 -> { // Right
                    searchCursor = Math.min(search.length(), searchCursor + 1);
                    return true;
                }
                case 263 -> { // Left
                    searchCursor = Math.max(0, searchCursor - 1);
                    return true;
                }
                default -> {
                    // Let charTyped consume printable keys; swallow everything else so hotkeys stay off.
                    return true;
                }
            }
        }
        if (naming) {
            switch (keyCode) {
                case 257, 335 -> { // Enter / keypad Enter
                    confirmSave();
                    return true;
                }
                case 256 -> { // Escape
                    naming = false;
                    return true;
                }
                case 259 -> { // Backspace
                    if (nameCursor > 0 && !designName.isEmpty()) {
                        int cursor = Math.min(nameCursor, designName.length());
                        designName = designName.substring(0, cursor - 1) + designName.substring(cursor);
                        nameCursor = cursor - 1;
                    }
                    return true;
                }
                case 261 -> { // Delete
                    if (nameCursor < designName.length()) {
                        designName = designName.substring(0, nameCursor) + designName.substring(nameCursor + 1);
                    }
                    return true;
                }
                case 262 -> { // Right
                    nameCursor = Math.min(designName.length(), nameCursor + 1);
                    return true;
                }
                case 263 -> { // Left
                    nameCursor = Math.max(0, nameCursor - 1);
                    return true;
                }
                default -> {
                    // Let charTyped consume printable keys; swallow everything else so hotkeys stay off.
                    return true;
                }
            }
        }
        int index = hoverBlock == null ? -1 : BoardSpace.index(hoverBlock[0], hoverBlock[1], hoverBlock[2]);
        switch (keyCode) {
            case 82 -> { // R
                if (index >= 0) {
                    send(C2SEditPayload.ACTION_ROTATE, index, null, null, false);
                    local[index] = BoardEdit.rotate(local[index]);
                } else {
                    rotatePendingFacing();
                }
                return true;
            }
            case 71 -> { // G: cycle the hovered container's gateway face
                if (index >= 0 && isContainerCell(index)) {
                    cycleAttachFace(index);
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

    /**
     * Cycles the hovered cell's gateway face: none -> each face not already held by another cell ->
     * none. The server validates the choice and echoes a fresh snapshot.
     */
    private void cycleAttachFace(int index) {
        Dir current = attachFaces.get(index);
        // Faces this cell may take: every face except the current one and those held elsewhere.
        List<Dir> free = new ArrayList<>();
        for (Dir face : Dir.VALUES) {
            if (face == current) {
                continue;
            }
            boolean taken = attachFaces.entrySet().stream()
                    .anyMatch(e -> e.getKey() != index && e.getValue() == face);
            if (!taken) {
                free.add(face);
            }
        }
        Dir next;
        if (free.isEmpty()) {
            next = null;
        } else if (current == null) {
            next = free.get(0);
        } else {
            // The first free face after the current in DIR order; wrap to none when there is none.
            int currentOrdinal = current.ordinal();
            next = free.stream().filter(f -> f.ordinal() > currentOrdinal).findFirst().orElse(null);
        }
        sendFace(index, next);
        if (next == null) {
            attachFaces.remove(index);
        } else {
            attachFaces.put(index, next);
        }
    }

    private void sendFace(int index, Dir face) {
        int packed = face == null ? 0xFF : face.ordinal();
        RedstonePcbs.platform().sendToServer(kind == C2SEditPayload.KIND_ITEM
                ? C2SEditPayload.faceItem(slot, index, packed)
                : C2SEditPayload.face(pos, index, packed));
    }

    /** Whether the editor's local grid copy has a container in the cell (only these can be attached). */
    private boolean isContainerCell(int index) {
        if (index < 0 || index >= local.length) {
            return false;
        }
        return BoardStates.partOf(local[index]).isContainer();
    }

    private void selectEntry(PcbIcons.Entry entry) {
        selectedEntry = entry;
        Part part = BoardStates.partFor(entry.item());
        selected = part == null ? Part.AIR : part;
        // A directional known part starts aimed sensibly; a plain block stays flat.
        pendingFacing = (part != null && (part.needsSupport() || part.pointsAtNeighbour()))
                ? Dir.DOWN : Dir.NORTH;
    }

    // --- panel ------------------------------------------------------------------------------------

    private void renderPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int px = panelX;
        int py = panelY;

        // Tab strip: uniform square icon tabs (creative-style), tooltip shows the name.
        tabRects.clear();
        int tabY = py;
        int tx = px;
        int tabSize = cell;
        for (PcbIcons.Tab tab : PcbIcons.TABS) {
            if (tx + tabSize > px + panelW) {
                tx = px;
                tabY += tabSize + 2;
            }
            boolean active = activeTab == tab;
            boolean hovered = mouseX >= tx && mouseX < tx + tabSize && mouseY >= tabY && mouseY < tabY + tabSize;
            int bg = active ? 0xFF8B8B8B : (hovered ? 0xFF505050 : 0xFF303030);
            graphics.fill(tx, tabY, tx + tabSize, tabY + tabSize, bg);
            graphics.fill(tx + 1, tabY + 1, tx + tabSize - 1, tabY + tabSize - 1,
                    active ? 0xFF5A5A5A : 0xFF212121);
            drawItem(graphics, new ItemStack(tab.icon), tx + 1, tabY + 1, tabSize - 2);
            tabRects.add(new TabRect(tab, tx, tabY, tabSize, tabSize));
            tx += tabSize + 2;
        }
        int y = tabY + tabSize + 4;

        // Search field, shown only on the search tab (as in the creative inventory).
        if (activeTab == PcbIcons.Tab.SEARCH) {
            searchX = px;
            searchY = y;
            searchW = panelW;
            searchH = 16;
            graphics.fill(searchX, searchY, searchX + searchW, searchY + searchH,
                    searching ? 0xFF101010 : 0xFF181818);
            int border = searching ? 0xFF9FA9FF : 0xFF606060;
            graphics.fill(searchX, searchY, searchX + searchW, searchY + 1, border);
            graphics.fill(searchX, searchY + searchH - 1, searchX + searchW, searchY + searchH, border);
            graphics.fill(searchX, searchY, searchX + 1, searchY + searchH, border);
            graphics.fill(searchX + searchW - 1, searchY, searchX + searchW, searchY + searchH, border);
            if (search.isEmpty()) {
                graphics.drawString(this.font, "Search", searchX + 4, searchY + 4, 0xFF707070, false);
            } else {
                graphics.drawString(this.font, fit(search, searchW - 8), searchX + 4, searchY + 4, 0xFFFFFF,
                        false);
            }
            if (searching && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                int cursor = Math.max(0, Math.min(searchCursor, search.length()));
                int cx = searchX + 4 + this.font.width(search.substring(0, cursor));
                graphics.fill(cx, searchY + 3, cx + 1, searchY + searchH - 3, 0xFFFFFFFF);
            }
            y += searchH + 4;
        } else {
            // No search box on a category tab, so its hit-box must not linger from the last frame.
            searchW = 0;
        }

        // Palette grid: 9 columns, 1-3 rows, paged and wheel-scrollable when there are more items.
        List<PcbIcons.Entry> visible = visibleEntries();
        int total = visible.size();
        int page = pageSize();
        int pageCount = Math.max(1, (int) Math.ceil(total / (double) page));
        palettePage = Math.max(0, Math.min(palettePage, pageCount - 1));
        paletteScroll = Math.max(0, Math.min(paletteScroll, Math.max(0, total - page)));
        // Scrolling shifts the window; paging snaps it. Only one is non-zero at a time.
        int start = Math.min(palettePage * page + paletteScroll, Math.max(0, total - 1));
        if (total == 0) {
            start = 0;
        }

        paletteGridX = px;
        paletteGridY = y;
        paletteGridW = PAL_COLS * cell;
        paletteGridH = palRows * cell;
        for (int i = 0; i < page; i++) {
            int idx = start + i;
            if (idx >= total) {
                break;
            }
            PcbIcons.Entry entry = visible.get(idx);
            int sx = px + (i % PAL_COLS) * cell;
            int sy = y + (i / PAL_COLS) * cell;
            boolean active = selectedEntry != null && selectedEntry.item() == entry.item();
            boolean available = creative || countOf(entry.item()) > 0;
            drawSlot(graphics, sx, sy, active, available);
            drawItem(graphics, entry.stack(), sx + 1, sy + 1, 16);
            int count = countOf(entry.item());
            if (!creative && count > 1) {
                graphics.drawString(this.font, String.valueOf(count), sx + 11, sy + 9, 0xFFFFFF, true);
            }
            paletteSlots.add(new PaletteSlot(entry, sx, sy, cell, cell));
        }

        y = paletteGridY + paletteGridH + 2;

        // Page / scroll indicator with arrows (both paging and hover-wheel scrolling are supported).
        pageButtons.clear();
        if (pageCount > 1 || total > page) {
            addPageButton(graphics, px, y, "<", () -> {
                if (paletteScroll > 0) {
                    paletteScroll = Math.max(0, paletteScroll - PAL_COLS);
                } else if (palettePage > 0) {
                    palettePage--;
                }
            });
            int pageNo = Math.min(palettePage + 1, pageCount);
            graphics.drawString(this.font, pageNo + "/" + pageCount, px + 22, y + 3, 0xE0E0E0, false);
            addPageButton(graphics, px + 46, y, ">", () -> {
                int maxScroll = Math.max(0, total - page);
                if (palettePage < pageCount - 1) {
                    palettePage++;
                    paletteScroll = 0;
                } else if (paletteScroll < maxScroll) {
                    paletteScroll = Math.min(maxScroll, paletteScroll + PAL_COLS);
                }
            });
            y += rowH + 2;
        } else {
            paletteScroll = 0;
        }

        y += 4;

        // View presets: two compact rows (5 + 4), sized to the panel width.
        for (int i = 0; i < PRESETS.length; i++) {
            int col = i % 5;
            int row = i / 5;
            int bx = px + col * (panelW / 5);
            int by = y + row * (rowH + 1);
            final int idx = i;
            addButton(graphics, bx, by, panelW / 5 - 2, rowH, PRESET_LABELS[i], () -> {
                yaw = PRESETS[idx][0];
                pitch = PRESETS[idx][1];
            });
        }
        y += (rowH + 1) * 2 + 3;

        // Zoom row.
        addButton(graphics, px, y, 20, rowH, "-",
                () -> zoomBy(-0.05F, gridX + gridSize / 2.0F, gridY + gridSize / 2.0F));
        addButton(graphics, px + 22, y, 20, rowH, "+",
                () -> zoomBy(0.05F, gridX + gridSize / 2.0F, gridY + gridSize / 2.0F));
        addButton(graphics, px + 44, y, 46, rowH, "Reset", () -> {
            zoom = 0.9F;
            panX = 0.0F;
            panY = 0.0F;
        });
        graphics.drawString(this.font, Math.round(zoom * 100) + "%", px + 94, y + 3, 0xFFFFFF, false);
        y += rowH + 2;

        // Layer row: zoom out/in, layer number, and the pulse button on the same line.
        addButton(graphics, px, y, 20, rowH, "-", () -> activeLayer = Math.max(0, activeLayer - 1));
        addButton(graphics, px + 22, y, 20, rowH, "+", () -> activeLayer = Math.min(GRID - 1, activeLayer + 1));
        graphics.drawString(this.font, "L " + (activeLayer + 1) + "/16", px + 46, y + 3, 0xFFFFFF, false);
        addButton(graphics, px + 90, y, panelW - 90, rowH, "Pulse Layer", () -> {
            send(C2SEditPayload.ACTION_PULSE_LAYER, activeLayer, null, null, false);
            for (int lx = 0; lx < BoardSpace.SIZE; lx++) {
                for (int lz = 0; lz < BoardSpace.SIZE; lz++) {
                    int idx = BoardSpace.index(lx, activeLayer, lz);
                    local[idx] = BoardEdit.pulse(local[idx]);
                }
            }
        });
        y += rowH + 3;

        // Signal inputs and the library, side by side.
        int half = (panelW - 2) / 2;
        addButton(graphics, px, y, half, rowH, inputOn ? "Input: On" : "Input: Off",
                () -> {
                    send(C2SEditPayload.ACTION_TOGGLE_INPUT, 0, null, null, false);
                    inputOn = !inputOn;
                });
        addButton(graphics, px + half + 2, y, panelW - half - 2, rowH,
                outputOn ? "Output: On" : "Output: Off", () -> {
                    send(C2SEditPayload.ACTION_TOGGLE_OUTPUT, 0, null, null, false);
                    outputOn = !outputOn;
                });
        y += rowH + 3;

        addButton(graphics, px, y, panelW, rowH, "Library", () -> {
            libraryMessage = "";
            searching = false;
            libraryOpen = true;
            sendLibrary(C2SLibraryPayload.ACTION_LIST, 0);
        });
        y += rowH + 4;

        graphics.drawString(this.font, "L: place  Shift+L: erase  R-click: use", px, y, 0x9F9F9F, false);
        graphics.drawString(this.font, "R: rotate  G: gateway face", px, y + 10, 0x9F9F9F, false);
    }

    private void drawSlot(GuiGraphics graphics, int x, int y, boolean active, boolean hasStock) {
        graphics.fill(x, y, x + cell - 2, y + cell - 2, active ? 0xFFFFE080 : 0xFF373737);
        graphics.fill(x + 1, y + 1, x + cell - 3, y + cell - 3, 0xFF8B8B8B);
        if (!hasStock) {
            graphics.fill(x + 1, y + 1, x + cell - 3, y + cell - 3, 0x80000000);
        }
    }

    private void addButton(GuiGraphics graphics, int x, int y, int w, int h, String label, Runnable action) {
        boolean hovered = lastMouseX >= x && lastMouseX < x + w && lastMouseY >= y && lastMouseY < y + h;
        graphics.fill(x, y, x + w, y + h, hovered ? 0xFF505050 : 0xFF303030);
        graphics.drawString(this.font, fit(label, w - 6), x + 3, y + 3, 0xE0E0E0, false);
        buttons.add(new Button(x, y, w, h, action));
    }

    /** A small palette page/scroll arrow, registered separately from the main buttons list. */
    private void addPageButton(GuiGraphics graphics, int x, int y, String label, Runnable action) {
        int w = 20;
        boolean hovered = lastMouseX >= x && lastMouseX < x + w && lastMouseY >= y && lastMouseY < y + rowH;
        graphics.fill(x, y, x + w, y + rowH, hovered ? 0xFF505050 : 0xFF303030);
        graphics.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + 3, 0xE0E0E0, false);
        pageButtons.add(new Button(x, y, w, rowH, action));
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
        int listH = Math.max(rows, 1) * 18;
        int w = 300;
        int h = 44 + listH + 48;
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        graphics.fill(x, y, x + w, y + h, 0xFF202020);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF3C3C3C);
        graphics.drawString(this.font, "Saved Designs", x + 8, y + 8, 0xFFFFFF, false);
        graphics.drawString(this.font, designs.size() + "/" + designLimit
                + " saved per player; paper is consumed on save", x + 8, y + 19, 0x909090, false);
        if (!libraryMessage.isEmpty()) {
            graphics.drawString(this.font, fit(libraryMessage, w - 16), x + 8, y + 30, 0xA0D0A0, false);
        }
        int rowY = y + 44;
        for (int i = 0; i < rows; i++) {
            final int idx = libraryScroll + i;
            if (idx >= designs.size()) {
                break;
            }
            graphics.drawString(this.font, fit(designs.get(idx).name(), w - 118), x + 8, rowY + 3, 0xE0E0E0,
                    false);
            addButton(graphics, x + w - 102, rowY, 28, 14, "Exp", () -> exportDesign(idx));
            addButton(graphics, x + w - 72, rowY, 30, 14, "Use", () -> {
                libraryMessage = "";
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
            addButton(graphics, x + w / 2 - 18, by, 18, 14, "-",
                    () -> libraryScroll = Math.max(0, libraryScroll - 1));
            addButton(graphics, x + w / 2 + 2, by, 18, 14, "+", () -> libraryScroll = Math.min(
                    Math.max(0, designs.size() - 8), libraryScroll + 1));
        }
        addButton(graphics, x + 8, by, 70, 14, canImport ? "Import" : "Import Off", () -> {
            if (canImport) {
                refreshImport();
                libraryMessage = "";
                importOpen = true;
            }
        });
        addButton(graphics, x + w - 60, by, 52, 14, "Close", () -> libraryOpen = false);

        int nameRow = y + 44 + listH + 4;
        int saveW = 58;
        nameFieldX = x + 8;
        nameFieldY = nameRow;
        nameFieldW = w - 16 - saveW - 4;
        nameFieldH = 16;
        drawNameField(graphics);
        String saveLabel = designs.size() >= designLimit ? "Full"
                : (canSaveDesign ? "Save" : "No Paper");
        addButton(graphics, x + w - 8 - saveW, nameRow, saveW, 16, saveLabel, this::confirmSave);
    }

    private void renderImportPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int rows = Math.min(importNames.size(), 8);
        int listH = Math.max(rows, 1) * 18;
        int w = 320;
        int h = 32 + listH + 44;
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        graphics.fill(x, y, x + w, y + h, 0xFF202020);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF3C3C3C);
        graphics.drawString(this.font, "Import Blueprint", x + 8, y + 8, 0xFFFFFF, false);
        graphics.drawString(this.font, "Files in " + BlueprintFiles.FOLDER, x + 8, y + 19, 0x909090, false);
        int rowY = y + 32;
        for (int i = 0; i < rows; i++) {
            final int idx = importScroll + i;
            if (idx >= importNames.size()) {
                break;
            }
            graphics.drawString(this.font, fit(importNames.get(idx), w - 70), x + 8, rowY + 3, 0xE0E0E0, false);
            addButton(graphics, x + w - 48, rowY, 40, 14, "Add", () -> importAt(idx));
            rowY += 18;
        }
        if (importNames.isEmpty()) {
            graphics.drawString(this.font, "(no .json files found)", x + 8, rowY + 3, 0x909090, false);
        }
        int by = y + h - 18;
        if (importNames.size() > 8) {
            addButton(graphics, x + w / 2 - 18, by, 18, 14, "-",
                    () -> importScroll = Math.max(0, importScroll - 1));
            addButton(graphics, x + w / 2 + 2, by, 18, 14, "+", () -> importScroll = Math.min(
                    Math.max(0, importNames.size() - 8), importScroll + 1));
        }
        addButton(graphics, x + 8, by, 60, 14, "Refresh", this::refreshImport);
        addButton(graphics, x + w - 60, by, 52, 14, "Back", () -> importOpen = false);
        if (!libraryMessage.isEmpty()) {
            graphics.drawString(this.font, fit(libraryMessage, w - 16), x + 8, y + h - 30, 0xA0D0A0, false);
        }
    }

    private void refreshImport() {
        importNames.clear();
        importPaths.clear();
        for (Path path : BlueprintFiles.list()) {
            importNames.add(path.getFileName().toString());
            importPaths.add(path);
        }
        importScroll = 0;
    }

    private void exportDesign(int idx) {
        if (idx < 0 || idx >= designs.size()) {
            return;
        }
        S2CLibraryPayload.Design design = designs.get(idx);
        try {
            Path file = BlueprintFiles.write(design.name(), design.data());
            libraryMessage = "Exported " + file.getFileName();
        } catch (Exception e) {
            libraryMessage = "Export failed";
        }
    }

    private void importAt(int idx) {
        if (idx < 0 || idx >= importPaths.size()) {
            return;
        }
        try {
            Blueprint blueprint = BlueprintFiles.read(importPaths.get(idx));
            RedstonePcbs.platform().sendToServer(new C2SLibraryPayload(kind, slot, pos,
                    C2SLibraryPayload.ACTION_IMPORT, 0, blueprint.name(), blueprint.grid()));
            libraryMessage = "Importing " + blueprint.name() + "...";
            importOpen = false;
        } catch (Exception e) {
            libraryMessage = "Could not read " + importNames.get(idx);
        }
    }

    private void drawNameField(GuiGraphics graphics) {
        int fx = nameFieldX;
        int fy = nameFieldY;
        int fw = nameFieldW;
        int fh = nameFieldH;
        graphics.fill(fx, fy, fx + fw, fy + fh, naming ? 0xFF101010 : 0xFF181818);
        int border = naming ? 0xFF9FA9FF : 0xFF606060;
        graphics.fill(fx, fy, fx + fw, fy + 1, border);
        graphics.fill(fx, fy + fh - 1, fx + fw, fy + fh, border);
        graphics.fill(fx, fy, fx + 1, fy + fh, border);
        graphics.fill(fx + fw - 1, fy, fx + fw, fy + fh, border);
        if (designName.isEmpty() && !naming) {
            graphics.drawString(this.font, "Name (optional)", fx + 4, fy + 4, 0xFF707070, false);
        } else {
            graphics.drawString(this.font, fit(designName, fw - 8), fx + 4, fy + 4, 0xFFFFFF, false);
        }
        if (naming && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cursor = Math.max(0, Math.min(nameCursor, designName.length()));
            int cx = fx + 4 + this.font.width(designName.substring(0, cursor));
            graphics.fill(cx, fy + 3, cx + 1, fy + fh - 3, 0xFFFFFFFF);
        }
    }

    private boolean inNameField(double mouseX, double mouseY) {
        return mouseX >= nameFieldX && mouseX < nameFieldX + nameFieldW
                && mouseY >= nameFieldY && mouseY < nameFieldY + nameFieldH;
    }

    private boolean inSearchField(double mouseX, double mouseY) {
        return activeTab == PcbIcons.Tab.SEARCH
                && mouseX >= searchX && mouseX < searchX + searchW
                && mouseY >= searchY && mouseY < searchY + searchH;
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

    /** The tooltip to show for whatever is under the cursor, or {@code null} for none. */
    private Component buildTooltip() {
        for (TabRect tab : tabRects) {
            if (lastMouseX >= tab.x() && lastMouseX < tab.x() + tab.w()
                    && lastMouseY >= tab.y() && lastMouseY < tab.y() + tab.h()) {
                return Component.literal(tab.tab().label);
            }
        }
        for (PaletteSlot slot : paletteSlots) {
            if (slot.contains(lastMouseX, lastMouseY)) {
                return slot.entry().stack().getHoverName();
            }
        }
        if (hoverBlock != null) {
            int index = BoardSpace.index(hoverBlock[0], hoverBlock[1], hoverBlock[2]);
            BlockState state = local[index];
            List<Component> lines = new ArrayList<>();
            lines.add(state.getBlock().getName());
            if (state.hasProperty(net.minecraft.world.level.block.RepeaterBlock.DELAY)) {
                lines.add(Component.literal("delay "
                        + state.getValue(net.minecraft.world.level.block.RepeaterBlock.DELAY)));
            } else if (state.hasProperty(net.minecraft.world.level.block.ComparatorBlock.MODE)) {
                lines.add(Component.literal(
                        state.getValue(net.minecraft.world.level.block.ComparatorBlock.MODE)
                                == net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT
                                        ? "subtract" : "compare"));
            } else if (state.is(Blocks.REDSTONE_WIRE)) {
                lines.add(Component.literal("power "
                        + state.getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER)));
            }
            Dir attach = attachFaces.get(index);
            if (attach != null) {
                lines.add(Component.literal("gateway: " + attach.name().toLowerCase(java.util.Locale.ROOT)));
            }
            return join(lines);
        }
        return null;
    }

    private static Component join(List<Component> lines) {
        net.minecraft.network.chat.MutableComponent joined = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                joined.append("\n");
            }
            joined.append(lines.get(i));
        }
        return joined;
    }

    /**
     * The palette entries to show: the active tab's creative items, filtered by the search query and
     * (outside creative) to items the player actually holds. Search is case-insensitive over the
     * display name.
     */
    private List<PcbIcons.Entry> visibleEntries() {
        String query = search.strip().toLowerCase(Locale.ROOT);
        List<PcbIcons.Entry> out = new ArrayList<>();
        for (PcbIcons.Entry entry : PcbIcons.entries(activeTab)) {
            if (!query.isEmpty()
                    && !entry.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            if (!creative && countOf(entry.item()) <= 0) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    private void computeInventory() {
        creative = this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.isCreative();
        itemCounts.clear();
        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.level == null) {
            return;
        }
        Inventory inv = this.minecraft.player.getInventory();
        countInto(inv.items);
        countInto(inv.offhand);
    }

    private void countInto(List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                itemCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
    }

    private int countOf(Item item) {
        return itemCounts.getOrDefault(item, 0);
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
        // Ask the server for the board every two ticks so live redstone changes show up.
        if (++localTickCounter >= 2) {
            localTickCounter = 0;
            send(C2SEditPayload.ACTION_REQUEST, 0, null, null, false);
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

    private record PaletteSlot(PcbIcons.Entry entry, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record TabRect(PcbIcons.Tab tab, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
