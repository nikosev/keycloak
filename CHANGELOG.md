# Changelog
All notable eosc-kc changes of Keycloak will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

For Keycloak upstream changelog please see https://www.keycloak.org/docs/latest/release_notes/index.html.
[Keycloak announcement for version 16.1.0](https://www.keycloak.org/2021/12/keycloak-1610-released)
Full Keycloak upstream jira issue can be shown if filtered by Fix version. 

Our Keycloak version is working well with PostgreSQL database. For using other SQL databases, text field in database need to be evaluated.

## [Unreleased]

### Fixed

- Fix bug when `client_secret` is not defined in the device authorization request [RCIAM-1089](https://jira.argo.grnet.gr/browse/RCIAM-1089)

## [16.1.0-2.10] - 2022-07-25

### Added
- Extra logs for failed introspection
- Added INTROSPCETION ERROR events with a short description to let admin know of any token introspection failures.

### Fixed
- Correct including claim in token introspection response only [RCIAM-1054](https://jira.argo.grnet.gr/browse/RCIAM-1054)
- Fixed a cache serialization issue and re-enabled cache for external token introspection requests.
- Fixed a ftl templating bug in Error pages [RCIAM-1049](https://jira.argo.grnet.gr/browse/RCIAM-1049)

### Changed
- Showing consent screen text instead of scope name in consent part of Application page in Account console [RCIAM-1057](https://jira.argo.grnet.gr/browse/RCIAM-1057)
- Make optional the use of PKCE for Clients configured with PKCE only for Device Code Flow [RCIAM-1069](https://jira.argo.grnet.gr/browse/RCIAM-1069)

## [16.1.0-2.9] - 2022-07-05
### Changed
- Disabled the cache for external token introspection requests.


## [16.1.0-2.8] - 2022-07-01
### Changed
- Remove consent required from Token Exchange [RCIAM-1048](https://jira.argo.grnet.gr/browse/RCIAM-1048)

## [16.1.0-2.7] - 2022-06-27
### Changed
- SAML IdP InResponseTo missing, warning instead of error

## [16.1.0-2.6] - 2022-06-20
### Changed
- User attribute value as text in database [RCIAM-1032](https://jira.argo.grnet.gr/browse/RCIAM-1032)
- Client description as text in database 
- Client attribute value as text in database [RCIAM-1026)](https://jira.argo.grnet.gr/browse/RCIAM-1026)

## [16.1.0-2.5] - 2022-06-08
### Fixed
- On realm delete, cancel SAML Federation task [RCIAM-1011](https://jira.argo.grnet.gr/browse/RCIAM-1011)
- AutoUpdated IdPs on realm removal, import realm [RCIAM-1012](https://jira.argo.grnet.gr/browse/RCIAM-1012)
- SAML Federation Partial Import corrections and tests [RCIAM-1011](https://jira.argo.grnet.gr/browse/RCIAM-1011)
### Changed
- Set Client Description length to 2048

## [16.1.0-2.4] - 2022-05-31
### Fixed
- Revoked Access Token can not be used in Userinfo endpoint and Token Exchange [RCIAM-1006](https://jira.argo.grnet.gr/browse/RCIAM-1006)

## [16.1.0-2.3] - 2022-05-24

### Fixed
- Instant.now().toEpochMilli instead of System.currentTimeMillis [RCIAM-1002](https://jira.argo.grnet.gr/browse/RCIAM-1002)
- Scope parameter in refresh flow [RCIAM-990](https://jira.argo.grnet.gr/browse/RCIAM-990)

## [16.1.0-2.2] - 2022-05-19
### Changed
- Fixed the key use in the SAML Federation metadata (SSPSSODescriptor) xml. 
- Fixed a bug regarding the keys. SAML now uses RSA "sig" key for signatures and RSA "enc" key for encryption. Previously, it used RSA "sig" for both signatures and encryption (completely ignored the "enc" key in SAML).

### Fixed
- Fix in IdP of SAML federation parsing

## [16.1.0-2.1] - 2022-04-19
### Fixed
- Device code flow json error responses [RCIAM-959](https://jira.argo.grnet.gr/browse/RCIAM-959)
### Changed
- Change indexes related to Federation and Identity Provider tables
- refresh token revoke per client and correct refresh flow [RCIAM-920](https://jira.argo.grnet.gr/browse/RCIAM-920)
### Added
- Sort countries list in client [RCIAM-791](https://jira.argo.grnet.gr/browse/RCIAM-791)
- SAML entityID/OIDC issuer showing in user if IdP display name does not exist [RCIAM-887](https://jira.argo.grnet.gr/browse/RCIAM-887)

## [16.1.0-2.0] - 2022-04-05
### Added
- Id token lifespan [RCIAM-930](https://jira.argo.grnet.gr/browse/RCIAM-930)
- Add indexes to related to Federation and Identity Provider tables 
- Eosc-kc version model with MigrationModel changes [RCIAM-945](https://jira.argo.grnet.gr/browse/RCIAM-945)
### Changed
- Support for configuring claims supported in Keycloak OP metadata [RCIAM-899](https://jira.argo.grnet.gr/browse/RCIAM-899)
- Specific error page for no principals [RCIAM-766](https://jira.argo.grnet.gr/browse/RCIAM-766)
### Fixed
- Fix problem for viewing client without view realm role
- Include 'urn:ietf:params:oauth:grant-type:token-exchange' in grant_types_supported field of Keycloak OP metadata, if token-exchange is enabled [RCIAM-915](https://jira.argo.grnet.gr/browse/RCIAM-915)
- Improve Keycloak SAML IdP SAML message bindings [RCIAM-942](https://jira.argo.grnet.gr/browse/RCIAM-942)
- Full support for XML boolean values for SAML [KEYCLOAK-10802](https://github.com/keycloak/keycloak/issues/10802)

## [v16.1.0-rc1.0.7] - 2022-03-17
### Changed
- Add ePTID principal option [RCIAM-916](https://jira.argo.grnet.gr/browse/RCIAM-916)
- Add is required configuration option for UserAttributeMapper and AttributeToRoleMapper [RCIAM-861](https://jira.argo.grnet.gr/browse/RCIAM-861)

### Fixed
- Fix ConcurrentModificationException bug when removing IdP from SAML federation
- Do not remove connection with Users for a removed IdP member of over one SAML federation [RCIAM-929](https://jira.argo.grnet.gr/browse/RCIAM-929)

## [v16.1.0-rc1.0.6] - 2022-03-14
### Changed
- Signing of SAML IdP logout requests separately [RCIAM-881](https://jira.argo.grnet.gr/browse/RCIAM-881)
- Service Provider Entity ID for SAMl Federation [RCIAM-904](https://jira.argo.grnet.gr/browse/RCIAM-904)

## [v16.1.0-rc1.0.5] - 2022-03-08
### Changed
- Changes related to NameIDFormat [RCIAM-882](https://jira.argo.grnet.gr/browse/RCIAM-882)
- Remove related User from removed IdP from SAML Federation [RCIAM-882](https://jira.argo.grnet.gr/browse/RCIAM-882)

## [v16.1.0-rc1.0.4] - 2022-03-04
### Changed
- Implementation improvements[RCIAM-860](https://jira.argo.grnet.gr/browse/RCIAM-860)
- Remove indexes from IdentityProviderEntity
### Fixed
- Bug correction for NameIDFormat [RCIAM-882](https://jira.argo.grnet.gr/browse/RCIAM-882)
- Field entityId of SAML IdP with * in ui

## [v16.1.0-rc1.0.3] - 2022-03-02
### Changed
- Allow omitting NameIDFormat [RCIAM-882](https://jira.argo.grnet.gr/browse/RCIAM-882)
- Add signing page roles in account console[RCIAM-860](https://jira.argo.grnet.gr/browse/RCIAM-860)
- EntityId in configuration of SAML IdP[EOSC-KC-133](https://github.com/eosc-kc/keycloak/issues/133)
- Record SAML login events based on SAML IdP entityID [EOSC-KC-134](https://github.com/eosc-kc/keycloak/issues/134)
### Fixed
- Check for null samlResponse for federation
- Avoid using missing jars in versions 16.0.0+ (SAML federation) [RCIAM-895](https://jira.argo.grnet.gr/browse/RCIAM-895)

## [v16.1.0-rc1.0.2] - 2022-02-25
### Added
- Add scope parameter to token exchange [RCIAM-843](https://jira.argo.grnet.gr/browse/RCIAM-843)
- Hide scopes from scopes_supported in discovery endpoint [RCIAM-859](https://jira.argo.grnet.gr/browse/RCIAM-859)
- Refresh token for offline_access [RCIAM-849](https://jira.argo.grnet.gr/browse/RCIAM-849)
### Fixed
- Fix scope bug in device authorization request [RCIAM-858](https://jira.argo.grnet.gr/browse/RCIAM-858)
- Changes in account console and account rest service [RCIAM-860](https://jira.argo.grnet.gr/browse/RCIAM-860)

## [v16.1.0-rc1.0.1] - 2022-02-15
### Changed
- Offline_access scope return always refresh_token [RCIAM-744](https://jira.argo.grnet.gr/browse/RCIAM-744)
- Correct messages for consent extension [RCIAM-791](https://jira.argo.grnet.gr/browse/RCIAM-791)

## [v16.1.0-rc1.0.0] - 2022-02-14
### Added
- Support for SAML IdP Federation
- User reaccepting Terms and Conditions. [EOSC-KC-48](https://github.com/eosc-kc/keycloak/issues/48)
- Terms and Conditions - periodic reset for all realm users. [EOSC-KC-49](https://github.com/eosc-kc/keycloak/issues/49)
- Email notification for add/remove group. [EOSC-KC-75](https://github.com/eosc-kc/keycloak/issues/75)
- View groups from Account Console. [EOSC-KC-61](https://github.com/eosc-kc/keycloak/issues/61)
- Identity Providers pager in Admin Console. [EOSC-KC-73](https://github.com/eosc-kc/keycloak/issues/73)
- Identity Providers pager in Linked Accounts of Account Console. [EOSC-KC-50](https://github.com/eosc-kc/keycloak/issues/50)
- Javascript SAML identity provider mapper. [KEYCLOAK-17685](https://issues.redhat.com/browse/KEYCLOAK-17685)
- SAML/ OIDC Identity Provider AutoUpdate. [EOSC-KC-119](https://github.com/eosc-kc/keycloak/issues/119)
- New release created on tag
- The idpLoginFullUrl common attribute passed to the ftl templates for any theme except from the default
- Include claim in token introspection response only [RCIAM-742](https://jira.argo.grnet.gr/browse/RCIAM-742)
- Device Authorization Grant with PKCE [KEYCLOAK-9710](https://github.com/keycloak/keycloak/issues/9710)
- External introspection endpoint [EOSC-KC-140](https://github.com/eosc-kc/keycloak/issues/140)

### Changed
- Increase User Attribute Value length to 4000 [EOSC-KC-132](https://github.com/eosc-kc/keycloak/issues/132)
- change emailVerified User field with UserAttributeMappers (conditional trust email). [EOSC-KC-70](https://github.com/eosc-kc/keycloak/issues/70)
- FreeMarkerLoginFormsProvider now has an additional common attribute passed to the ftl templates, the "uriInfo"
- Consent extension [RCIAM-791](https://jira.argo.grnet.gr/browse/RCIAM-791)

### Fixed
- Configure attribute name format in SAML UserAttribute mapper [EOSC-KC-121](https://github.com/eosc-kc/keycloak/issues/121)
- Fix scope bug in device authorization request [RCIAM-783](https://jira.argo.grnet.gr/browse/RCIAM-783)
