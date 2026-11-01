# Module entity

<!-- Generated from the module's descriptor. Do not edit. -->

Version 0.1.0.

## Capabilities

### entity.create

Effect: `local-write`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `type` | string | yes | yes |
| `values` | map | yes | no |

Results:

| Name | Type | Required |
|---|---|---|
| `id` | string | yes |
| `entity` | map | yes |

### entity.read

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `type` | string | yes | yes |
| `id` | string | yes | no |

Results:

| Name | Type | Required |
|---|---|---|
| `found` | bool | yes |
| `entity` | map | no |

### entity.reveal

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `type` | string | yes | yes |
| `id` | string | yes | no |
| `fields` | list | yes | yes |

Results:

| Name | Type | Required |
|---|---|---|
| `found` | bool | yes |
| `values` | map | no |

### entity.query

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `type` | string | yes | yes |
| `filter` | map | no | no |
| `sort` | string | no | no |
| `reverse` | bool | no | no |
| `limit` | int | no | no |
| `offset` | int | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `items` | list | yes |
| `count` | int | yes |
| `total` | int | yes |

### entity.update

Effect: `local-write`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `type` | string | yes | yes |
| `id` | string | yes | no |
| `values` | map | yes | no |

Results:

| Name | Type | Required |
|---|---|---|
| `found` | bool | yes |
| `entity` | map | no |

### entity.delete

Effect: `destructive`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `type` | string | yes | yes |
| `id` | string | yes | no |

Results:

| Name | Type | Required |
|---|---|---|
| `deleted` | bool | yes |

## Events

### entity.created

Fields:

| Name | Type | Required |
|---|---|---|
| `type` | string | yes |
| `id` | string | yes |
| `fields` | list | yes |

### entity.updated

Fields:

| Name | Type | Required |
|---|---|---|
| `type` | string | yes |
| `id` | string | yes |
| `fields` | list | yes |

### entity.deleted

Fields:

| Name | Type | Required |
|---|---|---|
| `type` | string | yes |
| `id` | string | yes |
