# Module praksis

<!-- Generated from the module's descriptor. Do not edit. -->

Version 0.1.0.

## Capabilities

### praksis.rules.list

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `layer` | string | no | yes |
| `untested` | bool | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `items` | list | yes |

### praksis.rules.read

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `rule` | string | yes | yes |

Results:

| Name | Type | Required |
|---|---|---|
| `rule` | string | yes |
| `layer` | string | yes |
| `file` | string | yes |
| `text` | string | yes |
| `tests` | string | yes |
| `effect` | string | yes |
| `description` | string | yes |
| `trigger` | string | yes |
| `needs` | list | yes |
| `tested` | bool | yes |

### praksis.agent.propose

Effect: `local-write`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `layer` | string | yes | yes |
| `rule` | string | yes | yes |
| `files` | list | yes | no |
| `reason` | string | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `ok` | bool | yes |
| `covers` | bool | yes |
| `problems` | list | yes |
| `staged` | bool | yes |
| `id` | string | yes |

### praksis.agent.proposals

Effect: `read-only`. Supports dry run.

Results:

| Name | Type | Required |
|---|---|---|
| `items` | list | yes |

### praksis.notes.save

Effect: `local-write`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `title` | string | yes | no |
| `text` | string | yes | no |

Results:

| Name | Type | Required |
|---|---|---|
| `id` | string | yes |

### praksis.history.find

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `text` | string | no | no |
| `type` | string | no | no |
| `since_hours` | int | no | no |
| `limit` | int | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `items` | list | yes |
| `searched` | int | yes |
| `report` | string | yes |

### praksis.history.trace

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `event` | string | yes | no |

Results:

| Name | Type | Required |
|---|---|---|
| `event` | map | yes |
| `steps` | list | yes |
| `calls` | list | yes |
| `rules` | list | yes |
| `report` | string | yes |

### praksis.history.summary

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `since_hours` | int | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `since` | string | yes |
| `events` | map | yes |
| `rules` | list | yes |
| `calls` | map | yes |
| `refused` | list | yes |
| `report` | string | yes |

### praksis.plan.catalog

Effect: `read-only`. Supports dry run.

Results:

| Name | Type | Required |
|---|---|---|
| `items` | list | yes |
| `report` | string | yes |

### praksis.plan.read

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `reads` | list | no | no |
| `reads_json` | string | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `report` | string | yes |
| `count` | int | yes |

### praksis.plan.propose

Effect: `local-write`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `request` | string | yes | no |
| `steps` | list | no | no |
| `steps_json` | string | no | no |
| `context` | string | no | no |
| `reason` | string | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `id` | string | yes |
| `steps` | int | yes |
| `refused` | int | yes |
| `runnable` | bool | yes |

### praksis.notes.list

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `limit` | int | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `items` | list | yes |
