// Casino — KubeJS integration example
// Drop this file into: <instance>/kubejs/server_scripts/casino.js
// Then run /reload (or restart the server).

const Casino = Java.loadClass('dev.casino.api.PriceKubeJsBridge')

ServerEvents.loaded(event => {
    console.info('[Casino] pricing ' + Casino.size() + ' items')

    // --- Reprice existing items -------------------------------------------
    Casino.set('minecraft:diamond', 9000)      // default is 8192
    Casino.set('minecraft:emerald', 20000)     // default is 16384
    Casino.set('minecraft:netherite_ingot', 75000)

    // --- Price things the mod does not know about -------------------------
    Casino.set('minecraft:dragon_breath', 4096)
    Casino.set('minecraft:echo_shard', 8192)

    // --- Make an item un-tradeable ----------------------------------------
    Casino.remove('minecraft:dirt')

    // --- Inspect the spread ------------------------------------------------
    // sellPrice() == floor(base * 0.8)   buyPrice() == ceil(base * 1.2)
    console.info('[Casino] diamond: house buys at ' + Casino.sellPrice('minecraft:diamond')
        + ', sells at ' + Casino.buyPrice('minecraft:diamond'))

    // --- Sanity check a value you just set --------------------------------
    if (!Casino.has('minecraft:echo_shard')) {
        console.warn('[Casino] echo_shard has no price!')
    }
})
