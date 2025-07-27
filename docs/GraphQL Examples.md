# GraphQL Examples

This document provides examples of GraphQL queries and mutations that can be used with the MC Local API.

## Queries

### Get Player Information

```graphql
query {
  player {
    position
    world
  }
}
```

### Get All Mods

```graphql
query {
  mods {
    id
    version
  }
}
```

### Get Current Screen

```graphql
query {
  screen
}
```

### Get Xaero Waypoint Sets

```graphql
query {
  xaeroWaypointSets {
    name
  }
}
```

### Combined Query

```graphql
query {
  player {
    position
    world
  }
  mods {
    id
    version
  }
  screen
  xaeroWaypointSets {
    name
  }
}
```

## Mutations

### Send Chat Command

```graphql
mutation {
  sendChatCommand(command: "time set day")
}
```

### Send Chat Message

```graphql
mutation {
  sendChatMessage(message: "Hello, world!")
}
```

### Create Xaero Waypoint Set

```graphql
mutation {
  createXaeroWaypointSet(name: "My New Waypoint Set") {
    name
  }
}
```

### Combined Mutation

```graphql
mutation {
  sendChatMessage(message: "Creating waypoint set...")
  createXaeroWaypointSet(name: "API Created Set") {
    name
  }
}
```

## GraphQL Endpoint

The GraphQL endpoint is available at:

```
POST http://localhost:<port>/graphql
```

Where `<port>` is the configured port in your MC Local API configuration.

## Error Handling

GraphQL will return errors in the following cases:

- Endpoints are disabled in the configuration
- Invalid arguments (empty strings, etc.)
- Xaero's Minimap session is not available

Example error response:

```json
{
  "data": null,
  "errors": [
    {
      "message": "Player not available",
      "locations": [
        {
          "line": 2,
          "column": 3
        }
      ],
      "path": ["player"]
    }
  ]
}
```
