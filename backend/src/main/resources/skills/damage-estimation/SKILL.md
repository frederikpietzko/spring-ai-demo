---
name: damage-estimation
description: How to check damage items, their categories and amounts per district. Use it before you judge an estimate.
---

# Damage estimation

Every damage item of a claim has one category and an estimated amount in whole minor units of the fictional currency.

Categories, the four cover sections of the policy wording:

- `VEHICLE`: cars, vans, bicycles.
- `BUILDING`: walls, roofs, windows, the structure of a house.
- `CONTENTS`: furniture and belongings inside a building.
- `GARDEN`: fences, sheds, plants, garden gnomes.

Districts of the city: `HARBOUR`, `OLD_TOWN`, `NEON_MARKET`, `RIVERSIDE` and `HILLTOP`. The neighbouring town is not a claim district.

How to check an estimate:

1. Every item needs a category from the list above and an amount above zero.
2. Add up the items: the sum is the total estimated damage of the claim.
3. A single garden item worth more than a whole building item is suspicious; ask the adjuster to look at it.
4. The payout is the total minus the policy deductible; the core system computes it, never compute it yourself.
