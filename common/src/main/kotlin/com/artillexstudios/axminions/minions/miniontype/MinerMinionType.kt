package com.artillexstudios.axminions.minions.miniontype

import com.artillexstudios.axapi.scheduler.Scheduler
import com.artillexstudios.axapi.scheduler.impl.FoliaScheduler
import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.events.MinionKillEntityEvent
import com.artillexstudios.axminions.api.events.MinionMineBlockEvent
import com.artillexstudios.axminions.api.minions.Minion
import com.artillexstudios.axminions.api.minions.miniontype.MinionType
import com.artillexstudios.axminions.api.utils.LocationUtils
import com.artillexstudios.axminions.api.utils.MinionUtils
import com.artillexstudios.axminions.api.utils.fastFor
import com.artillexstudios.axminions.api.warnings.Warnings
import com.artillexstudios.axminions.minions.MinionTicker
import com.artillexstudios.axminions.nms.NMSHandler
import dev.lone.itemsadder.api.CustomBlock
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import me.kryniowesegryderiusz.kgenerators.Main
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.ItemStack

class MinerMinionType : MinionType("miner", AxMinionsPlugin.INSTANCE.getResource("minions/miner.yml")!!) {
    companion object {
        private var asyncExecutor: ExecutorService? = null
        private val smeltingRecipes = ArrayList<FurnaceRecipe>()
        private val faces = arrayOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

        init {
            Bukkit.recipeIterator().forEachRemaining {
                if (it is FurnaceRecipe) {
                    smeltingRecipes.add(it)
                }
            }
        }
    }

//    private fun toSmelted(minion: Minion, drops: Collection<ItemStack>): MutableList<ItemStack> {
//        if (minion.getType().getConfig().getBoolean("gui.autosmelt.enabled")) {
//            val dropsList = ArrayList<ItemStack>(drops.size)
//            drops.forEach { item ->
//                smeltingRecipes.fastFor {
//                    if (it.inputChoice.test(item)) {
//                        dropsList.add(it.result)
//                    } else {
//                        dropsList.add(item)
//                    }
//                }
//            }
//        }
//
//
//        return dropsList
//    }

    override fun shouldRun(minion: Minion): Boolean {
        return MinionTicker.getTick() % minion.getNextAction() == 0L
    }

    override fun onToolDirty(minion: Minion) {
        val minionImpl = minion as com.artillexstudios.axminions.minions.Minion
        minionImpl.setRange(getDouble("range", minion.getLevel()))
        val tool = minion.getTool()?.getEnchantmentLevel(Enchantment.DIG_SPEED)?.div(10.0) ?: 0.1
        val efficiency = 1.0 - if (tool > 0.9) 0.9 else tool
        minionImpl.setNextAction((getLong("speed", minion.getLevel()) * efficiency).roundToInt())
    }

    override fun run(minion: Minion) {
        if (minion.getLinkedInventory() != null && minion.getLinkedInventory()?.firstEmpty() != -1) {
            Warnings.remove(minion, Warnings.CONTAINER_FULL)
        }

        if (minion.getLinkedChest() != null) {
            val type = minion.getLinkedChest()!!.block.type
            if (type != Material.CHEST && type != Material.TRAPPED_CHEST && type != Material.BARREL) {
                minion.setLinkedChest(null)
            }
        }

        if (minion.getLinkedInventory() == null) {
            minion.setLinkedChest(null)
        }

        if (!minion.canUseTool()) {
            Warnings.NO_TOOL.display(minion)
            return
        }

        if (minion.getLinkedInventory()?.firstEmpty() == -1) {
            Warnings.CONTAINER_FULL.display(minion)
            return
        }

        Warnings.remove(minion, Warnings.NO_TOOL)

        var amount = 0
        var xp = 0
        when (getConfig().getString("mode").lowercase(Locale.ENGLISH)) {
            "sphere" -> {
                LocationUtils.getAllBlocksInRadius(minion.getLocation(), minion.getRange(), false).fastFor { location ->
                    if (AxMinionsPlugin.integrations.kGeneratorsIntegration && Main.getPlacedGenerators()
                            .isChunkFullyLoaded(location)
                    ) {
                        val gen = Main.getPlacedGenerators().getLoaded(location)
                        val possible = gen?.isBlockPossibleToMine(location) ?: false

                        if (possible) {
                            val blockEvent = MinionMineBlockEvent(minion, location.block)
                            Bukkit.getPluginManager().callEvent(blockEvent)

                            if(blockEvent.isCancelled) return;

                            gen?.scheduleGeneratorRegeneration()
                            return@fastFor
                        }
                    }

                    val isStoneGenerator = MinionUtils.isStoneGenerator(location)

                    if (isStoneGenerator) {
                        val block = location.block
                        val blockEvent = MinionMineBlockEvent(minion, block)
                        Bukkit.getPluginManager().callEvent(blockEvent)

                        if(blockEvent.isCancelled) return;

                        val drops = block.getDrops(minion.getTool())
                        xp += NMSHandler.get().getExp(block, minion.getTool() ?: return)
                        drops.forEach {
                            amount += it.amount
                        }
                        minion.addToContainerOrDrop(drops)
                        location.block.type = Material.AIR
                    }
                }
            }

            "asphere" -> {
                if (Scheduler.get() !is FoliaScheduler) {
                    if (asyncExecutor == null) {
                        asyncExecutor = Executors.newFixedThreadPool(3)
                    }

                    asyncExecutor!!.execute {
                        LocationUtils.getAllBlocksInRadius(minion.getLocation(), minion.getRange(), false)
                            .fastFor { location ->
                                if (AxMinionsPlugin.integrations.kGeneratorsIntegration && Main.getPlacedGenerators()
                                        .isChunkFullyLoaded(location)
                                ) {
                                    val gen = Main.getPlacedGenerators().getLoaded(location)
                                    val possible = gen?.isBlockPossibleToMine(location) ?: false

                                    if (possible) {
                                        val blockEvent = MinionMineBlockEvent(minion, location.block)
                                        Bukkit.getPluginManager().callEvent(blockEvent)

                                        if(blockEvent.isCancelled) return@fastFor

                                        gen?.scheduleGeneratorRegeneration()
                                        return@fastFor
                                    }
                                }

                                val isStoneGenerator = MinionUtils.isStoneGenerator(location)

                                if (isStoneGenerator) {
                                    val blockEvent = MinionMineBlockEvent(minion, location.block)
                                    Bukkit.getPluginManager().callEvent(blockEvent)

                                    if(blockEvent.isCancelled) return@fastFor

                                    Scheduler.get().run {
                                        val block = location.block
                                        val drops = block.getDrops(minion.getTool())
                                        xp += NMSHandler.get().getExp(block, minion.getTool() ?: return@run)
                                        drops.forEach {
                                            amount += it.amount
                                        }
                                        minion.addToContainerOrDrop(drops)
                                        location.block.type = Material.AIR
                                    }
                                }
                            }
                    }
                } else {
                    LocationUtils.getAllBlocksInRadius(minion.getLocation(), minion.getRange(), false)
                        .fastFor { location ->
                            if (AxMinionsPlugin.integrations.kGeneratorsIntegration && Main.getPlacedGenerators()
                                    .isChunkFullyLoaded(location)
                            ) {
                                val gen = Main.getPlacedGenerators().getLoaded(location)
                                val possible = gen?.isBlockPossibleToMine(location) ?: false

                                if (possible) {
                                    val blockEvent = MinionMineBlockEvent(minion, location.block)
                                    Bukkit.getPluginManager().callEvent(blockEvent)

                                    if(blockEvent.isCancelled) return;

                                    gen?.scheduleGeneratorRegeneration()
                                    return@fastFor
                                }
                            }

                            val isStoneGenerator = MinionUtils.isStoneGenerator(location)

                            if (isStoneGenerator) {
                                val block = location.block
                                val blockEvent = MinionMineBlockEvent(minion, block)
                                Bukkit.getPluginManager().callEvent(blockEvent)

                                if(blockEvent.isCancelled) return;

                                val drops = block.getDrops(minion.getTool())
                                xp += NMSHandler.get().getExp(block, minion.getTool() ?: return)
                                drops.forEach {
                                    amount += it.amount
                                }
                                minion.addToContainerOrDrop(drops)
                                location.block.type = Material.AIR
                            }
                        }
                }
            }

            "line" -> {
                faces.fastFor {
                    LocationUtils.getAllBlocksFacing(minion.getLocation(), minion.getRange(), it).fastFor { location ->
                        if (AxMinionsPlugin.integrations.kGeneratorsIntegration && Main.getPlacedGenerators()
                                .isChunkFullyLoaded(location)
                        ) {
                            val gen = Main.getPlacedGenerators().getLoaded(location)
                            val possible = gen?.isBlockPossibleToMine(location) ?: false

                            if (possible) {
                                val blockEvent = MinionMineBlockEvent(minion, location.block)
                                Bukkit.getPluginManager().callEvent(blockEvent)

                                if(blockEvent.isCancelled) return;

                                gen?.scheduleGeneratorRegeneration()
                                return@fastFor
                            }
                        }

                        val isStoneGenerator = MinionUtils.isStoneGenerator(location)

                        if (isStoneGenerator) {
                            val block = location.block
                            val blockEvent = MinionMineBlockEvent(minion, block)
                            Bukkit.getPluginManager().callEvent(blockEvent)

                            if(blockEvent.isCancelled) return;

                            val drops = block.getDrops(minion.getTool())
                            xp += NMSHandler.get().getExp(block, minion.getTool() ?: return)
                            drops.forEach { item ->
                                amount += item.amount
                            }
                            minion.addToContainerOrDrop(drops)
                            location.block.type = Material.AIR
                        }
                    }
                }
            }

            "face" -> {
                LocationUtils.getAllBlocksFacing(minion.getLocation(), minion.getRange(), minion.getDirection().facing)
                    .fastFor { location ->
                        if (AxMinionsPlugin.integrations.kGeneratorsIntegration && Main.getPlacedGenerators()
                                .isChunkFullyLoaded(location)
                        ) {
                            val gen = Main.getPlacedGenerators().getLoaded(location)
                            val possible = gen?.isBlockPossibleToMine(location) ?: false

                            if (possible) {
                                val blockEvent = MinionMineBlockEvent(minion, location.block)
                                Bukkit.getPluginManager().callEvent(blockEvent)

                                if(blockEvent.isCancelled) return;

                                gen?.scheduleGeneratorRegeneration()
                                return@fastFor
                            }
                        }

                        if (AxMinionsPlugin.integrations.itemsAdderIntegration) {
                            val block = CustomBlock.byAlreadyPlaced(location.block)
                            if (block !== null) {
                                val blockEvent = MinionMineBlockEvent(minion, location.block)
                                Bukkit.getPluginManager().callEvent(blockEvent)

                                if(blockEvent.isCancelled) return;

                                val drops = block.getLoot(minion.getTool(), false)
                                drops.forEach {
                                    amount += it.amount
                                }
                                minion.addToContainerOrDrop(drops)
                                block.remove()
                                return@fastFor
                            }
                        }

                        val isStoneGenerator = MinionUtils.isStoneGenerator(location)

                        if (isStoneGenerator) {
                            val block = location.block
                            val blockEvent = MinionMineBlockEvent(minion, block)
                            Bukkit.getPluginManager().callEvent(blockEvent)

                            if(blockEvent.isCancelled) return;

                            val drops = block.getDrops(minion.getTool())
                            xp += NMSHandler.get().getExp(block, minion.getTool() ?: return)
                            drops.forEach {
                                amount += it.amount
                            }

                            minion.addToContainerOrDrop(drops)
                            location.block.type = Material.AIR
                        }
                    }
            }
        }

        val coerced =
            (minion.getStorage() + xp).coerceIn(0.0, minion.getType().getLong("storage", minion.getLevel()).toDouble())
        minion.setStorage(coerced)
        minion.setActions(minion.getActionAmount() + amount)
        minion.damageTool(amount)
    }
}