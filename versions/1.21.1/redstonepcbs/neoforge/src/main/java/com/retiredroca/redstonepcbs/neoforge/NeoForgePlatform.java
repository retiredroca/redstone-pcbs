package com.retiredroca.redstonepcbs.neoforge;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.block.PcbBlock;
import com.retiredroca.redstonepcbs.block.PcbBlockEntity;
import com.retiredroca.redstonepcbs.content.ModContent;
import com.retiredroca.redstonepcbs.data.ChipData;
import com.retiredroca.redstonepcbs.item.PcbItem;
import com.retiredroca.redstonepcbs.net.C2SEditPayload;
import com.retiredroca.redstonepcbs.net.C2SLibraryPayload;
import com.retiredroca.redstonepcbs.net.ClientPacketSink;
import com.retiredroca.redstonepcbs.net.ModNetwork;
import com.retiredroca.redstonepcbs.net.S2CLibraryPayload;
import com.retiredroca.redstonepcbs.net.S2COpenEditorPayload;
import com.retiredroca.redstonepcbs.net.S2CSnapshotPayload;
import com.retiredroca.redstonepcbs.platform.RedstonePcbsPlatform;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class NeoForgePlatform implements RedstonePcbsPlatform {
    private final IEventBus modBus;

    private final DeferredRegister<Block> blocks =
            DeferredRegister.create(BuiltInRegistries.BLOCK, RedstonePcbs.MOD_ID);
    private final DeferredRegister<Item> items =
            DeferredRegister.create(BuiltInRegistries.ITEM, RedstonePcbs.MOD_ID);
    private final DeferredRegister<BlockEntityType<?>> blockEntities =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, RedstonePcbs.MOD_ID);
    private final DeferredRegister<DataComponentType<?>> dataComponents =
            DeferredRegister.create(BuiltInRegistries.DATA_COMPONENT_TYPE, RedstonePcbs.MOD_ID);

    private final DeferredHolder<Block, PcbBlock> pcbBlock =
            blocks.register(ModContent.PCB_BLOCK_ID.getPath(),
                    () -> new PcbBlock(PcbBlock.boardProperties()));
    private final DeferredHolder<Item, PcbItem> pcbItem =
            items.register(ModContent.PCB_ITEM_ID.getPath(),
                    () -> new PcbItem(pcbBlock.get(), new Item.Properties()));
    private final DeferredHolder<BlockEntityType<?>, BlockEntityType<PcbBlockEntity>> pcbBeType =
            blockEntities.register(ModContent.PCB_BE_ID.getPath(),
                    () -> BlockEntityType.Builder.of(PcbBlockEntity::new, pcbBlock.get()).build(null));
    private final DeferredHolder<DataComponentType<?>, DataComponentType<ChipData>> chipComponent =
            dataComponents.register(ModContent.CHIP_ID.getPath(),
                    () -> DataComponentType.<ChipData>builder()
                            .persistent(ChipData.CODEC)
                            .networkSynchronized(ChipData.STREAM_CODEC)
                            .build());

    public NeoForgePlatform(IEventBus modBus) {
        this.modBus = modBus;
    }

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }

    @Override
    public void registerContent() {
        blocks.register(modBus);
        items.register(modBus);
        blockEntities.register(modBus);
        dataComponents.register(modBus);
        modBus.addListener(this::registerCapabilities);
    }

    private void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                pcbBeType.get(),
                (be, side) -> new net.neoforged.neoforge.items.wrapper.SidedInvWrapper(be, side));
    }

    @Override
    public void registerNetwork() {
        modBus.addListener(this::onRegisterPayloads);
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(C2SEditPayload.TYPE, C2SEditPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> ModNetwork.handleEdit((ServerPlayer) context.player(), payload)));
        registrar.playToServer(C2SLibraryPayload.TYPE, C2SLibraryPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> ModNetwork.handleLibrary((ServerPlayer) context.player(), payload)));
        registrar.playToClient(S2CSnapshotPayload.TYPE, S2CSnapshotPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPacketSink.snapshot(payload)));
        registrar.playToClient(S2COpenEditorPayload.TYPE, S2COpenEditorPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPacketSink.openEditor(payload)));
        registrar.playToClient(S2CLibraryPayload.TYPE, S2CLibraryPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPacketSink.library(payload)));
    }

    @Override
    public BlockEntityType<PcbBlockEntity> pcbBlockEntityType() {
        return pcbBeType.get();
    }

    @Override
    public DataComponentType<ChipData> chip() {
        return chipComponent.get();
    }

    @Override
    public Item pcbItem() {
        return pcbItem.get();
    }

    @Override
    public <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public <T extends CustomPacketPayload> void sendToServer(T payload) {
        PacketDistributor.sendToServer(payload);
    }
}
