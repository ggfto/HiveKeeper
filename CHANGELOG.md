## [0.12.6](https://github.com/ggfto/HiveKeeper/compare/v0.12.5...v0.12.6) (2026-07-21)


### Bug Fixes

* **agent-compose:** make the tunnel sidecar work under podman-compose ([86023f9](https://github.com/ggfto/HiveKeeper/commit/86023f9d2001046a7578db626c7abf73c09582a4))

## [0.12.5](https://github.com/ggfto/HiveKeeper/compare/v0.12.4...v0.12.5) (2026-07-21)


### Bug Fixes

* **web:** import the ui-kit stylesheet into a low-priority cascade layer ([f147639](https://github.com/ggfto/HiveKeeper/commit/f14763995ff2c5d77a922dc567076c0bde7e2368))

## [0.12.4](https://github.com/ggfto/HiveKeeper/compare/v0.12.3...v0.12.4) (2026-07-21)


### Bug Fixes

* **gateway:** don't let the mail health check gate readiness ([cdbb8a4](https://github.com/ggfto/HiveKeeper/commit/cdbb8a493cb185aece064a41ba0e567b857e0b8e))

## [0.12.3](https://github.com/ggfto/HiveKeeper/compare/v0.12.2...v0.12.3) (2026-07-21)


### Bug Fixes

* **build:** publish multi-arch images (amd64 + arm64) ([d9ad386](https://github.com/ggfto/HiveKeeper/commit/d9ad386c0edbe817ca96380ae1ee0d26ae3b08a8))

## [0.12.2](https://github.com/ggfto/HiveKeeper/compare/v0.12.1...v0.12.2) (2026-07-21)


### Bug Fixes

* **deploy:** correct the Keycloak healthcheck for the real 26 image ([2e025e5](https://github.com/ggfto/HiveKeeper/commit/2e025e5547ba13643f4265f4dd44c8ca660c9968))

## [0.12.1](https://github.com/ggfto/HiveKeeper/compare/v0.12.0...v0.12.1) (2026-07-21)


### Bug Fixes

* **deploy:** drop --hostname-strict-https, removed in Keycloak 26 ([92bb314](https://github.com/ggfto/HiveKeeper/commit/92bb314a0e9a2bb65fc9c62ddd3825729b02e508))

# [0.12.0](https://github.com/ggfto/HiveKeeper/compare/v0.11.0...v0.12.0) (2026-07-21)


### Features

* capture an adoption baseline, preload the RF forms, and refresh the docs ([3dfb9ee](https://github.com/ggfto/HiveKeeper/commit/3dfb9ee4605289aad76e96ca83cdaf3d1cfb5044))

# [0.11.0](https://github.com/ggfto/HiveKeeper/compare/v0.10.0...v0.11.0) (2026-07-21)


### Features

* **gateway:** active/standby agents per site ([ae19cd2](https://github.com/ggfto/HiveKeeper/commit/ae19cd2c96399226b1c1221c20c2002867351f9a))
* **gateway:** fail durable jobs over to the standby, and dedup on re-adopt ([cbda42c](https://github.com/ggfto/HiveKeeper/commit/cbda42cf6bb8ff7b0dab75dad92af385b92296b3))

# [0.10.0](https://github.com/ggfto/HiveKeeper/compare/v0.9.0...v0.10.0) (2026-07-20)


### Features

* **agent:** opt-in auto-update, and drain in-flight jobs on shutdown ([63c7097](https://github.com/ggfto/HiveKeeper/commit/63c7097dfa4d88da663f22b2f8126cf475e357e0))

# [0.9.0](https://github.com/ggfto/HiveKeeper/compare/v0.8.1...v0.9.0) (2026-07-20)


### Features

* **backup:** configure the org's backup repository from the console ([5d8be31](https://github.com/ggfto/HiveKeeper/commit/5d8be3165ead8f16688a362bcf95feb31c3f40fb))
* **deploy:** Portainer stack behind a Cloudflare Tunnel ([ded0570](https://github.com/ggfto/HiveKeeper/commit/ded0570523eb50f9a8f578274ef07dfc692488e9))
* **hiveos:** discover radios per device, and support the Wi-Fi 6 grammar ([b46a762](https://github.com/ggfto/HiveKeeper/commit/b46a762b85e70eb301b847289198cfca31f91e6a))
* **rf:** channel scan — what each radio hears, and which channel to pick ([20dbfb7](https://github.com/ggfto/HiveKeeper/commit/20dbfb72eea0e3b0c9bfef42472b773a729a60aa))

## [0.8.1](https://github.com/ggfto/HiveKeeper/compare/v0.8.0...v0.8.1) (2026-07-15)


### Bug Fixes

* **web:** install corepack explicitly, so the build survives Node 25+ ([9548d7a](https://github.com/ggfto/HiveKeeper/commit/9548d7adf9b562215bdfbf2b9d1ce39f3746b694))

# [0.8.0](https://github.com/ggfto/HiveKeeper/compare/v0.7.0...v0.8.0) (2026-07-15)


### Bug Fixes

* **agent:** commit the image's default agent.conf, which .gitignore was eating ([98de3a5](https://github.com/ggfto/HiveKeeper/commit/98de3a5973a1d878b361f1ebf06f5b86780f61ea))
* **deps:** patch the CRITICAL/HIGH CVEs the image scan caught in the app's dependencies ([ca95ce0](https://github.com/ggfto/HiveKeeper/commit/ca95ce0afe262ef3a4201f839c2b1b83587ec05b))
* **images:** patch base-image OS packages so the scan (and the published images) are clean ([40ad960](https://github.com/ggfto/HiveKeeper/commit/40ad9600e21ebf9ad4cde0ab55a0050e17914f96))
* **security:** stop provisioning users on sight, and close two audit residuals ([cdc0421](https://github.com/ggfto/HiveKeeper/commit/cdc04213d661d8271049454af4adde671cd46e2c))


### Features

* **agent:** configuration file, so a self-hosted agent is not configured by env alone ([c2f8833](https://github.com/ggfto/HiveKeeper/commit/c2f8833de4e1232178a0d7d8002f16732f6b8907))
* **auth:** sign in with GitHub, via Keycloak identity brokering ([62517f3](https://github.com/ggfto/HiveKeeper/commit/62517f352c581f1e2c8f103358c50b3628bfe560))
* **deploy:** backups, and the production runbook ([3bd5127](https://github.com/ggfto/HiveKeeper/commit/3bd5127a44d4f96b0bed8ff7b369894d5546a617))
* **deploy:** production stack — TLS, generated secrets, and an unterminated port for agents ([34e2436](https://github.com/ggfto/HiveKeeper/commit/34e24361448b7d4d6b4b8758a5f984d587fdb437))
* **ops:** health checks for the gateway and the agent ([823cb53](https://github.com/ggfto/HiveKeeper/commit/823cb53b25f7ede54f5241d24267edce574f58c4))
* **web:** admit an existing account, so a GitHub user can actually be added ([423972d](https://github.com/ggfto/HiveKeeper/commit/423972df46a517cee7e6c130fc8e31dd3fd7db62))
* **web:** production image for the console, with the IdP resolved at runtime ([cfc55c7](https://github.com/ggfto/HiveKeeper/commit/cfc55c725cea82c6ee1af2b5923b0bb21711d339))

# [0.7.0](https://github.com/ggfto/HiveKeeper/compare/v0.6.0...v0.7.0) (2026-06-30)


### Features

* **enroll:** agent certificate auto-renewal + revocation (slice 2) ([dbf7687](https://github.com/ggfto/HiveKeeper/commit/dbf76879910901662c50bbea2a9dfef4d5527183))

# [0.6.0](https://github.com/ggfto/HiveKeeper/compare/v0.5.1...v0.6.0) (2026-06-30)


### Bug Fixes

* **scripts:** dev-radius.ps1 — make it actually start FreeRADIUS ([6920e6d](https://github.com/ggfto/HiveKeeper/commit/6920e6d44962dc3b0531474d1122606ab296cb9b))
* **web:** Phase 4 live-validated — default-profile lock & phymode ordering ([0375def](https://github.com/ggfto/HiveKeeper/commit/0375def9e840c7f8a8df3005c5956e9c163d4d34))


### Features

* complete Phase 2 — Wi-Fi security suites, RADIUS, hardening, PPSK ([36a5def](https://github.com/ggfto/HiveKeeper/commit/36a5def4475a44c53d807f5b85a78091a310f176))
* complete Phase 3 — network policy, firewall, QoS, LLDP & static routes ([2b48d3e](https://github.com/ggfto/HiveKeeper/commit/2b48d3e1a5b4352a872a47af2e2792302619a43f))
* **enroll:** automated agent certificate enrollment (slice 1: token -> CSR -> signed cert) ([b48c3ba](https://github.com/ggfto/HiveKeeper/commit/b48c3ba163626dc78b19aa143dfd1b5b83a0d067)), closes [PKCS#10](https://github.com/PKCS/issues/10)
* Phase 5 — config templates (bulk apply-config across a scope) ([a7c68ee](https://github.com/ggfto/HiveKeeper/commit/a7c68ee03003aefa050374529c8d0fba59e25407))
* Phase 5 — fleet alert delivery (background poller + webhook/email) ([2c96b85](https://github.com/ggfto/HiveKeeper/commit/2c96b85e55a195d6ed7d798aa8c5bb200d841b2f)), closes [hi#clients](https://github.com/hi/issues/clients) [hi#power](https://github.com/hi/issues/power)
* Phase 5 — PPSK admin-minted keys via RADIUS (Caminho B, M2-M3) ([49dc2ff](https://github.com/ggfto/HiveKeeper/commit/49dc2ffa1f271575dfe4df062f36d692ef02c9f2))
* seal secret-bearing durable jobs to the agent (end-to-end at rest) ([063530e](https://github.com/ggfto/HiveKeeper/commit/063530e082f1851fa5f7a858c0f4cd9f85723672))
* **ssh:** TOFU host-key verification (default), no more promiscuous SSH ([9ebe523](https://github.com/ggfto/HiveKeeper/commit/9ebe52395ed383c31c7033203e8ad0b868d570bf))
* **web:** alert polish — single-source thresholds + active-alerts panel ([f101f67](https://github.com/ggfto/HiveKeeper/commit/f101f671be56bba9b84064c3e5575948991f0d63))
* **web:** complete Phase 1 radio completeness ([e552a1c](https://github.com/ggfto/HiveKeeper/commit/e552a1cdab19c57da998d0fd183519aaf36dc527))
* **web:** complete Phase 4 — advanced RF tuning & high-density knobs ([98a0953](https://github.com/ggfto/HiveKeeper/commit/98a09531a776aaaebb40c6906abeb9c5d555d78f)), closes [hi#density](https://github.com/hi/issues/density) [hi#density](https://github.com/hi/issues/density)
* **web:** Phase 4 bind flow — apply a custom radio profile to a radio ([42f7a18](https://github.com/ggfto/HiveKeeper/commit/42f7a1863a0b23c767cc74d85abf68330c3f2b54))
* **web:** Phase 5 — fleet alerts (threshold scan, in-console) ([749038d](https://github.com/ggfto/HiveKeeper/commit/749038df7f6c1e55d26a677364fb76a7b39e7b25))
* **web:** Phase 5 — named schedule objects (recurrent & one-time) ([2c0fc9f](https://github.com/ggfto/HiveKeeper/commit/2c0fc9fef3c9f282a84e221711a28389058bb68d))
* **web:** Phase 5 — PPSK via RADIUS wiring (Caminho B, Milestone 1) ([62ce643](https://github.com/ggfto/HiveKeeper/commit/62ce6439479a786420bf43e215e8c79de4190776))
* **web:** Phase 5 — scheduled (recurring) reboot ([53d4251](https://github.com/ggfto/HiveKeeper/commit/53d4251a79ad88ef37587460bc6d8e4ddd4b348b))

## [0.5.1](https://github.com/ggfto/HiveKeeper/compare/v0.5.0...v0.5.1) (2026-06-29)


### Bug Fixes

* **demo:** add setCredential to the demo gateway and make Identify work ([08d3a22](https://github.com/ggfto/HiveKeeper/commit/08d3a223f0ca63538c2c4378c505ae5071ce1460))

# [0.5.0](https://github.com/ggfto/HiveKeeper/compare/v0.4.0...v0.5.0) (2026-06-29)


### Features

* enable the on-AP admin password change (validated live on an AP230) ([b9c6478](https://github.com/ggfto/HiveKeeper/commit/b9c64782672c692df895f762c6f97bbdab536e22))

# [0.4.0](https://github.com/ggfto/HiveKeeper/compare/v0.3.0...v0.4.0) (2026-06-29)


### Features

* manage device credentials end-to-end, sealed to the agent ([c85a0a7](https://github.com/ggfto/HiveKeeper/commit/c85a0a71628746058e81526043aae04a87442ad1))
* **web:** classify device support level; document Phase 0a as shipped ([ff6bc2f](https://github.com/ggfto/HiveKeeper/commit/ff6bc2ff0cd570d34c17c91921ba01e0e5bb89fc))
* **web:** identify discovered hosts and set a credential at adopt ([62fedc8](https://github.com/ggfto/HiveKeeper/commit/62fedc878df9d4a4b6d316a08ccbaf22605b8723))

# [0.3.0](https://github.com/ggfto/HiveKeeper/compare/v0.2.0...v0.3.0) (2026-06-24)


### Features

* **web:** warn when radio config deviates from best practice ([381e3f0](https://github.com/ggfto/HiveKeeper/commit/381e3f0584e0e9df2950af7e6a4e7c6774f2f0fc))

# [0.2.0](https://github.com/ggfto/HiveKeeper/compare/v0.1.0...v0.2.0) (2026-06-18)


### Features

* add firmware upgrade and expose config restore via API/UI ([eccd3e8](https://github.com/ggfto/HiveKeeper/commit/eccd3e8affb013b772c0982449f94d3a54e00916))
* **deploy:** add Docker/Podman Compose stack ([4fe1ae1](https://github.com/ggfto/HiveKeeper/commit/4fe1ae17c460379c8ed437884c31ac5d7cdc4f51))
