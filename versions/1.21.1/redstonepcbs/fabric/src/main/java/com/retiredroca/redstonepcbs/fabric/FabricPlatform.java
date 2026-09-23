package com.retiredroca.redstonepcbs.fabric;

import com.retiredroca.redstonepcbs.block.PcbBlock;
import com.retiredroca.redstonepcbs.block.PcbBlockEntity;
import com.retiredroca.redstonepcbs.content.ModContent;
import com.retiredroca.redstonepcbs.data.ChipData;
import com.retiredroca.redstonepcbs.item.PcbItem;
import com.retiredroca.redstonepcbs.net.C2SEditPayload;
import com.retiredroca.redstonepcbs.net.C2SLibraryPayload;
import com.retiredroca.redstonepcbs.net.ModNetwork;
import com.retiredroca.redstonepcbs.net.S2CLibraryPayload;
import com.retiredroca.redstonepcbs.net.S2COpenEditorPayload;
import com.retiredroca.redstonepcbs.net.S2CSnapshotPayload;
import com.retiredroca.redstonepcbs.platform.RedstonePcbsPlatform;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class FabricPlatform implements RedstonePcbsPlatform {
    private BlockEntityType<PcbBlockEntity> pcbBeType;
    private DataComponentType<ChipData> chipComponent;
    private Item pcbItem;

    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public void registerContent() {
        PcbBlock pcbBlock = new PcbBlock(PcbBlock.boardProperties());
        Registry.register(BuiltInRegistries.BLOCK, ModContent.PCB_BLOCK_ID, pcbBlock);
        pcbItem = new PcbItem(pcbBlock, new Item.Properties());
        Registry.register(BuiltInRegistries.ITEM, ModContent.PCB_ITEM_ID, pcbItem);

        pcbBeType = BlockEntityType.Builder.of(PcbBlockEntity::new, pcbBlock).build();
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModContent.PCB_BE_ID, pcbBeType);

        chipComponent = DataComponentType.<ChipData>builder()
                .persistent(ChipData.CODEC)
                .networkSynchronized(ChipData.STREAM_CODEC)
                .build();
        Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, ModContent.CHIP_ID, chipComponent);

        net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
                .modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.REDSTONE_BLOCKS)
                .register(entries -> entries.accept(pcbItem));
    }

    @Override
    public void registerNetwork() {
        PayloadTypeRegistry.playC2S().register(C2SEditPayload.TYPE, C2SEditPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CSnapshotPayload.TYPE, S2CSnapshotPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(S2COpenEditorPayload.TYPE, S2COpenEditorPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SLibraryPayload.TYPE, C2SLibraryPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CLibraryPayload.TYPE, S2CLibraryPayload.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(C2SEditPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> ModNetwork.handleEdit(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(C2SLibraryPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> ModNetwork.handleLibrary(context.player(), payload)));
    }

    @Override
    public BlockEntityType<PcbBlockEntity> pcbBlockEntityType() {
        return pcbBeType;
    }

    @Override
    public DataComponentType<ChipData> chip() {
        return chipComponent;
    }

    @Override
    public Item pcbItem() {
        return pcbItem;
    }

    @Override
    public <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public <T extends CustomPacketPayload> void sendToServer(T payload) {
        ClientPlayNetworking.send(payload);
    }
}
