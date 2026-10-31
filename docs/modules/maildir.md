# Module maildir

<!-- Generated from the module's descriptor. Do not edit. -->

Version 0.1.0.

**Event sources:** `maildir.inbox`

## Capabilities

### mail.move

Effect: `local-write`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `message` | string | yes | no |
| `folder` | string | yes | yes |

Results:

| Name | Type | Required |
|---|---|---|
| `message` | string | yes |

### mail.list

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `account` | string | no | yes |
| `folder` | string | yes | yes |
| `limit` | int | no | no |
| `offset` | int | no | no |
| `sort` | string | no | no |
| `reverse` | bool | no | no |
| `threaded` | bool | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `items` | list | yes |
| `count` | int | yes |

### mail.search

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `account` | string | no | yes |
| `folder` | string | yes | yes |
| `query` | string | yes | no |
| `in` | string | no | no |
| `limit` | int | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `items` | list | yes |
| `count` | int | yes |

### mail.mark

Effect: `local-write`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `message` | string | yes | no |
| `seen` | bool | no | no |
| `flagged` | bool | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `message` | string | yes |

### mail.delete

Effect: `destructive`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `message` | string | yes | no |

Results:

| Name | Type | Required |
|---|---|---|
| `deleted` | bool | yes |

### mail.attachment

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `message` | string | yes | no |
| `name` | string | yes | no |
| `max_bytes` | int | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `name` | string | yes |
| `type` | string | yes |
| `size` | int | yes |
| `content` | bytes | yes |

### mail.folders

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `account` | string | no | yes |

Results:

| Name | Type | Required |
|---|---|---|
| `folders` | list | yes |

### mail.get

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `message` | string | yes | no |
| `images` | bool | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `from` | string | yes |
| `to` | string | yes |
| `cc` | string | yes |
| `subject` | string | yes |
| `date` | string | yes |
| `time` | timestamp | yes |
| `account` | string | yes |
| `message_id` | string | yes |
| `reply_to` | string | yes |
| `references` | string | yes |
| `text` | string | yes |
| `html` | string | yes |
| `blocks` | list | yes |
| `blocked` | int | yes |
| `attachments` | list | yes |
| `size` | int | yes |

### mail.append

Effect: `local-write`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `account` | string | no | yes |
| `folder` | string | yes | yes |
| `content` | bytes | yes | no |
| `seen` | bool | no | no |
| `draft` | bool | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `message` | string | yes |

### mail.accounts

Effect: `read-only`. Supports dry run.

Parameters:

| Name | Type | Required | Grant can limit |
|---|---|---|---|
| `check` | bool | no | no |

Results:

| Name | Type | Required |
|---|---|---|
| `items` | list | yes |

## Events

### mail.received

Fields:

| Name | Type | Required |
|---|---|---|
| `message` | string | yes |
| `account` | string | no |
| `folder` | string | yes |
| `size` | int | yes |
| `from` | string | no |
| `to` | string | no |
| `subject` | string | no |
| `message_id` | string | no |
| `cc` | string | no |
| `date` | string | no |
| `list_id` | string | no |
| `precedence` | string | no |
| `x_spam_flag` | string | no |
