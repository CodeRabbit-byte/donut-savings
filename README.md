# Donut Savings

A client-side Fabric mod for **DonutSMP** that works like a strict personal financial advisor.
It tracks your balance, earnings and spending, enforces your budget and savings goals, blocks
`/pay`, and makes you confirm purchases before you waste your money.

Minecraft 1.21.11 · Fabric Loader 0.19.3+ · Fabric API 0.141.6+1.21.11 · Java 21

## Features

- **Dashboard** (`U` or `/savings`): balance, total earned/spent, net saved, spending today, this
  week and this month with limit bars, remaining allowance, savings goal progress, earning rate.
- **Savings goals**: a main goal plus any number of named goals. A share of every earning is set
  aside for them, and money set aside is protected like your minimum balance.
- **Purchase Check screen**: when you click Confirm/Buy in a server menu (Auction House, shop),
  the click is held back. You see the item, price, balance before and after, the share of your
  goal you would lose, your remaining allowance, and how long it would take to earn it back.
  "Walk away" is the big button. "Buy anyway" unlocks after a delay, and large purchases need
  it pressed twice.
- **Spending rules**: daily, weekly and monthly limits, max single purchase, minimum balance,
  a cooldown between unnecessary purchases, and a spending lock.
- **/pay protection**: every `/pay` form is stopped before it is sent:
  "PAYMENT BLOCKED: You're supposed to be saving your money!"
- **STRICT SAVINGS MODE**: forces every protection on. While it is on, limits can only get
  stricter. Turning it off means typing a sentence and waiting out a countdown.
- **Achievements** for 100M, 500M, 1B, 5B and reaching your goal.
- **History**: purchases, sales, transfers and other changes are listed separately, with
  daily, weekly and monthly totals and your most expensive purchases.

## What it can block and what it can only track

| | |
|---|---|
| `/pay` (typed or clicked in chat) | **Blocked** before it is sent |
| Confirm/Buy clicks in server menus | **Held** until you confirm, and **blocked** when a rule says no |
| Prices | Read from item lore. If the server doesn't show one, the check runs with "price unknown" |
| Anything taken without a click or command | **Tracked only**, from balance changes |

Exact amounts come from server chat. The `/pay` lines were checked on DonutSMP. The shop, auction,
`/bal` and scoreboard patterns are best guesses, and you can edit them in
`config/donutsavings/config.json`. If something isn't detected, run `/savings debug`, do the thing
again, and look at `config/donutsavings/debug.log`.

The mod never sends commands, clicks or payments on its own. Everything is stored locally in
`config/donutsavings/`.

## Commands

`/savings` · `/savings settings` · `/savings status` · `/savings balance <amount>` ·
`/savings spent <amount> [note]` · `/savings earned <amount> [note]` · `/savings lock` ·
`/savings debug` · `/savings reload`

## Building

```
./gradlew build               # jar in build/libs, runs the unit tests
./gradlew runClientGameTest   # automated in-game test
```
