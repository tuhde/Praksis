// SPDX-License-Identifier: Apache-2.0
module praksis.dummy {
  requires praksis.api;
  provides praksis.api.Capability with praksis.dummy.EchoCapability;
}
