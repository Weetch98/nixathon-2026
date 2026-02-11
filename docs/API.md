# API Guide

This document describes the game API for the `Negotiation` and `Combat` phases, including request and response formats.

## Negotiation Phase

**Endpoint:** `POST /negotiate`  
**Content-Type:** `application/json`

### Request Example

```json
{
  "gameId": 12345,
  "turn": 1,
  "playerTower": {
    "playerId": 101,
    "hp": 100,
    "armor": 5,
    "resources": 25,
    "level": 2
  },
  "enemyTowers": [
    {
      "playerId": 102,
      "hp": 95,
      "armor": 3,
      "level": 1
    },
    {
      "playerId": 103,
      "hp": 80,
      "armor": 10,
      "level": 3
    }
  ],
  "combatActions": [
    {
      "playerId": 102,
      "action": {
        "targetId": 101,
        "troopCount": 15
      }
    }
  ]
}
```

### Response Example

```json
[
  {
    "allyId": 103,
    "attackTargetId": 102
  }
]
```

### Response Rules

- Return an empty array (`[]`) if you do not want to send any diplomacy message.
- `allyId`: Player you declare peace with (you will not attack this player).
- `attackTargetId` (optional): Player you plan to attack.
- A diplomacy message is sent only to the specified ally.
- Multiple messages to the same `allyId` are not allowed.

## Combat Phase

**Endpoint:** `POST /combat`  
**Content-Type:** `application/json`

### Request Example

```json
{
  "gameId": 12345,
  "turn": 1,
  "playerTower": {
    "playerId": 101,
    "hp": 100,
    "armor": 5,
    "resources": 25,
    "level": 2
  },
  "enemyTowers": [
    {
      "playerId": 102,
      "hp": 95,
      "armor": 3,
      "level": 1
    },
    {
      "playerId": 103,
      "hp": 80,
      "armor": 10,
      "level": 3
    }
  ],
  "diplomacy": [
    {
      "playerId": 103,
      "action": {
        "allyId": 101,
        "attackTargetId": 102
      }
    }
  ],
  "previousAttacks": [
    {
      "playerId": 102,
      "action": {
        "targetId": 101,
        "troopCount": 15
      }
    }
  ]
}
```

### Response Example

```json
[
  {
    "type": "armor",
    "amount": 5
  },
  {
    "type": "attack",
    "targetId": 102,
    "troopCount": 20
  },
  {
    "type": "upgrade"
  }
]
```

## Available Actions

### Build Armor

```json
{
  "type": "armor",
  "amount": 10
}
```

- Cost: `amount * 1` resource
- Effect: Blocks incoming damage
- Limit: Only one armor action per Combat phase

### Attack

```json
{
  "type": "attack",
  "targetId": 102,
  "troopCount": 25
}
```

- Cost: `troopCount * 1` resource
- Effect: Damages enemy armor first, then HP
- You can attack multiple targets per turn
- Multiple attacks with the same `targetId` are not allowed

### Upgrade

```json
{
  "type": "upgrade"
}
```

- Limit: Only one upgrade action per Combat phase
- Cost formula: `50 * (1.75 ^ (level - 1))`
- Level costs:
  - Level 1 -> 2: 50 resources
  - Level 2 -> 3: 88 resources
  - Level 3 -> 4: 153 resources
  - Level 4 -> 5: 268 resources
  - Level 5 -> 6: 469 resources
- Effect: Increases resource generation

## Technical Requirements

- Response time: Must respond within 1 second
- Timeout: No response means no actions are taken
- Validation: If any action in your response is invalid, the entire response is rejected and no actions are taken for that turn
