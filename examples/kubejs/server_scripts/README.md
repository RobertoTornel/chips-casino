# KubeJS integration

Edit the item price table from a KubeJS server script — no datapack, no restart.

The mod ships a spreadsheet of base item values. Players **sell** items to the house for
**80%** of that value and **buy** them back for **120%**, which is the mod's whole economy:
no trade loop can come out ahead.

```js
// examples/kubejs/server_scripts/casino.js
ServerEvents.loaded(event => {
    const prices = Java.loadClass('dev.macasino.api.PriceKubeJsBridge')

    prices.set('minecraft:diamond', 8192)      // set a base value
    prices.set('minecraft:netherite_ingot', 32768)
    prices.remove('minecraft:dirt')             // make an item untradable

    console.info(`table size: ${prices.size()}`)
    console.info(`diamond sells for ${prices.sellPrice('minecraft:diamond')}`)
    console.info(`diamond costs    ${prices.buyPrice('minecraft:diamond')}`)
})
```

## API

| Method | Effect |
|---|---|
| `set(itemId, value)` | Set the base value of an item |
| `remove(itemId)` | Make an item untradable |
| `get(itemId)` | Base value (0 when unset) |
| `has(itemId)` | Whether the item has a value |
| `size` | Number of priced items |
| `sellPrice(itemId)` | What the player receives (80% of base, minimum 1) |
| `buyPrice(itemId)` | What the player pays (120% of base, minimum 1) |
| `sellValueOfStack(stack)` | Payout for a whole ItemStack |
| `buyCostOfStack(stack)` | Cost of a whole ItemStack |

The sell/buy multipliers are fixed at 0.8 / 1.2 in `PriceTable`. They are deliberately not
exposed to scripting: a server admin who could set both would be able to remove the house
edge entirely, which is the one thing the economy depends on.
