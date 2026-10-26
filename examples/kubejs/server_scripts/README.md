# EMC Casino — KubeJS integration

The mod never hard-depends on KubeJS. If KubeJS is installed, its `Java.loadClass`
bridge reaches the mod's public static API directly.

## Namespace

```
dev.emccasino.api.EmcKubeJsBridge
```

| Method | Meaning |
|---|---|
| `set(itemId, emc)` | Set the base EMC value of an item |
| `remove(itemId)` | Make an item un-tradeable |
| `get(itemId)` | Base EMC value (0 when unset) |
| `deposit(itemId)` | What the player gets when depositing one (`floor(base × 0.8)`) |
| `withdraw(itemId)` | What it costs to withdraw one (`ceil(base × 1.2)`) |
| `has(itemId)` | Whether the item has an EMC value |
| `size()` | How many items are priced |
| `setById(itemId, emc)` | Like `set`, but validates the item exists first |

## Persistent pricing

Values set from a script live only for that server session. For values that
must survive restarts, ship a **data pack** file instead:

```
data/<your_namespace>/emc/prices.json
```

```json
{
  "minecraft:diamond": 9000,
  "minecraft:emerald": 20000
}
```

The mod merges every `data/*/emc/*.json` it finds into the table and re-applies
them on every `/reload`, so a data pack is the durable path and a KubeJS script
is the dynamic one.
