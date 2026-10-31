package education.poiesis.createbattlebots.block;

import education.poiesis.createbattlebots.CreateBattleBots;
import education.poiesis.createbattlebots.menu.GarageMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BattleBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateBattleBots.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateBattleBots.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, CreateBattleBots.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(BuiltInRegistries.MENU, CreateBattleBots.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateBattleBots.MOD_ID);

    public static final DeferredBlock<BattleNodeBlock> TRANSPONDER = BLOCKS.register("battle_transponder", () -> new BattleNodeBlock(properties(), BattleNodeBlock.Kind.CORE));
    public static final DeferredBlock<BattleNodeBlock> ARMOR = BLOCKS.register("combat_armor", () -> new BattleNodeBlock(properties(), BattleNodeBlock.Kind.ARMOR));
    public static final DeferredBlock<BattleNodeBlock> RELAY = BLOCKS.register("damage_relay", () -> new BattleNodeBlock(properties(), BattleNodeBlock.Kind.RELAY));
    public static final DeferredBlock<GarageBlock> GARAGE = BLOCKS.register("garage_console", () -> new GarageBlock(properties()));
    public static final DeferredBlock<GarageBlock> ARENA_CONTROLLER = BLOCKS.register("arena_controller", () -> new GarageBlock(properties()));
    public static final DeferredBlock<CombatPartBlock> WHEEL = BLOCKS.register("combat_wheel", () -> new CombatPartBlock(properties().noOcclusion(), true));
    public static final DeferredBlock<CombatPartBlock> SAW = BLOCKS.register("combat_saw", () -> new CombatPartBlock(properties().noOcclusion(), false));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BattleNodeEntity>> NODE_ENTITY = ENTITIES.register("battle_node", () -> BlockEntityType.Builder.of(BattleNodeEntity::new, TRANSPONDER.get(), ARMOR.get(), RELAY.get(), WHEEL.get(), SAW.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<GarageMenu>> GARAGE_MENU = MENUS.register("garage", () -> IMenuTypeExtension.create((id, inv, data) -> new GarageMenu(id, inv, data.readBlockPos())));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("battle_bots", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.createbattlebots"))
            .icon(() -> TRANSPONDER.asItem().getDefaultInstance())
            .displayItems((params, output) -> { output.accept(TRANSPONDER); output.accept(ARMOR); output.accept(RELAY); output.accept(GARAGE); output.accept(ARENA_CONTROLLER); output.accept(WHEEL); output.accept(SAW); }).build());

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f, 1200.0f).sound(SoundType.COPPER).requiresCorrectToolForDrops();
    }
    public static void register(IEventBus bus) {
        for (var block : new DeferredBlock[]{TRANSPONDER, ARMOR, RELAY, GARAGE, ARENA_CONTROLLER, WHEEL, SAW}) {
            ITEMS.register(block.getId().getPath(), () -> new BlockItem((net.minecraft.world.level.block.Block) block.get(), new Item.Properties()));
        }
        BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); MENUS.register(bus); TABS.register(bus);
    }
    private BattleBlocks() {}
}
