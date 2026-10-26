// EMC Casino — KubeJS integration example
// Drop this file into: <instance>/kubejs/server_scripts/emc_casino.js
// Then run /reload (or restart the server).

const EmcCasino = Java.loadClass('dev.emccasino.api.EmcKubeJsBridge')

ServerEvents.loaded(event => {
    console.info('[EMC Casino] pricing ' + EmcCasino.size() + ' items')

    // --- Reprice existing items -------------------------------------------
    EmcCasino.set('minecraft:diamond', 9000)      // default was 8192
    EmcCasino.set('minecraft:emerald', 20000)     // default was 16384
    EmcCasino.set('minecraft:netherite_ingot', 75000)

    // --- Price things the mod does not know about -------------------------
    EmcCasino.set('minecraft:dragon_breath', 4096)
    EmcCasino.set('minecraft:echo_shard', 8192)

    // --- Make an item un-tradeable ----------------------------------------
    EmcCasino.remove('minecraft:dirt')

    // --- Inspect the balance maths ----------------------------------------
    // deposit() == floor(base * 0.8), withdraw() == ceil(base * 1.2)
    console.info('[EMC Casino] diamond: deposit=' + EmcCasino.deposit('minecraft:diamond')
        + ' withdraw=' + EmcCasino.withdraw('minecraft:diamond'))

    // --- Sanity check a value you just set --------------------------------
    if (!EmcCasino.has('minecraft:echo_shard')) {
        console.warn('[EMC Casino] echo_shard has no EMC value!')
    }
})

// Re-apply prices after every /reload too, in case a datapack reset them.
ServerEvents.recipes(event => {
    EmcCasino.set('minecraft:diamond', 9000)
})
